# 更新日志

本文件记录 Happy Phone Agent (Phtomt) 的全部 noteworthy 变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 新增
- 占位：下一版本变更写在这里

---

## [v0.1.264] — 2026-09-10

对应提交 `c01b589`。

### 新增
- **ADB 无线配对通知栏向导** `notify/AdbPairingNotifier.kt`（121 行）
  - 搜索中 → 已发现设备 → 请求配对码（通知内联输入框）→ 配对中 → 成功/失败，全程通知栏驱动
  - `RemoteInput` 内联输入：用户无需回到 App 即可输入 6 位配对码
  - `POST_NOTIFICATIONS` 权限适配（Android 13+）
- **AdbPairingReceiver** `shizuku/adb/AdbPairingReceiver.kt`（22 行）
  - 接收通知内联配对码，委托给 `WirelessAdbPairingFlow.onPairingCode()`
- **WirelessAdbPairingFlow** `shizuku/adb/WirelessAdbPairingFlow.kt`（118 行）
  - Nsd 服务发现 → 配对码校验 → 网络配对 → Shizuku 拉起，完整状态机编排
- `AndroidManifest`：注册 `AdbPairingReceiver`，新增 `POST_NOTIFICATIONS` 权限

### 增强
- `AdbWirelessTransport`：`discoverService()` 改为公开方法
- `WirelessAdbModels`：`AdbPhase` 枚举扩展
- `MainViewModel`：接入配对流状态
- `SkillManagerScreen`：无线 ADB Tab 交互优化
- `AppModule`：Koin 注册新模块

---

## [v0.1.140] — 2026-09-02

对应提交 `c132122`。

### 新增

- **Skill 技能系统**（`app/src/main/java/com/phoneagent/skill/`）
  - `SkillCatalog`：内置技能库（系统级能力 + 实用脚本）
  - `SkillRegistry`：技能注册 / 查询 / 启用禁用管理
  - `SkillCompat`：旧 `legacyIntent` → 新 Skill 的兼容桥接
  - `SkillExecutionGateway`：统一执行入口（内部 intent / MCP 工具分发）
  - `SkillModels`：`Skill` / `SkillParam` / `SkillSource` / `SkillCategory` 数据模型
- **MCP 协议客户端**（`app/src/main/java/com/phoneagent/mcp/`）
  - `McpManager`：服务器配置 / 健康检查 / 绑定工具为 Skill
  - `McpClient`：通用 MCP 协议实现（initialize / tools/list / tools/call）
  - `OkHttpMcpTransportFactory`：HTTP + SSE 双模式传输
  - `McpModels`：MCP 协议数据模型
- **无线 ADB 配对**（`app/src/main/java/com/phoneagent/shizuku/adb/`）
  - `AdbWirelessTransport`：6 位配对码配对、无线调试连接
  - `WirelessAdbStateMachine`：状态机驱动（UNPAIRED → PAIRING → BOOTING → READY）
  - `ShizukuBootstrap`：配对成功后自动拉起 Shizuku
  - `AdbBootstrapTransport`：回退到 Shizuku provider 启动
  - `WirelessAdbModels`：阶段 / 错误 / 状态数据类
- **提示词模板引擎**（`app/src/main/java/com/phoneagent/prompt/`）
  - `PromptTemplate`：支持 `{task}{skills}{mcpTools}{controls}{currentApp}{lastResult}{goal}{auditRejection}{situational}` 占位符
  - `PromptTemplateStore`：DataStore 持久化，内置 + 自定义混合管理
- **控件树感知**（`app/src/main/java/com/phoneagent/perception/ControlTreeBuilder.kt` + `model/ControlNode.kt`）
  - 将无障碍节点压缩为结构化控件树供 LLM 消费
- **UI**（`app/src/main/java/com/phoneagent/ui/skill/SkillManagerScreen.kt`，651 行）
  - 技能 / MCP / 无线 ADB / 提示词 四段式 Tab 管理页
  - 技能卡片列表 + 批量选择 + 导出（JSON 到剪贴板）+ 导入
  - MCP 服务器增删改查 + 有效性测试 + 一键绑定为 Skill
  - 无线 ADB 配对引导（配对码输入 / 状态提示 / 错误恢复）
  - 提示词模板编辑 + 占位符说明

### 增强

- `MainViewModel`：新增 skills / mcp / adb / template 相关 StateFlow 与操作方法（+133 行）
- `IntentTranslator`：适配 Skill 系统，支持从 Skill 到 Intent 的扩展转译（+125 行）
- `AgentIntent` / `UiElement`：模型字段扩展
- `AppModule`：Koin 依赖注入更新，接入 SkillRegistry / McpManager / PromptTemplateStore

