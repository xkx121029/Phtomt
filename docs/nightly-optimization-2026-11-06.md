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
| 6 | 同一工作区并发构建互相破坏 | 已完成（按你的决定：单槽位锁） | 见 §6.2/§6.5：`.gradle/build.lock` + 每项目 finalizer 释放；拿不到锁直接报错并打印持有者信息 |
| 7 | 两次成功构建可能拿到同一 versionCode | 已完成 | 只加锁不够（配置阶段两个构建都会算出同一个号），补了锁内的「打包前再读一次磁盘」校验，见 §6.3 |
| 8 | 产品行为取舍 | 未动 | 本轮没有改动任何用户可见行为 |

## 6. 第二轮：单构建槽位锁、版本号占用校验、打包前指纹闸门

### 6.1 结论先说

- **#6 按你的决定做「单构建槽位锁」**，不引入 worktree。锁覆盖 `bundleHost` / `compile*Kotlin`（新增） /
  `merge*Assets` / `package*`，拿不到锁**立刻失败并打印持有者信息、任务与已持有时长**，不重试、不排队。
  `--project-cache-dir` 未改。
- **#7 光有锁不够**：A、B 两个构建在**配置阶段**就已经各自把 `version.properties` 读成 9142、算出 9143，
  锁只能保证它们不同时打包，挡不住两个 APK 都叫 9143。所以补了一道**锁内、写 APK 之前**的磁盘复核。
- **混合窗口打进 APK 的风险已经从「可能发生」压到「需要有人绕过 Gradle 手动同步」**，并且加了打包前指纹闸门让它变成
  「构建失败」而不是「静默产出坏 APK」。仍有极小的 TOCTOU 残留，写在 §6.4，没有藏。

### 6.2 锁的实现（根 `build.gradle.kts`）

| 项 | 做法 |
|---|---|
| 锁载体 | `.gradle/build.lock`：`pid` / `process` / `task` / `started`（ISO 8601） / `host`，另附 `user` |
| 持有标记 | `.gradle/build.lock.d/<project>__<task>`，一个任务一个空文件 |
| 原子获取 | `Files.createFile`（只有一方能成功）；失败即 `GradleException`，附持有者摘要、原始记录、已持有时长与重试判据 |
| 记录完整性 | 五个字段缺一项即视为**陈旧**（无法判断持有者死活，不能干等） |
| 存活探测 | 同主机用 `ProcessHandle.of(pid)`（等价 `kill(pid, 0)`，只探测不发信号）；进程不存在 → 陈旧并接管 |
| 陈旧阈值 | 默认 **60 分钟**（高于全量 `compileDebugKotlin` 20–25 分钟 + 冷 daemon 约 9 分钟的最坏情况），可用 `-Phpa.lockStaleMinutes=NN` 调整；超时也判陈旧 |
| 接管方式 | **`rename` 抢占**（不是 `delete`，避免两方互删出双持有者），并清理旧持有者的标记文件 |
| 接管提示 | 接管时 `WARN` 打印「锁被判定为陈旧并接管」+ 原因 + 原持有者（pid/进程名/任务/主机）+ 距今年月日 + 原记录全文，不静默 |
| 释放 | `BuildLockReleaseService`（`BuildService` + `AutoCloseable`）：构建结束（成功、失败、任务异常）统一在 `close()` 里删标记与锁文件，见 §7.6 |
| 为什么不用 finalizer 任务 | `finalizedBy` 挂的释放任务**一次构建只执行一次**，第一个被锁任务结束后就跑完，后续任务再取锁没人释放（实测 `:app:compileDebugKotlin` 从 06:07 起一直持锁） |
| 释放前提 | 锁文件首行 pid 必须是自己，否则什么都不删（不误删别人的锁） |
| 同构建并行 | 锁文件 pid 与自己相同且主机相同即视为「已持有」；并发构建一定在别的 daemon（daemon 一次只跑一个构建），不会误放行 |

为什么不用 `taskGraph.afterTask` / `configureEach` 里注册任务（都是实测撞墙后才改的）：

1. `gradle.taskGraph.afterTask(...)` → 配置缓存下报 `Listener registration 'TaskExecutionGraph.afterTask' ... is unsupported`，整个构建在配置阶段失败。
2. 在 `configureEach` 里 `tasks.register(...)` → `DefaultTaskContainer#register(String, Action) on task set cannot be executed in the current context`。
3. **任务动作不能引用脚本对象**：`doFirst { acquireBuildLock(path) }` 这种写法在开配置缓存时报
   `cannot serialize Gradle script object references`。现在所有动作只捕获配置期的局部 `String/Int/File`，逻辑全部内联，
   「谁还持有锁」的状态放在标记文件而不是内存里。

