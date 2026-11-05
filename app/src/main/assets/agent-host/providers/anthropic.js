// Anthropic Messages API provider.

import { postJSON, truncate, ProviderError } from './base.js';
import { parseSSE } from '../util/sse.js';

function toAnthropicMessages(messages) {
  const out = [];
  for (const m of messages) {
    if (m.role === 'system') continue; // system goes into the top-level `system` field
    if (m.role === 'assistant' && m.toolCalls?.length) {
      const blocks = [];
      if (m.content) blocks.push({ type: 'text', text: m.content });
      for (const tc of m.toolCalls) {
        blocks.push({ type: 'tool_use', id: tc.id, name: tc.name, input: tc.arguments ?? {} });
      }
      out.push({ role: 'assistant', content: blocks });
      continue;
    }
    if (m.role === 'tool') {
      out.push({
        role: 'user',
        content: [{ type: 'tool_result', tool_use_id: m.toolCallId, content: truncate(m.content) }],
      });
      continue;
    }
    out.push({ role: m.role, content: m.content });
  }
  return out;
}

export class AnthropicProvider {
  constructor({
    apiKey,
    baseUrl = 'https://api.anthropic.com',
    model = 'claude-sonnet-4-5',
    maxTokens = 8192,
    fetchImpl = fetch,
  }) {
    if (!apiKey) throw new Error('AnthropicProvider: apiKey is required');
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.model = model;
    this.maxTokens = maxTokens;
    this.fetchImpl = fetchImpl;
  }

  async *stream({ messages, tools, signal }) {
    const system = messages.filter((m) => m.role === 'system').map((m) => m.content).join('\n\n');
    const res = await postJSON(
      `${this.baseUrl}/v1/messages`,
      {
        headers: { 'x-api-key': this.apiKey, 'anthropic-version': '2023-06-01', accept: 'text/event-stream' },
        body: {
          model: this.model,
          max_tokens: this.maxTokens,
          ...(system ? { system } : {}),
          messages: toAnthropicMessages(messages),
          ...(tools?.length
            ? { tools: tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters })) }
            : {}),
          stream: true,
        },
        signal,
        fetchImpl: this.fetchImpl,
      },
    );

    let usage = null;
    let finishReason = 'end_turn';
    let finished = false;
    const toolBlocks = new Map(); // content_block index -> { id, name }
    for await (const ev of parseSSE(res.body, { signal })) {
      // 事件名有两种来源：payload 里的 type，或 SSE 的 event: 行。
      const kind = ev.type || ev.__event;
      switch (kind) {
        case 'message_start':
          usage = mergeUsage(usage, ev.message?.usage);
          break;
        case 'content_block_start':
          if (ev.content_block?.type === 'tool_use') {
            toolBlocks.set(ev.index, { id: ev.content_block.id, name: ev.content_block.name });
            yield { type: 'tool_call', index: ev.index, id: ev.content_block.id, name: ev.content_block.name, argsDelta: '' };
          } else if (ev.content_block?.type === 'text' && ev.content_block.text) {
            yield { type: 'text', delta: ev.content_block.text };
          }
          break;
        case 'content_block_delta':
          if (ev.delta?.type === 'text_delta' && ev.delta.text) {
            yield { type: 'text', delta: ev.delta.text };
          } else if (ev.delta?.type === 'thinking_delta' && ev.delta.thinking) {
            yield { type: 'thinking', delta: ev.delta.thinking };
          } else if (ev.delta?.type === 'input_json_delta') {
            const t = toolBlocks.get(ev.index);
            yield { type: 'tool_call', index: ev.index, id: t?.id, name: t?.name, argsDelta: ev.delta.partial_json || '' };
          }
          break;
        case 'message_delta':
          // message_delta 里的 usage 是累计值（output_tokens 会一路增长），
          // 覆盖而不是相加，否则长回答的输出 token 会被重复计费。
          if (ev.usage) usage = { ...(usage || {}), ...ev.usage };
          if (ev.delta?.stop_reason) finishReason = ev.delta.stop_reason;
          break;
        case 'message_stop':
          finished = true;
          yield { type: 'finish', reason: finishReason, usage };
          break;
        case 'error':
          throw new ProviderError(ev.error?.message || 'anthropic stream error', { body: JSON.stringify(ev).slice(0, 2000) });
        case 'ping':
          break;
        default:
          break;
      }
    }
    // 网关可能不发 message_stop 就断开：补终态，保证 usage 与收尾语义不丢。
    if (!finished) yield { type: 'finish', reason: finishReason, usage };
  }
}

/** Anthropic 的 usage 分两次到（message_start 的 input，message_delta 的 output），需要累加合并。 */
function mergeUsage(acc, add) {
  if (!add) return acc;
  if (!acc) return { ...add };
  const out = { ...acc };
  for (const [key, value] of Object.entries(add)) {
    if (typeof value === 'number') out[key] = (typeof out[key] === 'number' ? out[key] : 0) + value;
  }
  return out;
}
