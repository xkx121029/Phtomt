import { apiBaseUrl, staticOnly } from '../api/client.js'
import { pickFastest } from './downloadRank.js'
import { pickGithubAsset } from './githubAsset.js'

/**
 * 下载源择优（在用户浏览器里实测）。
 *
 * 安装包只在 GitHub / Gitee 的 Release 里，官网上没有 APK 文件，所以这里比较的是
 * 两个外部资产地址：
 *   - GitHub：先调 api.github.com 确认真有这个版本的资产（API 带 CORS），拿到真实地址；
 *   - Gitee：附件是否 attach 成功只有服务端知道（Gitee 接口不带 CORS），由
 *     /api/download/sources/:version 查一次列表并回传已验证的资产地址。
 * 两边各做一次 no-cors HEAD 量首字节，选更快的那个，只发 HEAD 不下载包体。
 *
 * 结果按会话缓存 15 分钟（全部不通只缓存 2 分钟，好让线路恢复后尽快纠正）。
 */

const CACHE_KEY = 'phtomt-download-source'
const CLIENT_TTL = 15 * 60 * 1000
const DEGRADED_TTL = 2 * 60 * 1000
const PROBE_TIMEOUT = 2500
/** 首字节超过这个时间就算「通但慢」，只用来标注，不影响胜负 */
const SLOW_MS = 1200

function readCache(version) {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const data = JSON.parse(raw)
    if (!data || data.version !== version) return null
    const ttl = data.degraded ? DEGRADED_TTL : CLIENT_TTL
    if (Date.now() - Number(data.cachedAt || 0) > ttl) return null
    return data
  } catch {
    return null
  }
}

function writeCache(payload) {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify({ ...payload, cachedAt: Date.now() }))
  } catch {
    /* 隐私模式写不进去也不影响下载 */
  }
}

/**
 * 只读缓存里的择优结果，不发任何探测请求。
 *
 * 首页按钮用它复用下载页已经测好的源；版本对不上或已过期就返回 null，
 * 调用方回落到服务端跳转入口——避免把别的版本的链接（或过期结论）套到这个版本上。
 */
export function cachedDownloadSource(version) {
  return version ? readCache(String(version)) : null
}

/** 从站点链接里取出 GitHub 仓库坐标 */
function repoOf(site) {
  const m = String(site?.links?.github || '').match(/github\.com\/([^/]+)\/([^/?#]+)/)
  return m ? `${m[1]}/${m[2]}` : ''
}

/** 只发 HEAD，量到响应头为止；跨域资产读不到状态码，只能判通与不通 */
async function headTiming(url) {
  const started = performance.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT)
  try {
    await fetch(url, {
      method: 'HEAD',
      mode: 'no-cors',
      cache: 'no-store',
      redirect: 'follow',
      signal: controller.signal
    })
    const ms = Math.round(performance.now() - started)
    return { status: ms > SLOW_MS ? 'slow' : 'ok', ttfbMs: ms }
  } catch (err) {
    return { status: 'down', detail: err?.name === 'AbortError' ? '超时' : '网络不可达' }
  } finally {
    clearTimeout(timer)
  }
}

/** GitHub：用 API 确认这个版本真的有资产，顺便拿到真实下载地址 */
async function githubCandidate(site, release) {
  const repo = repoOf(site)
  if (!repo || !release?.apk?.file || !release?.version) return null
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=30`, {
      headers: { Accept: 'application/vnd.github+json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(PROBE_TIMEOUT)
    })
    if (!res.ok) return null
    const list = await res.json()
    const hit = pickGithubAsset(list, release)
    return hit ? { id: 'github', label: 'GitHub Release', ...hit } : null
  } catch {
    return null
  }
}

/** 服务端返回的候选：Gitee 是查过附件列表确认存在的，GitHub 是按命名规则拼的 */
async function serverCandidates(release) {
  if (!release?.version) return []
  try {
    const res = await fetch(
      `${apiBaseUrl()}/api/download/sources/${encodeURIComponent(release.version)}`,
      { cache: 'no-store', signal: AbortSignal.timeout(PROBE_TIMEOUT * 2) }
    )
    if (!res.ok) return []
    const data = await res.json()
    return Array.isArray(data.candidates) ? data.candidates.filter((c) => c?.url) : []
  } catch {
    return []
  }
}

/**
 * 决定用哪个源。返回 null 表示没法测（静态站/缺包体），调用方保持默认跳转入口。
 * degraded=true 表示两边都没测通：url 为空，页面继续用服务端的默认跳转。
 */
export async function resolveDownloadSource(release, { site, refresh = false } = {}) {
  if (!release?.version || !release.apk?.file || staticOnly) return null

  const cached = refresh ? null : readCache(release.version)
  if (cached) return cached

  // 两路并行：GitHub API 直查 + 服务端候选（Gitee 已验证）
  const [apiGithub, server] = await Promise.all([
    githubCandidate(site, release),
    serverCandidates(release)
  ])

  // GitHub 以 API 结果为准；API 不通时保留服务端按规则拼出的地址作为候选
  const list = apiGithub
    ? [apiGithub, ...server.filter((c) => c.id !== 'github')]
    : server.slice()

  const probes = await Promise.all(list.map((c) => headTiming(c.url)))
  const withProbe = list.map((c, i) => ({ ...c, probe: probes[i] }))
  const decided = pickFastest(withProbe)

  const payload = {
    version: release.version,
    source: decided.source,
    url: decided.url,
    degraded: decided.degraded,
    measuredAt: new Date().toISOString(),
    candidates: withProbe.map((c) => ({
      id: c.id,
      label: c.label,
      url: c.url,
      status: c.probe.status,
      ttfbMs: c.probe.ttfbMs ?? null,
      detail: c.probe.detail || ''
    }))
  }

  writeCache(payload)
  return payload
}

/** 主按钮上的极轻状态提示：几百毫秒内测完就不出现，避免闪一下 */
export const PROBE_HINT_DELAY = 300