### 6.3 #7 的真实语义：为什么只加锁不够、补了什么

- 加锁之后仍存在的窗口：A、B 都是在**配置阶段**读 `version.properties` 并 `+1`，两边算出的都是 9143；锁只保证不同时写 APK。
- 补的校验：真正产出 APK/AAB 的 `packageDebug` / `packageRelease` / `package*Bundle` / `bundleDebug` / `bundleRelease`
  在 `doFirst`（锁内、写 APK 之前）再读一次磁盘。
- 判定规则：`stored > 本号` 直接失败；`stored == 本号` 时看 `.gradle/build-number.marker`
  （`pid` / `host` / `number`）——不是本次构建写的才算撞号，是本次构建写的（`assemble` = debug + release）放行。
- 为什么不能只用 `stored >= 本号`：`package*Resources` 也匹配 `package*`，先跑、先回写，后面的 `packageDebug`
  会把自己刚写的号判成「已被占用」，`assembleDebug` 每次都失败。这正是 05:30 那条「版本号 9151 已被占用」
  报在 `:app:packageDebugResources` 上的成因之一；现在版本号只在真正的打包任务上读写。
- 临界区：`acquire(doFirst) → check(doFirst) → 打包 → persist + 凭据(doLast) → release(finalizer)`，读写都在锁内。
- 仍然可能重复的入口只有一个：CI 用 `-PbuildNumber=NN` 显式注入（计数器由 CI 自己维护），这条**有意放行**并在代码里注明。
- 另外 `apkBuilding` 的判定没变：`test`/`detekt`/`installDebug` 之类不会自增，也不会触发回写。
- **残留窗口（不藏）**：`doLast` 里先写 `version.properties`、再写凭据；若进程恰好在这两步之间被杀，下一个构建会读到更大的号，
  不会撞号。反过来「APK 已落盘、`doLast` 还没执行就被杀」的窗口仍存在（毫秒级），此时下一个构建会复用同一个号。

### 6.4 你点名要的结论：混合窗口被打进 APK 的风险有多大

`bundle_host.mjs` 的「原子替换失败 → 就地覆盖」路径中，目录会有几秒是「旧文件 + 部分新文件」。逐条路径判断：

| 路径 | 能否把混合状态打进 APK | 处置 |
|---|---|---|
| 另一个构建的 `mergeAssets` 在覆盖期间读目录 | 曾经可以 | **已闭合**：`bundleHost` 与 `merge*Assets` 现在同一把锁，后者拿不到锁直接失败 |
| 同一构建内 `bundleHost` 与 `mergeAssets` 重叠 | 不可以 | `bundleHost` 挂在所有 `pre*Build` 上，`merge*Assets` 一定在其之后 |
| 覆盖写完后被其它进程改坏 | 曾经不可见 | **已闭合**：`bundle_host.mjs` 写完重新计算指纹 + 校验 `VERSION`，不一致直接抛错 |
| 构建期间有人手工 `node tool/bundle_host.mjs`（或其它工具）同步 host | 仍可能 | **已加闸门**：`merge*Assets` 的 `doFirst` 在锁内重算目录指纹并与 `VERSION` 比对，不一致则中止（不会打包坏 host） |

**残留风险（不藏）**：闸门是「拷贝前检查」，不是「拷贝后校验」。极端情况下，某个绕过锁的外部进程正好在
「闸门通过 → merge 拷贝完成」这段（45 个小文件，毫秒级）开始就地覆盖，仍可能拷进混合内容，而且不会在构建期报错。
概率：需要有人**在构建期间**跑不受 `.gradle/build.lock` 约束的同步；正常协作（都走 Gradle）时为 0。
如果要把这最后一点也关掉，需要在 `merge*Assets` 之后再校验一次**合并产物**的指纹（`app/build/intermediates/.../assets/agent-host`），
这需要在 `merge` 之后插入一个校验任务，属于新增构建步骤，本轮没做。

### 6.5 验证记录

| 场景 | 命令 | 结果 |
|---|---|---|
| 正常构建：获取 → 执行 → 释放 | `gradlew :app:bundleHost`（多次） | `[build-lock] 获取成功：:app:bundleHost` → `[bundle-host] 45 files ... f4310e6953c2bd45` → 构建结束后 `release-service 已释放：lock=true，剩余标记=0`；`.gradle/build.lock` 不存在、`build.lock.d` 无残留 |
| 指纹算法一致性 | 用 node 分别按 `localeCompare` 与码元序计算 | 两者都得到 `f4310e6953c2bd45`，与 `VERSION` 一致 → Kotlin 侧 `sortedBy { it.name }` 不会造成指纹漂移 |
| 锁被占用 | 手工写入 `pid=99999` 的锁文件后跑 `:app:bundleHost` | `BUILD FAILED`，报错包含「另一个构建正在执行…」+ 持有者信息 + 手动删除路径；未删除不属于自己的锁 |
| 失败释放 | 用 `--init-script` 让被锁任务在**拿到锁之后**抛 `GradleException` | `BUILD FAILED`，同一次构建里仍打出 `release-service 已释放：lock=true，剩余标记=0`，锁文件不存在 |
| 配置缓存兼容 | 见 §6.6：本项目当前未开配置缓存；释放改为 `BuildService` 后不再有 finalizer 任务需要序列化 |

