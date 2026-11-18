# 安全策略

## 报告漏洞

请**不要**在公开 Issue 中提交安全问题。通过 GitHub 的 Private vulnerability reporting（仓库 → Security → Report a vulnerability）提交，或在 Issue 中只写“需要私下联系”并留下联系方式。

请在报告中包含：受影响版本、复现步骤、影响面（是否需要用户交互）、是否已在真机验证。

## 凭据与密钥

本仓库**不允许**出现任何令牌、私钥、keystore、`local.properties`。CI 使用仓库 Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`），不得写入文件或日志。

### 一旦怀疑泄漏

1. 立即在对应平台撤销/轮换（GitHub token、Gitee token、SSH key、签名密钥）；
2. 不要试图用 `git filter-repo` 抹除后当作没发生——先轮换，再评估是否重写历史；
3. 检查 CI 日志、Release 附件、Wiki、Issue 评论是否残留；
4. 在安全公告中记录时间线（泄漏 → 发现 → 轮换 → 验证）。

## 支持范围

仅最新 beta（`main`）与最新 dev（`nightly`）接受安全修复；更早版本请先升级。
