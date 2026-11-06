# server/data 说明

## releases.json 是「离线兜底」，不是数据源

站点展示的版本列表、最新版本、下载入口，全部来自 **GitHub Releases**（服务端每 10 分钟
拉一次，见 `server/lib/releaseFeed.js`）。这里的 `releases.json` 只在 GitHub 不可达时顶上：

- 列表项会带 `source: "local"`，接口的 `releaseSource.degraded` 为 `true`，页面会出现
  「当前展示的是本地兜底的版本数据，可能不是最新」；
- GitHub 恢复后下一次刷新会自动切回，不需要重启服务。

**不要靠手改这个文件来「发布新版本」**：它不会影响首页/下载页展示的最新版。
要发版就把 APK 传到 GitHub 的 Release（CI 已经这么做），官网会自动跟上。

## 其它文件

`site.json` / `features.json` / `changelog.json` / `docs.json` / `faq.json` /
`scenarios.json` / `roadmap.json` / `stats.json` 都是正常的内容数据，后台可编辑。
