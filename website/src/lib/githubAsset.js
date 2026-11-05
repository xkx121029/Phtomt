/**
 * GitHub Release 资产的定位规则（纯函数，便于单独验证）。
 *
 * 为什么不能拼 `tag + 文件名`：资产命名会跟着渠道后缀变
 * （`v0.2.1278` → `v0.2.9106-beta` / `v0.2.1000085-dev`），写死任何一种拼法，
 * 版本号一变就 404，然后永远选镜像。所以只认两件事：
 *   1. Release 的 tag（或标题）归一化后与站点记录的版本一致；
 *   2. 或者某个资产名直接等于站点记录的文件名。
 * 命中后再从 assets 里挑 `.apk`（优先与站点文件名同名的那个）。
 */
export function normalizeTag(value) {
  return String(value || '').trim().replace(/^v/i, '').toLowerCase()
}

/**
 * @param {Array} releases `GET /repos/{repo}/releases` 的返回（数组）
 * @param {{version?: string, apk?: {file?: string}}} release 站点侧的版本记录
 * @returns {{url: string, asset: string, tag: string} | null}
 */
export function pickGithubAsset(releases, release) {
  const file = release?.apk?.file
  const want = normalizeTag(release?.version)
  if (!want || !file || !Array.isArray(releases)) return null

  const hit = releases.find((r) => {
    if (!r || r.draft) return false
    if (normalizeTag(r.tag_name) === want || normalizeTag(r.name) === want) return true
    return (r.assets || []).some((a) => a?.name === file)
  })
  if (!hit) return null

  const assets = (hit.assets || []).filter((a) => a?.browser_download_url)
  const asset = assets.find((a) => a.name === file) || assets.find((a) => /\.apk$/i.test(a.name || ''))
  return asset ? { url: asset.browser_download_url, asset: asset.name, tag: hit.tag_name } : null
}
