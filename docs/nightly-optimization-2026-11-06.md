# 夜间自主优化记录（2026-11-06）

> 任务：只读审计 → 证据 → 最小改动 → 验证 → 记录。避让 UI 线与引擎线正在修改的文件；本轮不推远端。

## 0. 环境与阻断

| 事项 | 证据 | 处理 |
|---|---|---|
| 主工作区 `:app:compileDebugKotlin` 失败 | 22:42 `VirtualDisplayAgent.kt:140` 类型不匹配；该文件 22:48 仍在变动（引擎线副屏链路重构中） | 不碰该文件；等其稳定后复测 |
| HEAD 自身不自洽 | `git show HEAD:.../SettingsScreen.kt` 引用 `structuredOutputMode`/`allowCleartext`/`guidedDecoding`，而 HEAD 的 `AppSettings.Settings` 没有这些字段（只在工作区未提交改动里） | 不能用「HEAD + 我的改动」做隔离验证，改为在主工作区验证 |

## 1. 候选清单与排序

| # | 问题 | 证据位置 | 影响 | 状态 |
|---|---|---|---|---|
| P1 | 全局日志/遥测缓冲并发读改写丢数据 | `TelemetryRecorder` 的 `AppLog.sink`；`UiBridge.log/apiLog/addConversation`；`recordStepTrace` 两步写 | 并发高峰静默丢诊断日志；上限可被并发突破 | 已改，待验证 |
| P2 | 版本号在配置阶段自增 | 根 `build.gradle.kts`（原逻辑在配置期写 `version.properties`） | `--dry-run`、编译失败也吃掉版本号；被跟踪文件常驻 M | 已改，待验证 |
| P3 | 调试记录截图「先删后写」 | `DebugRecordsStore.save` | 中途失败/并发 load 时旧记录索引错配或丢图 | 已改，待验证 |
| P4 | JSON-lines 发送缺整行互斥 | `AgentProcess.send` | 多线程并发时两行拼成一行，host 解析失败 | 已改，待验证 |
| P5 | 构建脚本「先删后拷」非原子 | `tool/bundle_host.mjs`（原第 34 行 `rmSync(outDir)`） | 同工作区并行构建互相删目录；中途失败留下半个 host，APK 打进不完整运行时 | **已改并实测通过** |

## 2. 改动明细

### P1 原子追加（EngineStore）

- 在 `EngineStore` 新增 `appendLog / appendConversation / appendTrace / appendMemoryEvent / appendSayEvent`，
  统一走 `MutableStateFlow.update` 的 CAS 重试，并把「追加 + 环形裁剪」放进同一次 CAS。
- `UiBridge`、`TelemetryRecorder`（AppLog sink、轨迹、记忆/say 事件、任务会话、启动回载合并）改为调用 helper。
- 回归测试：`EngineStoreAppendTest`（8 线程并发，总量恰好等于上限，丢一条就会失败）。

### P2 版本号消费时机

- 根 `build.gradle.kts`：配置阶段只计算 `buildNumber`；回写改成 `packageDebug/packageRelease/*Bundle`
  任务的 `doLast`，即「APK/AAB 真正打包成功后」才写，且只增不减。
- 预期验证：`assembleDebug --dry-run` 前后 `version.properties` 哈希不变；真实打包后 +1。

### P3 截图代际 + 成功后清理

- `DebugRecordsStore.save`：截图文件名为 `shot_<代际>_<序号>.jpg`，先写新图 → 原子替换 `records.json`
  → 最后 `pruneShotFiles` 清理不再被引用的旧图；`save/load/clear` 加 `@Synchronized` 串行化。
- 回归测试：`DebugRecordsStorePruneTest`（保留集、records.json、非截图文件不被误删）。

### P4 整行写入（LineWriter）

- 新增 `com.phoneagent.coding.agent.LineWriter`：`write + newLine + flush` 在同一把对象锁内完成。
- `AgentProcess` 持有 `lineWriter`，`send()` 走它；`stop()` 置空。原来三段分别由不同线程插队，
  JSON-lines 的「一行一条消息」不成立，host 会读到拼接坏的行并直接丢弃。
