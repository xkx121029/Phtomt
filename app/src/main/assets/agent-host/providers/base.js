// Shared provider plumbing: error type, internal message format, JSON POST helper.

export class ProviderError extends Error {
  constructor(message, { status, body } = {}) {
    super(message);
    this.name = 'ProviderError';
    this.status = status;
    this.body = body;
  }
}

export const MAX_TOOL_OUTPUT = 30000;

export function truncate(text, max = MAX_TOOL_OUTPUT) {
  const s = String(text ?? '');
  if (s.length <= max) return s;
  return s.slice(0, max) + `\n... [truncated, ${s.length - max} more chars]`;
}

/**
 * Internal message format (deliberately OpenAI-shaped):
 *   { role: 'system' | 'user' | 'assistant' | 'tool',
 *     content: string,
 *     toolCalls?: [{ id, name, arguments }],   // assistant only
 *     toolCallId?: string, name?: string }      // tool only
 */

export async function postJSON(url, { headers = {}, body, signal, fetchImpl = fetch }) {
  let res;
  try {
    res = await fetchImpl(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...headers },
      body: JSON.stringify(body),
      signal,
    });
  } catch (err) {
    if (err?.name === 'AbortError') throw err;
    // undici 把真实原因（证书链/DNS/拒绝连接…）藏在 err.cause 里，只报 message
    // 会是干巴巴的 "fetch failed"。把 cause 的一级摘要带上，现场问题一眼可判。
    const cause = err?.cause ? ` (${err.cause.code ?? err.cause.message ?? err.cause})` : '';
    throw new ProviderError(`network error talking to ${url}: ${err.message}${cause}`, { body: String(err) });
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new ProviderError(`HTTP ${res.status} from ${url}`, { status: res.status, body: text.slice(0, 4000) });
  }
  return res;
}