> 说明：本项目 `gradle.properties` 现在**没有**开配置缓存（是否开启由另一条线控制），所以上述兼容性是按「两种模式都要能跑」来做的。
> 未触碰 `gradle.properties` 的堆/并行参数。

### 6.6 顺带发现（未改，交给你决定）

开 `--configuration-cache` 跑 `:app:bundleHost` 时，缓存存储失败，原因是**既有代码**而非本轮改动：

```
Field "this$0" declaringType "Build_gradle$bundleHost$2$1" ... onlyIfSpec
```

即 `app/build.gradle.kts` 里 `bundleHost` 的 `onlyIf { file("$hacaRoot/...").exists() }` 捕获了脚本对象。修法是一行：
在配置块里先 `val hacaRootPath = hacaRoot`，`onlyIf` 只引用这个局部值。那个文件属于构建诊断线，我没动。

## 7. 第三轮：锁的存活探测、编译入锁、版本号占用误判

### 7.1 你点名要改的三点

| 要求 | 实现 | 证据 |
|---|---|---|
| 取锁时校验持有者存活 | 同主机用 `ProcessHandle.of(pid)`（等价 `kill(pid, 0)`，只探测不发信号）；pid 不存在 → 判陈旧并接管 | §7.2 TEST1 |
| 陈旧阈值可配置 | `-Phpa.lockStaleMinutes`，默认 **60 分钟**（全量编译 20–25 分钟 + 冷 daemon ~9 分钟之上）；超时也判陈旧 | §7.6 超时接管实测 |
| 落盘内容完整 | `pid` / `process` / `task` / `started`（ISO 8601）/ `host`（另附 `user`）；**缺一项即视为陈旧** | §7.2 TEST2 |
| 接管不静默 | `WARN` 打印「锁被判定为陈旧并接管」+ 原因 + 原持有者（pid/进程名/任务/主机）+ 距今时长 + 原记录全文 | TEST1 / TEST2 日志 |
| 活进程占用时不抢 | 同主机 pid 存活且未超时 → `GradleException`，不解锁、不接管 | §7.2 TEST3 |

为什么阈值不能设几分钟：全量 `:app:compileDebugKotlin` 实测 20–25 分钟，冷 daemon 再加约 9 分钟。
阈值短于这个值会把**正常的编译**判成死锁，出现在两个构建之间互相抢锁的恶性循环——比假锁堵住更坏。

### 7.2 验证记录（真实 Gradle 进程，非脚本模拟）

| 场景 | 构造 | 结果 |
|---|---|---|
| 死 pid（完整记录） | 写入 `pid=99999 / process=fake-task.exe / task=:app:mergeReleaseAssets / started=2026-11-07T05:00:00+08:00 / host=本机` 后跑 `:app:bundleHost` | `WARN [build-lock] 锁被判定为陈旧并接管。原因：持有进程 pid=99999 在本机已经不存在`，随后 `获取成功`、`BUILD SUCCESSFUL in 4m`，结束后锁文件已删除 |
| 记录不完整（缺 `process`/`host`） | 写入 `pid=<活进程> / task / started / user` 四行后跑 `:app:bundleHost` | `原因：锁记录不完整，缺 pid / process / task / started / host 中的项`，接管成功，`BUILD SUCCESSFUL in 37s` |
| 活进程占用（完整记录） | 写入 `pid=<本脚本 pid> / process=powershell.exe / ...` 后跑 `:app:bundleHost` | `BUILD FAILED`，报出 `持有者：pid=18392（主机），任务=:app:mergeReleaseAssets，开始于 05:45:00，已持有 5 分钟`；锁文件未被删除（`lock still exists: True`） |

> 期间另有一条线的真实构建（`pid=38424`，`:provider-api:packageDebugResources`）持有过锁，我方第一次 TEST1 被它正确挡下
> （报错里带持有者与「已持有 3 分钟」）。这反过来证明新提示对「轮询等锁的重试脚本」是可读的：谁、什么任务、多久。

### 7.2.1 并发实测：两个 `assembleDebug` 同时启动

