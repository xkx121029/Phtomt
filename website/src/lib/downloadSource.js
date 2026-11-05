import { apiBaseUrl, staticOnly } from '../api/client.js'
import { decideSource } from './downloadRank.js'
import { pickGithubAsset } from './githubAsset.js'

/**
 * 下载源择优（在用户浏览器里实测）。
 *
 * 为什么不在服务端测：服务端跑在家里 NAS 上，它到 GitHub 的连通性与用户完全不同
 * （实测 NAS 直连 GitHub 超时，而本机 1 秒内就能拿到首字节），拿它的结果排序会给用户
 * 指一条自己打不开的线。所以这里对两个地址各做一次 HEAD：
 *   - 本站直链（现有镜像）：同源 HEAD，能读到状态码；
 *   - GitHub Release 资产：先用 GitHub API 确认该版本确实有资产（API 带 CORS，
 *     资产地址本身也能顺带拿到），再对资产地址做 no-cors HEAD 量首字节。
 * 只发 HEAD、不读响应体，不为测速下载整个 APK；单源超时 2.5 秒。
 *
 * 判据：GitHub 首字节不超过镜像的 1.15 倍（含 100ms 抖动余量）就用 GitHub。
 * 结果按会话缓存 15 分钟（全部不通只缓存 2 分钟，好让线路恢复后尽快纠正）。
 */

const CACHE_KEY = 'phtomt-download-source'
const CLIENT_TTL = 15 * 60 * 1000
const DEGRADED_TTL = 2 * 60 * 1000
const PROBE_TIMEOUT = 2500
/** 首字节超过这个时间就算「通但慢」 */
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
 * 调用方回落到默认直链——避免把别的版本的链接（或过期结论）套到这个版本上。
 */
export function cachedDownloadSource(version) {
  return version ? readCache(String(version)) : null
}

/** 本站直链：静态站指向随站点发布的 apk，有后端时走运行时上传目录 */
export function directApkUrl(release) {
  const file = release?.apk?.file
  if (!file) return ''
  if (staticOnly) return `${import.meta.env.BASE_URL}apk/${file}`
  return `${apiBaseUrl()}/apk/${encodeURIComponent(file)}`
}

/** 从站点链接里取出 GitHub 仓库坐标 */
function repoOf(site) {
  const m = String(site?.links?.github || '').match(/github\.com\/([^/]+)\/([^/?#]+)/)
  return m ? `${m[1]}/${m[2]}` : ''
}

/** 只发 HEAD，量到响应头为止；no-cors 用于跨域资产（拿不到状态码，只判通与不通） */
async function headTiming(url, mode) {
  const started = performance.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT)
  try {
    const res = await fetch(url, {
      method: 'HEAD',
      mode,
      cache: 'no-store',
      redirect: 'follow',
      signal: controller.signal
    })
    const ms = Math.round(performance.now() - started)
    if (mode === 'cors' && !res.ok) {
      return { status: 'down', detail: `HTTP ${res.status}` }
    }
    return { status: ms > SLOW_MS ? 'slow' : 'ok', ttfbMs: ms }
  } catch (err) {
    return { status: 'down', detail: err?.name === 'AbortError' ? '超时' : '网络不可达' }
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 查 GitHub 是否真的有这个版本的资产。
 * 定位规则见 githubAsset.js（按版本匹配，不拼固定 tag/文件名）。
 * API 不可达（国内常见）、限流、或该版本没传资产，都返回 null —— 等同于该源不可用。
 */
async function findGithubAsset(site, release) {
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

/**
 * 决定用哪个源。返回 null 表示没法测（静态站/缺包体），调用方保持默认链接。
 * degraded=true 表示两条线都没测通：url 为空，页面继续用默认直链。
 */
export async function resolveDownloadSource(release, { site, refresh = false } = {}) {
  if (!release?.version || !release.apk?.file || staticOnly) return null

  const cached = refresh ? null : readCache(release.version)
  if (cached) return cached

  const originUrl = directApkUrl(release)
  // API 查资产与镜像 HEAD 并行，省掉一段首尾相接的等待
  const [github, originProbe] = await Promise.all([
    findGithubAsset(site, release),
    headTiming(originUrl, 'cors')
  ])
  const githubProbe = github
    ? await headTiming(github.url, 'no-cors')
    : { status: 'down', detail: '未找到 Release 资产' }

  const decided = decideSource({
    githubProbe,
    originProbe,
    githubUrl: github?.url || '',
    originUrl
  })

  const payload = {
    version: release.version,
    source: decided.source,
    url: decided.url,
    degraded: decided.degraded,
    measuredAt: new Date().toISOString(),
    candidates: [
      { id: 'github', label: 'GitHub Release', url: github?.url || '', status: githubProbe.status, ttfbMs: githubProbe.ttfbMs ?? null, detail: githubProbe.detail || '' },
      { id: 'origin', label: '本站直链', url: originUrl, status: originProbe.status, ttfbMs: originProbe.ttfbMs ?? null, detail: originProbe.detail || '' }
    ]
  }

  writeCache(payload)
  return payload
}

/** 主按钮上的极轻状态提示：几百毫秒内测完就不出现，避免闪一下 */
export const PROBE_HINT_DELAY = 300