### 修复

- `SkillManagerScreen`：`selected` 状态改用 `mutableStateOf(mutableSetOf())` 加委托，确保 UI 正确重组
- `SkillManagerScreen`：`LocalContext.current` 在 `onClick` lambda 中非法调用 → 提升到 Composable 顶层一次性读取
- `HomeScreen`：补充 `Icons.Rounded.Devices` 图标导入

### 测试

- 新增 12 个单元测试：
  - `skill/SkillRegistryTest` / `SkillCompatTest` / `SkillExecutionGatewayTest`
  - `mcp/McpClientTest`
  - `shizuku/adb/ShizukuBootstrapTest`
  - `prompt/PromptTemplateEngineTest`
  - `perception/ControlTreeBuilderTest`
  - `execution/LongRunModelTest` / `RealModelDecisionTest` / `SimulatedTaskRunTest`
  - `execution/IntentTranslatorTest` / `IntentTranslatorStrategyTest`

---

## [v0.1.138] — 2026-09-01

对应提交 `f6caefa`。

### 新增

- **统一截图入口** `app/src/main/java/com/phoneagent/screen/ScreenCapture.kt`
  - 优先使用无障碍服务 `takeScreenshotBitmap()`（API 30+，无需 MediaProjection 前台服务）
  - 回退到 `ScreenSharingService`（MediaProjection 路径，兼容 API < 30）
  - 截图前自动隐藏悬浮窗，结束后恢复，避免污染画面
- **调试记录持久化** `app/src/main/java/com/phoneagent/debug/DebugRecordsStore.kt`
  - 日志 / 步骤轨迹 / 执行历史 / 对话写入 app 内部目录 `hpa_debug_hist/`
  - AgentEngine 启动时回载，重启不丢
  - 截图缩略图统一压缩到 360px 宽，降低内存与磁盘占用
- 单元测试：`ShellCommandsTest`（282 行）、`IntentTranslatorStrategyTest`（124 行）

### 重构

- `IntentTranslator`：`internal fun interface IntentTranslationStrategy` 改为 `private fun interface`
  - 解决 `TranslationContext`（文件内类型）被 `internal` 接口方法签名暴露导致的 Kotlin 编译错误
  - 对外暴露的仅保留 `class IntentTranslator` 本身，策略实现类变为文件内私有细节
- `AgentEngine`：接入 `ScreenCapture` 与 `DebugRecordsStore`
- `EngineRules`：规则引擎硬约束增强（+24 行）
- `AgentAccessibilityService`：截图能力扩展（+54 行）

---

## [v0.1.137] — 2026-08-31

对应提交 `fc567c2`。

### 新增

- **IntentTranslator 转译层** `app/src/main/java/com/phoneagent/execution/IntentTranslator.kt`
  - 将 AI 决策 `AgentIntent` → 端侧执行指令 `AgentAction` 的独立转译层
  - 策略模式：`OpenAppStrategy` / `TargetLocationStrategy` / `ScrollToStrategy`，每种意图一颗策略
  - `TranslationContext`：通道模式 / 页面快照 / 视觉坐标 hint / 公共动作基线
  - 新增意图只需实现 `IntentTranslationStrategy` 并注册进分派表
- **IntentResolver** `execution/IntentResolver.kt`：目标控件定位分发路由
- **AppNameResolver** `execution/AppNameResolver.kt`：App 名称 → 包名解析
- **CapabilityManager** `execution/CapabilityManager.kt`：通道能力探测（无障碍 / Shizuku / MediaProjection）
- **EngineRules** `agent/EngineRules.kt`：硬约束规则引擎（拦截危险动作 / 只读模式横切约束）
- **FloatingUi** `floating/FloatingUi.kt`：悬浮窗 UI 独立组件，从 `FloatingWindowService` 解耦
- **TemplateMatcher** `task/TemplateMatcher.kt`：长线任务模板匹配引擎
- **AgentIntent** `model/AgentIntent.kt`：标准化意图数据模型

### 重构

- `AgentEngine`：±673 行大重构，职责拆分，接入转译层 + 规则引擎
- `AgentPrompts`：提示词大更新
- `FloatingWindowService`：+307 行，悬浮窗增强
- `AiClient` / `ActionExecutor` / `LocalDecisionEngine` / `CloudAgent`：适配新架构

### 基础设施

- GitHub Actions CI：`.github/workflows/android-ci.yml`
- 6 个单元测试类初始入库

---

## [v0.1.136] — 2026-08-22

