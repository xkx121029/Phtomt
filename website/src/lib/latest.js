/**
 * 「最新版本」的唯一判据。
 *
 * 服务端按发布时间倒序给出 `releases`（数据来自 GitHub Releases），并额外给一个
 * `site.downloadTarget`：有稳定版就指向最新稳定版，没有就退回最新测试包。
 * 这里优先用 downloadTarget，再退到列表首项，保证「主按钮下载的」就是「页面上写的」。
 */
export function latestRelease(bootstrap) {
  const list = (bootstrap?.releases || []).filter((r) => r?.apk?.file && r.published !== false)
  const target = bootstrap?.site?.downloadTarget
  if (target?.version) {
    const hit = list.find((r) => String(r.version).replace(/^v/i, '') === String(target.version).replace(/^v/i, ''))
    if (hit) return hit
  }
  return list[0] || bootstrap?.site?.latest || null
}