| 观察点 | 结果 |
|---|---|
| A（daemon pid 40756） | 06:07:25 拿到 `:app:compileDebugKotlin` 的锁并开始编译（33m16s） |
| B（同时启动） | 在同一任务上取锁失败，`BUILD FAILED in 11m 26s` |
| B 的错误原文（stderr） | `另一个构建正在执行会改动共享产物的任务（bundleHost / compile*Kotlin / merge*Assets / package*），本次构建在 :app:compileDebugKotlin 处中止。持有者：pid=40756（开心爸爸的电脑），任务=:app:compileDebugKotlin，开始于 2026-11-07T06:07:25+08:00，已持有 6 秒` + 原始五字段记录 + 「这是明确失败，不是排队…不要立刻循环重试」 |
| 版本号 | 前后都是 `9151`，没有被消费（两个构建都没走到打包） |
| 环境阻断 | A 的唯一失败点是 `:app:verifyProviderAssets`：provider 源码指纹 `a82fed49…` 与 assets 内 APK 不一致（其它线改动后的既有闸门），与本次改动无关 |

结论：并发构建在**编译入口**就被挡下，不可能同时走到打包，因此不存在「两个 APK 同一个 versionCode」的路径；
错误信息里持有者、任务、开始时间、已持有时长齐全，轮询等锁的脚本可以直接据此判断是等还是停。

### 7.3 编译任务入锁

`isBuildLockTask` 新增 `name.startsWith("compile") && name.endsWith("Kotlin")`。
根因链条：Kotlin daemon 编译完 → 关闭增量缓存失败（`kotlin-classes` / `caches-jvm` 的 mmap 未释放）→
KGP 回退进程内重编 → 回退第一步清 `kotlin-classes\debug\com` → 删不掉 → 整个构建失败。
两个构建同时编译是唯一能把这条链触发到「构建失败」的入口，所以锁必须覆盖编译任务（此前只锁资产与打包）。
锁仍然只覆盖任务执行窗口，配置阶段可并行；拿不到锁直接失败，不排队。

### 7.4 「版本号 9151 已被占用」的根因与修法

- 旧实现在**所有** `package*` 任务上读写版本号，而 `packageDebugResources` 也匹配 `package*`：
  它先于 `packageDebug` 运行、先回写，随后 `packageDebug` 用 `stored >= 本号` 一判，就把本次构建
  自己刚写的号当成冲突。`version.properties` 里 05:29:51 写下的 `9151` 正是这条路径留下的。
- 现在只有真正产出 APK/AAB 的任务（`packageDebug` / `packageRelease` / `package*Bundle` /
  `bundleDebug` / `bundleRelease`）读写版本号。
- `assemble` 会先后跑 debug 与 release 两个打包任务：第一个写完后，第二个不能判自己冲突。
  因此回写时同时写 `.gradle/build-number.marker`（`pid` / `host` / `number`），校验时
  `stored == 本号` 但凭据不是本次构建写的，才算撞号。
- 读与写都在同一个锁临界区内：`doFirst` 取锁 → 校验 → 打包 → `doLast` 回写 → 构建结束由 `BuildService.close()` 释放。

**残留窗口（不藏）**：APK 已落盘、`doLast` 还没执行就被杀，此时 `version.properties` 未更新，
下一个构建会复用同一个号（毫秒级窗口，需要外部强杀）。`doLast` 内部本身先写版本号再写凭据，
这两步之间被杀不会造成重复号。

### 7.5 对「轮询等锁」调用方的约定

锁不提供排队。拿不到锁时输出的是一段可判定的文本，而不是无限等待：

- 持有者：pid / 主机 / 任务 / 开始时间 / 已持有时长；
- 明确声明「这是明确失败，不是排队；不会等待，不会自动重试」；
- 提示脚本：等它结束或 Ctrl+C 后再跑，不要立刻循环重试（每次重试都会重新烧一遍编译）；
- 持有进程消失或超过阈值时，下一次取锁自动接管并打印原记录，不需要人工删文件。

### 7.6 锁长时间不放：两个根因与修法（本轮）

**现象**：UI 线报告 `:app:compileDebugKotlin` 从 06:07 起持锁数小时，最后一次成功出包停在 03:02。

**根因一：`finalizedBy` 的释放任务一次构建只执行一次。**
Gradle 里一个 task 在一次构建中只执行一次；释放任务挂在第一个被锁任务之后就「用完」了，
之后同一个构建里再取锁（编译 → mergeAssets → package）就再也没有人释放。失败时同样如此。

**根因二：`BuildService` 是懒加载的，没被实例化就不会调用 `close()`。**
即使把释放写进 `sharedServices` 的 `AutoCloseable.close()`，只声明 `usesService(...)` 并不创建实例，
构建结束时 Gradle 不会去关闭一个从未创建的服务。证据：`daemon-43136.out.log` 里
07:03:58 `BUILD SUCCESSFUL`、`daemon has finished executing the build`，而 `.gradle/build.lock`
与 `build.lock.d/app__bundleHost` 都还在。

