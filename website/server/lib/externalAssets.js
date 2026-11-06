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
const LIST_TIMEOUT = 6000

let giteeCache = { at: 0, repo: '', list: null }

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

async function giteeReleases(repo) {
  if (giteeCache.list && giteeCache.repo === repo && Date.now() - giteeCache.at < LIST_TTL) {
    return giteeCache.list
  }
  const res = await fetch(`${GITEE_API}/repos/${repo}/releases?page=1&per_page=50`, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(LIST_TIMEOUT)
  })
  if (!res.ok) throw new Error(`Gitee 返回 ${res.status}`)
  const list = await res.json()
  const data = Array.isArray(list) ? list : []
  giteeCache = { at: Date.now(), repo, list: data }
  return data
}

/**
 * Gitee 侧的 APK 资产。查不到（CI 没 attach 成功、接口不通）就返回 null，
 * 调用方会落到 GitHub；两边都没有时页面仍会给出 Release 页，不会给死链。
 */
export async function giteeAsset(release) {
  const repo = repoOf(links().gitee, 'gitee\\.com')
  const file = release?.apk?.file
  const want = normalizeTag(release?.version)
  if (!repo || !file || !want) return null

  let list
  try {
    list = await giteeReleases(repo)
  } catch {
    return null
  }

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
 * 按顺序给出这个版本可用的外部资产：Gitee 优先（服务端能确认资产存在，国内也快），
 * 其次 GitHub。返回空数组表示两边都定位不到，调用方应回落到 Release 页面。
 */
export async function externalAssets(release) {
  if (!release?.apk?.file) return []
  const out = []
  const gitee = await giteeAsset(release)
  if (gitee) out.push(gitee)
  const github = githubAsset(release)
  if (github) out.push(github)
  return out
}

/** 无 JS 兜底（302）与 App 更新接口只需要一个「现在最可能拿到包」的地址 */
export async function preferredAsset(release) {
  const list = await externalAssets(release)
  return list[0] || null
}

/** 发行页地址：两边都定位不到资产时把用户送到这里，而不是一个必然 404 的直链 */
export function releasePage() {
  const l = links()
  return l.releases || l.github || 'https://github.com'
}
