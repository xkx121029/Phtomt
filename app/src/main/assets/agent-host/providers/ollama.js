// Ollama / LM Studio 等本地服务 Provider（能力 #3）。
//
// 这两类服务不speak OpenAI 的 /chat/completions，而是自己的 /api/chat：
//   Ollama      POST {base}/api/chat   { model, messages, tools, stream }
//   LM Studio   POST {base}/api/v1/chat/completions （OpenAI 形状，走 OpenAICompatibleProvider）
// 流式返回是 NDJSON（每行一个 JSON），不是 SSE，所以这里自带一个行解析器。

import { ProviderError, truncate } from './base.js';

/** 去掉末尾的 /v1、/api 之类后缀，得到服务根地址。 */
export function normalizeLocalBaseUrl(baseUrl, fallback) {
  const raw = String(baseUrl || '').trim();
  if (!raw) return fallback;
  return raw.replace(/\/+$/, '').replace(/\/(v1|api)$/, '');
}

/** 逐行读取 NDJSON 响应体，yield 每行的 JSON 对象。 */
async function* parseNDJSON(body, { signal } = {}) {
  if (!body) throw new ProviderError('ollama: response has no body');
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      if (signal?.aborted) return;
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (!line) continue;
        try {
          yield JSON.parse(line);
        } catch {
          /* 半行/坏行：NDJSON 天生有损，忽略 */
        }
      }
    }
    if (buffer.trim()) {
      try {
        yield JSON.parse(buffer.trim());
      } catch {
        /* 尾部残缺，忽略 */
      }
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* 已关闭 */
    }
  }
}

function toOllamaMessages(messages) {
  const out = [];
  for (const m of messages) {
    if (m.role === 'system') {
      out.push({ role: 'system', content: m.content });
    } else if (m.role === 'assistant' && m.toolCalls?.length) {
      out.push({
        role: 'assistant',
        content: m.content || '',
        tool_calls: m.toolCalls.map((tc) => ({
          id: tc.id,
          type: 'function',
          function: { name: tc.name, arguments: JSON.stringify(tc.arguments ?? {}) },
        })),
      });
    } else if (m.role === 'tool') {
      out.push({ role: 'tool', tool_call_id: m.toolCallId, content: truncate(m.content) });
    } else {
      out.push({ role: m.role, content: m.content });
    }
  }
  return out;
}

/**
 * Ollama Provider（本地推理，零外部依赖）。
 *
 *   new OllamaProvider({ baseUrl: 'http://127.0.0.1:11434', model: 'qwen2.5-coder:7b' })
 *
 * 说明：Ollama 的 tool_calls 参数是 JSON 字符串，与 OpenAI 一致；usage 字段在
 * 最后一条 done=true 的帧里（prompt_eval_count / eval_count）。
 */
export class OllamaProvider {
  constructor({
    apiKey = '',
    baseUrl = 'http://127.0.0.1:11434',
    model,
    numCtx,
    keepAlive,
    fetchImpl = fetch,
    extraHeaders = {},
  } = {}) {
    if (!model) throw new Error('OllamaProvider: model is required');
    this.apiKey = apiKey;
    this.baseUrl = normalizeLocalBaseUrl(baseUrl, 'http://127.0.0.1:11434');
    this.model = model;
    this.numCtx = numCtx;
    this.keepAlive = keepAlive;
    this.fetchImpl = fetchImpl;
    this.extraHeaders = extraHeaders;
    this.kind = 'ollama';
  }

  /** 探测本地服务是否可用（列模型），用于设置页的“测试连接”。 */
  async probe({ signal } = {}) {
    const res = await this.fetchImpl(`${this.baseUrl}/api/tags`, {
      headers: this.#headers(),
      signal,
    });
    if (!res.ok) throw new ProviderError(`ollama /api/tags -> HTTP ${res.status}`, { status: res.status });
    const data = await res.json();
    return (data.models || []).map((m) => m.name);
  }

  #headers() {
    return this.apiKey ? { authorization: `Bearer ${this.apiKey}`, ...this.extraHeaders } : { ...this.extraHeaders };
  }

  async *stream({ messages, tools, signal }) {
    const res = await this.fetchImpl(`${this.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...this.#headers() },
      body: JSON.stringify({
        model: this.model,
        messages: toOllamaMessages(messages),
        stream: true,
        ...(tools?.length
          ? {
              tools: tools.map((t) => ({
                type: 'function',
                function: { name: t.name, description: t.description, parameters: t.parameters },
              })),
            }
          : {}),
        ...(this.numCtx ? { options: { num_ctx: this.numCtx } } : {}),
        ...(this.keepAlive ? { keep_alive: this.keepAlive } : {}),
      }),
      signal,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new ProviderError(`ollama /api/chat -> HTTP ${res.status}: ${text.slice(0, 500)}`, {
        status: res.status,
        body: text.slice(0, 4000),
      });
    }

    let usage = null;
    let index = 0;
    for await (const frame of parseNDJSON(res.body, { signal })) {
      if (frame.error) throw new ProviderError(`ollama error: ${frame.error}`, { body: JSON.stringify(frame).slice(0, 2000) });
      const msg = frame.message;
      if (msg?.content) yield { type: 'text', delta: msg.content };
      // 新版 Ollama 的推理模型把思考过程放在 thinking 字段，不能混进正文。
      if (msg?.thinking) yield { type: 'thinking', delta: msg.thinking };
      if (Array.isArray(msg?.tool_calls)) {
        for (const tc of msg.tool_calls) {
          yield {
            type: 'tool_call',
            index: index++,
            id: tc.id || `ollama_${index}`,
            name: tc.function?.name,
            argsDelta: typeof tc.function?.arguments === 'string'
              ? tc.function.arguments
              : JSON.stringify(tc.function?.arguments ?? {}),
          };
        }
      }
      if (frame.done) {
        usage = {
          input_tokens: frame.prompt_eval_count ?? 0,
          output_tokens: frame.eval_count ?? 0,
          total_duration_ns: frame.total_duration,
        };
        yield { type: 'finish', reason: frame.done_reason ?? (usage.output_tokens ? 'tool_calls' : 'stop'), usage };
      }
    }
  }
}
