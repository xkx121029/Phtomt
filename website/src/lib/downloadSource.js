import { apiBaseUrl, staticOnly } from '../api/client.js'
import { pickFastest } from './downloadRank.js'

/**
 * 下载源择优（在用户浏览器里实测）。
 *
 * 安装包只在 GitHub / Gitee 的 Release 里，官网上没有 APK 文件，所以这里比较的是
 * 两个外部资产地址：
 *   - GitHub：资产地址按 tag + 文件名就能拼出来，本地直接算，立刻开始探测；
 *   - Gitee：附件是否 attach 成功只有服务端知道（Gitee 接口不带 CORS），由
 *     /api/download/sources/:version 回传已验证的资产地址。
 * 两边各做一次 no-cors HEAD 量首字节，选响应更快的那个，只发 HEAD 不下载包体。
 * 注意这只反映 TTFB，不代表真实吞吐，所以对外只说「响应更快」，不说「下载更快」。
 *
 * 为什么不先调 api.github.com 确认资产：那是一次额外的跨域往返，慢起来要 2.5 秒，
 * 而且匿名限额只有 60 次/小时/IP（同一出口的多个访客会互相挤掉）。服务端返回的
 * GitHub 地址本来就是同一条命名规则拼的，本地再拼一次结果一致，没必要验。
 *
 * 结果按会话缓存 15 分钟（全部不通只缓存 2 分钟，好让线路恢复后尽快纠正）。
 * 存在 localStorage 而不是 sessionStorage：同一台机器开多个标签页时不用重复探测。
 */

const CACHE_KEY = 'phtomt-download-source'
const CLIENT_TTL = 15 * 60 * 1000
const DEGRADED_TTL = 2 * 60 * 1000
const PROBE_TIMEOUT = 2500
/** 首字节超过这个时间就算「通但慢」，只用来标注，不影响胜负 */
const SLOW_MS = 1200

function readCache(version) {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
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
    localStorage.setItem(CACHE_KEY, JSON.stringify({ ...payload, cachedAt: Date.now() }))
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

/** GitHub：资产地址按 tag + 文件名拼，和服务端 server/lib/externalAssets.js 同一条规则 */
function githubCandidate(site, release) {
  const m = String(site?.links?.github || '').match(/github\.com\/([^/]+)\/([^/?#]+)/)
  const file = release?.apk?.file
  const version = String(release?.version || '').trim()
  if (!m || !file || !version) return null
  const tag = /^v/i.test(version) ? version : `v${version}`
  return {
    id: 'github',
    label: 'GitHub Release',
    url: `https://github.com/${m[1]}/${m[2]}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(file)}`
  }
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

  // GitHub 地址本地就能拼，探测立刻开跑，不必等 /sources 回来；
  // /sources 只负责带回来「Gitee 侧已验证的那个地址」。
  const github = githubCandidate(site, release)
  const githubProbe = github ? headTiming(github.url) : null
  const server = await serverCandidates(release)

  const list = server.slice()
  if (github && !list.some((c) => c.url === github.url)) list.push(github)

  // 已经在跑的那次探测直接复用，不重复发请求
  const probes = await Promise.all(
    list.map((c) => (github && c.url === github.url ? githubProbe : headTiming(c.url)))
  )
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
