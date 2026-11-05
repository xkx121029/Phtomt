# 发版链路

一句话版：**GitHub Actions 出包 → GitHub Release 是权威产物 → Gitee 发行版由 CI 建壳 → 本机计划任务每 30 分钟自动补挂 APK 附件**。
最后一步已经自动化（`HPA-GiteeAttach` 计划任务），发完版不需要人工介入。

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

## 4. Gitee 附件补挂（自动：Windows 计划任务）

CI 只做两件事：建 Gitee 发行版、在 body 里写 GitHub 直链。**APK 附件不在 CI 里传**，因为 CI 出口到 Gitee 实测约 **15.7 KB/s**，71 MB 要 ~76 分钟，会把 job 拖超时（历史上 `timeout-minutes: 40` 只传到 37,617,664 / 71,312,626 字节）。

补挂改由本机计划任务做：局域网出口实测 **95 KB/s**，71 MB 约 13 分钟。

### 4.1 令牌：本机设置一次，脚本只读环境变量

```powershell
setx GITEE_TOKEN "…"     # 用户级环境变量，只做一次
```

- **需要重开终端才生效**（新开的 PowerShell / IDE 才读得到）。
- 新设置的环境变量对已登录会话里启动的计划任务可能还不可见：实测第一轮任务会以退出码 2 跳过，**5 分钟后的自动重试就读到了并成功**，不需要重启机器。
- 脚本只读 `os.environ["GITEE_TOKEN"]`，读不到就记一条明确日志并以 **2** 退出。没有硬编码、没有配置文件兜底、日志里不回显令牌。
- 不要把令牌写进任何提交。

### 4.2 计划任务 `HPA-GiteeAttach`

| 项 | 值 |
|----|----|
| 动作 | `C:\Program Files\Python39\pythonw.exe "…\tool\gitee_attach.py"`，工作目录=仓库根 |
| 触发器 | ① 用户登录时；② 每 30 分钟重复，无结束时间 |
| 运行身份 | 当前用户，`InteractiveToken`，`LeastPrivilege`（不要求以管理员运行） |
| 单次上限 | 40 分钟（71 MB @ 95 KB/s ≈ 13 分钟，留足余量） |
| 失败重试 | 2 次，间隔 5 分钟 |
| 并发 | `MultipleInstancesPolicy=IgnoreNew`（OS 层）+ 脚本内文件锁（双保险） |
| 日志 | `%LOCALAPPDATA%\hpa\gitee-attach.log`，按天滚动，保留 14 天 |
| 锁文件 | `%LOCALAPPDATA%\hpa\gitee-attach.lock` |

换机器/删掉后重建（PowerShell，普通用户权限即可）：

```powershell
$repo = "d:\xkx\xkx_appproj\happy_phone agent"
$act  = New-ScheduledTaskAction -Execute "C:\Program Files\Python39\pythonw.exe" `
          -Argument ('"' + $repo + '\tool\gitee_attach.py"') -WorkingDirectory $repo
$t1   = New-ScheduledTaskTrigger -AtLogOn
$t2   = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) `
          -RepetitionInterval (New-TimeSpan -Minutes 30) -RepetitionDuration (New-TimeSpan -Days 3650)
$set  = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -RestartCount 2 `
          -RestartInterval (New-TimeSpan -Minutes 5) -ExecutionTimeLimit (New-TimeSpan -Minutes 40) `
          -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$prn  = New-ScheduledTaskPrincipal -UserId "$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName "HPA-GiteeAttach" -Action $act -Trigger @($t1,$t2) `
  -Settings $set -Principal $prn -Description "补挂 APK 到 Gitee 发行版" -Force
