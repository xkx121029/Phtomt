import { snapshot } from '../data/fallback.js'
import { applySiteMeta } from '../lib/siteMeta.js'

/**
 * API 客户端。
 *
 * 两条运行路径：
 *  - 有后端（Express 托管 / Vite 代理）：所有内容走 /api/*，后台可写。
 *  - 纯静态导出（GitHub Pages）：没有后端，读操作回落到构建期快照，
 *    写操作直接抛错——后台会据此显示「当前为静态站，无法编辑」。
 */

const TOKEN_KEY = 'phtomt-admin-token'
export const isStaticBuild = Boolean(__STATIC_EXPORT__)

export class ApiError extends Error {
  constructor(message, status, code) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 隐私模式下 localStorage 可能不可用，忽略即可 */
  }
}

const base = () => import.meta.env.VITE_API_BASE || ''

async function request(path, { method = 'GET', body, auth = false } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth) {
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  let res
  try {
    res = await fetch(`${base()}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (err) {
    throw new ApiError('无法连接到服务，请确认后端已启动', 0, 'network')
  }

  const text = await res.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = { message: text.slice(0, 200) }
    }
  }

  if (!res.ok) {
    throw new ApiError(payload?.message || `请求失败（${res.status}）`, res.status, payload?.error)
  }
  return payload
}

export const api = {
  get: (path) => request(path),
  adminGet: (path) => request(path, { auth: true }),
  post: (path, body, auth = false) => request(path, { method: 'POST', body, auth }),
  put: (path, body, auth = false) => request(path, { method: 'PUT', body, auth }),
  patch: (path, body, auth = false) => request(path, { method: 'PATCH', body, auth }),
  del: (path, auth = false) => request(path, { method: 'DELETE', auth }),

  /** multipart 上传：不能设 Content-Type，交给浏览器带 boundary */
  async upload(path, file, field = 'file') {
    const form = new FormData()
    form.append(field, file)
    const headers = {}
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    const res = await fetch(`${base()}${path}`, { method: 'POST', headers, body: form })
    const payload = await res.json().catch(() => null)
    if (!res.ok) throw new ApiError(payload?.message || '上传失败', res.status, payload?.error)
    return payload
  }
}

/**
 * 站点 AI 问答。
 *
 * 状态探测是普通 JSON，走 request()；提问是 SSE，必须走另一条路——
 * request() 会先把整个 body 读完再解析，流式回答拿到手时早就生成完了。
 */
export const assistant = {
  status: () => api.get('/api/ai/status'),

  /**
   * 流式提问。
   *
   * @param {{role:'user'|'assistant', content:string}[]} messages 完整对话，末条必须是 user
   * @param {{onDelta?:(chunk:string, full:string)=>void, signal?:AbortSignal}} options
   * @returns {Promise<{text:string, sources:string[], chars:number}>}
   */
  async chat(messages, { onDelta, signal } = {}) {
    if (isStaticBuild) throw new ApiError('当前是静态站点，没有可用的问答后端', 0, 'static')

    let res
    try {
      res = await fetch(`${base()}/api/ai/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages }),
        signal
      })
    } catch (err) {
      if (err?.name === 'AbortError') throw err
      throw new ApiError('无法连接到服务，请确认后端已启动', 0, 'network')
    }

    // 上游没配好、被限流这类情况在 SSE 开始之前就已经是普通 JSON 错误了
    if (!res.ok) {
      const payload = await res.json().catch(() => null)
      throw new ApiError(payload?.message || `请求失败（${res.status}）`, res.status, payload?.error)
    }
    if (!res.body) throw new ApiError('当前浏览器不支持流式响应', 0, 'no-stream')

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let text = ''
    let result = { sources: [], chars: 0 }

    const handle = (raw) => {
      const line = raw.trim()
      if (!line.startsWith('data:')) return
      let event
      try {
        event = JSON.parse(line.slice(5).trim())
      } catch {
        return
      }
      if (event.type === 'delta' && event.text) {
        text += event.text
        onDelta?.(event.text, text)
      } else if (event.type === 'error') {
        // 生成中途的失败：已经流出来的内容由调用方决定留不留
        throw new ApiError(event.message || '生成失败', res.status, 'upstream')
      } else if (event.type === 'done') {
        result = { sources: event.sources || [], chars: event.chars || text.length }
      }
    }

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // 按行切而不是按空行切块：一条事件可能被 TCP 拆成几段送达
        let nl = buffer.indexOf('\n')
        while (nl >= 0) {
          const line = buffer.slice(0, nl)
          buffer = buffer.slice(nl + 1)
          handle(line)
          nl = buffer.indexOf('\n')
        }
      }
      if (buffer.trim()) handle(buffer)
    } finally {
      reader.cancel().catch(() => {})
    }

    return { text, ...result }
  }
}

