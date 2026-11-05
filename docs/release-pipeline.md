# 发版链路

一句话版：**GitHub Actions 出包 → GitHub Release 是权威产物 → Gitee 发行版由 CI 建壳、由本机补挂附件**。
最后一步不是全自动，发完版要在本机跑一条命令（见「Gitee 附件补挂」）。

## 1. 谁触发、出什么包

| 触发 | 渠道 | 版本号 | 产物 |
|------|------|--------|------|
| push `main` | beta | `0.2.<N>-beta` | `HappyAgent-0.2.<N>-beta.apk` |
| push `nightly` | dev | `0.2.<N>-dev` | `HappyAgent-0.2.<N>-dev.apk` |
| `workflow_dispatch` | 手选 beta/dev | 同上 | 同上 |
| PR → `main` | 不发布 | — | 只跑门禁与构建 |

- 版本号唯一权威副本是 **`nightly` 分支上的 `version.properties`**。beta 也读它的 `BUILD_NUMBER`，跑完再把 `+1` 写回同一文件，避免两条分支各长一个计数器。
- 工作流 `concurrency` 刻意不带分支名：dev/beta 共用这一个计数器，必须串行读-自增-回写。
- APK 是 **debug 变体 + 固定 debug keystore**（`secrets.DEBUG_KEYSTORE_B64`），证书 SHA-256 `2b67b91a…3cbeed`，与本机 `hpa-publish` 的包同签名，可互相覆盖安装。

## 2. 出包前的两道硬断言

`android-ci.yml` 里出包之后、上传之前有两步会直接让 run 变红，不靠人肉发现：

1. **`Verify APK signature`** —— `apksigner` 验最终 APK 的证书指纹必须等于 `2b67b91a…3cbeed`。只检查「keystore 文件被还原」不够：曾经发生过 keystore 还原成功但 AGP 没用它、三次 CI 包指纹各不相同的情况。
2. **`Assert bundled coding runtime`** —— 用 `unzip -l` 检查最终 APK 必须含 `lib/arm64-v8a/libnode.so`、三个 `libicu*`、`assets/agent-host/host-stdio.js`。缺任何一个即失败。

> 为什么加第 2 条：`v0.2.9114-beta` 曾把**不含编码运行时**的包发出去，CI 全绿。缺 `libnode.so` 时 `RuntimeBootstrap.runtime()` 会抛「libnode.so 不在 nativeLibraryDir」，编码 Agent 完全不可用，而 beta 恰恰是「相对正式」的那条线。

这些产物必须**入库**（`app/src/main/jniLibs/arm64-v8a/*.so`、`app/src/main/assets/agent-host/`），因为 CI 只检本仓库，没有上游仓库可拉。
`.gitignore` 里对它们的豁免规则放在**文件末尾**——后面还有全局 `*.so` 规则，写在前面会被重新忽略。

## 3. GitHub 侧

- APK 挂 `GitHub Release`（tag = `v0.2.<N>-<channel>`），同时作为 Actions artifact 保留 14 天。
- **下载走 Actions artifact 接口**，不要用 Release 直链：
  `GET https://api.github.com/repos/xkx121029/Phtomt/actions/artifacts/<id>/zip`
  （会 303 到 `*.blob.core.windows.net`，本机可用；`github.com:443` 的 Release 直链实测会被阻断 / 偶发 reset。）

## 4. Gitee 附件补挂（**手动，一条命令**）

CI 只做两件事：建 Gitee 发行版、在 body 里写 GitHub 直链。**APK 附件不传**，因为 CI 出口到 Gitee 实测约 **15.7 KB/s**，71 MB 要 ~76 分钟，会把 job 拖超时（历史上 `timeout-minutes: 40` 只传到 37,617,664 / 71,312,626 字节）。

本机（局域网出口实测 89–96 KB/s，71 MB 约 13 分钟）补挂：

```powershell
$env:GITEE_TOKEN = "<令牌>"
python tool/gitee_attach.py                 # 自动挑最新的 dev/beta 发行版
# 或指定版本 / 指定本地包
python tool/gitee_attach.py --tag v0.2.9117-dev --apk .\HappyAgent-0.2.9117-dev.apk
```

脚本行为：幂等（同名同字节数跳过）、服务端字节数校验、下载带 `-C -` 断点续传与重试（跨洋下载偶发 reset，不重下整包）。
令牌只从环境变量读，不落盘、不回显。

> **这不是自动化。** 发版流程的最后一步依赖「有人在本机跑上面这条命令」。
> 还没做的自动化选项：Windows 计划任务定时轮询 GitHub Release、或监听 release webhook 自动补挂。做之前需要先解决令牌怎么安全地交给这个常驻进程。

## 5. Gitea 的定位

现状：Gitea 只是 **GitHub 的镜像**（`main` / `nightly` 两条分支内容与 GitHub 对齐），不承担构建。

**Gitea Actions 为什么没启用**：

- 需要在局域网侧常驻一个 `act_runner`，并且它要能跑 Android 构建——JDK 17 + Android SDK 36 + NDK + `apksigner`，镜像几 GB；每次构建还要占 4 核以上 10 分钟以上，NAS 上跑会挤占其它服务。
- 已有 GitHub Actions 覆盖构建与发布，Gitea 再跑一遍是重复消耗，且两条 CI 会争抢 `version.properties` 这一个计数器。
- 收益是「断网也能出包」，当前价值不足以抵消上面的成本。

**重新启用的前置条件**（任一未满足就不要开）：

1. 有一台常驻机器（≥4 核 / 8 GB 内存 / Docker 可用）跑 `act_runner`，且有预构建好的 Android 构建镜像；
2. Gitea 版本已开启 Actions（1.19+），runner 注册到 `xkx/happy_phone_agent` 仓库；
3. 解决版本号归属：明确只允许一条 CI 自增 `version.properties`（另一条只读），否则会拿到同一个号；
4. 在 Gitea 侧配好 `DEBUG_KEYSTORE_B64` / `DEBUG_KEYSTORE_PASS`，否则签名断言会红；
5. 明确 Gitea 侧的产物去处（Gitea 内建 release，还是仅作构建校验）。