# 若 Register-ScheduledTask 报 0x80070005（权限），改用 schtasks /create /xml <导出文件> /f
```

> 注册时踩过一次：`Register-ScheduledTask` 在本机返回 `HRESULT 0x80070005 Access denied`，改用 `schtasks.exe /create /tn … /xml …` 成功。任务本身仍是普通用户权限。

### 4.3 脚本行为

- **自动挑选**：取 GitHub 发行版列表里 `created_at` 最新、且带 `.apk` 附件的那个（dev/beta 都覆盖）。也可 `--tag v0.2.9119-beta` 指定，`--apk` 指定本地文件（跳过下载）。
- **下载**：走 `api.github.com/repos/.../releases/assets/<id>`（`Accept: application/octet-stream`）。**不能**用 `github.com/.../releases/download/...` 直链——本机实测 `(56) Connection was reset`，无人值守扛不住。带 `-C -` 断点续传与 6 次重试，下载后校验字节数。
- **幂等**：Gitee 上已有同名且字节数一致的附件则跳过（`0` 退出）。
- **单实例**：文件锁拿不到直接以 `3` 退出，不叠加传输。
- **服务端校验**：上传后回查 `attach_files`，字节数不符以 `5` 退出。
- **重试**：Gitee API 3 次（5/10 秒退避）；上传失败重试 1 次。

### 4.4 验收记录（2026-11-06，`v0.2.9119-beta`）

| 步骤 | 证据 |
|------|------|
| 计划任务自己下载并上传 | 日志 `03:58:10 开始` → `04:11:19 完成：HappyAgent-0.2.9119-beta.apk = 71348294 B，耗时 771s`（95 KB/s） |
| 删除附件后能自动补回来 | 删除 `attach id=3606185`（HTTP 204），下载链接变 `404`；任务跑完出现新 `attach id=3606213`，`71,348,294 B`，`curl -I` → `HTTP=200 application/zip` |
| 幂等（下一轮 30 分钟触发） | `04:11:58 跳过：HappyAgent-0.2.9119-beta.apk 已挂载且字节数一致（71348294 B）` |
| 并发保护 | `03:41:42 上一轮仍在传输（锁被占用），本次跳过`，任务 `Last Result=3` |
| 令牌未设置 | 日志 `未设置环境变量 GITEE_TOKEN，本次跳过`，进程 `exit=2`，Gitee 上附件数不变（无半截文件） |
| 任务状态 | `Status: Ready`，`Last Run Time: 4:11:41`，`Last Result: 0`，`Next Run Time: 4:41:40` |

## 5. 发版后到底谁做什么

| 环节 | 自动发生 | 需要人工 |
|------|----------|----------|
| push 到 `main` / `nightly` | GitHub Actions 构建、跑门禁、断言签名与编码运行时、建 GitHub Release、建 Gitee 发行版（body 写 GitHub 直链） | 无 |
| 版本号自增 | CI 读写 `nightly` 分支的 `version.properties` | 无 |
| Gitee APK 附件 | 本机计划任务每 30 分钟轮询，自动下载 + 上传 + 校验 | 无（只要机器开着且已登录） |
| 失败重试 | 任务层 2 次 / 5 分钟；脚本层 API 3 次、上传 1 次 | 连续多轮都失败时看日志 |
| 首次配置 | — | `setx GITEE_TOKEN`、注册计划任务（见 4.1 / 4.2） |
| 补历史版本（如 `v0.2.9118-beta`） | 自动轮询只处理最新那个 | `python tool/gitee_attach.py --tag v0.2.9118-beta` |

## 6. 出问题怎么排查

先看一个文件：`%LOCALAPPDATA%\hpa\gitee-attach.log`（按天滚动，保留 14 天，历史文件形如 `gitee-attach.log.2026-11-05`）。

| 现象 | 先看哪里 | 退出码含义 / 常见原因 |
|------|----------|------------------------|
| Gitee 上没有附件，任务却显示「就绪」 | 日志末尾几行 | **0** 成功（正常，含「已挂载，跳过」）；**3** 上一轮还没跑完，本轮让路（无害，等下一轮） |
| 日志只有「未设置环境变量 GITEE_TOKEN」 | 检查 `setx` 是否执行、终端是否重开 | **2** 令牌没读到。重新 `setx GITEE_TOKEN "…"`，或登出登入一次 |
| 日志「Gitee API 调用失败（已重试 3 次）」 | 该行的 `rc=` 与响应片段 | **4** 网络/令牌失效/接口限流。`rc=56` 多为链路 reset；`rc=28` 超时；响应里 `401` 说明令牌过期 |
| 日志「Gitee 附件校验失败：服务端 None B」 | 上传那一轮的 `curl 返回（第 n 次，rc=…）` | **5** 上传没落盘或字节数不符。下一轮会自动重试；连续出现查出口带宽/磁盘 |
| 日志「读取 GitHub 发行版失败」 | 同上 | **4** GitHub 匿名接口限流（60 次/小时/IP）或断网 |
| 日志「下载不完整」 | 缓存目录 `.gitee-cache` | **5** 断点续传残留半包；删掉该文件让下轮重下 |
| 任务根本没在跑 | `schtasks /query /tn HPA-GiteeAttach /v /fo LIST` 看 `Status` / `Next Run Time` | 任务被禁用或触发器丢失 → 按 4.2 重新注册 |
| 想知道某轮到底挑了哪个版本 | 日志 `开始：pid=… tag=(自动)` 之后的「下载/复用」行 | 挑的是 `created_at` 最新的带 APK 发行版 |

手动触发一轮（等价于计划任务自己跑，用来验证）：

```powershell
schtasks /run /tn HPA-GiteeAttach
Get-Content "$env:LOCALAPPDATA\hpa\gitee-attach.log" -Tail 20 -Encoding UTF8
```

## 7. Gitea 的定位

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
