import * as store from './store.js'

/**
 * 安装包只放在 GitHub / Gitee 的 Release 里，官网不再保存 APK 文件。
 *
 * 这里负责把「版本记录」翻译成外部 Release 的资产地址：
 *   - GitHub：资产地址规则固定，直接按 tag + 文件名拼；
 *   - Gitee：附件是 CI 逐个 attach 的，可能没传成功，所以查一次 Release 列表确认资产
 *     真的存在（Gitee 从 NAS 可达，GitHub 常常超时，因此只在必要时才问外部接口）。
 *
 * 为什么不缓存到 releases.json：那需要一次同步任务去维护，容易再次出现过期值。
 * 这里每次按需解析，Gitee 列表在内存里缓存 5 分钟，够用且不会写脏数据。
 */

const GITEE_API = 'https://gitee.com/api/v5'
const LIST_TTL = 5 * 60 * 1000
const FAIL_TTL = 60 * 1000
const LIST_TIMEOUT = 6000
/** `/sources` 是页面加载时调的，可以等一个短预算；302 一秒都不等 */
export const SOURCES_WAIT = 800

const giteeCache = { at: 0, repo: '', list: null, nextAt: 0, inflight: null, warned: false }

/** 从站点链接里取出 host 对应的 owner/repo */
function repoOf(url, host) {
  const m = String(url || '').match(new RegExp(`${host}/([^/]+)/([^/?#]+)`))
  return m ? `${m[1]}/${m[2]}` : ''
}

function links() {
  return store.read('site')?.links || {}
}

function normalizeTag(value) {
  return String(value || '')
    .trim()
    .replace(/^v/i, '')
    .toLowerCase()
}

/** GitHub 的 tag 约定：版本号带 v 前缀 */
function tagOf(version) {
  const v = String(version || '').trim()
  if (!v) return ''
  return /^v/i.test(v) ? v : `v${v}`
}

/** GitHub 资产地址可直接拼出来，不需要 API（NAS 访问 GitHub 常常超时） */
export function githubAsset(release) {
  const repo = repoOf(links().github, 'github\\.com')
  const file = release?.apk?.file
  const tag = tagOf(release?.version)
  if (!repo || !file || !tag) return null
  return {
    id: 'github',
    label: 'GitHub Release',
    tag,
    asset: file,
    url: `https://github.com/${repo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(file)}`
  }
}

async function fetchGiteeReleases(repo) {
  const res = await fetch(`${GITEE_API}/repos/${repo}/releases?page=1&per_page=50`, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(LIST_TIMEOUT)
  })
  if (!res.ok) throw new Error(`Gitee 返回 ${res.status}`)
  const list = await res.json()
  return Array.isArray(list) ? list : []
}

/**
 * 后台刷新 Gitee 的 Release 列表。
 *
 * 之前是「谁需要谁去拉」：点下载 → 302 里 await 一次 Gitee 接口。Gitee 正常时
 * 是几十毫秒，可一旦慢到 6 秒超时，用户就是点了按钮干等 6 秒才开始下载。
 * 现在改成启动预热 + 每 5 分钟刷新，请求路径只读内存；刷新失败沿用旧列表并退避 60 秒。
 */
export function warmGitee(repo = repoOf(links().gitee, 'gitee\\.com')) {
  if (!repo) return Promise.resolve(null)
  if (giteeCache.inflight) return giteeCache.inflight
  if (Date.now() < giteeCache.nextAt) return Promise.resolve(null)

  const task = (async () => {
    try {
      const list = await fetchGiteeReleases(repo)
      giteeCache.repo = repo
      giteeCache.list = list
      giteeCache.at = Date.now()
      giteeCache.nextAt = 0
      giteeCache.warned = false
      return list
    } catch (err) {
      // 拉不到就继续用上一次的列表：Gitee 接口抖动时，附件多半仍然能下
      giteeCache.nextAt = Date.now() + FAIL_TTL
      if (!giteeCache.warned) {
        giteeCache.warned = true
        console.warn(`[externalAssets] Gitee 列表刷新失败，先沿用上次结果：${err?.message || err}`)
      }
      return null
    } finally {
      giteeCache.inflight = null
    }
  })()
  giteeCache.inflight = task
  return task
}