/**
 * 带快照兜底的读取。
 * 静态站直接返回快照；有后端时若请求失败也回落到快照，页面不至于白屏。
 */
async function read(path, pick) {
  if (isStaticBuild) return pick(snapshot)
  try {
    return await api.get(path)
  } catch (err) {
    if (err.status === 0 || err.status >= 500) return pick(snapshot)
    throw err
  }
}

/** 聚合响应里站点信息挂在某个字段下时，取出并入注册表后原样返回整包 */
function applySiteMetaOf(key) {
  return (payload) => {
    applySiteMeta(payload?.[key])
    return payload
  }
}

export const content = {
  // 首屏与站点元信息是「通道显示名 / 日志配色」两张表的唯一来源，
  // 读到就顺手并入本地注册表，视图里 channelLabel() 即可直接用
  bootstrap: () => read('/api/bootstrap', (s) => s.bootstrap).then(applySiteMetaOf('site')),
  // applySiteMeta 只往注册表里并两张展示表、本身不返回内容，所以不能直接当 .then 的
  // 映射函数用——那样调用方拿到的是 undefined，页头页脚求值 site.version 就会抛错、
  // 被 Vue 当成渲染失败整块吞掉（表现为页眉页脚凭空消失）。这里显式把站点对象传下去。
  site: () =>
    read('/api/site', (s) => s.site).then((site) => {
      applySiteMeta(site)
      return site
    }),
  features: () => read('/api/features', (s) => s.features),
  releases: (channel = 'all') =>
    read(`/api/releases?channel=${encodeURIComponent(channel)}`, (s) => s.releases),
  latestRelease: () => read('/api/releases/latest', (s) => s.releases?.[0] || null),
  /**
   * 更新日志。有后端时服务端分页，静态站本地切片——
   * 两条路径都返回同一个 { total, page, size, items } 形状，调用方不必分支。
   */
  changelog: ({ limit, page = 1, size = 20 } = {}) => {
    const qs = limit ? `?limit=${limit}` : `?page=${page}&size=${size}`
    return read(`/api/changelog${qs}`, (s) => {
      const items = limit ? s.changelog.slice(0, limit) : s.changelog.slice((page - 1) * size, page * size)
      return { total: s.changelog.length, page, size: limit ? items.length : size, items }
    })
  },
  changelogOf: (version) => read(`/api/changelog/${encodeURIComponent(version)}`, (s) =>
    s.changelog.find((c) => c.version.replace(/^v/i, '') === String(version).replace(/^v/i, ''))
  ),
  docs: () => read('/api/docs', (s) => s.docs),
  doc: (slug) => read(`/api/docs/${encodeURIComponent(slug)}`, (s) => s.docs.find((d) => d.slug === slug)),
  faq: () => read('/api/faq', (s) => s.faq),
  scenarios: (category = '') =>
    read(`/api/scenarios${category ? `?category=${encodeURIComponent(category)}` : ''}`, (s) =>
      category ? (s.scenarios || []).filter((x) => x.category === category) : s.scenarios || []
    ),
  roadmap: () => read('/api/roadmap', (s) => s.roadmap || { updatedAt: null, note: '', phases: [] }),
  stats: () => read('/api/stats', (s) => s.site?.stats || null)
}