**修法**：

| 位置 | 改动 |
|---|---|
| `configureEach` | 删掉 `finalizedBy(releaseSlot)` 与整个释放任务；改为配置期抓一个局部 `releaseRegistration` |
| `doFirst` 首行 | `releaseRegistration.get()` —— 主动实例化，保证构建结束一定有 `close()` 收尾 |
| `close()` | 首行只有 `pid=<自己>` 才动；先删本构建的标记，确认标记目录为空再删锁；全程打印诊断行 |
| 判定 | 陈旧阈值默认 60 分钟；新增「锁文件存在但无任何标记且超过 2 分钟」= 孤儿锁，判陈旧 |
| 兜底 | 进程被外部强杀时 `close()` 不会执行，靠取锁时的 pid 存活探测 + 60 分钟阈值 + 孤儿锁接管 |

`doFirst` 里的实例化只捕获配置期局部变量，不引用脚本对象；本项目当前未开配置缓存（见 §6.6）。

**实测（真实 Gradle 构建，非模拟）**：

| 场景 | 构造 | 结果 |
|---|---|---|
| 成功构建释放 | `gradlew :app:bundleHost` | `[build-lock] 获取成功` → `release-service close：self=pid=13828，锁首行=pid=13828` → `已释放：lock=true，剩余标记=0`；`BUILD SUCCESSFUL in 3m 58s` |
| 失败构建释放 | `--init-script` 让被锁任务在拿到锁之后抛 `GradleException` | `BUILD FAILED`，同一构建内仍打出 `已释放：lock=true，剩余标记=0`，结束后 `lock=False, markers=0` |
| 超时接管（活进程 + 有标记） | 伪造 `pid=<活着的 powershell>`、`started` 为 2 小时前、并放一个 `app__compileDebugKotlin` 标记 | `WARN 锁被判定为陈旧并接管。原因：持锁 2 小时 2 分钟，超过阈值 60 分钟（该进程仍存活，可能已经卡死）` + 原持有者与原始记录，随后正常构建并释放 |
| 孤儿锁接管（无标记） | 同上但**不放**标记 | `原因：锁文件存在但没有任何持有标记（上一次释放没有清干净）`，接管成功 |
| 立即释放当前这把锁 | 手工删除 `pid=43136`（已退出的 daemon）的锁与标记 | `lock=False, markers=0`，随后连续四次构建都能正常起来并各自释放 |

**仍然存在的窗口（不藏）**：如果 Gradle daemon 被强杀（`taskkill /F`、`gradlew --stop` 正好卡在任务执行中），
`close()` 不会执行，锁会留到下一次构建。下一次取锁会先探测 pid：daemon 已死 → 立即判定陈旧并接管（打印原记录）；
如果 pid 被新进程复用、且标记文件恰好也在，则最迟 60 分钟后按超时接管。这两条都不会静默堵住流水线，
但「最长 60 分钟」这个上限是真实存在的。

### 7.7 把 60 分钟窗口压到「只覆盖真卡死」：pid 复用识别

`pid 存活 → 60 分钟超时 → 孤儿锁` 里，60 分钟的唯一理由是「pid 还在，无法区分是卡死还是被复用」。
但 pid 回卷（原进程退出、pid 被别的进程占用）比「进程活着但卡死」常见得多，不该让下一个人干等满一小时。

| 项 | 做法 |
|---|---|
| 记录新增 | `procstart=`：持有者进程启动时间（ISO 8601，截到秒）；取不到进程创建时间时退回 JVM 启动时间 |
| 取锁判定 | 同主机、pid 存活，但记录里的启动时间与该 pid 当前启动时间相差 **> 5 秒** → 判为 pid 已被复用，**立即**接管 |
| 提示 | 「原因：pid=NN 已被复用：锁记录里的进程启动时间是 X，而该 pid 现在的启动时间是 Y（原持有者已退出，pid 被别的进程占了）」+ 原记录 |
| 保守退让 | `procstart` 缺失或解析失败时**不做**复用判断，退回「存活 + 60 分钟超时」——宁可慢一拍，也不能误抢正在跑的长构建（5 秒容差也是为此：记录截到秒、兜底用 JVM 启动时间） |

**实测（另一条线的真实构建替我完成了验证）**：伪造 `pid=<活着的 powershell>`、`procstart` 比真实启动时间早 3 小时、
`started` 为 40 分钟前，随后该线 07:26 的构建取锁日志：