/** 只读内存；缺失或过期时顺手触发后台刷新，但不阻塞调用方 */
function giteeListSync(repo) {
  if (!repo) return null
  if (!giteeCache.list || Date.now() - giteeCache.at >= LIST_TTL) warmGitee(repo)
  return giteeCache.repo === repo ? giteeCache.list : null
}

/** 启动预热 + 定时刷新；timer 不阻止进程退出 */
export function initGiteeAssets() {
  warmGitee().catch(() => {})
  const timer = setInterval(() => warmGitee().catch(() => {}), LIST_TTL)
  timer.unref?.()
  return timer
}

function matchGitee(list, release) {
  const file = release?.apk?.file
  const want = normalizeTag(release?.version)
  if (!file || !want) return null

  const hit = list.find((r) => {
    if (!r) return false
    if (normalizeTag(r.tag_name) === want || normalizeTag(r.name) === want) return true
    return (r.assets || []).some((a) => a?.name === file)
  })
  if (!hit) return null

  const assets = (hit.assets || []).filter((a) => a?.browser_download_url)
  const asset = assets.find((a) => a.name === file) || assets.find((a) => /\.apk$/i.test(a.name || ''))
  if (!asset) return null

  return {
    id: 'gitee',
    label: 'Gitee Release',
    tag: hit.tag_name,
    asset: asset.name,
    url: asset.browser_download_url
  }
}

/**
 * Gitee 侧的 APK 资产（只读缓存）。查不到（CI 没 attach 成功、接口不通、还没预热完）
 * 就返回 null，调用方会落到 GitHub；两边都没有时页面仍会给出 Release 页，不会给死链。
 */
export function giteeAssetSync(release) {
  const repo = repoOf(links().gitee, 'gitee\\.com')
  const list = giteeListSync(repo)
  return list ? matchGitee(list, release) : null
}

/** 同上，但允许为首次预热等一小段（只给 /sources 用） */
export async function giteeAsset(release, { wait = 0 } = {}) {
  const repo = repoOf(links().gitee, 'gitee\\.com')
  if (!repo || !release?.apk?.file) return null
  let list = giteeListSync(repo)
  if (!list && wait > 0) {
    await Promise.race([warmGitee(repo), new Promise((resolve) => setTimeout(resolve, wait))])
    list = giteeCache.repo === repo ? giteeCache.list : null
  }
  return list ? matchGitee(list, release) : null
}

/**
 * 按顺序给出这个版本可用的外部资产：Gitee 优先（服务端能确认资产存在，国内也快），
 * 其次 GitHub。返回空数组表示两边都定位不到，调用方应回落到 Release 页面。
 *
 * `wait` 只给页面加载时的 `/sources` 用：首次预热还没完成时最多等这么久；
 * 302 传 0，永远只读内存。
 */
export async function externalAssets(release, { wait = 0 } = {}) {
  if (!release?.apk?.file) return []
  const out = []
  const gitee = await giteeAsset(release, { wait })
  if (gitee) out.push(gitee)
  const github = githubAsset(release)
  if (github) out.push(github)
  return out
}

/** 无 JS 兜底（302）与 App 更新接口只需要一个「现在最可能拿到包」的地址 */
export async function preferredAsset(release) {
  if (!release?.apk?.file) return null
  return giteeAssetSync(release) || githubAsset(release)
}

/** 发行页地址：两边都定位不到资产时把用户送到这里，而不是一个必然 404 的直链 */
export function releasePage() {
  const l = links()
  return l.releases || l.github || 'https://github.com'
}
