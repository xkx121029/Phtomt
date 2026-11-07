import * as store from './store.js'
import { config } from '../config.js'

/**
 * 版本数据源：以 GitHub Releases 为准，本地 `releases.json` 只在拉不到时兜底。
 *
 * 为什么不再以 `releases.json` 为主：CI 每次出包都会直接挂 Release，
 * 而 JSON 要靠人工同步，一旦漏同步，官网就会把两个月前的包当「最新」发出去。
 *
 * 缓存策略（无 token 的 GitHub API 限额是 60 次/小时）：
 *  - 成功：10 分钟一次；
 *  - 失败：2 分钟后重试，期间对外标 degraded，页面提示「可能不是最新」。
 * 配了 GITHUB_TOKEN 就带上（个人访问令牌或 Actions 的 GITHUB_TOKEN 均可），
 * 限额提到 5000 次/小时；令牌只从环境变量读，不写进仓库。
 */

const API = 'https://api.github.com'
const OK_TTL = 10 * 60 * 1000
const FAIL_TTL = 2 * 60 * 1000
const FETCH_TIMEOUT = 8000
const PER_PAGE = 30

const state = {
  list: [],
  /** 'github' = 实时数据；'local' = 降级到 releases.json */
  source: 'local',
  fetchedAt: null,
  degraded: true,
  error: '',
  nextAt: 0,
  inflight: null,
  timer: null
}

const normalizeVersion = (input) => {
  const v = String(input || '').trim()
  if (!v) return ''
  return /^v/i.test(v) ? v : `v${v}`
}

const compareVersion = (a, b) => {
  const pa = String(a).replace(/^v/i, '').split('.').map((n) => Number(n) || 0)
  const pb = String(b).replace(/^v/i, '').split('.').map((n) => Number(n) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const diff = (pa[i] || 0) - (pb[i] || 0)
    if (diff) return diff
  }
  return 0
}

