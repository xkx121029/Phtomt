# Happy Phone Agent (Phtomt)

> 一个基于 AI 的 Android 智能体应用：用一句自然语言描述，让手机自己把事做完。

## 简介

**Happy Phone Agent**（应用名：Happy Agent）是一款运行在 Android 设备上的 AI 智能体 App。用户用自然语言描述一个目标（例如"帮我订一份外卖"、"把微信消息全部标为已读"），应用会自动规划步骤、观察当前页面、做出决策，并在授权范围内执行点击 / 滑动 / 输入，完成任务。

无障碍、内置 Shizuku server、Termux 三条能力通道全部在独立的 Provider APK 里，主应用经 AIDL 远程驱动，自身不碰系统权限代码。

## 核心特性

- 🤖 **AI 驱动决策** — 主模型决策 + 思考模型规划，支持流式输出与结构化意图
- 🔀 **三形态路由** — 每次输入先判定是「手机操控 / 编码委托 / 纯对话」，再决定走哪条执行体
- 👁️ **多源视觉** — 端侧 3B（GGUF + JNI）框选 + 云端视觉模型描述 + ML Kit 中文 OCR 兜底，按页面复杂度自动分流
- 🖱️ **AI 只描述意图** — 44 个意图交给端侧转译层，AI 不写命令、不选通道、不算坐标
- 🔒 **三档动作模式** — 保守 / 均衡 / 自由，与执行通道正交，连续越权即收尾
- 🧩 **独立权限 Provider** — 无障碍 + 内嵌 Shizuku server + 无线 ADB 引导 + Termux，随包安装、签名级 AIDL
- 🛡️ **安全防护** — 敏感页只读、手机号/身份证/银行卡脱敏、不可逆动作强制二次确认
- 🎯 **带验证执行** — 页面指纹前后比对，区分「操作已交付」与「操作真生效」
- ⚡ **端侧快速决策** — 弹窗 / 加载 / 广告等高频场景本地处理，不烧云端额度
- 🔁 **长任务不跑偏** — 任务记忆、检查点、熔断计数、任务模板复用
- 🪟 **悬浮窗 + 通知 + Widget + 磁贴** — 四套入口共同呈现任务状态
- 🧠 **记忆体系** — 用户画像、经验规则、异常经验、页面记忆（虚拟线框画面 + 控件热点）
- 💻 **设备内编码** — PRoot + Alpine + JDK + Gradle + Android SDK 一键安装，可在手机上直接 `assembleDebug`
- 🌐 **中文 / 英文双语提示词** — 59 个提示词区块按任务类型裁切装配
- 📱 **Material 3 Expressive** — Compose 实现，深色模式、四套色板、统一动效体系

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.4.20 |
| 构建 | AGP / Gradle Wrapper | 8.13.2 / 8.14.2 |
| UI | Jetpack Compose (BOM) | 2026.06.01 |
| 设计 | Material 3 Expressive | 1.4.0 |
| 图标 / 毛玻璃 | Lucide Compose / Haze | 1.1.0 / 1.4.0 |
| 架构 | MVVM + 状态流 | StateFlow / Coroutines |
| 依赖注入 | Koin | 4.0.0 |
| 网络 | OkHttp | 4.12.0 |
| 序列化 | kotlinx-serialization | 1.7.3 |
| 存储 | DataStore Preferences | 1.1.1 |
| 端侧视觉 | Qwen2.5-VL-3B（GGUF + JNI `visionbridge`） | 双通路 |
| 本地 OCR | ML Kit 中文文字识别 | 16.0.1 |
| 静态检查 | detekt（基线只减不增） | 1.23.8 |
| Java / SDK | JDK 17 · minSdk 26 · compileSdk 36 · targetSdk 36 | — |

> `:env` 组件的 targetSdk 固定 **28**：Android 10+ 的 W^X 只允许 targetSdk < 29 的应用 `execve` 私有目录下的程序，这是该组件独立成 APK 的唯一原因。

## 项目结构

> 顶层目录职责、模块依赖、版本号流向、训练器边界与产物落点，见 [docs/project-structure.md](docs/project-structure.md)。