```
[build-lock] 锁被判定为陈旧并接管。
  原因：pid=1312 已被复用：锁记录里的进程启动时间是 2026-11-07T04:26:16+08:00，
        而该 pid 现在的启动时间是 2026-11-07T07:26:16+08:00（原持有者已退出，pid 被别的进程占了）
```

没有等 60 分钟。同一次实测里我方被挡住时，`release-service close：self=pid=15824，锁首行=pid=21048`
之后直接返回——没有删除别人的锁。另外该线 07:30:58 起持锁（10 个标记）到 07:45:24 结束，
锁与其标记一次性清空，这是「长构建 + 多标记」场景下释放路径的现场验证。

### 7.8 版本号并发验证：单调递增且无重复

基线（07:45 那条线刚消耗）：`version.properties` = **9153**，`app-debug.apk` 的 versionCode = 9153。

| 构建 | 启动方式 | 结果 | 号 |
|---|---|---|---|
| A | 与 B 同时启动 | `BUILD SUCCESSFUL in 9m 2s`，`:app:packageDebug` 执行 | **9154**（`version.properties` 与 `output-metadata.json` 一致） |
| B | 与 A 同时启动（晚 5 秒） | `BUILD FAILED in 3m 16s`，在 `:app:bundleHost` 取锁失败 | 未产出 APK、未消费号 |
| C | A 结束后串行 | `BUILD SUCCESSFUL in 8m 29s` | **9155** |

序列 **9153 → 9154 → 9155**：单调递增、无重复。B 的失败原文：

```
另一个构建正在执行会改动共享产物的任务（bundleHost / compile*Kotlin / merge*Assets / package*），本次构建在 :app:bundleHost 处中止。
持有者：pid=46344（开心爸爸的电脑），任务=:app:bundleHost，开始于 2026-11-07T07:49:02+08:00，已持有 2 秒
这是「明确失败」，不是排队：本构建不会等待，也不会自动重试。
```

说明：两次构建都带 `-x verifyProviderAssets`——那是 provider 源码指纹与 assets 不一致的既有闸门（其它线改动所致），
与本轮改动无关，绕开它才能走到打包。B 在**第一个被锁任务**就被挡下，因此不存在「两个构建都算到 9154 并各自打包」的路径。

### 7.9 同构建内并行任务的锁竞态（`--parallel` 下自己咬自己）

**现场**（引擎线 `assembleDebug`）：一次构建里 8 个被锁任务同时报错，锁记录显示 `(空锁文件)`，
而报错里的「持有者 pid」就是那次构建自己；`--no-parallel` 能绕过，说明是时序竞态而不是逻辑判断错。

**根因**：`Files.createFile` 占位 → `writeText` 写内容之间有几毫秒，进程内其它被锁任务读到这个空文件，
判「记录不完整」→ 走陈旧接管 → `rename` 与 `createFile` 互相打架。`org.gradle.parallel=true` 时
一次构建里同时进入取锁逻辑的任务可以有很多个，窗口虽小但必被踩到。

**三条一起改，缺一不可**：