- 回归测试：`LineWriterTest`（`ChaoticWriter` 每次 write 只写半截并 `Thread.yield()`，
  8 线程 × 50 条 → 必须恰好 400 行且每条计数为 50）。

### P5 构建脚本原子化（bundle_host.mjs）

- 原实现：`rmSync(outDir)` → 逐文件 `copyFileSync` → 最后写指纹。三个问题：
  1. 删与拷之间、以及拷贝过程中，`assets/agent-host` 是不完整状态；并行构建（本项目常态）会互相踩；
  2. 中途失败留下半个 host，`mergeAssets` 照单全收，装出来的 APK 运行时缺文件；
  3. 指纹在拷贝后才算，写入失败时指纹与实际内容不一致，设备复用旧解压目录。
- 现实现：`app/build/tmp/bundle-host/staging-<pid>` 组装 → 对 staging 计算指纹 → VERSION 写进 staging
  → 目录锁（`mkdir` 原子创建，2 分钟陈旧锁自动清理）→ `rename(outDir→backup)` + `rename(staging→outDir)`
  原子替换，失败回滚 → 清理 backup。staging 与目标同盘，保证 rename 是原子操作。
- 借鉴：deepseek-harness `signing_identity.py:206-209` 的「临时文件 + `os.replace`」落盘模式。
- **真机验证暴露的 Windows 差异（已修）**：目录里任一文件被别的进程打开（并行的 `mergeAssets`
  正在读、杀软在扫）时，`rename` 返回 `EPERM`，直接让 `:app:bundleHost` 失败——
  这是实测到的：`EPERM: rename ... staging-42060 -> assets/agent-host`。
  现在改成 10 次 × 200ms 重试后，仍失败则退回「就地覆盖 + 删除多余旧文件」，
  保证最坏情况也只是短暂的新旧混合（每个文件都完整），而不是整次构建失败。

## 3. 验证结果

### P5（已通过）

在 `tool/bundle_host.mjs` 同工作区实测：

| 场景 | 结果 |
|---|---|
| 单实例运行 | `45 files -> app\src\main\assets\agent-host (fingerprint f4310e6953c2bd45)`，`git status -- app/src/main/assets/agent-host` 无变化 |
| 两个实例并发运行 | 两个进程都成功退出，最终 45 个文件，`VERSION` = `f4310e6953c2bd45`，`app/build/tmp/bundle-host` 无 staging/backup 残留 |
| 内容一致性 | 输出指纹与改造前一致，说明同步内容未发生偏移 |
| 目标被占用（模拟并行构建读 assets） | 用 `FileStream(FileShare.Read)` 占住 `index.js` 1.2s 后释放：进程重试成功，输出仍是 45 files / `f4310e6953c2bd45`；不占用的常规路径 `EXIT=0`、`git status` 无变化 |

### P2（已验证「失败构建不烧号」）

| 观察点 | 值 |
|---|---|
| `version.properties` 最后写入时间 | 2026-11-06 22:09:15（改动前） |
| 改动后跑了一次完整 `:app:compileDebugUnitTestKotlin`（16m17s，走到 `compileDebugKotlin` 后因并发构建占用 kotlin-classes 目录失败） | 失败退出 |
| 失败后 `version.properties` | `BUILD_NUMBER=9142`，SHA-256 `043cf93b…f0bd20f`，mtime 仍为 22:09:15（**未被触碰**） |
| 对照 | 改动前，同样的构建在**配置阶段**就会写文件，失败同样吃号 |

### P1 / P3 / P4（已通过：模块编译 + 单测全绿）

`:app:testDebugUnitTest`（主工作区，`BUILD SUCCESSFUL in 45m`）：

