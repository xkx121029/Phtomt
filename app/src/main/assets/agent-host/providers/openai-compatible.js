// OpenAI-compatible chat-completions provider (works with OpenAI, and any
// OpenAI-compatible gateway: OpenRouter, one-api/new-api style relays, vLLM, Ollama, ...).

import { postJSON, truncate, ProviderError } from './base.js';
import { parseSSE } from '../util/sse.js';

function toOpenAIMessages(messages) {
  return messages.map((m) => {
    if (m.role === 'assistant' && m.toolCalls?.length) {
      return {
        role: 'assistant',
        content: m.content || '',
        tool_calls: m.toolCalls.map((tc) => ({
          id: tc.id,
          type: 'function',
          function: { name: tc.name, arguments: JSON.stringify(tc.arguments ?? {}) },
        })),
      };
    }
    if (m.role === 'tool') {
      return { role: 'tool', tool_call_id: m.toolCallId, content: truncate(m.content) };
    }
    return { role: m.role, content: m.content };
  });
}

/**
 * 把各种网关的 content 形状归一成字符串：
 *   - "text"                         标准
 *   - [{type:'text', text:'...'}]    部分网关（Responses API 风格）把内容拆成块
 *   - {text:'...'}                   极少数网关包一层对象
 * 返回 null 表示没有可用的正文。
 */
function textOf(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) {
    const parts = value
      .map((part) => {
        if (typeof part === 'string') return part;
        if (part && typeof part.text === 'string') return part.text;
        if (part && typeof part.content === 'string') return part.content;
        return '';
      })
      .join('');
    return parts || null;
  }
  if (value && typeof value.text === 'string') return value.text;
  return null;
}

/** tool_calls 的 arguments 有时是对象而不是字符串；统一成增量字符串。 */
function argsText(args) {
  if (typeof args === 'string') return args;
  if (args == null) return '';
  try {
    return JSON.stringify(args);
  } catch {
    return '';
  }
}

export class OpenAICompatibleProvider {
  constructor({
    apiKey,
    baseUrl = 'https://api.openai.com/v1',
    model,
    fetchImpl = fetch,
    extraHeaders = {},
  }) {
    if (!model) throw new Error('OpenAICompatibleProvider: model is required');
    this.apiKey = apiKey || '';
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.model = model;
    this.fetchImpl = fetchImpl;
    this.extraHeaders = extraHeaders;
  }

  // Yields normalized events:
  //   { type: 'text', delta }
  //   { type: 'thinking', delta }        // 推理模型/网关的 reasoning 字段
  //   { type: 'tool_call', index, id, name, argsDelta }
  //   { type: 'finish', reason, usage }
  async *stream({ messages, tools, signal }) {
    const res = await postJSON(
      `${this.baseUrl}/chat/completions`,
      {
        // accept 必须显式要 SSE：部分中转网关（new-api/企业网关/CDN 前置）只看这个头，
        // 不给就整段缓冲完再回，界面上就成了"等很久然后一次性蹦出来"。
        headers: { authorization: `Bearer ${this.apiKey}`, accept: 'text/event-stream', ...this.extraHeaders },
        body: {
          model: this.model,
          messages: toOpenAIMessages(messages),
          ...(tools?.length ? { tools: tools.map((t) => ({ type: 'function', function: t })), tool_choice: 'auto' } : {}),
          stream: true,
          stream_options: { include_usage: true },
        },
        signal,
        fetchImpl: this.fetchImpl,
      },
    );

    // 网关忽略了 stream:true、直接回一份完整 completion 的情况（真机常见：企业网关、
    // 部分中转在 compression/keep-alive 下会把 SSE 攒成普通 JSON）。parseSSE 只认
    // `data:` 行，这种回包会让它一个 chunk 都拿不到 → 本轮"什么输出都没有"。
    // 这里按 content-type 兜住，把普通 JSON 翻译成同一套事件：文字一次性吐出，
    // 工具调用整段参数一次给完，agent 的工具循环照样能跑。
    const contentType = (res.headers && typeof res.headers.get === 'function'
      ? res.headers.get('content-type') || ''
      : '') || '';
    if (!contentType.toLowerCase().includes('text/event-stream')) {
      const data = await res.json().catch(() => null);
      const choice = data?.choices?.[0];
      const message = choice?.message || {};
      const text = textOf(message.content);
      if (text) yield { type: 'text', delta: text };
      const reasoning = textOf(message.reasoning_content ?? message.reasoning);
      if (reasoning) yield { type: 'thinking', delta: reasoning };
      const calls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
      for (let i = 0; i < calls.length; i += 1) {
        const tc = calls[i];
        yield {
          type: 'tool_call',
          index: tc.index ?? i,
          id: tc.id,
          name: tc.function?.name,
          argsDelta: argsText(tc.function?.arguments),
        };
      }
      yield { type: 'finish', reason: choice?.finish_reason || 'stop', usage: data?.usage ?? null };
      return;
    }

    let usage = null;
    let finished = false;
    for await (const chunk of parseSSE(res.body, { signal })) {
      // 网关把错误塞进流里（余额不足、模型不存在、内容审核）时，
      // 不抛出来就会表现为「流正常结束但模型什么都没说」。
      const errPayload = chunk?.error;
      if (errPayload) {
        const message = typeof errPayload === 'string' ? errPayload : errPayload.message || JSON.stringify(errPayload);
        throw new ProviderError(`provider stream error: ${message}`, { body: JSON.stringify(errPayload).slice(0, 2000) });
      }
      if (chunk.usage) usage = chunk.usage;
      const choice = chunk.choices?.[0];
      if (!choice) continue;
      const delta = choice.delta || choice.message || {};
      const text = textOf(delta.content);
      if (text) yield { type: 'text', delta: text };
      const reasoning = textOf(delta.reasoning_content ?? delta.reasoning);
      if (reasoning) yield { type: 'thinking', delta: reasoning };
      if (Array.isArray(delta.tool_calls)) {
        for (const tc of delta.tool_calls) {
          yield {
            type: 'tool_call',
            index: tc.index ?? 0,
            id: tc.id,
            name: tc.function?.name,
            argsDelta: argsText(tc.function?.arguments),
          };
        }
      }
      if (choice.finish_reason) {
        finished = true;
        yield { type: 'finish', reason: choice.finish_reason, usage };
      }
    }

    // 有些网关流结束时既不发 finish_reason 也不发 [DONE]：补一条终态，
    // 否则 agent 拿不到 usage、也判断不了本轮是正常收尾。
    if (!finished) yield { type: 'finish', reason: 'stop', usage };
  }
}
