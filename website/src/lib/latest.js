/**
 * 「最新版本」的唯一判据。
 *
 * 规则：只看已发布、有包体的版本，取版本号最大的一条。服务端的 `/api/releases`
 * 与 bootstrap 的 `releases` 都按同一套 compareVersion 全渠道倒序，所以数组首项就是
 * 版本号最大的那条——不要再按 `site.latest`（那是最新 **稳定版**）另取一份。
 *
 * 首页主按钮、首页版本号、下载页主卡片都走这里，保证「显示的版本」和「下载的版本」是同一个。
 */
export function latestRelease(bootstrap) {
  const list = (bootstrap?.releases || []).filter((r) => r?.apk?.file && r.published !== false)
  return list[0] || bootstrap?.site?.latest || null
}