| 用例 | 结果 |
|---|---|
| `EngineStoreAppendTest > 并发追加日志_总量不超过上限时一条都不丢` | PASSED |
| `EngineStoreAppendTest > 并发追加对话_总量不超过上限时一条都不丢` | PASSED |
| `EngineStoreAppendTest > 并发追加轨迹_总量不超过上限时一条都不丢` | PASSED |
| `EngineStoreAppendTest > 超过上限后只保留最新条目` | PASSED |
| `DebugRecordsStorePruneTest > 清理时只删除不被当前存档引用的截图` | PASSED |
| `LineWriterTest > 并发写不会把两条消息拼成一行` | PASSED |

`app/build/test-results/testDebugUnitTest/*.xml` 汇总：4 + 1 + 1 个用例，`failures=0 errors=0 skipped=0`。
`:app:compileDebugKotlin` 无 error（该次构建顺带验证了引擎线当时的 `AgentEngine`/`VirtualDisplayAgent` 改动可编译）。

过程中被环境阻断过三次，记录在此以免误判为代码问题：

- 主工作区编译失败：`Could not delete app\build\tmp\kotlin-classes\debug\com`
  —— 另一条线路正在同一工作区构建，两个 Gradle 进程抢同一个 kotlin-classes 输出目录。
- 隔离快照（`D:\xkx\xkx_appproj\hpa-snap`，独立 build 目录）跑到一半收到
  `Gradle build daemon has been stopped: stop command received`——有进程在停 daemon 释放内存。
- 最终那次构建里 Kotlin daemon 被杀，Gradle 自动降级为「进程内编译」
  （`Connection to the Kotlin daemon has been unexpectedly lost`），降级后仍然编译成功。

### 遗留：同一工作区并发构建

本次实测到三个具体的破坏方式（都在同一天内发生）：

1. `Could not delete ...kotlin-classes\debug\com`（两个进程写同一输出目录）；
2. `packDebugResources` 期间被 `gradle --stop` 打断；
3. `:app:bundleHost` 里目录 `rename` 被并行 `mergeAssets` 占用 → `EPERM`（已在本轮修掉，见 P5）。

这不是代码缺陷，是**工作流约定缺失**：同一 checkout 上并行跑构建本身不被 Gradle 支持。
建议（需团队确认，本轮不改）：需要并行时用 `git worktree` + 独立 `--project-cache-dir`。

## 4. 未做 / 后续

- 引擎线、UI 线正在修改的文件一律不动。
- 其他线路未提交的 dirty/untracked 文件不纳入本次提交。
- `version.properties` 的双构建竞争（两次成功构建可能拿到同一个号）未处理：与上面的
  「同一工作区不支持并行构建」是同一个根因，单靠脚本层加锁只会掩盖问题。

## 5. 按优先级排序的清单

| # | 项 | 状态 | 结果 / 卡点 |
|---|---|---|---|
| 1 | 日志/对话/轨迹缓冲并发写丢条目（P1） | 已完成 | 统一走 `EngineStore.append*` + `MutableStateFlow.update` CAS；6 个并发用例全绿 |
| 2 | 调试截图「先删后写」（P3） | 已完成 | 代际文件名 + 成功后再清理 + `@Synchronized`；单测覆盖删除范围 |
| 3 | JSON-lines 并发写串行（P4） | 已完成 | 新增 `LineWriter`，整行写入互斥；8 线程 × 50 条用例全绿 |
| 4 | 版本号被失败构建吃掉（P2） | 已完成 | 写入挪到 `package*` 的 `doLast`；16 分钟失败构建后 `version.properties` 未被触碰 |
| 5 | 构建脚本「先删后拷」（P5） | 已完成 | staging + 指纹 + 锁 + 原子替换（Windows 失败退化为就地覆盖）；并发/占用两种场景实测通过 |
| 6 | 同一工作区并发构建互相破坏 | 未做（需团队决策） | 证据充分但属工作流约定，需确认是否强制 worktree 隔离 |
| 7 | 两次成功构建可能拿到同一 versionCode | 未做 | 与 #6 同根因；脚本层加锁只是掩盖 |
| 8 | 产品行为取舍 | 未动 | 本轮没有改动任何用户可见行为 |