```
happy_phone agent/
├── app/
│   └── src/main/
│       ├── java/com/phoneagent/
│       │   ├── PhoneAgentApp.kt            # Application 入口（Koin 初始化）
│       │   ├── core/                       # 跨层基础设施（零业务语义）
│       │   │   ├── AgentBudgets.kt         # 回注 AI 的字符预算（4000）唯一数值定义点
│       │   │   ├── ai/                     # AiClient / ModelCatalog / VisionRouting / ProviderPresets
│       │   │   ├── log/                    # AppLog 日志门面 + runCatchingLogged
│       │   │   ├── notify/                 # 任务进度 / 主动反馈 / 配对向导通知
│       │   │   ├── security/               # SensitivePageDetector + DataSanitizer + SecretCodec
│       │   │   ├── text/                   # HtmlToMarkdown / HumanTranslator / TimeFormats
│       │   │   └── window/OverlayWindowSpec.kt
│       │   ├── domain/                     # 纯领域模型与规则（无 Android 依赖）
│       │   │   ├── model/                  # AgentAction / AgentIntent / ScreenSnapshot / UiElement
│       │   │   │                           # AppPageIndex / AgentState / DebugModels / PhantomModels
│       │   │   └── rules/                  # EngineRules / ShellCommands / LocalDecisionEngine
│       │   │                               # PageTypeRules / PageActionLabels / SessionContext
│       │   ├── data/
│       │   │   ├── prefs/AppSettings.kt    # DataStore 全部配置项
│       │   │   ├── store/                  # MemoryStore / TaskStore / DebugRecordsStore
│       │   │   │                           # McpStore / AiMemoryDedupe / EvolvedRule
│       │   │   └── export/LogExporter.kt   # 日志与诊断报告导出
│       │   ├── device/                     # 设备能力接入（全部经 Provider 转发）
│       │   │   ├── a11y/                   # AgentAccessibilityService（远程门面）/ ActionExecutor
│       │   │   ├── perception/ForegroundProbe.kt   # UsageStats 前台应用交叉验证
│       │   │   ├── provider/ProviderManager.kt     # 安装引导 / 版本比对 / AIDL 绑定 / 自检
│       │   │   ├── screen/                 # ScreenCapture / ScreenSharingService / VirtualDisplayAgent
│       │   │   ├── shell/                  # ShizukuManager / ShizukuBootstrap / TermuxBridge / AdbStatus
│       │   │   └── vision/                 # OnDeviceVision / NativeVisionEngine / LocalDetector
│       │   ├── engine/                     # Agent 编排
│       │   │   ├── AgentEngine.kt          # ReAct 主循环骨架（约 3200 行）
│       │   │   ├── AgentPrompts.kt         # 提示词门面（正文在 prompt/ 下）
│       │   │   ├── TaskKindDetector.kt     # 任务类型唯一判定点（词表 + 计分）
│       │   │   ├── TaskRoute.kt            # 三形态路由决策
│       │   │   ├── TaskSession.kt / DeviceFacts.kt / MemoryBrief.kt / RuleScoper.kt
│       │   │   ├── AgentResponseParser.kt / DecisionModels.kt
│       │   │   ├── runtime/                # EngineStore / TaskLifecycle / UserGate / UiBridge
│       │   │   │                           # TelemetryRecorder / TaskFinalizer / EngineLimits / StepTexts
│       │   │   ├── planning/PlanningFlow.kt        # 歧义检测 / 澄清 / 计划生成 / 模板复用 / 批准
│       │   │   ├── decision/               # DecisionConfig / DecisionPolicy / VisionResultCache
│       │   │   ├── execution/              # IntentTranslator / IntentResolver / ExecutionCore
│       │   │   │                           # ClickRunner / VerifiedClickExecutor / ShellRules
│       │   │   │                           # ActionMode + ActionPolicy / AppNameResolver / CapabilityManager
│       │   │   ├── perception/             # PageAnnotator（语义 ID）/ ControlTreeBuilder / PageFingerprint
│       │   │   ├── context/ContextInjector.kt      # 记忆 / 规则 / 环境事实注入
│       │   │   ├── memory/PageMemoryRecorder.kt    # 页面记忆采集
│       │   │   ├── prompt/                 # PromptCatalog（59 区块）/ PromptBodies / PromptAssembler
│       │   │   ├── coding/CodingAgentBridge.kt     # vibecoding 委托桥
│       │   │   ├── connect/AiTools.kt              # 连接测试 / 模型列表 / 能力探测
│       │   │   ├── skill/SkillRunner.kt            # 技能 / 宏 / MCP 执行
│       │   │   └── network/CloudAgent.kt
│       │   ├── coding/                     # 端侧编码 agent
│       │   │   ├── agent/                  # AgentClient / AgentProcess / RuntimeBootstrap / Workspace
│       │   │   ├── device/                 # DeviceWorkspace / HostBridgeServer（含 env_* 桥方法）
│       │   │   ├── env/                    # CodingEnvClient / CodingEnvBootstrap / ProjectToolchainProbe
│       │   │   ├── skill/AcaSkillCatalog.kt# 随包 skill 落盘与扫描
│       │   │   ├── SharedFiles.kt          # 系统分享进来的文件 + 容器格式文本摘录
│       │   │   └── CodingAgentService.kt / EditorActivity.kt / PreviewActivity.kt
│       │   ├── feature/                    # 功能域
│       │   │   ├── browser/                # BrowserChannel / BrowserExecutor / BrowserGuard
│       │   │   │                           # HeadlessWebHost / script（导航·交互·读取）
│       │   │   ├── skill/                  # SkillCatalog / SkillRegistry / SkillExecutionGateway
│       │   │   │                           # SkillCompat / SkillParamInferencer / MacroStepExpander
│       │   │   ├── mcp/                    # McpClient / McpManager / McpMarketplace / McpRetryPolicy
│       │   │   ├── adskip/AdContentFilter.kt       # 运行时广告识别与剔除
│       │   │   ├── document/DocumentEngine.kt      # 文档落盘 + Agent 页内嵌预览
│       │   │   ├── assistant/              # 数字助理角色 + VoiceInteractionService + Assist 上下文
│       │   │   ├── entry/                  # TaskEntry（分享/深链入口）+ TaskControlReceiver
│       │   │   ├── notify/                 # NotificationListenerService + 通知流
│       │   │   ├── update/                 # 官网版本查询与安装
│       │   │   ├── edge/                   # EdgeLighting 曲面边缘光效
│       │   │   ├── task/TemplateMatcher.kt # 任务模板匹配
│       │   │   └── test/                   # TestEngine / TestPresets / RealScenes
│       │   ├── overlay/                    # FloatingWindowService / FloatingUi / MarqueeView
│       │   │                               # CursorOverlayService / PageMarkOverlayService
│       │   │                               # AgentTileService / AgentStatusWidgetProvider / SuccessMarkView
│       │   ├── ui/                         # 界面层（页面 = 入口 + 单一职责拆分文件）
│       │   │   ├── MainActivity.kt / MainTabs.kt / MainViewModel.kt
│       │   │   ├── home/ · chat/ · coding/ · agent/ · memory/ · settings/ · skill/
│       │   │   ├── debug/                  # 执行流 / 指标 / 对话 / 日志 四个视角
│       │   │   ├── browser/ · test/ · onboarding/ · voice/ · model/
│       │   │   ├── components/             # Components / Glass / Markdown / Haptic / Formatters
│       │   │   ├── theme/                  # Color / Type / Theme / Motion / MotionHelpers
│       │   │   └── icons/AppIcons.kt
│       │   └── di/AppModule.kt             # Koin 依赖注入
│       ├── assets/
│       │   ├── agent-host/                 # 内置 Node 运行时执行体（由 tool/bundle_host.mjs 生成，不入库）
│       │   └── skills/happyac-coding/      # 随包编码手册 skill（skill.json + prompt.md）
│       └── res/
├── provider/                       # 独立权限服务 APK（无障碍 + 内嵌 Shizuku + 无线 ADB 引导 + Termux）
├── provider-api/                   # 跨进程契约（AIDL IDeviceProvider + Parcelable）+ 两端共用事实层
├── env/                            # 编码环境组件 APK（targetSdk 28，PRoot + Alpine + 工具链）
├── env-api/                        # 编码环境跨进程契约（AIDL IEnvProvider / IEnvCallback）
├── third_party/shizuku/            # vendor 自 RikkaApps/Shizuku 的 9 个 Gradle 模块
├── config/
│   ├── build-modules.properties    # 可选构建模块开关（Android 默认启用，Vue/Git/GitHub 默认关闭）
│   ├── detekt/                     # detekt 规则 + 基线（只减不增）
│   └── provider-assets/            # Provider 源码指纹（构建时校验）
├── docs/build-modules.md           # 可选构建模块的配置、任务与兼容性校验说明
├── docs/release-pipeline.md        # 发版链路：CI 触发与断言、GitHub 产物、Gitee 附件补挂（手动）、Gitea 现状
├── promo/                          # 单文件 HTML 交互式 UI 复刻（app.html / index.html / ui-film.html）
├── tool/                           # 构建辅助脚本：bundle_host.mjs（同步编码 host）/ bundle_runtime.py（打包 Node 运行时）/ ICU 裁剪
├── website/                        # 官网（Vue3 SPA + Express 内容 API，见 website/API.md）
├── tests/                          # 提示词批量测试脚本（Node）
├── 杂项/                           # 设计 / 复盘文档
├── .github/workflows/              # android-ci（编译 + 测试 + detekt + lint）/ site-pages
├── build.gradle.kts · settings.gradle.kts（`version.properties` 由构建生成，不入库）
```