const humanSize = (bytes) => {
  if (!Number.isFinite(bytes) || bytes <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

/** 从站点外链里取 GitHub 仓库；换了仓库不用改代码 */
function repo() {
  const url = String(store.read('site')?.links?.github || '')
  const m = url.match(/github\.com\/([^/]+)\/([^/?#]+)/)
  return m ? `${m[1]}/${m[2].replace(/\.git$/, '')}` : ''
}

/**
 * 渠道判定：以 CI 的 tag 命名为准（`v0.2.91xx-dev` / `v0.2.91xx-beta`）。
 * 少数手工打的包没有后缀，退一步看 Release 标题里的中文标识。
 *
 * 两处都判不出来时按 dev 兜底，并标记 `resolved: false` 供排查：
 * 宁可把一个没标注的包说成开发版，也不能让它冒充稳定版去占首页主按钮。
 */
export function channelDetail(tag, name = '') {
  const t = String(tag || '').toLowerCase()
  if (t.endsWith('-dev')) return { channel: 'dev', resolved: true }
  if (t.endsWith('-beta')) return { channel: 'beta', resolved: true }
  if (t.endsWith('-nightly')) return { channel: 'nightly', resolved: true }
  if (t.endsWith('-stable')) return { channel: 'stable', resolved: true }
  const n = String(name || '')
  if (n.includes('开发版') || /\(dev\)/i.test(n)) return { channel: 'dev', resolved: true }
  if (n.includes('测试版') || /\(beta\)/i.test(n)) return { channel: 'beta', resolved: true }
  return { channel: 'dev', resolved: false }
}

export function channelOf(tag, name = '') {
  return channelDetail(tag, name).channel
}

/** 构建号＝tag 末段数字；三段以后可能被手工灌成 2000092，所以只当展示用 */
function buildOf(tag) {
  const parts = String(tag || '').replace(/^v/i, '').split('.')
  const last = Number(parts[parts.length - 1])
  return Number.isFinite(last) ? last : 0
}

/** 把 GitHub 的正文压成一句摘要：去掉 Markdown 标记，取第一段有效文字 */
function summaryOf(body) {
  const line = String(body || '')
    .split(/\r?\n/)
    .map((s) => s.replace(/^[#>\-*\s]+/, '').replace(/\*\*/g, '').trim())
    .find((s) => s && !/^版本/.test(s) && !/^versionCode/i.test(s))
  return line || ''
}

function fromGithub(item) {
  const tag = String(item.tag_name || '')
  const asset = (item.assets || []).find((a) => /\.apk$/i.test(a.name || ''))
  const ch = channelDetail(tag, item.name)
  return {
    version: normalizeVersion(tag),
    build: buildOf(tag),
    channel: ch.channel,
    // false = 后缀和标题都没给出渠道，按 dev 兜底
    channelResolved: ch.resolved,
    date: String(item.published_at || item.created_at || '').slice(0, 10),
    publishedAt: item.published_at || item.created_at || null,
    published: true,
    title: item.name || tag,
    summary: summaryOf(item.body),
    apk: asset
      ? {
          file: asset.name,
          size: asset.size,
          sizeText: humanSize(asset.size),
          url: asset.browser_download_url,
          updatedAt: asset.updated_at || item.published_at || null
        }
      : null,
    notes: [],
    source: 'github'
  }
}

/** 本地记录只用来补「手写摘要 / 校验值 / 更新说明」，不再决定「哪个是最新」 */
function mergeLocal(list) {
  const local = new Map(
    (store.read('releases') || []).map((r) => [normalizeVersion(r.version), r])
  )
  return list.map((r) => {
    const hit = local.get(normalizeVersion(r.version))
    if (!hit) return r
    return {
      ...r,
      summary: hit.summary || r.summary,
      title: hit.title || r.title,
      notes: Array.isArray(hit.notes) ? hit.notes : r.notes,
      apk: r.apk
        ? {
            ...r.apk,
            sha256: hit.apk?.sha256 || r.apk.sha256,
            sizeText: hit.apk?.sizeText || r.apk.sizeText
          }
        : hit.apk || null
    }
  })
}

/** 最新判据：发布时间倒序。号段混用（v0.2.1278 / v0.2.9106 / v0.2.2000092）时，纯数字排序会把已停用的号段顶到最前 */
function sortByTime(list) {
  return [...list].sort((a, b) => {
    const ta = Date.parse(a.publishedAt || '') || 0
    const tb = Date.parse(b.publishedAt || '') || 0
    if (tb !== ta) return tb - ta
    return compareVersion(b.version, a.version)
  })
}

function localList() {
  return sortByTime(
    (store.read('releases') || [])
      .filter((r) => r.published !== false)
      .map((r) => ({
        ...r,
        version: normalizeVersion(r.version),
        channel: r.channel || 'stable',
        publishedAt: r.publishedAt || (r.apk?.updatedAt ?? null) || `${r.date || ''}T00:00:00Z`,
        source: 'local'
      }))
      .filter((r) => r.apk && r.apk.file)
  )
}

async function fetchGithub() {
  const target = repo()
  if (!target) throw new Error('站点外链里没有 GitHub 仓库地址')
  const headers = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'phtomt-site'
  }
  if (config.githubToken) headers.Authorization = `Bearer ${config.githubToken}`
  const res = await fetch(`${API}/repos/${target}/releases?per_page=${PER_PAGE}`, {
    headers,
    signal: AbortSignal.timeout(FETCH_TIMEOUT)
  })
  if (!res.ok) {
    const left = res.headers.get('x-ratelimit-remaining')
    throw new Error(`GitHub API ${res.status}${left === '0' ? '（限额已用尽）' : ''}`)
  }
  const items = await res.json()
  const list = sortByTime(
    (Array.isArray(items) ? items : [])
      .filter((i) => !i.draft)
      .map(fromGithub)
      .filter((r) => r.apk && r.apk.file)
  )
  if (!list.length) throw new Error('GitHub 上没有带 APK 资产的 Release')
  return mergeLocal(list)
}

/** 拉一次并把结果写进内存；并发调用共用同一个请求 */
export async function refresh({ force = false } = {}) {
  if (!force && Date.now() < state.nextAt) return snapshot()
  if (state.inflight) return state.inflight
  state.inflight = (async () => {
    try {
      const list = await fetchGithub()
      state.list = list
      state.source = 'github'
      state.degraded = false
      state.error = ''
      state.fetchedAt = new Date().toISOString()
      state.nextAt = Date.now() + OK_TTL
    } catch (err) {
      state.error = err?.message || String(err)
      if (!state.list.length) {
        state.list = localList()
        state.source = 'local'
      }
      state.degraded = true
      state.nextAt = Date.now() + FAIL_TTL
      console.warn(`[releaseFeed] 拉取 GitHub Releases 失败，${state.source === 'local' ? '回退本地 releases.json' : '沿用上一次成功数据'}：${state.error}`)
    } finally {
      state.inflight = null
    }
    return snapshot()
  })()
  return state.inflight
}

/** 同步快照：路由里直接读，不阻塞请求 */
export function snapshot() {
  return {
    list: state.list,
    source: state.source,
    fetchedAt: state.fetchedAt,
    degraded: state.degraded,
    error: state.error,
    repo: repo(),
    // 后缀与标题都没标渠道、按 dev 兜底的版本号；列表非空说明 CI 命名该补后缀了
    unknownChannels: state.list
      .filter((r) => r.channelResolved === false)
      .map((r) => r.version)
  }
}

/** 频道过滤后的列表（已按发布时间倒序） */
export function listReleases({ channel = 'all' } = {}) {
  return snapshot()
    .list.filter((r) => (channel && channel !== 'all' ? r.channel === channel : true))
}

export function findRelease(version) {
  const target = normalizeVersion(version)
  return snapshot().list.find((r) => normalizeVersion(r.version) === target) || null
}

export function latestRelease({ channel = 'all' } = {}) {
  return listReleases({ channel })[0] || null
}

/**
 * 官网主按钮的目标：优先最新稳定版；一条稳定版都没有时退回最新测试包，
 * 并把 `stable: false` 传给页面，由页面写明「暂无稳定版」。
 */
export function downloadTarget() {
  const stable = latestRelease({ channel: 'stable' })
  if (stable) return { release: stable, stable: true }
  return { release: latestRelease({ channel: 'all' }), stable: false }
}

export async function init() {
  await refresh({ force: true })
  if (!state.timer) {
    state.timer = setInterval(() => {
      refresh().catch(() => {})
    }, 60 * 1000)
    state.timer.unref?.()
  }
  return snapshot()
}
