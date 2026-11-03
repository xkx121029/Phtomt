# Eta 全量实现拆解与改造对照报告

> 研究对象：GitHub `Mangi-11/Eta`，commit `42edf0e4aaeb35ee9518104b8ebca66275ce9b37`（2026-11-03，`refactor(ui): 移除自定义设置包装并统一对齐 Miuix 原生组件规范`）
> 对照对象：本仓库 `happy_phone agent`（Kotlin + Compose 手机自动化 Agent，`main` 分支当前工作区）
> 研究方法：`git clone` 到本机临时目录，逐模块静态阅读源码与构建脚本；**未使用浏览器自动化**；未运行 Eta 应用。
> 结论性质：只做研究与建议，**未修改本项目任何代码**（本文件是唯一新增文件）。
> 行号均为上述 commit 的静态行号，后续 Eta 改动可能漂移。

---

## 目录

- [0. 一页结论](#0-一页结论)
- [1. 阅读范围与体量](#1-阅读范围与体量)
- [2. 模块地图](#2-模块地图)
- [3. 逐模块拆解](#3-逐模块拆解)
  - [3.1 构建配置与模块划分](#31-构建配置与模块划分)
  - [3.2 入口与生命周期](#32-入口与生命周期)
  - [3.3 权限与系统能力获取](#33-权限与系统能力获取)
  - [3.4 悬浮窗与跑马灯/状态覆盖](#34-悬浮窗与跑马灯状态覆盖)
  - [3.5 任务完成卡片与状态反馈](#35-任务完成卡片与状态反馈)
  - [3.6 Agent 主循环与决策/执行解耦](#36-agent-主循环与决策执行解耦)
  - [3.7 提示词与工具（skill/tool）定义](#37-提示词与工具-skilltool-定义)
  - [3.8 状态存储](#38-状态存储)
  - [3.9 错误恢复与重试](#39-错误恢复与重试)
  - [3.10 并发与线程模型](#310-并发与线程模型)
  - [3.11 可观测性与日志](#311-可观测性与日志)
  - [3.12 UI 层结构与主题令牌](#312-ui-层结构与主题令牌)
  - [3.13 测试与构建脚本](#313-测试与构建脚本)
- [4. 四块重点深入分析](#4-四块重点深入分析)
  - [4.1 ① 浮层如何压过状态栏且不被裁切](#41--浮层如何压过状态栏且不被裁切)
  - [4.2 ② 任务完成卡片的结构与动效](#42--任务完成卡片的结构与动效)
  - [4.3 ③ 首帧反馈与单步耗时/并发设计](#43--首帧反馈与单步耗时并发设计)
  - [4.4 ④ 能力/工具注册与扩展机制](#44--能力工具注册与扩展机制)
- [5. 与本项目的差距对照表](#5-与本项目的差距对照表)
- [6. 行动建议（浮层线 / 引擎线 / 通用）](#6-行动建议浮层线--引擎线--通用)
- [7. 明确不建议照搬的部分](#7-明确不建议照搬的部分)
- [8. 附录](#8-附录)

---

## 0. 一页结论

1. **"跑马灯能压过状态栏"的真正原因不是窗口类型，而是 insets。** Eta 的边缘光窗口用 `setFitInsetsTypes(0)` 清空系统栏 inset 适配、高度取"真实屏幕尺寸"而不是 `MATCH_PARENT`、再加 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 与 `FLAG_LAYOUT_NO_LIMITS`，所以坐标原点就是物理屏左上角，绘制路径可以压到状态栏与挖孔区。本项目的 `EdgeLightingService` 用的是 `clearInsets = false`，坐标系起点天然落在状态栏下方——即使画到自身画布 `y=0`，也仍然在状态栏下沿。
2. **Eta 没有"滚动字幕跑马灯"。** 它的常驻元素是"全屏边缘光 + 顶部胶囊"，胶囊第二行用 `StartEllipsis`（从尾部省略）制造"字幕向前推进"的观感，文字变化只做 220ms 交叉淡入，不横向滚动。本项目 `MarqueeView` 是真横向滚动（60 px/s、gap 120px），这是两套完全不同的信息形态。
3. **完成卡片是独立 BottomSheet 窗口，而不是把进度面板原地换成"完成态"。** 结构：拖拽把手 + 20dp 状态圆点（勾/叉）+ 15sp 半粗标题 + 可滚动 Markdown 正文（底部渐变遮罩）+ 一行三键（复制图标 / 完成 / 回到 Eta 或打开副屏）。动效：自下而上 spring 进入 + 200ms 淡出，下滑 96dp 关闭，静止 8s 自动关，卡片窗口高度取屏高 50% 但内容贴底。
4. **响应速度靠"少一次往返"而不是"更快的模型"。** Eta 的动作工具在返回结果里**自带一次执行后的观察摘要**（`after` 字段，最多 30 节点、不带截图），默认 `observe_screen` 也不带截图（最多 60 节点）。模型下一步通常不需要再调观察工具，等于每步省掉一整轮"观察请求 + 图片上传 + 首 token 延迟"。同时系统提示与工具目录作为稳定前缀保持字节级一致以便命中 provider 前缀缓存。
5. **工具扩展是"一份声明式注册表"驱动三处：** `AgentToolRequirements.REQUIREMENTS` 同时决定①给模型看的目录（`gui=true` 的工具自动注入"可能需要等待/取得控制权"提示）②运行期权限校验（accessibility/root/lsposed/系统权限/ColorOS）③App 里"能力列表"的展示。执行入口 `AgentLocalTools.execute` 按同一张表校验，不靠提示词猜测。
6. **本项目真正缺的是两件小事，而不是大重构：** ①浮层"让路"（agent 手势落点在自家胶囊范围内时，临时 `FLAG_NOT_TOUCHABLE` + 淡出，结束后 900ms 恢复）；②"动作即观察"（动作结果里带 diff 摘要），两者都能独立落地。
7. **照搬会踩的坑：** `TYPE_ACCESSIBILITY_OVERLAY` 依赖无障碍服务存活（服务被收回时浮层一起消失）；胶囊从屏幕顶部滑入的动效违反本项目"仅自下而上"的动效约束；Miuix 主题/毛玻璃/半透明与项目视觉规范冲突；Eta 的 insets 方案要求"真实尺寸 + 清空 insets"，与当前 `OverlayWindowSpec` 的 `clearInsets=false` 约定相反，改的时候必须同步改三处使用方并回归截图/触摸。

---

## 1. 阅读范围与体量

### 1.1 仓库体量（静态统计）

| 维度 | Eta | happy_phone agent |
| --- | --- | --- |
| Kotlin 文件（main） | 约 616 个 | 约 295 个 |
| main Kotlin 行数 | 约 111k | 约 67k |
| 测试文件 | 约 302 个 | 约 80 个 |
| 模块 | 单模块 `:app` | 单模块 `:app` |
| compileSdk / minSdk / targetSdk | 37 / 33 / 37 | 36 / 26 / 36 |
| UI 基座 | Miuix（HyperOS 风格）+ Compose | Material 3 Expressive |
| 特权通道 | 无障碍 + Root shell + libxposed 模块 | 无障碍 + 截图/注入（另有副屏模式） |

### 1.2 实际读过的模块（非 README）

- 构建：`settings.gradle.kts`、`app/build.gradle.kts`（含 `versionCode yyyyMMdd+序号` 规则、APK 命名规则）、`gradle/libs.versions.toml`、`.github/workflows/*`
- 入口/生命周期：`EtaApp.kt`、`AndroidManifest.xml`、`MainActivity`、`AgentRuntimeService`、`AgentExecutionService`、`AgentRuntimeClient`、`AgentRuntimeWire`
- 系统能力：`agent/accessibility/*`（服务、Keeper、MainThreadCallGate、ScreenshotWindowPolicy）、`agent/device/RootShellDeviceController`、`hook/*`、`assets`/Xposed 配置
- 浮层：`agent/overlay/*`（Capsule、Content、Clearance、State、VisibilityPolicy、GestureIndicator、VirtualDisplayStageCard）、`AgentRuntimeService` 的窗口创建与清除段
- 引擎：`agent/model/*`（AgentLoop、AgentModelClient、各 Provider 适配、PromptBuilder、ToolCatalog、ContextSession、Retry）、`agent/tool/*`（AgentLocalTools、Requirements、Capabilities、AfterActionSummary）
- 存储/恢复：`data/db/*`、`AgentRunCheckpointStore`、`AgentRunArchiveStore`、`AgentRuntimeSession`
- 并发：`GuiControlCoordinator`、线程创建、`MainThreadCallGate`、手势回调 latch
- UI/主题：`ui/app/AgentAppTheme.kt`、`ui/screens/*`、`ui/components/*`
- 测试/构建：`app/src/test/**`（Robolectric + JUnit）、CI workflow

---

## 2. 模块地图

### 2.1 Eta 侧

| 分层 | 包/目录（`app/src/main/kotlin/io/github/mangi/eta/`） | 职责 | 代表文件 |
| --- | --- | --- | --- |
| 应用壳 | `EtaApp.kt`、`MainActivity`、`ui/app` | 进程初始化、Compose 宿主、Miuix 主题、会话/设置状态 | `EtaApp.kt`、`ui/app/AgentAppTheme.kt` |
| 运行时服务 | `agent/runtime` | 前台绑定服务、run 注册表、事件总线、GUI 所有权、浮层窗口生命周期、IPC 协议 | `AgentRuntimeService.kt`（1517 行）、`AgentRuntimeRunExecutor.kt`、`GuiControlCoordinator.kt`、`AgentRuntimeWire.kt` |
| 引擎 | `agent/model` | 主循环、Provider 适配、提示词、工具目录、上下文压缩、重试 | `AgentLoop.kt`、`AgentModelClient.kt`、`AgentPromptBuilder.kt`、`AgentToolCatalog.kt` |
| 工具 | `agent/tool` | 22 个设备工具的统一执行器、声明式需求表、动作后观察摘要 | `AgentLocalTools.kt`（80KB）、`AgentToolRequirements.kt`、`AgentAfterActionSummary.kt` |
| 设备能力 | `agent/accessibility`、`agent/device` | 无障碍服务、Main 线程调用门、截图策略、Root shell 观察/tap/swipe、手势完成等待 | `AgentAccessibilityService.kt`（108KB）、`RootShellDeviceController.kt` |
| 浮层 | `agent/overlay` | 顶部胶囊、全屏边缘光、完成卡片、手势指示、副屏舞台卡、清除协调 | `AgentOverlayCapsule.kt`、`AgentOverlayContent.kt`（边缘光+卡片）、`AgentOverlayClearance.kt` |
| 扩展 | `agent/skill`、`agent/subagent`、`agent/mcp` | SKILL.md 技能安装/读取、子代理并行、MCP 服务器接入 | `AgentSkillCatalog.kt`、`AgentSubagentRunner.kt` |
| 存储 | `data/db` | Room：会话、消息、run 结果/归档、在飞 run、上下文检查点、Provider、技能、MCP | `RuntimeRunEntities` 等 |
| 特权 | `hook`、`agent/accessibility/AgentAccessibilityKeeper` | libxposed 模块（系统侧保活/权限）、无障碍保活 | 模块入口 + hook 集 |
| UI | `ui/screens`、`ui/components` | 聊天、任务过程、设置、技能/MCP 管理 | 多个 Compose 屏 |

### 2.2 本项目对应位置（速览）

| 分层 | 本项目位置（`app/src/main/java/com/phoneagent/`） | 备注 |
| --- | --- | --- |
| 应用壳 | `MainActivity.kt`、`ui/theme/*` | Material 3 Expressive |
| 浮层 | `overlay/FloatingWindowService.kt`（1939 行）、`overlay/MarqueeView.kt`、`feature/edge/EdgeLightingService.kt`、`feature/edge/EdgeLightingView.kt`、`overlay/CursorOverlayService.kt`、`overlay/PageMarkOverlayService.kt` | 顶部面板 + 底部跑马灯 + 悬浮球 + 底部选项卡 + 边缘光 |
| 窗口约束 | `core/window/OverlayWindowSpec.kt` | 统一封装 `TYPE_APPLICATION_OVERLAY`、`fitInsetsTypes`、组合 alpha 上限 |
| 引擎 | `engine/AgentEngine.kt`、`engine/loop/*`、`engine/runtime/*`、`core/ai/*` | 分阶段 StepRunner、SSE 累积后解析 JSON 意图 |
| 工具 | 提示词 `engine/prompt/*` + 执行 `ActionExecutor`/`AgentEngine` | 工具是"提示词文档 + JSON 动作分发"，非原生 tool_calls |
| 存储 | `engine/store/*`（TaskStore/检查点） | 文件/JSON 持久化 |
| 遥测 | `engine/runtime/TelemetryRecorder.kt` | 已有耗时/视觉/执行指标 |

---

## 3. 逐模块拆解

### 3.1 构建配置与模块划分

**职责**：单模块产出 APK；版本号与 CI 构建元数据耦合；无多模块拆分。

**关键文件**
- `settings.gradle.kts:26`：`include(":app")`，仅一个模块。
- `app/build.gradle.kts:33-43`：`namespace/applicationId = io.github.mangi.eta`，`compileSdk 37`、`minSdk 33`、`targetSdk 37`、`versionName 3.4.0`；`versionCode` 规则为 `yyyyMMdd + 两位当日序号`。
- `app/build.gradle.kts:143-145`：APK 输出文件名固定为 `Eta-v{versionName}.apk`，构建类型后缀由 `versionNameSuffix` 承担。
- `gradle/libs.versions.toml`：`libxposed api/service 102.0.0`、`miuix 0.9.4`、`okhttp 5.5.0`（含 `okhttp-sse`）、`room 2.8.5`、`kotlinx-serialization 1.11.0`、`robolectric 4.17`。

**核心数据结构**：无。

**对外接口**：Gradle 任务；`versionName`/`versionNameSuffix` 被 CI 与站点读取。

**值得学**
- 版本号与构建类型分离：`versionName` 只表语义版本，CI 元数据走 suffix，发布文件名稳定可被下载页引用。
- 单模块 + 严格依赖目录（`libs.versions.toml`）在小团队里降低构建复杂度。

**不值得学**
- `compileSdk 37` / `targetSdk 37`（Android 17 预览）激进；本项目 `targetSdk 36` 更稳。
- 单模块 111k 行已接近需要拆分的规模（`runtime` 单文件 1517 行、`AgentAccessibilityService` 108KB、`AgentLocalTools` 80KB），Eta 自己的可维护性在下降。

---

### 3.2 入口与生命周期

**职责**：App 进程 → 绑定运行时服务 → 前台服务保活 → 无障碍服务 → 每次任务一个后台线程。

**关键文件/位置**
- `EtaApp.kt`：Application 初始化（主题、日志、数据库、模块状态）。
- `app/src/main/AndroidManifest.xml:142-167`：`AgentRuntimeService`（`exported=true`，靠 `Messenger` 绑定 + `ACTION_KEEP_ALIVE` 保活）、`AgentExecutionService`（`foregroundServiceType=specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）、`AgentAccessibilityService`（`BIND_ACCESSIBILITY_SERVICE` + `@xml/agent_accessibility_service`）；声明 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`POST_PROMOTED_NOTIFICATIONS`、`READ_ASSIST_STRUCTURE_SCREEN_CONTENT`、自定义权限 `io.github.mangi.eta.permission.CONTROL_ACCESSIBILITY_PROTECTION` 等。
- `agent/runtime/AgentRuntimeService.kt:90-92`：`mainHandler = Handler(Looper.getMainLooper())`、`resultIo` 单线程、`serviceMessenger`（Messenger IPC，携带 `Bundle` + 图片 PFD）。
- `AgentRuntimeService.kt:142-162`：`onCreate` 初始化生命周期注册表、挂接 `AgentOverlayClearance`（浮层让路）、订阅 GUI 控制状态与副屏状态并同步胶囊尺寸。
- `AgentRuntimeService.kt:169-183`：`onStartCommand` 用 `ACTION_KEEP_ALIVE` 保活；`onUnbind` 时若有活跃 run，**run 继续跑**（客户端解绑不打断）。
- `AgentRuntimeService.kt:468` 附近：每个 run 起一个名为 `agent-runtime` 的线程，内部 `runBlocking` 跑 `AgentRuntimeRunExecutor`；主线程只负责 UI 与窗口。
- `agent/runtime/AgentExecutionService.kt:96-133`：前台通知随状态更新；API 36.1+ 申请 Live Update 提升（`ProgressStyle` + `setShortCriticalText` + `requestPromotedOngoing`），状态栏胶囊文本压到约 7 个字符。

**核心数据结构**
- `AgentRuntimeWire`：`MSG_START_RUN / MSG_CANCEL / MSG_EVENT ...`，事件体为 `Bundle`，图片走 `ParceFileDescriptor`。
- run 注册表：`runId → AgentRunRecord`（状态、事件序列、上下文快照）。

**对外接口**：`AgentRuntimeClient`（App 侧绑定 + 发送 + 订阅事件）；`AgentRuntimeService.onEtaUiVisibilityChanged()`（App 前后台变化影响浮层与息屏）。

**值得学**
- 运行时是独立 Service（`Messenger` 绑定 + `KEEP_ALIVE` + 客户端解绑不打断）：任务执行不依赖聊天页存活，页面切走/App 退到后台都不会中断 run。
- 前台通知与浮层状态共用同一个 `AgentOverlayStatus` 枚举，**文案只有一个真源**。
- 通知状态短文本按"状态栏胶囊约 7 字符"设计，避免被系统截断成省略号。

**不值得学**
- `KEEP_ALIVE` + 前台服务在国内厂商 ROM 上仍可能被清理，Eta 额外依赖无障碍保活/模块；本项目已有前台服务与掉线检测，不必再加一层常驻。

---

### 3.3 权限与系统能力获取

**职责**：三条通道并存——无障碍（首选）、Root shell（结构化 dump + 注入）、libxposed 模块（系统侧能力/保活）；没有 Shizuku。

**关键文件/位置**
- `app/src/main/res/xml/agent_accessibility_service.xml`：`canRetrieveWindowContent`、`canPerformGestures`、`canTakeScreenshot`、`notificationTimeout=50`、监听 `TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED / TYPE_VIEW_SCROLLED`。
- `agent/accessibility/AgentAccessibilityKeeper.kt`：无障碍被系统关闭后的自愈（检测 + 引导/尝试恢复）。
- `agent/accessibility/MainThreadCallGate.kt`：所有 `AccessibilityNodeInfo` 访问强制回主线程串行（避免 "Cannot perform this action on a sealed/ detached node" 与竞态）。
- `agent/accessibility/ScreenshotWindowPolicy.kt`：截图时排除自家浮层窗口（`TYPE_ACCESSIBILITY_OVERLAY` 天然被系统过滤掉一部分，仍做二次过滤）。
- `agent/device/RootShellDeviceController.kt`：`uiautomator dump` + `input tap/swipe` + `screencap`，在无障碍不可用/需要跨应用时降级；`observe()` 优先无障碍树，失败回退 root dump。
- `hook/*`：libxposed 模块，系统侧保活/权限钩子（用于"无障碍不被杀"与部分系统能力）。

**核心数据结构**：`AgentToolCapabilities`（accessibility / root / lsposed / systemAccess / colorOs / gui / freshObservation 六个布尔）。

**对外接口**：`AgentToolRequirements` 读取能力快照做工具投影与执行校验（详见 4.4）。

**值得学**
- **能力快照而不是散落判断**：所有工具是否可见/可执行都来自同一个 `AgentToolCapabilities`，避免"提示词说能点、运行时点不了"。
- 主线程调用门是硬约束：无障碍 node 操作必须串行，否则偶发 `IllegalStateException`。
- 截图为独立策略：自家浮层不进入模型视野，避免"模型看到自己的胶囊"导致自我循环。

**不值得学**
- Root + Xposed 的权限面太大（Play/应用商店不可上架、普通用户门槛高）；本项目走无障碍 + 副屏截图/注入更可控。
- `notificationTimeout=50ms` 会把事件频率拉高；本项目若无事件驱动需求不必照搬。

---

### 3.4 悬浮窗与跑马灯/状态覆盖

**职责**：两个窗口：①全屏边缘光（装饰 + 状态色）②顶部胶囊（状态/工具/控制/补充输入/副屏舞台/失败重试）；另有一个底部完成的独立卡片窗口。

**关键文件/位置**
- 窗口创建：`AgentRuntimeService.kt:985-1057`（`showOverlay()`：无障碍可用时直接用 `TYPE_ACCESSIBILITY_OVERLAY`，**免 `SYSTEM_ALERT_WINDOW`**；仅回退态才检查悬浮窗权限（`L987-988`）。先加光带全屏窗口，再加胶囊；光带 `addView` 失败只记日志、胶囊失败则整体返回）。
- 胶囊参数：`AgentRuntimeService.kt:1271-1287`（`TYPE_ACCESSIBILITY_OVERLAY`（无障碍可用时）/`TYPE_APPLICATION_OVERLAY` 回退；`FLAG_LAYOUT_IN_SCREEN`、`FLAG_NOT_FOCUSABLE|NOT_TOUCH_MODAL`；`gravity=TOP|CENTER_HORIZONTAL`；`y = statusBarHeightPx`；宽 272dp、高 68dp，预留阴影与缩放余量）。
- 光带参数：`AgentRuntimeService.kt:1327-1356`（`FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS|FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE`；`height = 真实显示高度`；`setFitInsetsTypes(0)`；`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`；`gravity=TOP|START`）。
- 屏幕圆角：`AgentRuntimeService.kt:1362-1374`（优先 `Display.getRoundedCorner()`，拿不到回退 28dp；并做 200ms 轮询刷新，兼容折叠屏/旋转）。
- 窗口尺寸契约：`AgentRuntimeService.kt:1503-1512`（胶囊 272/290/324dp 三档宽度、折叠 68dp、控制态 124dp、舞台 524dp、补充输入 260dp、手势判定外扩 8dp）。
- 胶囊布局/动效：`agent/overlay/AgentOverlayCapsule.kt`（折叠态两行：状态 13sp + 详情 11sp 单行 `StartEllipsis`；展开态控制条从上方 `slideInVertically { -it }` 弹簧进入；宽度/高度 `animateContentSize`；`CapsuleCorner = 22.dp`）。
- 边缘光绘制：`agent/overlay/AgentOverlayContent.kt:136-296`（squircle 路径向内缩 1.5dp；头部 14dp 模糊光晕 + 3dp 实线；`Brush.sweepGradient` 4200ms 无限旋转；`alpha=overlayPhaseAlpha`）。
- 让路协调：`agent/overlay/AgentOverlayClearance.kt`（手势矩形与胶囊矩形相交才让路；等待 `CountDownLatch`；`fadeHeadStart=90ms`、`restoreDelay=900ms`、`waitTimeout=300ms`）。
- 触摸开关：`AgentRuntimeService.kt:1063-1081`（`setCapsuleYielded`：立即改 `FLAG_NOT_TOUCHABLE` 并 `updateViewLayout`，**不**依赖窗口 alpha/属性动画）。
- 状态投影：`agent/overlay/AgentOverlayVisualPolicy.kt`（phase → 光色/胶囊高亮色/脉冲节奏）。

**核心数据结构**
- `AgentOverlayState`（`phase`、`status: AgentOverlayStatus`、`detailText`、`result`、`hasForegroundTool`、`isHosted`）。
- `AgentOverlayStatus` sealed：`Reasoning / RunningTool(name) / HostedToolRunning / GeneratingAnswer / Paused / Stopping / Finished / Failed`。
- 清除协议：`OverlayClearance.around(areaPx, gestureRect) { ... }`。

**对外接口**：`service.showOverlay()` / `syncActiveRunOverlayVisibility()`；`AgentLocalTools.clearOverlay(displayId, vararg points) { gesture }`（`AgentLocalTools.kt:1621`，供点击/滑动/长按工具调用）。

**值得学**
- **"浮层是操作层，不是对话层"**：`AgentOverlayVisibilityPolicy.kt:10-15` 规定只有真正取得 GUI 控制权或结束态才显示浮层；纯问答 run 不弹。
- **让路用窗口 flag，而不是把窗口移走**：`FLAG_NOT_TOUCHABLE` 立即生效，`updateViewLayout` 不重建窗口；移动窗口反而会引起闪烁与坐标抖动。
- **光带是全屏窗口、胶囊是局部窗口**：两窗口互相独立，光带永远 `NOT_TOUCHABLE`，所以永远不抢手势/不挡状态栏下拉。
- **屏幕圆角从 `Display.getRoundedCorner` 取**：视觉上光带贴合物理圆角，不靠硬编码。

**不值得学**
- 胶囊从顶部滑入（`slideInVertically { -it }`）违反本项目"仅自下而上"的动效约束，照搬会被视觉评审打回。
- 三档宽度（272/290/324dp）+ 四种高度的窗口尺寸表，配合 `animateContentSize`，在本项目当前"面板 + 跑马灯 + 悬浮球"三窗口结构里会显著增加窗口协调复杂度。
- 200ms 轮询屏幕圆角是"能用但粗糙"的折中；本项目若需要圆角，应在 `onConfigurationChanged`/`DisplayListener` 里事件驱动。

---

### 3.5 任务完成卡片与状态反馈

**职责**：任务结束后给"结果 + 下一步动作"，而不是只给一个"完成"。

**关键文件/位置**
- 卡片窗口：`AgentRuntimeService.kt:1141-1250`（`showResultCard()`：窗口高 = 屏高 50%，`gravity=BOTTOM`，`FLAG_LAYOUT_NO_LIMITS`，`setFitInsetsTypes(0)`，`navigationBarsPadding()` 由内容负责；注册返回键回调先关卡片）。
- 卡片 UI：`agent/overlay/AgentOverlayContent.kt:302-527`。
- 自动关闭与复核窗口：`AgentRuntimeService.kt:1499-1501`（`HIDE_DELAY_MS=2500`、`RESULT_REVIEW_DELAY_MS=120_000`、`RESULT_CARD_HEIGHT_RATIO=0.5`）。
- 状态文案：`agent/overlay/AgentOverlayText.kt`（`Finished / Failed / Paused / Stopping ...` 的中英文本）。

**核心数据结构**
- `AgentOverlayState.result`：`summary`（Markdown）、`failed`、`hasVirtualDisplayApp`、`runId`。
- 卡片本地状态：`visible / dragOffset / touching / interactionTick`。

**对外接口**：`onClose`（移除窗口）、`onOpenEta`（按 `runId` 深链回到会话）、`onOpenVirtualApp`（副屏应用前台化）。

**值得学**
- **"结果可操作"**：复制到剪贴板（1.4s 变勾）、完成（留当前应用）、回到 Eta（看完整过程）三键，直接覆盖用户结束任务后的三种真实意图。
- **"交互期间不自动关"**：`interacting = touching || scroll.isScrollInProgress || dragOffset>0`，任何滚动/按压都重置 8s 计时；这是卡片类浮层的基本尊重。
- **退场与移除解耦**：`close()` 先播 200ms 退场动画，再 `onClose()` 移除窗口；避免"卡片被瞬间抽走"。
- **下滑关闭带阻尼**：只允许向下拖，向上带阻尼，阈值 96dp；符合单手操作直觉。

**不值得学**
- 卡片最大高度 420dp + 窗口 50% 屏高 + 内容 `weight(1f, fill=false)`，在矮屏/大字号下会出现"内容区被压到几乎不可见"的边界（Eta 靠 `heightIn(max)` 与窗口高度双约束，调参成本高）。
- 卡片里直接放 Markdown 渲染器（`StaticMarkdown`），对本项目意味着要新增一套富文本渲染与主题适配，收益不匹配成本。

---

### 3.6 Agent 主循环与决策/执行解耦

**职责**：一个 run = 一个线程里的"请求 → 解析 tool_calls → 串行执行 → 回填结果 → 下一轮"，直到模型不再调工具或触发停止。

**关键文件/位置**
- `agent/model/AgentLoop.kt:90` 起：`run(...)`；外层 `while` 控制轮次/预算；内部完成系统提示 + 历史 + 观察结果的组装；流式回调只用于 UI 状态（`onDelta` → 状态文本），工具调用在流结束后统一解析；每轮先做上下文压缩判定，再请求 Provider。
- `AgentLoop.kt:280-325`：`executeToolBatch`——**设备工具严格按模型给出的顺序串行执行**；只有"连续的子代理调用"才允许 `async` 并行。注释明确：GUI 是有状态的，并行点击会互相覆盖。
- `agent/model/AgentModelClient.kt:179`：提示词前缀稳定性注释（"工具目录与系统提示都是请求前缀，按轮重算会让一次瞬时状态抖动废掉整段缓存"）。
- `agent/tool/AgentLocalTools.kt:181`：`execute()`——先按 `REQUIREMENTS` 校验能力，再对 `gui=true` 工具套 `guiControl.withAction { generation -> ... }`（单写者锁 + 用户暂停代际失效），动作完成后统一走 `afterAction`。
- `agent/tool/AgentLocalTools.kt:520`：`afterAction()`——动作结束后立即抓一次轻量观察，生成 `AgentAfterActionSummary`（新增/消失/文本变化/可点目标），作为工具结果的一部分返回。
- `agent/tool/AgentAfterActionSummary.kt`：摘要结构（`changed`、`appeared`、`disappeared`、`focus`、`hint`）。
- `agent/model/AgentScreenObservationContract.kt`：`Options(maxNodes, includeScreenshot)`；默认 `resolve` 给"无截图 + 60 节点"；`afterAction` 用"无截图 + 30 节点"。

**核心数据结构**
- `ToolCall(name, argumentsJson, id)`、`ToolResult(content, images, after: AgentAfterActionSummary?)`。
- `AgentRuntimeRunExecutor`：把 `AgentLoop`、`AgentLocalTools`、`AgentModelClient`、`AgentRunTiming` 组装起来；产生 `AgentEvent` 流。
- `AgentEvent`：`RunStarted / RoundStarted / AssistantDelta / ToolStarted / ToolFinished / RunFinished / RunFailed`。

**对外接口**：`AgentEvent`（UI、浮层、通知、DB 都从这里投影）；`AgentLoop.run` 可被主 run 与子代理复用。

**值得学**
- **决策与执行解耦靠"工具结果即观察"**：模型不需要"想 → 观察 → 再想"，动作结果已经把变化告诉它。这是响应速度的最大来源。
- **串行是显式设计，不是偷懒**：注释写明并行点击会破坏 GUI 状态；本项目也应保持"设备动作串行"。
- **GUI 所有权是独立协调器**（`GuiControlCoordinator`）：单写者 + 等待队列 + 用户暂停打断 + 代际号防脏回调，而不是散落在每个工具里。
- **前缀稳定**：系统提示与工具目录按"只增不减"维护，避免每轮重排导致缓存全失效。

**不值得学**
- 主循环里对"子代理并行"的例外处理，会让执行顺序语义变复杂（子代理与设备工具交错时，模型很难预测副作用顺序）。本项目没有子代理，不需要这层。

---

### 3.7 提示词与工具（skill/tool）定义

**职责**：系统提示是"行为契约"，工具目录是"能力契约"，两者都从声明式结构生成。

**关键文件/位置**
- `agent/model/AgentPromptBuilder.kt:12` / `:36`：`buildInitialMessages()` / `buildSystemMessages()`。
  - 多条 system message 分段：平台与权限现状、观察语义（默认不带截图）、动作语义（`after` 字段怎么用）、坐标语义（截图归一化 0–999 vs UI 树屏幕 px）、失败与停止条件、`release_gui` 的使用时机。
  - 关键句：动作结果里的 `after` 已经给出变化，**不要为了"确认"再调用 observe**；只有 `after` 缺失/信息不足时才观察。
- `agent/model/AgentToolCatalog.kt:8` 起：`build(capabilities)` 从 `REQUIREMENTS` 投影出 `ToolSpec` 列表。
- `agent/model/AgentToolSchema.kt`：`ToolSpec(name, description, parametersJsonSchema)`。
- `agent/tool/AgentToolRequirements.kt:15-109`：`ToolRequirement(gui, freshObservation, accessibility, root, lsposed, systemAccess, colorOs)`；`REQUIREMENTS` 是一张 `name → requirement` 的常量表；`rootRequirement()` 等辅助。
- `agent/tool/AgentToolCapabilities.kt`：`project(requirements, snapshot)` 决定"给模型看哪些"。
- `agent/model/AgentGestureToolCatalog.kt`：`tap/swipe/long_press` 等参数描述，明确坐标空间。
- `agent/skill/*`：`SKILL.md`（frontmatter `name/description` + 正文 + `scripts/` 资源）安装到应用私有目录；`AgentSkillCatalog` 提供"按需读取"（渐进披露），不是把所有技能塞进系统提示。
- `agent/mcp/*`：MCP 服务器配置与工具桥接。

**核心数据结构**
- `ToolRequirement` 六布尔位。
- `ToolSpec`（name / description / JSON Schema）。
- `SKILL.md` 文件树 + frontmatter。

**对外接口**：Provider 适配层把 `ToolSpec` 转成 OpenAI `tools` / Anthropic `tools` / Gemini `tools`；`AgentLocalTools` 按 `name` 分发。

**值得学**
- **一份表，三处消费**：目录/校验/UI 都读同一张 `REQUIREMENTS`，不会出现"文档里有、代码里没有"的漂移。
- **工具描述承担"何时用/何时不用"**：例如 observe 描述里直接写"动作结果已含 after，通常无需再观察"，把延迟优化写进提示词契约。
- **技能是文件而不是代码**：SKILL.md + scripts 可按需加载，扩展不需要改 APK 代码。
- **能力不可用时工具直接不出现**，而不是"出现后执行报错"，减少模型无效尝试。

**不值得学**
- 系统提示过长（多段 system message + 平台差异 + 工具使用细则），会显著抬高首 token 延迟；Eta 靠前缀缓存对冲，若 provider 不支持缓存会明显变慢。
- 工具目录 22 个 + 技能 + MCP 同时暴露，模型选择成本高；本项目工具面更窄，不必追求同等覆盖。

---

### 3.8 状态存储

**职责**：会话与 run 的可恢复存储；进程被杀后能恢复"在飞 run"和执行归档。

**关键文件/位置（`data/db/`）**
- Room 数据库（`@Database`），实体大致分为：
  - 会话与消息：`conversations`、`conversation_messages`（含附件引用、provider 元数据）。
  - run 生命周期：`runtime_inflight_runs`（正在跑/可恢复）、`runtime_results`（结果摘要 + 事件计数）、`runtime_archive_runs` / `runtime_archive_events`（完成后归档）。
  - 上下文：`context_checkpoints`（压缩前快照）、`chunked_text`（长文本分块，避免单行过大）。
  - 配置：`providers`、`skills`、`mcp_servers`。
- `agent/runtime/AgentRunCheckpointStore.kt`：运行期检查点（每轮/每工具后落盘，含消息索引与工具计数），服务重启时用于"接着跑"或"安全失败"。
- `agent/runtime/AgentRunArchiveStore.kt`：完成后把事件流批量写归档（`resultIo` 单线程，避免主线程 IO）。
- `AgentRuntimeSession`：内存态（当前会话、活跃 run、事件游标）。

**核心数据结构**：`RunRecord(runId, status, startedAt, finishedAt, roundCount, toolCount, summary, errorKind)`；`ContextCheckpoint(messageIndex, tokenEstimate, compactedAt)`。

**对外接口**：`RuntimeRunStore`（start/append/finish/restore）、`ConversationStore`。

**值得学**
- **在飞 run 独立表**：进程被杀后能区分"可恢复"与"已结束"，不会把半截任务当成功。
- **结果与事件分离**：列表页只查 `runtime_results`（轻），详情页才拉 `archive_events`（重），避免一次查询把几十 MB 事件读进内存。
- **长文本分块**：`chunked_text` 解决 SQLite 单行上限与内存峰值。

**不值得学**
- 四张 run 表 + 归档事件表对单机 Agent 是偏重的 schema；本项目用 JSON 文件 + 单表已能覆盖。

---

### 3.9 错误恢复与重试

**职责**：网络/Provider 抖动可重试；GUI 状态不可重试（必须重新观察）；用户暂停可打断；服务重启可恢复。

**关键文件/位置**
- `agent/model/AgentModelRetry.kt:69-70`：`MAX_RETRIES=3`、`BASE_DELAY_MS=2000`；指数退避 2s/4s/8s（文档 `docs/AGENT_RUNTIME.md` 明确写"2/4/8 秒"）；重试只包住 Provider 请求，**丢弃半截推理内容**，轮次计数加一（事件里能看到重试痕迹）。
- 超时：连接 15s / 写 30s / 读 5min（同文档）。
- `AgentLoop`：工具异常 → 结果里回填 `error` 文本给模型，让其换策略；设备连续失败（如无障碍断开）→ `RunFailed` 并保留检查点。
- `AgentRuntimeService`：服务重启时读 `runtime_inflight_runs`，优先恢复；无法恢复则生成"中断"结果卡。

**核心数据结构**：`RetryPolicy(maxRetries, baseDelay, retryableKinds)`；`RunErrorKind(Provider, Device, Permission, UserCancelled, Internal)`。

**对外接口**：`AgentModelClient.complete(...)` 内部重试；`AgentRunCheckpointStore.save/restore`。

**值得学**
- **只重试"幂等"的部分**：Provider 请求可重试；点击/滑动不可重试（必须先重新观察）。
- **重试丢弃半截输出**：避免把两次流式的半截 reasoning 拼进上下文。
- **错误分类驱动 UI**：`Permission` 类错误直接给"去授权"按钮，而不是让模型自己想办法。

**不值得学**
- 5 分钟读超时对手机 Agent 太长；用户会以为卡死。本项目应给"首 token 超时"单独阈值（如 20–30s）。

---

### 3.10 并发与线程模型

**职责**：主线程（UI/窗口/无障碍调用）、每 run 一个后台线程（引擎）、`resultIo` 单线程（持久化）、Provider 网络线程池（OkHttp）、手势回调 latch。

**关键文件/位置**
- `AgentRuntimeService.kt:90-92`：`mainHandler` + `resultIo`。
- `AgentRuntimeService.kt:468` 附近：`Thread(..., "agent-runtime")`。
- `GuiControlCoordinator`：`Mutex`/条件变量式单写者；等待队列 FIFO；用户暂停时当前动作完成后让出；`generation` 防止旧回调写新状态。
- `AgentAccessibilityService`：`CountDownLatch` 等待 `dispatchGesture` 回调（`L1930-1986`），`awaitWindowChanged`（`L318-321`），`awaitScrollSignal`（`L421-446`），以及主线程同步 `MainThreadCallGate`（`L2034-2052`）。
- `AgentLoop.kt:276-325`：设备工具串行；连续子代理可并行。

**核心数据结构**：GUI 租约（`owner runId + generation`）；事件序列（单 run 内单调）。

**对外接口**：`GuiControlCoordinator.withAction(runId) { ... }`；`MainThreadCallGate.call { ... }`。

**值得学**
- **GUI 单写者**：任何时刻只有一个 run 能碰屏幕；多任务排队而不是互相抢。
- **代际号**：用户暂停/取消后，旧动作的回调不能继续推进 UI。
- **主线程门**：无障碍 node 调用强制串行，消除一类玄学崩溃。

**不值得学**
- 每 run 一个线程 + `runBlocking` 的模型在高并发（多 run）时会堆线程；本项目单 run 场景足够，但不要扩散成"每个子任务一个线程"。

---

### 3.11 可观测性与日志

**职责**：事件流是唯一真源；耗时埋点在关键阶段；日志分级 + 脱敏。

**关键文件/位置**
- `agent/runtime/AgentRunTiming.kt:15-48`：阶段标记——`Preparation`、`ProviderRequestStarted`、`ProviderResponseStarted`（拿到响应头）、`FirstDelta`、`ToolStart/ToolEnd`、`Finish`；每段记录 `atMs` 与 `durationMs`。
- `AgentEvent`：UI（聊天过程）、浮层（状态）、通知（短文本）、DB（归档）都从事件投影，避免各自维护状态。
- `AndroidAgentLogger`（`core/log` 一带）：分级（DEBUG/INFO/WARN/ERROR）、按 run 分文件/环形缓冲、敏感信息脱敏。
- `docs/AGENT_RUNTIME.md`：明确"计时标记只做记录，不参与决策"。

**核心数据结构**：`RunTiming(marks: List<TimingMark>, totals: Map<Stage, Long>)`。

**对外接口**：`AgentRunTiming.mark(stage)`；调试面板读取。

**值得学**
- **按阶段埋点而不是只记总时长**：能定位"是 provider 慢、还是工具慢、还是首 token 慢"。
- **事件驱动所有 UI**：浮层/通知/列表不会出现"三个地方说得不一样"。
- **敏感信息脱敏在日志层统一做**，不靠调用方自觉。

**不值得学**
- 事件体用 `Bundle` + PFD 的 IPC 设计在进程内会多一次序列化；本项目单进程可直接用数据类。

---

### 3.12 UI 层结构与主题令牌

**职责**：Miuix 风格的 Compose UI；主题令牌集中；浮层与 App 共用状态枚举。

**关键文件/位置**
- `ui/app/AgentAppTheme.kt`：Miuix `Theme` 包装；颜色/字号/圆角从 `MiuixTheme.colorScheme` 取；自定义只保留必要 slot。
- `ui/screens/*`：聊天、任务过程（步骤时间线）、设置、技能/MCP 管理、Provider 配置。
- `agent/overlay/AgentOverlayContent.kt`：浮层内直接用 `MiuixTheme.colorScheme`，与 App 同源。
- 动效：Compose `spring/tween`；卡片 200ms 退场；光带 4200ms 旋转；胶囊文本 220ms 交叉淡入。

**核心数据结构**：主题令牌（surface / onSurface / secondaryContainer / accent）；相位色（`overlayPhaseAccent`）。

**对外接口**：`AgentAppTheme`、`overlayPhaseAccent(phase)`、`overlayPhaseAlpha(phase)`。

**值得学**
- **浮层与 App 共用一套语义色**：不会出现"App 里是蓝色、浮层里是紫色"的割裂。
- **相位到颜色/透明度的集中映射**：状态颜色只在一个函数里定义。
- **动效参数集中在常量里**（200ms/4200ms/220ms），便于统一调参。

**不值得学**
- Miuix 是小米 HyperOS 视觉语言，与本项目 Material 3 Expressive 冲突；只借"令牌集中"方法，不借组件。
- 光带 4200ms 无限旋转 + 14dp 模糊在低端机上持续 GPU 开销偏大。

---

### 3.13 测试与构建脚本

**职责**：JVM 单测（Robolectric）覆盖纯逻辑与 Compose 语义；CI 做编译 + 单测 + 静态检查。

**关键文件/位置**
- `app/src/test/**`：约 302 个测试文件，覆盖工具需求表、提示词构建、观察摘要、窗口参数、状态机、上下文压缩、重试策略等纯逻辑。
- `app/build.gradle.kts:114-124`：`lint` 配置、`unitTests.isIncludeAndroidResources = true`（Robolectric 需要）。
- `.github/workflows/*`：PR 检查（编译 + 单测 + lint/detekt 类闸门）+ 发布构建。
- 版本号规则与 APK 命名（见 3.1）由 CI 读取。

**值得学**
- **窗口参数与工具表都有单测**：这类"看起来只是常量"的东西最容易回归，Eta 用纯函数 + 断言锁住。
- **Robolectric 开 Android 资源**：Compose/资源相关逻辑可在 JVM 跑，不必每次都上真机。
- **CI 只跑必要闸门**：编译 + 单测 + 静态检查，不做设备测试（设备测试另走人工/脚本）。

**不值得学**
- 302 个测试文件对当前团队是重资产；本项目更适合"先补关键纯函数测试"（窗口参数、工具表、状态机）。

---

## 4. 四块重点深入分析

### 4.1 ① 浮层如何压过状态栏且不被裁切

#### 4.1.1 结论先行

Eta 的"光带"能画到状态栏与挖孔区，靠的是**四个条件同时成立**：

1. 窗口高度取**真实显示尺寸**（`getRealSize/getRealMetrics`），不是 `MATCH_PARENT`；
2. `WindowManager.LayoutParams.setFitInsetsTypes(0)`（API 30+）清空系统栏 inset 适配；
3. `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`；
4. `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`。

再加窗口类型选择：无障碍可用时用 `TYPE_ACCESSIBILITY_OVERLAY`（不需要悬浮窗权限、z 序高、且会被截图过滤），否则回退 `TYPE_APPLICATION_OVERLAY`。

#### 4.1.2 代码级细节（Eta）

- 窗口创建：`app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt:985-1057`
  - 无障碍不可用且无悬浮窗权限时直接 `return`（不显示任何浮层）；否则先加光带全屏窗口、再加胶囊。光带 `addView` 失败只记日志（胶囊仍可用），胶囊失败则整体返回。
- 光带参数：同文件 `L1327-1356`
  - `type = overlayType()`：无障碍可用 → `TYPE_ACCESSIBILITY_OVERLAY`，否则 `TYPE_APPLICATION_OVERLAY`（`L1315-1321`，注释明确"免 SYSTEM_ALERT_WINDOW，且截图 `filterValidWindows` 可过滤"）。
  - `flags = FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE`
  - 高度取 `Display.getRealSize(point).y`，注释原文：**"真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏"**（`L1328`）。
  - `setFitInsetsTypes(0)`（`L1353`），注释原文：**"非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上"**（`L1351-1352`）。
  - `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`（`L1354`）。
  - `gravity = TOP or START`，`x=y=0`（`L1348-1350`）。
- 胶囊参数：同文件 `L1271-1287`
  - 同样 `FLAG_LAYOUT_IN_SCREEN`、`FLAG_HARDWARE_ACCELERATED`、`FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL` + `PixelFormat.TRANSLUCENT`
  - **但不加 `NO_LIMITS`，也不清 insets**
  - `gravity = TOP|CENTER_HORIZONTAL`；`y = statusBarHeightPx()`，而 `statusBarHeightPx()` 取 `statusBars | displayCutout` 的 `getInsetsIgnoringVisibility` 顶部值（`L1289-1296`）——所以胶囊同时避开状态栏与挖孔
  - 宽 `CAPSULE_WINDOW_WIDTH_DP`（副屏时 `CAPSULE_MICRO_WINDOW_WIDTH_DP`）、高 `CAPSULE_COLLAPSED_HEIGHT_DP`（`L1273-1274`）
- 结果卡参数：同文件 `L1298-1313`
  - `MATCH_PARENT` 宽，`height = resultCardWindowHeightPx()`；`FLAG_HARDWARE_ACCELERATED|FLAG_NOT_TOUCH_MODAL|FLAG_LAYOUT_IN_SCREEN`（**没有** `NOT_FOCUSABLE`，因为要接收输入）
  - `gravity = BOTTOM|CENTER_HORIZONTAL`，`x=y=0`
- 圆角与路径：`agent/overlay/AgentOverlayContent.kt:136-296`
  - `screenCornerRadii` 从 `Display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT/...)` 取，回退 28dp（`AgentRuntimeService.kt:1362-1374`）
  - 用 squircle 路径向内缩 1.5dp，避免 1px 描边被屏幕圆角裁掉
- 触摸与裁切：
  - 光带永远 `FLAG_NOT_TOUCHABLE`，不会挡状态栏下拉/导航手势；
  - 胶囊可触摸（有控制按钮），但位置在状态栏下，不覆盖系统栏；
  - 截图时通过 `ScreenshotWindowPolicy` 过滤自家窗口。

#### 4.1.3 本项目现状与差距

- `app/src/main/java/com/phoneagent/core/window/OverlayWindowSpec.kt:31-55`
  - `passiveFullScreen(...)` 用 `TYPE_APPLICATION_OVERLAY`、`MATCH_PARENT`、`FLAG_LAYOUT_NO_LIMITS`、可选 `fitInsetsTypes = 0`、`alpha = 0.4`（组合 alpha 上限说明在 `L15-22`）。
  - **没有** `layoutInDisplayCutoutMode`；高度是 `MATCH_PARENT` 而不是真实尺寸。
- `app/src/main/java/com/phoneagent/feature/edge/EdgeLightingService.kt:89-107`
  - `params = OverlayWindowSpec.passiveFullScreen(clearInsets = false)`（`L95`），注释写"光效按自身 inset 参数绘制，故保留系统默认 fitInsets"。
  - 结果：窗口内容区从状态栏下沿开始，`EdgeLightingView` 即使画到自身 `y=0`，物理上也落在状态栏下方——**这是"光带压不到最顶"的直接原因**。
- `app/src/main/java/com/phoneagent/overlay/FloatingWindowService.kt:402-431`（顶部任务面板）
  - 已正确做 `fitInsetsTypes=0`、`FLAG_LAYOUT_IN_SCREEN|NO_LIMITS`，并按 `statusBars | displayCutout` 计算顶部安全线（`L382-386`）；说明"清 insets"这套在本项目已有先例，只是边缘光窗口没采用。
- 底部跑马灯：`FloatingWindowService.kt:456-510`
  - 独立 `TYPE_APPLICATION_OVERLAY`、`WRAP_CONTENT`、`FLAG_NOT_TOUCHABLE`、`fitInsetsTypes=0`，`y` 按导航栏高度上移——它本来就"避开"系统栏，不需要覆盖。

#### 4.1.4 可落地结论

1. 若目标是"边缘光压过状态栏"：
   - 边缘光窗口改 `clearInsets = true`（或单独参数），
   - 高度取 `WindowMetrics.bounds`（API 30+）或 `Display.getRealSize`，
   - 补 `layoutInDisplayCutoutMode = ALWAYS`。
   - 保持 `FLAG_NOT_TOUCHABLE` 与组合 alpha < 0.8（现有 `PASSIVE_ALPHA` 约定不能破）。
2. 若目标是"胶囊/跑马灯压过状态栏"：不建议——本项目已有顶部面板占用状态栏下方空间，跑马灯再上移会与面板/状态栏视觉冲突；Eta 也用 `y=statusBarHeight` 主动避开。
3. 若要用 `TYPE_ACCESSIBILITY_OVERLAY`：
   - 优点：无需悬浮窗权限、z 序更稳、可被截图过滤；
   - 风险：无障碍服务被系统回收时窗口直接消失；必须在服务连接/断开时重建/降级到 `TYPE_APPLICATION_OVERLAY`。
   - 本项目已有"无障碍掉线检测"（`ObservationPhase.kt:63-83`），可复用该信号做窗口降级。

---

### 4.2 ② 任务完成卡片的结构与动效

#### 4.2.1 窗口层

`AgentRuntimeService.kt:1141-1250`：

- 独立窗口，`gravity = BOTTOM`，`FLAG_LAYOUT_NO_LIMITS`，`setFitInsetsTypes(0)`；
- 高度 = `screenHeight * RESULT_CARD_HEIGHT_RATIO(0.5f)`（`L1501`），但内容贴底、`heightIn(max=420dp)`；
- 内容侧 `navigationBarsPadding()` + 左右 10dp + 底部 10dp；
- 注册返回键回调：返回 = 先关卡片（不退出当前 App）；
- 自动关闭：卡片内部 8s；窗口侧保留 `RESULT_REVIEW_DELAY_MS = 120_000` 作为"复核窗口"，避免用户刚想看就被移除。

#### 4.2.2 结构（`agent/overlay/AgentOverlayContent.kt:302-527`）

自上而下：

1. **拖拽把手**：36×4dp 圆角条，顶部 8dp，`onSurfaceVariantSummary` 30% 透明；
2. **状态行**：20dp 圆形底（accent 14% 透明）+ 勾/叉图标 13dp + 15sp SemiBold 标题（"已完成"/"未完成"）；
3. **正文**：`StaticMarkdown`（Answer 语气），可垂直滚动；底部 28dp 透明→surface 渐变遮罩，提示"还有内容"；
4. **动作行**：复制图标按钮（44dp 圆形，`secondaryContainer`，复制后 1.4s 变勾）+ "完成"（次级文字按钮，`weight(1f)`）+ "回到 Eta" / "打开画面"（主色文字按钮，`weight(1f)`）。

#### 4.2.3 动效与手势

- 进入：`slideInVertically(spring(DampingRatioLowBouncy, StiffnessMediumLow)) { it }` + `fadeIn(tween(200))`——**从底边向上**，与本项目"仅自下而上"一致。
- 退出：`slideOutVertically(tween(200)) { it }` + `fadeOut(tween(200))`，动画结束后才 `onClose()` 移除窗口（`L312-320`）。
- 拖拽：只允许向下；向上有阻尼；松手位移 > 96dp 关闭，否则 spring 回弹（`L378-387`、`L344`）。
- 自动关闭：`interacting = touching || scroll.isScrollInProgress || dragOffset > 0` 时暂停计时（`L330-338`）。
- 圆角/阴影：28dp 圆角、16dp 阴影、ambient 18% 黑（`L364-366`）。

#### 4.2.4 本项目现状

- `app/src/main/java/com/phoneagent/overlay/FloatingWindowService.kt:1853-1874` 的 `showDone()`：
  - 在**同一顶部面板**里隐藏 dot/marquee/status/progress，换成 `donePanel` + `SuccessMarkView`（勾选动画），`showPanelWithAnim`（自下而上）；
  - 顶部面板不自动消失，也没有"复制结果/回到 App/继续"动作；
  - 通知同步更新为"任务完成：…"。
- 差距：
  1. 缺少"结果正文"（只有一行完成提示）；
  2. 缺少"下一步动作"（复制/回到会话/打开副屏）；
  3. 缺少独立窗口，长结果无处展示；
  4. 缺少自动关闭/下滑关闭/交互期间暂停计时。

#### 4.2.5 可落地结论

- 若要做"完成卡片"，最小可行版本：
  - 复用现有顶部面板做"完成态"（保留 `SuccessMarkView`），但在面板内增加**一行可滚动结果 + 两个按钮**（复制 / 完成）；
  - 若要独立卡片窗口，复制 Eta 的"底部贴边 + 自下而上 + 200ms 退场"三件事即可，**不要**复制 50% 屏高 + 420dp 双约束与 Markdown 渲染。
- 本项目硬约束映射：
  - 无 Toast/系统弹窗 → 用浮层卡片，符合；
  - 动效仅自下而上 → Eta 卡片进入方向可用，胶囊方向不可用；
  - 纯色不透明 → Eta 的 `surface`（不透明）+ 阴影可用；不要用毛玻璃/半透明。

---

### 4.3 ③ 首帧反馈与单步耗时/并发设计

#### 4.3.1 时间线（Eta）

1. **提交**：App 侧把用户消息写入会话（本地立即可见），随后通过 `AgentRuntimeClient` 发 `MSG_START_RUN`；服务端 `ingest` 建 run 并回 `RunStarted`。
2. **首帧反馈**：
   - 聊天页：用户消息 + "正在处理"状态由事件投影，不等模型；
   - 浮层：`AgentOverlayVisibilityPolicy.kt:10-15` 规定**只有取得 GUI 控制权（或结束态）才显示胶囊**；纯思考阶段不弹浮层，避免"浮层闪一下又没动作"。
3. **单步**：
   - 观察（默认 UI 树、无截图、60 节点）→ Provider 请求（流式）→ 流结束解析 tool_calls → 校验能力 → GUI 加锁 → 执行 → 立即轻量观察 → 生成 `after` 摘要 → 回填 → 下一轮。
4. **结束**：`RunFinished` → 胶囊 2.5s 后隐藏（`HIDE_DELAY_MS`）→ 完成卡片出现 → 8s 自动关（交互期间暂停）。

#### 4.3.2 支撑"快"的机制清单

| 机制 | 位置 | 作用 |
| --- | --- | --- |
| 动作结果自带 `after` 摘要 | `AgentLocalTools.kt:520`；`AgentAfterActionSummary.kt` | 省掉下一步的完整 observe 往返 |
| 默认 observe 不带截图 | `AgentScreenObservationContract.kt` 默认 Options | 省掉截图编码 + 图片上传 + 视觉 token |
| `after` 预算更小（30 节点） | `AgentLocalTools.kt:1734` | 工具结果短，减少 prompt 体积与解析时间 |
| 原生 tool_calls | `OpenAiChatCompletionsProvider.kt:110-111` 等 | 不需要模型输出 JSON 再解析，减少格式错误与重试 |
| 系统提示/工具目录稳定前缀 | `AgentModelClient.kt:179` | 命中 provider 前缀缓存，首 token 更快 |
| 设备工具串行 | `AgentLoop.kt:276-325` | 避免并发点击导致状态错乱与重试 |
| GUI 单写者 + 等待队列 | `GuiControlCoordinator` | 多任务不抢屏，失败不重入 |
| 首 token 前标记 | `AgentRunTiming.kt:15-48` | 能区分"网络慢/模型慢/工具慢" |
| 通知短文本 7 字符 | `AgentExecutionService.kt:126-133` | 状态栏胶囊不截断，用户一眼可读 |

#### 4.3.3 本项目现状

- 提交与首帧：`TaskRunner.kt:36-42` + `UiBridge`/`StepRunner`，提交后浮层/面板立即更新（比 Eta 更"早"），但**每一步都要重新观察**。
- 观察：`ObservationPhase.kt:60-115` + `StepObserver.kt:38-120`
  - 已有"截图按需 + 指纹复用"，不是无脑截图；
  - 但观察结果**只作为下一步输入**，动作结果里没有 `after` 摘要 → 每步固定多一次完整观察链路。
- 决策：`DecisionPhase.kt:148-193` + `AiClient.kt:235-358`
  - SSE 累积完整正文后 `parseAgentIntent`，**非原生 tool_calls**；
  - 解析失败走"纠错重发"，增加一次往返。
- 执行：`StepRunner.kt:10-30` 三阶段串行；`TaskRunner.kt:42` 步数循环。
- 遥测：`TelemetryRecorder.kt:56-130` 已有 `recordVisionMs / recordExecMs / recordStepTrace`，**缺**"provider 首 token/请求阶段"与"提交→首帧"指标。

#### 4.3.4 可落地结论

按收益排序：

1. **动作即观察（最高收益，低风险）**：在 `ActionExecutor` 执行完点击/滑动/输入后，立刻抓一次轻量 UI 快照（不截图、节点上限 20–30），把"新增/消失/文本变化/焦点"塞进动作结果与下一步上下文。收益：多数步骤省掉一次完整 `ObservationPhase`（含标注、截图判定、页面记忆）。
2. **给决策链路加阶段计时**：在 `AiClient` 记录 `requestStart / responseStart / firstDelta / streamEnd`，在 `TelemetryRecorder` 增加字段。收益：能证明"慢在首 token还是慢在工具"。
3. **原生 tool_calls（中收益，中风险）**：需要 provider 支持 + 参数 schema 迁移；建议先在"云端决策"单通道灰度，保留 JSON 解析兜底。
4. **工具批量上限**：即使协议支持并行工具调用，设备动作也必须串行（Eta 的注释是硬证据）；可在 prompt 里明确"一次只给一个设备动作"。

---

### 4.4 ④ 能力/工具注册与扩展机制

#### 4.4.1 三层结构（Eta）

1. **能力快照**：`AgentToolCapabilities`（accessibility/root/lsposed/systemAccess/colorOs/gui/freshObservation）。
2. **声明式需求表**：`AgentToolRequirements.REQUIREMENTS`（`name → ToolRequirement`），常量、可单测。
3. **投影与执行**：
   - `AgentToolCatalog.build(capabilities)` → `List<ToolSpec>`（给 Provider）；
   - `AgentLocalTools.execute()` 按同一张表做运行期校验；
   - App 的能力列表/权限引导读取同一张表。

#### 4.4.2 关键代码

- `agent/tool/AgentToolRequirements.kt:15-109`
  - `data class ToolRequirement(gui, freshObservation, accessibility, root, lsposed, systemAccess, colorOs)`
  - `REQUIREMENTS: Map<String, ToolRequirement>`，例如 `tap/swipe/long_press → gui=true`；`observe_screen → freshObservation=true`；`input_text` 可能要求 accessibility/systemAccess。
- `agent/tool/AgentToolCapabilities.kt`
  - `project(...)`：能力不满足的工具**直接不出现**在目录里；`gui=true` 的工具在描述里追加"可能需要等待/释放控制权"的提示。
- `agent/model/AgentToolCatalog.kt:8`
  - 从 `REQUIREMENTS` + `AgentToolSchema` 生成 `ToolSpec`；描述文本本身承担"何时用/何时不用"。
- `AgentLocalTools.kt:181`
  - 执行前再用同一张表校验一次（防止目录与运行期能力漂移）；`gui=true` 自动套 `GuiControlCoordinator.withAction`。
- 扩展点：
  - **技能**：`SKILL.md`（frontmatter + 正文 + `scripts/`）装到私有目录，按需读取；
  - **MCP**：`agent/mcp` 把外部服务器工具桥接成 `ToolSpec`；
  - **子代理**：`agent/subagent` 复用 `AgentLoop`，可并行（唯一允许并行的工具类型）。

#### 4.4.3 本项目现状

- 工具是"提示词文档 + JSON 动作分发"：
  - `app/src/main/java/com/phoneagent/engine/prompt/*`（提示词正文与工具说明）；
  - `ActionExecutor` / `AgentEngine` 按动作名分发；
  - `core/ai/ChatModels.kt` 的 `ToolSpec` 仅用于模型能力探测，不参与 Agent 决策链。
- 差距：
  1. 没有"一份表"：提示词里的工具说明、执行分发、权限校验是三套地方，容易漂移；
  2. 没有能力投影：工具是否可用靠提示词描述与运行期报错，模型会做无效尝试；
  3. 没有 `gui` 标记：无法自动加锁/让路。

#### 4.4.4 可落地结论

- 最小改造（不引入原生 tool_calls 也能做）：
  1. 新增 `ToolRequirement` 表（name → 需要的通道/是否 GUI/是否需要新鲜观察），先用它生成"给模型的工具清单文本"与运行期校验；
  2. 在 `ActionExecutor` 入口按表校验，GUI 类动作统一走一个"设备动作锁"（哪怕当前是单 run，也能防重入）；
  3. 表加单测（每个动作名必须有条目；GUI 动作不得标记为可并行）。
- 不建议一步到位做 MCP/子代理：本项目工具面窄，收益低于维护成本。

---

## 5. 与本项目的差距对照表

| 维度 | Eta | 本项目 | 差距性质 | 建议 |
| --- | --- | --- | --- | --- |
| 浮层覆盖状态栏 | 光带全屏窗口：真实高度 + `fitInsetsTypes(0)` + cutout ALWAYS + NO_LIMITS | 边缘光 `clearInsets=false`；`MATCH_PARENT`；无 cutout 模式（`EdgeLightingService.kt:95`，`OverlayWindowSpec.kt:31-55`） | 能力缺口（可直接补） | 浮层线：改参数并回归 |
| 浮层窗口类型 | `TYPE_ACCESSIBILITY_OVERLAY`（可用时）/`TYPE_APPLICATION_OVERLAY` | 统一 `TYPE_APPLICATION_OVERLAY`（`OverlayWindowSpec.kt:36`） | 可选优化 | 不急着换，先补 insets |
| 浮层让路 | `FLAG_NOT_TOUCHABLE` 动态切换 + 90ms 提前淡出 + 900ms 恢复（`AgentOverlayClearance.kt`） | 无让路机制；面板可触摸 | 真实缺陷（agent 手势可能被自家浮层吞掉） | 浮层线：补 clearance |
| 完成反馈 | 独立底部卡片 + 复制/完成/回会话 + 8s 自动关 + 下滑关 | 顶部面板原地变完成态 + 勾动画（`FloatingWindowService.kt:1853`） | 体验缺口 | 浮层线：先加结果行+两键，再考虑独立窗口 |
| 状态文案真源 | `AgentOverlayStatus` 一处，浮层/通知/聊天共用 | 多处 `pushFloating` 字符串 | 一致性风险 | 通用：状态枚举化 |
| 观察策略 | 默认无截图、60 节点；after 30 节点无截图 | 有按需截图+指纹复用（`StepObserver.kt`），但无 after 摘要 | 半差距 | 引擎线：补 after |
| 动作后反馈 | 工具结果自带变化摘要 | 下一步重新观察 | **最大延迟来源** | 引擎线：动作即观察 |
| 工具协议 | 原生 tool_calls（OpenAI/Anthropic/Gemini 适配） | SSE 累积正文 + JSON 解析（`AiClient.kt:235-358`） | 架构差异 | 引擎线：灰度原生工具调用 |
| 工具注册 | 一张 `REQUIREMENTS` 表驱动目录/校验/UI | 提示词+分发+校验分散 | 可维护性差距 | 通用：先落表 |
| GUI 并发 | 单写者锁 + 队列 + 代际 | 单 run，无显式锁 | 规模差异 | 通用：预留锁接口 |
| 重试 | 3 次，2/4/8s，只包 Provider | 有纠错重发（`DecisionPhase.kt:181-185`） | 策略差异 | 引擎线：区分首 token 超时 |
| 存储 | Room + 在飞/结果/归档 + 检查点 | JSON/文件 + 检查点 | 规模差异 | 不迁移 |
| 遥测 | 阶段标记（含首 token） | 步骤耗时（vision/exec） | 指标缺口 | 通用：补阶段埋点 |
| 测试 | 302 文件，窗口参数/工具表有单测 | 80 文件 | 资产差距 | 通用：先补纯函数测试 |
| UI 基座 | Miuix | Material 3 Expressive | 不可迁移 | 只借令牌方法 |

---

## 6. 行动建议（浮层线 / 引擎线 / 通用）

> 每条给"动机 → 落地步骤 → 验收标准"，均按本项目硬约束（无系统弹窗/Toast、动效仅自下而上、跑马灯已移到底部、不引入第三方库）设计。

### 6.1 浮层线

#### A1. 边缘光真正压过状态栏（参数级，低风险）

- 动机：当前 `clearInsets=false` 让光带永远从状态栏下沿开始。
- 步骤：
  1. 在 `OverlayWindowSpec` 增加"真实尺寸 + 清空 insets + cutout ALWAYS"的专用工厂（不要改动现有 `passiveFullScreen` 的默认语义，避免影响光标/页标窗口）；
  2. `EdgeLightingService` 改用该工厂；
  3. 高度用 `WindowMetrics.bounds`（API 30+），API 26–29 回退 `Display.getRealSize`；
  4. 保留 `FLAG_NOT_TOUCHABLE` 与 `alpha = PASSIVE_ALPHA`（组合 < 0.8 的约定不变）。
- 验收：全屏截图对比，光带最上沿覆盖状态栏背景；状态栏下拉、返回手势、三指截屏均不受影响；组合 alpha 仍 < 0.8（用现有注释的公式复核）。

#### A2. 浮层让路（clearance）

- 动机：顶部面板可触摸且可能落在 agent 点击坐标上，导致"点了没反应"。
- 步骤：
  1. 给 `FloatingWindowService` 增加"矩形让路"能力：命中 agent 手势点时，立即给窗口加 `FLAG_NOT_TOUCHABLE` 并 `updateViewLayout`（不重建窗口），同时把面板 alpha 淡到 0（90ms 提前量）；
  2. 手势结束后 900ms 恢复（等待系统把触摸事件交还应用）；
  3. 让路范围 = 面板矩形外扩 8dp（对应 Eta 的 `CAPSULE_CLEARANCE_SLOP_DP=8`）；
  4. 与 `ActionExecutor` 的手势入口串联（点击/滑动/长按都走同一包装）。
- 验收：agent 点击面板覆盖区域的控件，连续 20 次全部命中；用户手动点击面板按钮不受影响；无窗口闪烁/重建日志。

#### A3. 完成态升级（不新建窗口的版本）

- 动机：当前只有一行完成提示，用户无法复制结果/回到会话。
- 步骤：
  1. 在现有 `donePanel` 内增加"结果摘要"（最多 3 行，超出省略）与两个按钮：复制、完成；
  2. 复制按钮点后 1.4s 变勾（复用现有图标体系，不引第三方）；
  3. 保留 `SuccessMarkView` 与自下而上动画；
  4. 若后续要做独立卡片，再按 Eta 的"底部贴边 + 200ms 退场 + 8s 自动关"实现，但**不要**照搬 50% 屏高 + 420dp 双约束。
- 验收：完成态出现后 8s 或点"完成"关闭；复制内容与任务结果一致；动效方向全部自下而上。

#### A4. 状态文案单一真源

- 动机：`pushFloating("…", "ACTING")` 这类字符串散落，容易不同步。
- 步骤：定义 `AgentUiStatus` 密封类（Reasoning/RunningTool/Paused/Finishing/Failed），浮层、通知、聊天过程都从它投影；文案集中在一个映射函数。
- 验收：三处文案一致；新增状态只改一处。

### 6.2 引擎线

#### B1. 动作即观察（after-summary）

- 动机：每步固定多一次完整观察，是当前最大延迟来源。
- 步骤：
  1. 在 `ActionExecutor` 执行成功后，抓一次"轻量快照"：不截图、节点上限 20–30、只提取 `新增/消失/文本变化/焦点/可点目标`；
  2. 把摘要附加到 `StepSession`（如 `session.lastActionDelta`），在 `DecisionPhase` 组装消息时作为"上一步结果"的一部分；
  3. `ObservationPhase` 下一步若检测到 `lastActionDelta` 与当前页面指纹一致，可跳过标注/截图，直接用摘要 + 上一个快照；
  4. 摘要生成失败时保持现有完整观察，保证正确性优先。
- 验收：同一任务（如"打开设置 → 显示 → 字体大小"）平均步数不上升的前提下，单步 `vision+observe` 耗时分位下降 ≥ 20%（用 `TelemetryRecorder` 现有指标对比）。

#### B2. 阶段计时补齐

- 动机：现有 `recordVisionMs/recordExecMs` 无法定位 provider 慢在首 token 还是慢在工具。
- 步骤：在 `AiClient` 流式请求里记录 `requestStart/responseStart/firstDelta/streamEnd`，通过 `TelemetryRecorder` 写字段；不改 UI。
- 验收：一次任务可导出"提交→首帧 / 首 token / 流结束 / 工具"四段耗时。

#### B3. 原生 tool_calls 灰度（可选，后置）

- 动机：JSON 解析失败会触发纠错重发，增加往返。
- 步骤：仅对云端决策通道开启 `tools` + `tool_choice=auto`，保留 JSON 解析兜底；设备动作仍一次一个。
- 验收：解析失败率下降；任务成功率不下降；关闭开关可回退到旧链路。

#### B4. 首 token 超时与重试分层

- 动机：统一长超时会让"模型没响应"表现为"卡死"。
- 步骤：把"连接/首 token/整段"拆成三个阈值（如 15s/30s/180s）；首 token 超时重试一次，整段超时直接失败并给用户可读原因。
- 验收：弱网下 30s 内给出明确状态，不再无限等待。

### 6.3 通用

#### C1. 工具需求表（先声明，后执行）

- 步骤：`ToolRequirement`（gui/freshObservation/accessibility/systemAccess）→ 生成提示词清单 → `ActionExecutor` 校验 → 加单测。
- 验收：每个动作名都有条目；GUI 动作 100% 走设备动作锁；目录与执行不漂移。

#### C2. 纯函数测试补强

- 优先补：窗口参数（insets/flags/高度）、工具需求表、状态机映射、坐标换算。
- 验收：这些文件改动必须伴随测试变更；CI 通过。

#### C3. 可观测性面板

- 把 `TelemetryRecorder` 的步骤指标与新增阶段指标做成调试页（不进入正式 UI），支持导出 JSON。
- 验收：能用一次任务的数据回答"慢在哪一段"。

---

## 7. 明确不建议照搬的部分

1. **`TYPE_ACCESSIBILITY_OVERLAY` 作为默认窗口类型**
   - 依赖无障碍服务存活；服务被回收/用户关闭时浮层直接消失；与"无障碍掉线检测"逻辑耦合。
   - 本项目应保留 `TYPE_APPLICATION_OVERLAY`，只在确有 z 序需求时按能力切换。

2. **胶囊从屏幕顶部滑入（`slideInVertically { -it }`）**
   - 违反本项目"动效仅自下而上"的硬约束；照搬会被视觉评审打回。
   - 若要顶部胶囊，进入/退出应改为"自下而上 + 淡入淡出"或"高度展开"。

3. **Miuix 组件与 HyperOS 视觉语言**
   - 与 Material 3 Expressive 冲突；组件级照搬会带来两套设计体系并存。
   - 只借"令牌集中（颜色/圆角/动效常量）"的方法。

4. **光带 4200ms 无限旋转 + 14dp 模糊**
   - 低端机持续 GPU 开销；本项目已有 `EdgeLightingView` 的轻量实现，不必替换。
   - 若需要"呼吸感"，用低频 alpha 脉冲（现有 `Motion.DurationPulse=900ms`）而非旋转 sweep。

5. **50% 屏高 + 420dp 双约束的完成卡片**
   - 矮屏/大字号下内容区会被压扁；调参成本高。
   - 用"内容自适应 + 最大高度 + 底部贴边"更稳。

6. **四张 run 表 + 归档事件表的存储 schema**
   - 对单机 Agent 过重；本项目 JSON + 检查点已够。
   - 只借"结果与事件分离""在飞 run 独立"两条原则。

7. **302 个测试文件的全量移植**
   - 维护成本高于收益；先补关键纯函数测试。

8. **长系统提示 + 22 工具 + 技能 + MCP 全量暴露**
   - 依赖 provider 前缀缓存与强模型；本项目模型/通道更窄，工具面应保持精简。

9. **`notificationTimeout=50ms` 的无障碍事件配置**
   - 事件频率高、耗电；本项目无事件驱动需求时不要照搬。

10. **子代理并行的例外**
    - 设备动作并行会破坏 GUI 状态；Eta 自己也只允许子代理并行。本项目无子代理，不需要引入这层复杂度。

---

## 8. 附录

### 8.1 Eta 关键常量速查

| 常量 | 值 | 位置 |
| --- | --- | --- |
| 胶囊窗口宽（折叠/微透镜/舞台） | 272 / 290 / 324 dp | `AgentRuntimeService.kt:1503-1505` |
| 胶囊窗口高（折叠/控制/舞台/补充） | 68 / 124 / 524 / 260 dp | `AgentRuntimeService.kt:1506-1509` |
| 手势让路外扩 | 8 dp | `AgentRuntimeService.kt:1511` |
| 屏幕圆角回退 | 28 dp | `AgentRuntimeService.kt:1512` |
| 浮层隐藏延迟 | 2500 ms | `AgentRuntimeService.kt:1499` |
| 结果复核窗口 | 120000 ms | `AgentRuntimeService.kt:1500` |
| 结果卡高度比例 | 0.5 | `AgentRuntimeService.kt:1501` |
| 胶囊让路等待/提前淡出/恢复 | 300 / 90 / 900 ms | `AgentOverlayClearance.kt` |
| 结果卡自动关闭/退场/圆角 | 8000 / 200 ms / 28 dp | `AgentOverlayContent.kt:525-527` |
| 结果卡下滑阈值 | 96 dp | `AgentOverlayContent.kt:344` |
| observe 默认节点/截图 | 60 / 否 | `AgentScreenObservationContract.kt` |
| after 节点/截图 | 30 / 否 | `AgentLocalTools.kt:1734` |
| Provider 重试 | 3 次，2/4/8 s | `AgentModelRetry.kt:69-70` |
| 上下文压缩触发比 | 0.85 | `AgentContextSession.kt` |
| 通知短文本长度 | 约 7 字符 | `AgentExecutionService.kt:126` |

### 8.2 Eta 文件索引（按阅读优先级）

| 主题 | 文件 |
| --- | --- |
| 运行时/窗口 | `agent/runtime/AgentRuntimeService.kt` |
| 主循环 | `agent/model/AgentLoop.kt` |
| Provider/提示词 | `agent/model/AgentModelClient.kt`、`AgentPromptBuilder.kt` |
| 工具执行 | `agent/tool/AgentLocalTools.kt`、`AgentAfterActionSummary.kt` |
| 工具注册 | `agent/tool/AgentToolRequirements.kt`、`AgentToolCapabilities.kt`、`agent/model/AgentToolCatalog.kt` |
| 浮层 UI | `agent/overlay/AgentOverlayCapsule.kt`、`AgentOverlayContent.kt` |
| 浮层协调 | `agent/overlay/AgentOverlayClearance.kt`、`AgentOverlayVisibilityPolicy.kt`、`AgentOverlayState.kt` |
| 无障碍 | `agent/accessibility/AgentAccessibilityService.kt`、`MainThreadCallGate.kt`、`AgentAccessibilityKeeper.kt` |
| 存储/恢复 | `data/db/*`、`agent/runtime/AgentRunCheckpointStore.kt`、`AgentRunArchiveStore.kt` |
| 并发 | `agent/runtime/GuiControlCoordinator.kt` |
| 文档（交叉验证） | `docs/AGENT_RUNTIME.md`、`docs/TECHNICAL.md` |

### 8.3 本项目对照文件索引

| 主题 | 文件 |
| --- | --- |
| 顶部面板/跑马灯/完成态 | `app/src/main/java/com/phoneagent/overlay/FloatingWindowService.kt` |
| 跑马灯视图 | `app/src/main/java/com/phoneagent/overlay/MarqueeView.kt` |
| 边缘光窗口/视图 | `app/src/main/java/com/phoneagent/feature/edge/EdgeLightingService.kt`、`EdgeLightingView.kt` |
| 窗口参数约定 | `app/src/main/java/com/phoneagent/core/window/OverlayWindowSpec.kt` |
| 主循环 | `app/src/main/java/com/phoneagent/engine/loop/TaskRunner.kt`、`StepRunner.kt`、`ObservationPhase.kt`、`DecisionPhase.kt` |
| 观察/截图策略 | `app/src/main/java/com/phoneagent/engine/runtime/StepObserver.kt` |
| 遥测 | `app/src/main/java/com/phoneagent/engine/runtime/TelemetryRecorder.kt` |
| 模型调用/解析 | `app/src/main/java/com/phoneagent/core/ai/AiClient.kt`、`AgentResponseParser.kt` |
| 动效令牌 | `app/src/main/java/com/phoneagent/ui/theme/Motion.kt` |

### 8.4 未覆盖/不确定项

- Eta 的 Xposed 模块入口未在 Kotlin 主源码里找到显式 `IXposedHookLoadPackage` 实现（依赖 `libxposed api 102` 的加载约定与 `hook/` 包），未在真机上验证其保活效果。
- Eta 的"副屏（VirtualDisplay）"完整链路只读了个别文件（`VirtualDisplayStageCard`、`AgentRuntimeService` 的打开/取消），未逐行拆解；本报告只在需要处提及。
- Eta 的模型 Provider 兼容矩阵（哪些通道真正启用原生 tools）依赖运行时能力探测，未逐一实测。
- 本报告未运行 Eta，也未在本项目上执行改造；所有"收益"判断基于代码结构与链路推导，落地时需用 `TelemetryRecorder` 实测验证。