**代码规模**（以当前工作区为准）：`app` 主模块 **273** 个 Kotlin 源文件、约 **6.4 万**行；单元测试 **73** 个文件、**793** 个 `@Test`（含 debug 变体专有的日志转发测试）；`:provider` 29 个源文件；`:env` 4 个源文件。

另有 `app/src/debug` 一套**仅 debug 变体**的日志转发通道（`LogServerCore` / `LogTransferSession` / `UsbDebugWatcher`），把 logcat 与应用内日志经本机端口推给 `tool/hpa_log_receiver.py`，release 包里不存在这段代码。

## 快速开始

### 1. 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17（`compileOptions` 与 `kotlin.jvmTarget` 均为 17）
- Android SDK 36
- 一台 Android 8.0+ 手机
- **不需要**单独安装 Shizuku：内置 server 随 Provider APK 一起安装

### 2. 构建

```bash
git clone <repository-url>
cd happy_phone-agent

# 构建 Debug APK（版本号默认读 version.properties）
./gradlew :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

版本号由根 `build.gradle.kts` 统一计算，`:app` 与 `:provider` 读同一个值：`versionCode = buildNumber`、`versionName = "0.2.$buildNumber"`。取值优先级是 `-PbuildNumber=NN`（CI 用 `github.run_number` 注入）> `version.properties` 记录值 > git 提交数；只有真正产出 APK 的任务（`assemble` / `bundle` / `build`）才会自增并回写，跑 detekt / 单测不吃版本号。版本号只增不减，避免 `versionCode` 回退导致覆盖安装失败。

`version.properties` 不入库（见 `.gitignore`），首次构建时以 git 提交数为基线自动生成。

### 3. 改了子模块 / 首次克隆之后

两个子 APK 都是**构建产物**，`*.apk` 被全局忽略、不入库，克隆后需要先同步一次：

| 场景 | 必须执行 | 不同步的后果 |
|---|---|---|
| 首次克隆 | `./gradlew :app:syncProviderAssets :app:syncEnvAssets` | `assets/` 下只有 `version.txt`（APK 被 `*.apk` 全局忽略），包里没有组件，装到手机后通道全不可用 |
| 改了 `provider/` · `provider-api/` | `./gradlew :app:syncProviderAssets` | 构建**直接失败**：`mergeDebugAssets` / `mergeReleaseAssets` 会比对源码指纹（`config/provider-assets/*.sha256`） |
| 改了 `env/` · `env-api/` | `./gradlew :app:syncEnvAssets` | 不会失败，但打进包里的是旧组件（运行时能力落后） |

`env-api` 参与主应用编译，契约不一致会直接编译报错。

### 4. 安装与配置

1. 安装 APK 到手机
2. 首次打开按引导安装 **服务组件 Provider**（内含无障碍服务与 Shizuku server）
3. 授予权限：
   - **无障碍服务** — 读屏与执行动作（必需）
   - **悬浮窗** — 可选，关闭后任务照常执行，进度只在 App 内显示
   - **通知使用权** — 可选，用于读取验证码 / 消息提醒、清理通知
   - **屏幕录制** — MediaProjection 截图回退路径
   - **自启动 / 电池优化白名单** — 保活
4. 在 **设置 → AI 接入 → 云端模型** 配置端点与 Key，点「测试连接」验证
5. 在 **设置 → 设备接入 → 权限与执行通道** 跑一次「执行通道自检」：安装 → 无障碍开关 → AIDL 绑定 → 服务侧无障碍实例 → 元素树读取 → 截图读取 → Termux 三步，每步都给实际数值，全部通过后再执行任务

### 5. 质量闸门

```bash
./gradlew detekt                    # 静态检查（规则见 config/detekt/detekt.yml）
./gradlew :app:checkDetektBaseline  # 基线只减不增，已挂到 check
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:lintDebug
```

CI（[android-ci.yml](.github/workflows/android-ci.yml)）在 PR / push 到 main 时按 detekt → 基线守卫 → 单测 → 覆盖率 → lint → assembleDebug 的顺序跑完整条门禁。

### 6. 可选构建模块（按需启用）

默认 Android 构建之外，构建环境提供四类**可选**扩展模块，统一由 [config/build-modules.properties](config/build-modules.properties) 的开关控制。模块任务不挂到 `build` / `check` / `assemble` 上，启用或禁用都不改变默认构建的任务图与耗时。

```bash
./gradlew buildModules        # 查看四个模块的开关、前置条件与任务
./gradlew buildModulesCheck   # 只对已启用的模块做兼容性校验

./gradlew androidCheck androidPackage   # Android：环境校验 + Debug APK 时间戳归档
./gradlew vueCheck vueInstall vueBuild   # Vue：官网依赖 / 静态构建（vueDev 为长驻开发服务器）
./gradlew gitStatus gitInfo             # Git：工作区摘要 + 构建信息 JSON
./gradlew githubCheck githubLink        # GitHub：远程校验 / 关联（githubSync 默认只演练）
```

| 模块 | 开关 | 默认 | 主要任务 |
|---|---|---|---|
| Android 平台构建 | `hpa.modules.android.enabled` | 启用 | `androidCheck`、`androidPackage` |
| Vue 开发服务器 | `hpa.modules.vue.enabled` | 关闭 | `vueCheck`、`vueInstall`、`vueDev`、`vueBuild` |
| Git 工具链 | `hpa.modules.git.enabled` | 关闭 | `gitCheck`、`gitStatus`、`gitInfo` |
| GitHub 关联与同步 | `hpa.modules.github.enabled` | 关闭 | `githubCheck`、`githubLink`、`githubSync` |

禁用模块的任务仍可调用（打印提示后正常结束）；也可用 `-Phpa.modules.<模块>.enabled=true` 临时覆盖开关。完整配置项、失败处理与启用/禁用验证矩阵见 [docs/build-modules.md](docs/build-modules.md)。

## 在线 UI 演示

不想装设备先看界面，直接打开 [promo/app.html](promo/app.html) —— 一份**零依赖的单文件**交互式复刻：浏览器双击即开，图标与插画全为内联 SVG，不联网、不引任何 CDN。

- **整机外壳**：应用被装进 412×892 逻辑屏（434×914 机身 + 圆角 + 侧键 + 底部指示条），窗口变化只做整机等比缩放，不做响应式重排
- **页面覆盖**：主页、对话、编码、操控（计划审批 / 执行 / 完成）、记忆图谱与页面记忆下钻、设置全套二级页、技能与 MCP、内置浏览器、文档阅读、测试工作台、调试页，以及开屏引导、协助浮层、不可逆动作确认卡、语音录入浮层
- **主题与色板**：跟随系统深浅色，四套色板（玄青 / 赭石 / 黛蓝 / 绛紫）在「设置 → 主题」即时切换
- **与源码对齐**：设计令牌取自 `Color.kt` / `Theme.kt` / `Type.kt` / `Motion.kt`，页面结构与各 `ui/` 下的 Compose 源码一一对应

同目录下 `promo/index.html`、`promo/ui-film.html` 为宣传片分镜页面（演示视频不入库）。

## 核心模块详解

### 三形态路由 — TaskKindDetector + TaskRoute

每次输入进执行体之前先判定一次形态，判定唯一走 [TaskKindDetector](app/src/main/java/com/phoneagent/engine/TaskKindDetector.kt)：

| 形态 | 判据 | 执行体 |
|---|---|---|
| **Coding** | 编码词命中数 ≥ 对话词与操作词 | 委托设备端编码 agent，不进 ReAct 感知循环 |
| **Chat** | 只命中对话词，且 doc / open / browse / fetch / assistant 全不命中 | 主模型直答一次（`CHAT_TEMPERATURE = 0.3`），不注入操控提示词 |
| **Agent** | 其余情况 | 原 ReAct 主循环 |

同一个判定器还决定提示词怎么裁块（`flagsOf`），所以「按网页任务裁提示词」与「按普通任务注入附加指导」用的是同一份口径，不会各说一套。多需求并存（如"写个脚本顺便发我微信"）按命中词数取最高置信度者，并在时间线提示其余需求可再下一单。

### 意图转译层 — AI 只说做什么

44 个合法意图（[IntentType](app/src/main/java/com/phoneagent/domain/model/AgentIntent.kt#L133-L205)）经 [IntentTranslator](app/src/main/java/com/phoneagent/engine/execution/IntentTranslator.kt) 的策略表落到 34 种 `ActionType`。AI **不输出命令、不输出像素坐标、不选通道**。

转译有三种结果：

| 结果 | 含义 | 引擎处置 |
|---|---|---|
| `Command` | 得到一条可执行动作 | 走执行层 |
| `Failed` | 页面条件不满足 / 参数非法 | 原因回注 AI 换路子 |
| `MissingParam` | 缺必填字段（如 tap 没 target） | 端侧先向 AI 追问补全，再重转译 |

6 个 `browse_*` 意图和 `see` 不在这张表里：前者由内置浏览器用 DOM 脚本直接落地，后者由引擎就地调视觉模型，都不产生 `ActionType`。这两类登记在 `IntentType.VIA_OTHER_CHANNELS`，与 `ALL` 的补集由单测强制闭合。

### 动作模式 — 三档授权

[ActionMode](app/src/main/java/com/phoneagent/engine/execution/ActionMode.kt) 与通道模式（`CapabilityManager.Mode`）**正交**：

- 通道模式回答「能不能执行」（设备有没有权限）
- 动作模式回答「允不允许 AI 提出这类请求」（用户给了多大授权）

| 档位 | 放行范围 |
|---|---|
| 保守 CONSERVATIVE | 23 项低风险意图（只读 / 导航 / 滚动 / 本地读写 / 只读浏览） |
| 均衡 BALANCED（默认） | 前置 + 16 项中风险 + 3 项高风险，即转译层全部意图 |
| 自由 FREE | 前置 + `shell`（AI 自写命令）+ `a11y`（18 个无障碍端点直调，AI 自写命令首次执行前需用户确认） |

门控点只有一处：主循环里技能归一化之后、浏览器分流之前。这个位置是刻意的——`browse_*` 不走转译层，只有卡在这里才能一并覆盖。连续 3 次被拒即收尾，并提示用户去放宽档位。

### ReAct 主循环

```
观察 → 广告过滤 → 敏感页判定 → 页面记忆 → 宏接力 → 端侧/云端决策
     → 技能归一化 → 动作模式门控 → 浏览器分流 → 意图转译 → 带验证执行 → 留痕
```

几个关键设计：

- **端侧优先**：`LocalDecisionEngine` 先处理弹窗、加载、广告这类高频页面；连续 5 次端侧决策强制走一次云端复核
- **决策异常不计步**：云端抖动时 `step--` 后重试，避免网络问题把步数预算烧光；连续 5 次异常才收尾
- **提前完成拦截**：有计划时门槛是规划步数的 60%，无计划时是 3 步；但页面已出现明确完成证据（复用幂等判定）时直接放行
- **不加"不确定就完成"**：系统提示词要求卡住时输出 `give_up`，而不是含糊收尾
- **中途掉线检测**：无障碍服务在任务中途消失（厂商安全守护清权限）立即暂停并引导重新授权，不进入盲循环

### Provider — 独立权限服务

[ProviderManager](app/src/main/java/com/phoneagent/device/provider/ProviderManager.kt) 是主应用侧门户，负责三件事：安装引导与版本比对、`bindService` 进 `IDeviceProvider`（3 秒超时 + 断连自动重绑）、带超时兜底的远程调用。

几个硬处理：

- **只有通道真死了才清代理**：`DeadObjectException` / `RemoteException` 才作废，`TransactionTooLargeException` 属于业务失败（回包过大），保留连接
- **作废即真正解绑**：只清引用不解绑的话，系统认为连接仍活动，下次 `bindService` 不再回调，代理再也拿不回来——这正是「显示已连接但命令全发不出去」的成因
- **大块数据分片**：截图与元素树走 `beginPayload` / `readPayload` / `endPayload`，单片 128KB，避开 Binder 约 1MB 的事务上限
- **执行通道自检**：把「装没装 → 开没开 → 绑没绑 → 树能不能读 → 图能不能截 → Termux 三步」拆成可观察的步骤，每步给实际数值（版本号 / apiVersion / 字节数 / 元素个数）

### 执行通道 — 三条，一个判据

[ShellRules.pickChannel](app/src/main/java/com/phoneagent/engine/execution/ShellRules.kt) 是通道选择的唯一判据：

```
AUTO：Shizuku（binder）→ ADB 引导会话 → Termux → NONE
显式 SHIZUKU / TERMUX：只认自己那条，不可用即 NONE
```

| 通道 | 权限级别 | 能做什么 |
|---|---|---|
| 内置 Shizuku | shell（adb） | `am` / `pm` / `settings` / `input` 等系统命令 |
| ADB 引导会话 | shell（adb） | 同上；server 拉起前后的过渡态，对用户统一呈现为 Shizuku |
| Termux | **普通应用权限** | curl / python / jq / 文本处理，**不能**执行系统命令 |

Termux 的 `RUN_COMMAND` 权限、结果 PendingIntent 回传、`allow-external-apps` 判定全部在 Provider 内完成；主应用侧 [TermuxBridge](app/src/main/java/com/phoneagent/device/shell/TermuxBridge.kt) 只转发 AIDL 并暴露缓存状态。

### ShellCommands — AI 友好命令

自由模式下 AI 自写命令时不必记忆 ADB 语法。坐标支持三种写法：百分比（`50%`）、比例（`0.5`，严格小于 1 才视为比例）、像素（`500`）。

| 命令 | 参数 | 说明 |
|---|---|---|
| `tap` | `x y` | 点击 |
| `lp` | `x y` | 长按 1500ms |
| `dt` | `x y` | 双击 |
| `sw` | `x1 y1 x2 y2` | 滑动 |
| `su` / `sd` / `sl` / `sr` | `x y` | 方向滑动屏幕的 25%（至少 100px） |
| `key` | `BACK/HOME/ENTER/RECENTS/MENU/VOLUME_UP/.../数字` | 按键 |
| `back` / `home` / `recents` | — | 系统键 |
| `text` | `"文字"` | 输入文字 |
| `am` | 包名 / 完整组件 | 裸包名自动补成启动语义 |
| `stop` | 包名 | 强制停止应用 |
| `launch` | 包名 | 启动应用 |
| `dump` | 包名 | 列出 Activity |
| `info` | — | 当前前台 Activity |
| `window_size` / `battery` / `date` | — | 设备信息 |
| `brightness` | `0~255` | 屏幕亮度 |
| `screenshot` | — | 截图到 `/sdcard` |
| `wifi_on` / `wifi_off` | — | WiFi 开关 |
| `raw` | 完整 ADB 命令 | 直接透传（兜底） |

### 视觉链路 — 三源分流

[VisionRouting](app/src/main/java/com/phoneagent/core/ai/VisionRouting.kt) 把「用哪一路视觉」做成纯函数，可单测：

| 来源 | 触发条件 | 产物 |
|---|---|---|
| 端侧 3B（GGUF + JNI） | 已下载、加载成功、且（未开混合路由或页面简单） | 控件类型 + 用途 + 归一化坐标 |
| 云端视觉模型 | 混合路由判为复杂页，或端侧不可用 | 截图文字描述 |
| ML Kit 中文 OCR | 前两者都没产出 | 文字区域聚类成控件 |

「页面复杂度」的判据是**元素树稀疏度**：`elements.size ≤ 3` 视为复杂页（无障碍读不到控件，需强语义理解），走云端；否则走端侧 3B。

配套硬约束：

- 主模型能不能直接读图走三态（`AUTO` 听模型库真实探测 / `ON` / `OFF` 强制覆盖），探测不出就按不发图处理
- 端侧 3B 原生层自带 120 秒推理看门狗，超时返回 `__ERR_TIMEOUT__` 由 Kotlin 侧回退 OCR
- 视觉链路整体有 25 秒外层看门狗，超时放弃视觉描述、只用元素树继续决策
- 页面指纹 + 截图尺寸 + 任务 ID 命中时复用上一步视觉结果，整段跳过三路调用

### 语义标注 — 让 AI 用 id 而不是坐标

[PageAnnotator](app/src/main/java/com/phoneagent/engine/perception/PageAnnotator.kt) 在每次观察后为 26 类常见控件分配稳定语义 id，注入 AI 上下文后可以直接 `target={by:"id",value:"send_btn"}`：

```
dlg_allow / dlg_dismiss / ad_skip / back_btn / close_btn / search_box / search_btn / refresh_btn
send_btn / confirm_btn / checkout_btn / delete_btn / switch_toggle / select_box / more_btn / add_btn
next_btn / login_btn / share_btn / collect_btn / copy_btn / download_btn / upload_btn
settings_btn / sort_btn / clear_input
```

两条实现约束：元素特征一次性预计算（原实现按 26 个类别各扫一遍全量元素，长列表页会重复生成数千字符串）；`dlg_dismiss` 只在确认是真弹窗（存在正向按钮且元素 ≤ 8）时才标定，避免把普通页的「取消」误判成关闭弹窗。

### 提示词 — 59 个区块按任务裁切

[PromptCatalog](app/src/main/java/com/phoneagent/engine/prompt/PromptCatalog.kt) 是全项目提示词正文的唯一清单，每个区块带装配条件（`requires` / `excludes`），由 `PromptAssembler` 排序、拼接、整组一次性渲染占位符。

- **永不裁的安全块**：铁律、授权范围、禁止输出、能力声明（`safe = true`）
- **按任务裁的两个大块**：非网页任务省掉「网页浏览」，非打开任务省掉「打开链接与文件」
- **引导块**：语音助手、编码、对话三类只在明确命中时注入，未知任务不注入也不误导
- 数组拼接时 `body + sep` 必须精确复现原文换行——`PromptSnapshotTest` 用金样本锁定，改 `sep` 就等于改提示词

### 记忆体系

| 类型 | 存储 | 用途 |
|---|---|---|
| 用户画像 | `MemoryStore` | 偏好、习惯，跨任务生效 |
| AI 记忆 | `MemoryStore`（去重合并） | 任务结束由模型提炼的长期事实 |
| 经验规则 | `EvolvedRule` | 带作用域的规则，按前台应用计数推断适用范围 |
| 异常经验 | `AnomalyMemoryEngine` | 同类页面异常的历史处置，命中即复用 |
| 任务记忆 | `MemoryStore`（`task_memory` 键） | 目标 / 需求 / 已验证方法 / 状态 / 步骤，容量 30 条 |
| 页面记忆 | `MemoryStore` | 页面控件空间树 + 热点，绘制纯线框虚拟手机画面 |

页面记忆会在屏幕上用淡色圆环标记已记忆控件（不挡触摸），并在决策提示里注入「这个页面可以从哪里去哪里」。

### 编码环境 — 在手机里 build APK

`:env` 是独立 APK（targetSdk 28），承载 PRoot + Alpine 根文件系统；主应用经 `IEnvProvider` 驱动。整个链路由 [CodingEnvBootstrap](app/src/main/java/com/phoneagent/coding/env/CodingEnvBootstrap.kt) 固化成可重复执行的流程：

```
探测 → 装组件 → 全文件访问 → 组件存储授权 → Linux 根 → JDK 17 → Gradle → cmdline-tools
     → platform / build-tools → aapt2 兼容层 → assembleDebug
```

每次调用只推进到**第一个需要用户动手的环节**并拉起对应系统页面，返回 `needsUserAction`；用户办完重跑即可，已完成的步骤不会重复执行。

两处硬约束在代码里显式处理：环境组件是另一个 uid，读不到主应用 filesDir，私有工作区会先镜像到 `/storage/emulated/0/happyac-build/<工程名>`；aapt2 只有 x86_64 版，arm64 设备上由 `qemu-x86_64` + glibc 转译执行。

工程版本不需要手填：优先读 `gradle-wrapper.properties` 的 `distributionUrl`，没有 wrapper 时按 AGP 版本查官方配套表（8.0–9.4 全覆盖），`compileSdk` / `buildToolsVersion` 从模块脚本解析。

### 浏览器通道 — browse_*

6 个浏览意图由内置 WebView 用 DOM 脚本落地，**与设备能力无关**（不依赖无障碍 / Shizuku / Termux），因此 AI 能"亲眼看到"网页内容。

- 正文回传为 Markdown，链接内联为 `[文字](网址)`
- 抓回来的 HTML 先嗅探转换再截断——顺序反过来的话，4000 字符常常停在 `<head>` 中段
- 元素点击优先按文字定位，无文字时才用 CSS 选择器
- 回注预算与 shell 输出统一走 `AgentBudgets.REINJECT_MAX_CHARS`（4000）

### 编码 Agent — 设备内 vibecoding

编码任务由内置 Node 宿主（`assets/agent-host`，随包）执行，与操控链路**彻底隔离**：独立会话 ID、独立工作区、独立状态目录（`<工作区>/.happyac/agent`，切工作区即切一整套记忆）。

- **工作区**：由 `CodingWorkspaces` 的登记表决定（默认 `filesDir/workspaces/default`，也可指向共享存储）；会话 / 记忆 / 用量 / 模型档位全部跟着工作区走
- **宿主桥**：`HostBridgeServer` 给 Node 侧暴露工作区文件、编辑器、预览，以及 `env_status` / `env_setup` / `env_build` / `env_cancel` 四个环境桥方法
- **CA 对齐**：Node 自带 Mozilla CA 缺部分国内网关根证书，启动时把系统 CA 拼成 PEM 传给 `NODE_EXTRA_CA_CERTS`（叠加而非替换），让 Node 与 OkHttp 信任面一致
- **随包技能**：`assets/skills/<name>` 在首次使用时按 `skill.json` 的 version 落盘到 `filesDir/skills`，版本没变就跳过（不覆盖用户改动）
- **分享进来的文件**：docx / xlsx / pptx / zip 会额外解析出纯文本摘录（同目录 `<名>.text.txt`），否则 `read_file` 读到的是二进制

### 技能与 MCP

- **内置技能**：`SkillCatalog` 把每个意图登记成带参数描述的技能，`legacyIntent` 指回原意图名，`SkillCompat.normalize` 保证「AI 叫旧意图名 / 技能 id / 技能中文名」都能落到同一条转译链路
- **宏技能**：多步技能由端侧接力派发，派发前整宏预检（步骤存在且启用、意图类步骤过当前动作模式门控），任一步被拒则整宏不启动；运行期任一步失败即停并交还 AI
- **MCP 技能**：远程 JSON-RPC 调用，单次 20 秒超时、输出截断 1200 字符、连续 3 次拒绝即收尾；市场条目只作快捷填充（公共 REST 站点不是 MCP 服务，需自建网关）
- **缺参自动推断**：因缺必填参数被拒时，端侧先从历史成功参数 / 上一步结果 / 任务原文 / 页面文本推断补全，免去一轮向 AI 追问

### 安全防护

| 机制 | 实现 |
|---|---|
| 敏感页只读 | `SensitivePageDetector` 识别支付 / 密码 / 验证码等关键词；连续 5 次接管仍敏感即收尾 |
| 数据脱敏 | 18 位身份证 → 16~19 位银行卡 → 11 位手机号，按长度降序掩码；少于 11 位数字走快速通道直接返回 |
| 不可逆动作确认 | `needsUserConfirmation` 的动作执行前必问，120 秒超时视为取消 |
| 自由模式命令确认 | AI 自写命令在本任务首次执行前确认一次，拒绝后回注「别重试这条」 |
| 幂等保护 | 页面已含完成证据时跳过重复副作用（提交 / 发送 / 下单 / 支付 / 删除） |
| 密钥存储 | `SecretCodec` 加密后再落 DataStore |

## 配置说明

设置页按用途分三层：设置主页 → 分类详情 → 二级页。分类为 AI 接入（云端模型 / 端侧视觉）、编码 Agent（含编码环境）、运行行为（提示词与长线任务）、设备接入（权限与执行通道）、外观（主题 / 界面效果 / 底部选项卡）、系统（数据与存储 / 软件更新 / 关于与帮助）。

### 模型配置

| 模型职责 | 用途 | 默认 | 必需 |
|---|---|---|---|
| 主模型 | 每步决策、意图理解、对话直答 | `glm-4.7-flash` | ✅ |
| 视觉模型 | 截图描述、坐标定位 | `glm-4.6v-flash` | 可选 |
| 思考模型 | 规划、歧义检测、重规划 | `glm-4.7-flash` | 可选 |

端点（Base URL + Key）与模型（能力徽章来自真实请求探测）分离管理，「职责分配」把模型绑到主 / 视觉 / 思考三个槽位。链路聚合默认关闭，开启后思考模型参与复杂规划。配置不完整时任务**不会启动**，直接给出「去配置」而不是跑到链路异常才报错。

### 其他关键开关

| 设置项 | 默认 | 说明 |
|---|---|---|
| `executionChannel` | AUTO | AUTO / SHIZUKU / TERMUX；历史 `ADB` 值读取时归一为 AUTO |
| `actionMode` | BALANCED | CONSERVATIVE / BALANCED / FREE |
| `attachScreenshot` | 开 | 每步附送截图给模型 |
| `enableExternalVision` | 开 | 启用端侧 3B |
| `smartVisionRoute` | 开 | 混合路由：简单任务走端侧，复杂任务走云端 |
| `pageMemoryEnabled` | 开 | 页面记忆 + 屏幕热点标记 |
| `floatingWindowEnabled` | 开 | 悬浮窗（关闭不影响任务执行） |
| `enableReview` | 开 | 独立审核者 AI 复核每步动作是否基于页面证据 |
| `virtualDisplayEnabled` | 关 | 副屏模式：任务在模拟显示上运行，不抢用户前台（需 Shizuku） |
| `promptLanguage` | CN | 提示词语言（中文 / 英文） |

## 入口一览

| 入口 | 实现 |
|---|---|
| 应用主界面 | 六个可配置 Tab：主页 / 对话 / 编码 / 操控 / 记忆 / 设置（顺序与可见性可调，主页与设置不可隐藏） |
| 桌面悬浮窗 | `FloatingWindowService` — 计划批准、歧义澄清、手动指导、任务停止 |
| 系统通知 | `TaskProgressNotifier` 任务进度 + `ActiveNotifier` 主动反馈（卡顿 / 死循环 / 模板失效等） |
| 桌面 Widget | `AgentStatusWidgetProvider` — 任务名 + 状态 + 步数 |
| 快捷设置磁贴 | `AgentTileService` — 一键唤起 / 收起悬浮窗与任务 |
| App Shortcuts | 静态快捷方式（新建任务 / 继续上次 / 打开记忆）+ Google App Actions `GET_THING` 语音直达 |
| 分享目标 | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` / `ACTION_PROCESS_TEXT` / `ACTION_VIEW`（"用其他应用打开"也认） |
| 系统数字助理 | `HpaVoiceInteractionService` — 长按电源键 / 边缘手势唤起，带屏带电回到 Agent 页 |
| 语音输入 | 系统 `SpeechRecognizer` 或配置的 OpenAI 兼容转写端点 |
| 外部广播 | `TaskControlReceiver` — 供 Tasker / MacroDroid 等下发任务（默认关闭，需口令校验） |

## 版本与更新

版本号规则见「快速开始 → 构建」。完整变更记录在 [CHANGELOG.md](CHANGELOG.md)（遵循 Keep a Changelog + 语义化版本）。

自动更新走官网接口 `GET {baseUrl}/api/update/check?channel=<stable|beta|dev|nightly>`，默认站点 `https://hpa.linshuxu.us.kg`；下载安装优先经内置 Shizuku 静默装，不可用时退回系统安装器。

## 杂项文档索引

项目根目录 `杂项/` 存放全部设计 / 复盘文档：

| 文档 | 内容 |
|------|------|
| [项目完整流程说明.md](杂项/项目完整流程说明.md) | 全局架构总览：感知 / 决策 / 执行 / 视觉 / 持久化 / 展示六层 + 提示词体系 |
| [HPAv2.0计划文档.md](杂项/HPAv2.0计划文档.md) | 提示词与所需代码功能的分层对应表 |
| [HPA中长线任务优化及agent逻辑优化文档.md](杂项/HPA中长线任务优化及agent逻辑优化文档.md) | 长线任务进度记忆、检查点、防死循环、模板机制设计 |
| [HPA动作执行逻辑优化文档.md](杂项/HPA动作执行逻辑优化文档.md) | 意图 DSL（intent / target{by,value}）与执行层转译规范 |
| [IntentTranslator转译层原理.md](杂项/IntentTranslator转译层原理.md) | 意图 → 动作矩阵转译、通道选择 |
| [IntentTranslator_Bug分析.md](杂项/IntentTranslator_Bug分析.md) | 转译层历史 Bug 复盘与根因分析 |
| [HPA项目提示词文档.md](杂项/HPA项目提示词文档.md) / [HPA提示词文档.md](杂项/HPA提示词文档.md) / [HPA提示词系统文档.md](杂项/HPA提示词系统文档.md) | AI 系统 / 规划 / 决策提示词与参数规范 |
| [HPA项目AI提示词全集.md](杂项/HPA项目AI提示词全集.md) | 全部提示词汇总（中文 / 英文双版本） |
| [HPA项目ai温度文档.md](杂项/HPA项目ai温度文档.md) | 各提示词 temperature 取值依据 |
| [PHA调试页原始+翻译双语信息展示设计文档.md](杂项/PHA调试页原始%20+%20翻译双语信息展示设计文档.md) | 调试页人话 / 原始双语展示、诊断导出、主动通知设计 |
| [HPS ai标准化测试.md](杂项/HPS%20ai标准化测试.md) / [HPS-agnes测试结果.md](杂项/HPS-agnes测试结果.md) | 提示词标准化测试方案与实测结果 |
| [HPA系统级集成可发展项目清单.md](杂项/HPA系统级集成可发展项目清单.md) | 14 项系统级集成点的调研与落地状态 |

`.trae/documents/` 下另有按主题的设计方案（Provider 通道、页面记忆、动作模式、编码环境、宽屏布局等）。

## 贡献指南

1. Fork 本仓库
2. 创建特性分支（`git checkout -b feature/xxx`）
3. 提交（提交信息遵循 Conventional Commits）
4. 推送并发起 Pull Request

### 代码规范

- 遵循 [Kotlin 官方编码规范](https://developer.android.com/kotlin/style-guide)，4 空格缩进
- 分层依赖方向单向：`core` / `domain` 是最底层，不得反向依赖 `engine` / `device` / `ui`；`engine` 内部按 `runtime / planning / decision / execution / perception / context` 子包拆分，主类只留主循环骨架
- 新增意图必须同时登记 `IntentType.ALL`、转译策略表（或 `VIA_OTHER_CHANNELS`）、动作模式风险档——三处不一致单测会直接红
- 新增提示词区块只在 `PromptCatalog` 里登记，正文放 `PromptBodies`
- UI 变更遵循 Material 3 Expressive
- 不留死代码：README 与注释提到但代码里已不存在的类名，视为 bug

## 许可协议

本项目基于 [MIT License](LICENSE) 开源。

## 致谢

- Material Design 设计团队 / Jetpack Compose 社区 / Google ML Kit
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 权限通道的底层实现（vendor 至 `third_party/shizuku`）
- [Coda](https://github.com/happy-everyday-everyweek/coda) — 安卓端 Agent 工具，其工程组织与运行时方案提供了参考（AGPL-3.0）
- [MobileGym](https://github.com/Purewhiter/mobilegym) — 可验证、高并行的移动 GUI Agent 研究平台，为任务设计与验证思路提供参考（Apache-2.0）
- [Aether（扶摇）](https://github.com/Zhou-Shilin/Aether) — 进行中状态回显（过程弱化、结果成卡、微光扫过文字）借鉴其层次处理手法（GPL-3.0）
- 所有开源贡献者