对应提交 `6248dd1`。

### 新增

- **长线任务系统** `app/src/main/java/com/phoneagent/task/TaskStore.kt`
  - 检查点持久化（DataStore）+ 模板库 + 执行策略
  - 中断恢复 / 任务分支 / 策略切换
- **AI 日志翻译** `app/src/main/java/com/phoneagent/debug/HumanTranslator.kt`
  - 将调试页的 AI 决策信息翻译为中文，方便非技术用户阅读
- **执行状态主动通知** `app/src/main/java/com/phoneagent/debug/ActiveNotifier.kt`
- **长线任务设置页** `app/src/main/java/com/phoneagent/ui/settings/SettingsLongRun.kt`
- **视觉控件数据模型** `app/src/main/java/com/phoneagent/vision/DetectedControl.kt`（统一外挂视觉 Agent 入口，替换原 LocalUiDetector / LocalVisionEngine）

### 增强

- `AgentEngine`：+374 行（长线任务逻辑 / 视觉回退 / 温度策略）
- `DebugScreen`：+392 行（双语 / 对比 / 翻译面板）
- `PageAnnotator`：+244 行（页面标注增强）

---

## [v0.1.134] — 2026-08-22

对应提交 `76c93c9`。

### 修复

- `AgentEngine`：元素树稀疏时（游戏 / WebView）自动截图并交给视觉模型处理，作为无障碍回退
- 视觉回退配置项在设置页的接入

---

## [v0.1.133] — 2026-08-22

对应提交 `4e50e33`。

### 新增

- **外部视觉服务 AIDL** `app/src/main/aidl/com/phoneagent/ondevice/IVisionService.aidl`
  - 跨进程视觉识别接口，允许外挂视觉 Agent 接入
- **外挂视觉 Provider** `app/src/main/java/com/phoneagent/vision/ExternalVisionProvider.kt`
- **Markdown 预览组件** `app/src/main/java/com/phoneagent/ui/workspace/MarkdownPreview.kt`

---

## [v0.1.132] — 2026-08-20

对应提交 `da76725`。

### 新增

- **ShellCommands** `app/src/main/java/com/phoneagent/agent/ShellCommands.kt`
  - AI 友好 ADB 命令解析器：`tap` / `swipe` / `key` / `launch` / `input` / `back` / `home` / `enter` 等
  - 支持比例 / 百分比 / 像素坐标多格式
- **ShizukuManager**（原模块增强）
  - 三态状态管理（UNAVAILABLE / PERMISSION_DENIED / READY）
  - 高权限 Shell 执行通道
- **AdSkipperCore** `app/src/main/java/com/phoneagent/adskip/AdSkipperCore.kt`
  - 自动识别并跳过广告弹窗 / 开屏广告
- **AppPageIndex**：App 页面直达索引（深链）
- **WorkAreaEngine**：工作区文件管理
- **TaskProgressNotifier**：系统通知进度
- **EdgeLighting**：曲面边缘光效

### 修复

- `.gitignore` 添加 `BUGS/` 与 `.trae/` 排除规则
- 移除 `BUGS/` 目录（改用 GitHub Issues 跟踪）

### 文档

- `README.md` 全面更新，补充所有新模块说明与版本历史

---

## [v0.1.130] — 2026-08-13

对应提交 `4eb400d`。

### 新增

- Happy Phone Agent (Phtomt) 首次开源
- 基于 Jetpack Compose + Material 3 Expressive 的 Android Agent 框架
- 多模型链路聚合（主模型 + 视觉模型 + 思考模型）
- ReAct 执行循环（观察 → 决策 → 执行 → 验证）
- 无障碍服务权限申请 / 悬浮窗权限管理
- 敏感页面检测 + 数据脱敏
- 页面指纹验证（防止执行过程中 App 跳转）
- Koin 依赖注入
- 版本号自动递增（`version.properties` + Gradle 构建钩子）
- MIT License

### 基础设施

- Git 初始化 + Gitee / GitHub 双远程推送
- `README.md` / `LICENSE` / `.gitignore`

---

## 版本号说明

`versionName` 格式为 `0.1.BUILD_NUMBER`，`versionCode` 等于 BUILD_NUMBER。
BUILD_NUMBER 存储在 `version.properties`，每次执行 `assemble` / `bundle` 任务时自动自增。
语义化主版本号在项目准备发布稳定版时手动提升。

## 贡献

变更较大的功能请先在 Issues 中开 Discussion，小修直接提 PR。
提交信息遵循 Conventional Commits 风格（`feat:` / `fix:` / `refactor:` / `docs:` / `test:` / `build:`）。