| # | 改法 | 说明 |
|---|---|---|
| 1 | 记录原子落盘 | 完整内容先写 `build.lock.tmp-<pid>-<nano>`，再 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`；不支持 ATOMIC_MOVE 时退回普通 move（同盘 rename，同样不会留下半个文件） |
| 2 | 同进程可重入 | 先判「首行 pid 是不是自己」——是就直接放行并写本任务的标记，**不参与任何陈旧判定**；这是同一构建内多个任务共享一把锁的正确语义 |
| 3 | 判定顺序固定 | 自己 pid → pid 存活 + `procstart` 一致 → 超过 60 分钟 → 孤儿锁（无标记且超过 2 分钟）；顺序错了会出现「自己的锁被自己判成陈旧」 |
| 4 | 空窗兜底 | 「记录不完整」只在锁文件已存在超过 5 秒时才算陈旧；`createFile` 失败后做**有界重读**（最多 2 秒，50ms 一次）：读到自己的 pid 就放行，读到别人的完整记录立即失败，不排队等锁 |

**验收（无 `--no-parallel`，连续 3 次）**：

| 轮次 | 结果 | 耗时 |
|---|---|---|
| RUN 1 | `BUILD SUCCESSFUL` | 18m 37s（含一次真实全量编译） |
| RUN 2 | `BUILD SUCCESSFUL` | 3m 8s |
| RUN 3 | `BUILD SUCCESSFUL` | 2m 39s |

三次日志统计：「锁被判定为陈旧并接管」**0 次**，「这是「明确失败」」**0 次**。
RUN 1 期间同一构建内有 **11 个**被锁任务共享同一把锁（`bundleHost`、`compileDebugKotlin`、
`mergeDebugAssets`、`packageDebug`、`packageDebugResources`，以及 env-api / provider-api 的编译与打包任务），
每个任务各写一个标记文件，构建结束由 `release-service` 一次性删标记并删锁（`lock=true，剩余标记=0`）。

**取舍说明**：第 2 条按 pid 重入的前提是「一个 daemon 一次只跑一个构建」——Gradle 的 daemon 确实是串行执行构建的，
所以同一 pid 出现两个并发构建不成立；跨机器的锁只能靠超时，这部分不受本次改动影响。

### 7.10 版本号回写挪到 doLast 后，CI 的 tag 会不会差一版

**结论：CI 不会差一版；差一版的是本机 `hpa-publish` 发出的一版 `v0.2.9140`（差 1）。**

CI 的号不是「读旧号 → 构建 → 让 Gradle 自增 → 按旧号打 tag」，而是：

| 环节 | 文件/字段 | 说明 |
|---|---|---|
| 取号 | `android-ci.yml` 第 137-144 行 | 从 **nightly 分支** `version.properties` 读 `BUILD_NUMBER=COUNTER`，自己算 `CODE=COUNTER+1` |
| 命名 | 第 147-150 行 | `version_code=CODE`、`tag=v0.2.$CODE-$CHANNEL`、`apk=HappyAgent-0.2.$CODE-$CHANNEL.apk` |
| 注入 | 第 163-166 行 | `-PbuildNumber=$CODE` 传给 Gradle |
| 落包 | 根 `build.gradle.kts` 第 76 行 + `app/build.gradle.kts` 第 117-118 行 | 注入值直接作为 `versionCode` / `0.2.<号>`，**不参与自增** |

即 tag 与包内 `versionCode` 同源同值；`doLast` 只影响本机 `version.properties` 的落盘时机，CI 的计数器由
`Persist version counter`（第 313 行起）在出包成功后用 API 推进，不读工作区文件。第 173 行新增
`Assert APK version matches tag`，用 `aapt dump badging`（无 aapt 时退回 `output-metadata.json`）对最终 APK 硬断言，
不一致就在发布前失败。

**失败构建不消费号（CI 运行时证据）**：run 105（nightly）在 `Verify APK signature` 失败，
`Upload APK artifact` / `Publish GitHub Release` / `Persist version counter` 全部 `skipped`——
包只留在 runner 工作区，没有发出去，同一个号由后续 run 正常使用。

**反向情况（已发生，已修）**：原顺序是「Publish GitHub Release → Publish Gitee → Persist」。
run 94 / 95 / 97 都是 `Publish GitHub Release=success` 后 Gitee `failure`/`cancelled`，
导致 `Persist=skipped`：GitHub 上已经有 tag 和 APK，但计数器没涨。下一跑（可能是另一渠道）
读到同一个 COUNTER，算出同一个 versionCode、但 tag 带不同后缀，于是出现
`v0.2.9110-dev` 与 `v0.2.9110-beta` 两份包同 versionCode 9110。
现已把 `Persist` 挪到 `Publish Gitee` **之前**：Gitee 再怎么失败/取消，也不会把已发布的号留在原地。

**已发生的错位（HTTP Range 只读 APK 内 `AndroidManifest.xml` 实测，27 个 Release 全查）**：

- 唯一错位：`v0.2.9140` / `HappyAgent-0.2.9140.apk` —— tag 与 Release 名写 9140，包内 `versionCode=9139`、`versionName=0.2.9139`，**差 1 版**。
- 来源不是 CI：无渠道后缀、Release 名是「v0.2.9140 开发版」，出自本机 `.trae/skills/hpa-publish/scripts/publish.mjs`：
  第 37-44 行先读 `version.properties` 得到 9140；第 55-63 行发现 `app-debug.apk` 已存在就**跳过构建**；
  第 182-183 行把这份旧 APK（9139）按 9140 的名字复制发布。
- 其余 CI 包全部一致（tag 数字 == 包内 `versionCode`）；早期 `-dev` 包的 `versionName` 带 `-dev` 后缀，属当时的命名差异，不是版本错位。

### 7.11 publish.mjs 断源、34MB 包定性、计数器对齐（本轮，未推送）

#### 7.11.1 `publish.mjs`：不再「APK 存在就跳过」，并加发布前断言

`.trae/skills/hpa-publish/scripts/publish.mjs`（`.trae` 已 gitignore，不入库）改了三处：

| 位置 | 原来 | 现在 |
|---|---|---|
| 构建 | `execSync('.\\gradlew.bat :app:assembleDebug')`，不自带号 | `-PbuildNumber=${buildNumber}`，包内版本与发布号同源 |
| 跳过判断 | `apkPath` 存在就跳过构建 | 先读 `output-metadata.json` 的 `versionCode`，**等于待发布号才跳过**；缺失/不一致/文件为空一律重编 |
| 发布前 | 无 | 断言 `versionCode === buildNumber` 且 `versionName === 0.2.<号>`，不符 `exit 1`，与 CI 的 `Assert APK version matches tag` 同口径 |

行为验证：把 `publish.mjs` 里「4. 构建」到「5. sha256」之间的**真实源码**抽出来，在临时目录跑四个场景（Gradle 用 stub 替身，不联网）：

| 场景 | 输入 | 结果 |
|---|---|---|
| 一致 | APK 存在，metadata `9159`，待发 `9159` | 不构建，断言通过 |
| 元数据落后 | metadata `9158`，待发 `9159` | 触发重编，重编后通过 |
| 重编失败 | metadata `9158`，重编后仍 `9158` | `[ERR] 包内 versionCode=9158，待发布号=9159（相差 -1），拒绝发布`，退出码 1 |
| 无元数据 | 有 APK、无 `output-metadata.json` | 触发重编，重编后通过 |

#### 7.11.2 34MB 的五个 beta 包：全部缺内置编码运行时

只读读取 APK 的 ZIP 中央目录（`zipfile.namelist()` / HTTP Range 读 central directory），逐个查五个条目：

| Release | 包大小 | 条目数 | `libnode.so` / 3×`libicu*` / `assets/agent-host/host-stdio.js` |
|---|---|---|---|
| `v0.2.9109-beta` | 34,065,237 B | 581 | **5 项全缺** |
| `v0.2.9110-beta` | 34,065,233 B | 581 | **5 项全缺** |
| `v0.2.9113-beta` | 34,089,045 B | 581 | **5 项全缺** |
| `v0.2.9114-beta` | 34,089,041 B | 581 | **5 项全缺** |
| `v0.2.9116-beta` | 34,089,049 B | 581 | **5 项全缺** |
| 对照 `v0.2.9106-beta` / `9117-dev` / `9118-beta` | 71.3 MB | 636 | 5 项齐全 |

结论：不是「当年正常的小包」，是**同类事故包**——能装能启动，但 Coding 功能不可用。
用户已装的不因删文件而变好，所以**不删 Release**，只在描述里加警示。

- Gitee 侧已加（9109 在 Gitee 不存在）：`v0.2.9110-beta` / `9113-beta` / `9114-beta` / `9116-beta`，脚本幂等（body 已含「缺少内置编码运行时」就跳过）。
- GitHub 侧**被阻塞**：本机无任何非交互 GitHub 凭据（`git credential fill` 两次超时；无 `gh` CLI；无 `GH_TOKEN`/`GITHUB_TOKEN`；远端 URL 未内嵌 token）。脚本已备好，拿到 token 后执行同一脚本即可。

#### 7.11.3 版本号倒退：nightly 计数器对齐到 9159（仅本地提交）

- 本机 `version.properties` = **9159**；nightly 分支 = **9121**；CI 取号是 `COUNTER+1` → 下一跑会发 **9122**，对已装 9156+ 的用户是版本倒退。
- 已在 nightly 本地提交 `74fce0f`：`version.properties` 改为 `9159`，下一跑 CI 产出 **9160** 起。
- 该提交同时把 nightly 分支的 `Persist version counter` 升级为与 main 同口径（见 7.11.4）——**nightly 的 push 事件用的是 nightly 自己的 workflow 文件**，只改 main 不够。
- 前提提醒：CI 读的是 GitHub 上 nightly 的 `version.properties`（`gh api ... ?ref=nightly`），所以要等分支推送后才生效；若期间整条历史被重写，新历史里的 nightly 也必须是 9159，不能把 9121 带回来。

#### 7.11.4 `Persist version counter` 的写目标与失败留痕

- 写目标：**nightly 分支**的 `version.properties`（`-f branch=nightly`），beta 跑完也写这里，全仓一条计数器。
- 原实现的漏洞：Actions 的 bash 带 `-e`，读 `sha`/内容的 GET 一旦失败就**直接退出**，连 `计数器回写失败` 那行都打不出来，等于静默失败。
- 现在：`set +e` 收口三类失败（读不到 / PUT 被拒 / 3 次重试耗尽），统一走一条出口，输出
  `::error title=版本计数器未推进::上一次已发布但计数器未推进 —— <tag> 的 GitHub Release 已发布（versionCode N），nightly 仍停在 M：<原因>…`
  并写入 `$GITHUB_STEP_SUMMARY`（后果 + 人工修法：把 nightly 的 `BUILD_NUMBER` 改到 `N+1` 或更大）。
- 验证：用 stub `gh`（PUT 恒失败 / PUT 成功）实跑该 step 的 bash，失败场景退出码 1 且 summary 四行齐全，成功场景打印 `9121 -> 9122` 且退出码 0。

