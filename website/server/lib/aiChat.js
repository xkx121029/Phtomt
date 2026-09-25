import { chatUrl } from './aiSettings.js'

/**
 * OpenAI 兼容端点的最小客户端。
 *
 * 只做两件事：发一次非流式请求（后台自检用）、发一次流式请求（官网问答用）。
 * 不引 openai SDK：这里只用得上一个字段子集，多一个依赖就要跟着它升级、跟着它出兼容问题；
 * Node 18+ 自带 fetch 与 ReadableStream，够用了。
 */

export class UpstreamError extends Error {
  constructor(status, message) {
    super(message)
    this.name = 'UpstreamError'
    this.status = status
  }
}

/** 常见状态码的处置建议。上游的错误说明大多不可读，能补一句「该往哪改」就好很多 */
function statusHint(status) {
  if (status === 401 || status === 403) return '密钥无效，或这把密钥没有该模型的权限'
  if (status === 404) return '接口地址可能不对，检查是否需要填到 /v1'
  if (status === 429) return '上游限流，稍后再试'
  if (status >= 500) return '上游服务异常'
  return ''
}

/** 把上游的错误响应体榨成一句话。各家格式不一，逐层退让而不是直接甩 JSON */
export function upstreamMessage(text, status = 0) {
  const raw = String(text || '').trim()
  const hint = statusHint(status)

  if (!raw) return hint ? `上游没有返回内容（${hint}）` : '（上游没有返回内容）'

  // HTML 错误页（反向代理/框架给的 404 页）：整段贴给用户没人看得懂，换成能照着改的话
  if (!raw.startsWith('<')) {
    try {
      const json = JSON.parse(raw)
      const msg = json?.error?.message || json?.error?.msg || json?.message || json?.msg
      if (msg) return String(msg).slice(0, 300)
    } catch {
      /* 不是 JSON，按文本处理 */
    }
    const cut = raw.slice(0, 300)
    return hint ? `${cut}（${hint}）` : cut
  }

  return hint || `上游返回 ${status}，返回内容不是 JSON——多半是接口地址不对`
}

function request(cfg, messages, { stream, maxTokens, signal }) {
  return fetch(chatUrl(cfg), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${cfg.apiKey}`
    },
    body: JSON.stringify({
      model: cfg.model,
      messages,
      temperature: cfg.temperature,
      max_tokens: maxTokens ?? cfg.maxTokens,
      stream
    }),
    signal
  })
}

/** 非流式补全，返回纯文本 */
export async function complete({ cfg, messages, maxTokens, signal }) {
  let res
  try {
    res = await request(cfg, messages, { stream: false, maxTokens, signal })
  } catch (err) {
    throw new UpstreamError(0, `无法连接到 ${chatUrl(cfg)}：${err.message}`)
  }
  if (!res.ok) throw new UpstreamError(res.status, upstreamMessage(await res.text().catch(() => ''), res.status))

  const data = await res.json().catch(() => null)
  const text = data?.choices?.[0]?.message?.content
  if (typeof text !== 'string') {
    throw new UpstreamError(res.status, '上游返回里没有 choices[0].message.content，可能不是 OpenAI 兼容端点')
  }
  return text.trim()
}

/**
 * 流式补全：每收到一段增量就回调一次 [onDelta]，返回完整文本。
 *
 * 逐行解析而不是按空行切事件块：SSE 的 `data:` 是逐行的，按行处理对
 * 「一个事件多行 data」和「一个数据块里塞了多个事件」两种上游行为都成立。
 */
export async function streamChat({ cfg, messages, maxTokens, signal, onDelta }) {
  let res
  try {
    res = await request(cfg, messages, { stream: true, maxTokens, signal })
  } catch (err) {
    throw new UpstreamError(0, `无法连接到 ${chatUrl(cfg)}：${err.message}`)
  }
  if (!res.ok) throw new UpstreamError(res.status, upstreamMessage(await res.text().catch(() => ''), res.status))

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let full = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed.startsWith('data:')) continue
      const payload = trimmed.slice(5).trim()
      if (!payload || payload === '[DONE]') continue

      let json
      try {
        json = JSON.parse(payload)
      } catch {
        continue // 半行或心跳，跳过
      }
      // 有些中转会在流里夹一条错误事件，不能当成正文拼进去
      if (json?.error) throw new UpstreamError(res.status, upstreamMessage(JSON.stringify(json), res.status))

      const piece = json?.choices?.[0]?.delta?.content
      if (typeof piece === 'string' && piece) {
        full += piece
        onDelta(piece)
      }
    }
  }

  return full
}
