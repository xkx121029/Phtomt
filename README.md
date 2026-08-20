# Happy Phone Agent (Phtomt)

> 一个基于 AI 的 Android 智能体应用，能够理解用户意图并自动操控手机完成复杂任务。

## 简介

**Happy Phone Agent** 是一款运行在 Android 设备上的 AI 智能体 App（应用名：Happy Agent）。用户只需用自然语言描述一个目标（例如"帮我订一份外卖"、"把微信消息全部标为已读"），应用会自动规划步骤、观察当前页面、做出决策并在授权后执行点击/滑动/输入等操作，协助用户完成日常手机任务。

## 核心特性

- 🤖 **AI 驱动决策** — 集成主流大语言模型，支持流式思考和智能规划
- 👁️ **视觉理解** — 云端视觉模型 + 本地 ML Kit OCR 双引擎，识别页面控件语义和截图文字
- 🔗 **多模型链路聚合** — 主模型（决策）+ 视觉模型（截图描述/坐标定位）+ 思考模型（规划/重规划）可按需组合
- 🖱️ **双通道执行** — 无障碍服务（基础）+ Shizuku ADB Shell（高权限）双通道执行
- 🔧 **AI 友好命令解析** — ShellCommands 将 AI 短命令（如 `tap 500 800`）翻译为 ADB 命令，支持比例/百分比/像素坐标
- 🛡️ **内置跳广告** — AdSkipperCore 自动识别并关闭青少年模式弹窗、跳过按钮、倒计时广告
- 📍 **常用 App 直达** — AppPageIndex 为系统设置、高德地图等常用 App 提供深链直达
- 🛡️ **安全防护** — 敏感页面自动检测与只读保护；手机号、身份证号、银行卡号自动脱敏
- 📍 **页面指纹验证** — 基于控件特征的页面指纹比对，确保每次操作真正生效
- ⚡ **端侧快速决策** — 内置本地决策引擎，自动处理弹窗/加载等高频场景，减少云端调用
- 🪟 **悬浮窗实时进度** — 液态玻璃悬浮窗 + 系统通知双通道显示任务状态
- 📋 **多任务队列** — 支持顺序执行多个任务，任务可随时取消和重新规划
- 🌐 **中文/英文双语提示词** — 可自由切换，内置自动翻译（AI 缓存去重）
- 📱 **Material 3 设计** — 严格遵循 Material 3 Expressive 视觉标准，支持深色模式
- 💡 **边缘光效** — EdgeLighting 曲面屏边缘光晕效果

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.0.21 |
| UI | Jetpack Compose | 2024.12.01 |
| 设计 | Material 3 | 1.3.1 |
| 架构 | MVVM + 状态流 | StateFlow / Co-routines |
| 依赖注入 | Koin | 4.0.0 |
| 网络 | OkHttp | 4.12.0 |
| 序列化 | kotlinx-serialization | 1.7.3 |
| 存储 | DataStore | 1.1.1 |
| 本地 OCR | Google ML Kit Text Recognition | 中文支持 |
| ADB 执行 | Shizuku | 高权限 Shell 通道 |
| 最低 SDK | Android 8.0 (API 26) | — |
| 目标 SDK | Android 15 (API 35) | — |

## 项目结构

```
happy_phone agent/
├── app/
│   └── src/main/
│       ├── java/com/phoneagent/
│       │   ├── a11y/              # 无障碍服务 & 执行器
│       │   │   ├── AgentAccessibilityService.kt
│       │   │   └── ActionExecutor.kt
│       │   ├── adskip/            # 跳广告引擎 🆕
│       │   │   └── AdSkipperCore.kt
│       │   ├── agent/             # Agent 引擎 & 提示词
│       │   │   ├── AgentEngine.kt
│       │   │   ├── AgentPrompts.kt
│       │   │   ├── AgentPrompt.kt
│       │   │   └── ShellCommands.kt      # AI 友好命令解析器 🆕
│       │   ├── ai/                # AI 客户端 & 模型配置
│       │   │   ├── AiClient.kt
│       │   │   ├── AiDecision.kt
│       │   │   ├── ChatModels.kt
│       │   │   └── GlmDefaults.kt
│       │   ├── data/prefs/        # DataStore 持久化
│       │   │   └── AppSettings.kt
│       │   ├── decision/          # 端侧决策引擎
│       │   │   └── LocalDecisionEngine.kt
│       │   ├── di/                # Koin 依赖注入
│       │   │   └── AppModule.kt
│       │   ├── edge/              # 边缘光效 🆕
│       │   │   ├── EdgeLightingService.kt
│       │   │   └── EdgeLightingView.kt
│       │   ├── execution/         # 带验证执行器
│       │   │   └── VerifiedClickExecutor.kt
│       │   ├── floating/          # 悬浮窗服务
│       │   │   ├── FloatingWindowService.kt
│       │   │   ├── LiquidGlassDrawable.kt  # 液态玻璃绘制 🆕
│       │   │   ├── MarqueeView.kt
│       │   │   └── SuccessMarkView.kt      # 成功标记视图 🆕
│       │   ├── memory/            # 记忆存储 & 异常学习
│       │   │   └── MemoryStore.kt
│       │   ├── model/             # 数据模型
│       │   │   ├── AgentAction.kt
│       │   │   ├── AgentState.kt
│       │   │   ├── AgentLog.kt
│       │   │   ├── AppPageIndex.kt        # App 页面直达索引 🆕
│       │   │   ├── DebugModels.kt
│       │   │   ├── PermissionRadar.kt
│       │   │   ├── PhantomModels.kt
│       │   │   ├── ScreenSnapshot.kt
│       │   │   └── UiElement.kt
│       │   ├── network/           # 云端 Agent 通信
│       │   │   └── CloudAgent.kt
│       │   ├── notify/            # 系统通知进度 🆕
│       │   │   └── TaskProgressNotifier.kt
│       │   ├── perception/        # 页面标注 & 指纹
│       │   │   ├── PageAnnotator.kt
│       │   │   └── PageFingerprint.kt
│       │   ├── screen/            # 屏幕截图服务
│       │   │   └── ScreenSharingService.kt
│       │   ├── security/          # 安全检测 & 脱敏
│       │   │   ├── SensitivePageDetector.kt
│       │   │   └── DataSanitizer.kt
│       │   ├── shizuku/          # Shizuku ADB 通道 🆕
│       │   │   └── ShizukuManager.kt
│       │   ├── test/              # 测试引擎 & 场景
│       │   │   ├── TestEngine.kt
│       │   │   ├── TestModels.kt
│       │   │   ├── TestPresets.kt
│       │   │   └── RealScenes.kt
│       │   ├── vision/           # 本地视觉 OCR 🆕
│       │   │   └── LocalVisionEngine.kt
│       │   ├── workspace/        # 工作区引擎 🆕
│       │   │   └── WorkAreaEngine.kt
│       │   └── ui/                # UI 界面
│       │       ├── MainActivity.kt
│       │       ├── MainViewModel.kt
│       │       ├── agent/AgentScreen.kt
│       │       ├── components/
│       │       ├── debug/DebugScreen.kt
│       │       ├── home/HomeScreen.kt
│       │       ├── memory/MemoryGraphScreen.kt   # 记忆图谱 🆕
│       │       ├── settings/             # 设置模块拆分
│       │       │   ├── SettingsScreen.kt
│       │       │   ├── SettingsAdSkip.kt        # 跳广告设置 🆕
│       │       │   ├── SettingsAgent.kt         # Agent 设置 🆕
│       │       │   ├── SettingsAiModels.kt       # AI 模型设置 🆕
│       │       │   ├── SettingsComponents.kt   # 组件设置 🆕
│       │       │   ├── SettingsHome.kt          # 主页设置 🆕
│       │       │   └── SettingsVisual.kt        # 视觉设置 🆕
│       │       ├── test/TestScreen.kt
│       │       ├── workspace/            # 工作区 UI 🆕
│       │       │   ├── WorkAreaScreen.kt
│       │       │   ├── FileListScreen.kt
│       │       │   └── FileEditorScreen.kt
│       │       └── theme/
│       └── res/                    # 资源文件
├── gradle/
│   ├── libs.versions.toml          # 版本目录
│   └── wrapper/
├── tests/                          # AI 提示词测试脚本
├── 杂项/                           # 杂项文档
├── BUGS/                           # 已知问题记录
├── build.gradle.kts
├── settings.gradle.kts
└── version.properties
```

## 快速开始

### 1. 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 35
- 一台 Android 手机（需开启开发者选项）
- （可选）安装 Shizuku 以启用 ADB 高权限执行

### 2. 构建步骤

```bash
# 克隆项目
git clone <repository-url>
cd happy_phone-agent

# 清理并构建 Debug APK
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

### 3. 安装与配置

1. 安装 APK 到手机
2. 打开 App，授予以下权限：
   - **无障碍服务** — 读取屏幕元素并执行操作
   - **悬浮窗** — 显示实时进度覆盖层
   - **屏幕录制** — 用于截图视觉模型
   - **通知权限** — 显示前台服务通知
   - **自启动** — 保持后台服务运行
   - **Shizuku** — 启用 ADB 级 Shell 命令（可选，增强执行能力）
3. 在 **设置** 页面配置 AI 模型（API Base URL、API Key、Model Name）
4. 点击 **测试连接** 验证模型可用性
5. 开始使用！

## 核心模块详解

### ShellCommands — AI 友好命令解析器

AI 不再需要记忆复杂的 ADB 语法，只需使用简洁的命名命令：

| 命令 | 参数 | 说明 |
|------|------|------|
| `tap` | `x y` | 点击（支持比例/百分比/像素坐标） |
| `lp` | `x y` | 长按 1500ms |
| `dt` | `x y` | 双击 |
| `sw` | `x1 y1 x2 y2` | 滑动 |
| `key` | `BACK/HOME/ENTER/数字` | 按键 |
| `text` | `"文字"` | 输入文字 |
| `launch` | `包名` | 启动应用 |
| `brightness` | `0~255` | 屏幕亮度 |
| `raw` | `完整ADB命令` | 直接透传（兜底） |

### Shizuku — ADB 高权限通道

当安装了 Shizuku 时，App 可通过 ADB Shell 执行更强大的命令：
- 三态状态管理：`UNAVAILABLE` / `PERMISSION_DENIED` / `READY`
- 支持执行任意 ADB 命令（通过 ShellCommands 转换）
- 未安装 Shizuku 时自动回退到无障碍服务通道

### AdSkipperCore — 内置跳广告

独立于 Agent 运行的后台广告拦截引擎：
- 识别优先级：青少年模式弹窗 > 显式"跳过"按钮 > 纯倒计时角标 > 关闭按钮
- 内置冷却机制，防止重复点击和死循环
- 精确匹配 + 位置约束 + 控件类型约束，降低误触

### AppPageIndex — App 页面直达

为常用 App 提供稳定的页面直达方式，避免 AI 自行编造 scheme：
- 系统设置：Wi-Fi、蓝牙、显示、应用管理等
- 高德地图：地图首页、路线规划、附近
- 支持 URI 深链 > Intent Action > 包名直达的降级策略

### LocalVisionEngine — 本地 OCR

基于 Google ML Kit 的端侧文字识别：
- 支持中文 Text Recognition
- 返回归一化坐标（cx, cy）供视觉定位使用
- 离线运行，无网络延迟

### WorkAreaEngine — 工作区

文件管理和 AI 辅助编辑工作区：
- 文件浏览和编辑
- AI 辅助内容生成
- 任务结果归档

## 配置说明

### 模型配置

支持三类模型独立配置：

| 模型类型 | 用途 | 是否必需 |
|---------|------|---------|
| 主模型 | 决策、规划、意图理解 | ✅ 必需 |
| 视觉模型 | 截图描述、坐标定位 | 可选（默认 glm-4.6v-flash） |
| 思考模型 | 深度思考、重规划 | 可选（链路聚合模式） |

### 链路聚合

默认情况下仅使用主模型 + 可选视觉模型。开启链路聚合后可同时启用思考模型，用于复杂规划和重规划场景。

### 执行通道

- **无障碍通道**（默认）：通过 AccessibilityService 执行，兼容性好但能力有限
- **Shizuku 通道**（可选）：通过 ADB Shell 执行，支持更丰富的命令集

### 提示词语言

支持中文/英文双语提示词，可在设置中切换。

## 架构设计

### Agent 执行循环 (ReAct)

```
观察屏幕 → 页面标注 → 端侧/云端决策 → 双通道执行 → 带验证 → 记录 → 循环
```

1. **观察**：通过无障碍服务获取屏幕所有可交互元素
2. **标注**：自动识别页面类型（弹窗/广告/加载/正常）和上下文
3. **决策**：端侧引擎优先处理高频场景，其余委托给 AI 模型
4. **执行**：Shizuku 通道优先，无障碍通道兜底；前后指纹比对确认生效
5. **记录**：保存执行历史供多轮对话参考

### 安全防护

- **敏感页面检测**：识别支付、个人信息等敏感页面，自动切换为只读模式
- **数据脱敏**：手机号（11位）、身份证号（18位）、银行卡号自动遮蔽
- **用户确认**：关键操作（如支付跳转）强制要求用户确认

## 版本历史

### v0.1.132 (132)

- 🆕 **ShellCommands**：AI 友好命令解析器，支持 tap/swipe/key 等命名命令，三坐标格式
- 🆕 **ShizukuManager**：ADB 高权限 Shell 执行通道，三态状态管理
- 🆕 **AdSkipperCore**：内置跳广告引擎，识别青少年模式弹窗/跳过按钮/倒计时角标
- 🆕 **AppPageIndex**：常用 App 页面直达索引库（系统设置/高德地图/抖音等深链）
- 🆕 **LocalVisionEngine**：基于 ML Kit 的本地中文 OCR，离线识别文字区域
- 🆕 **WorkAreaEngine**：工作区文件管理引擎
- 🆕 **TaskProgressNotifier**：独立系统通知进度（与悬浮窗互补）
- 🆕 **EdgeLighting**：曲面边缘光晕效果
- 🆕 **LiquidGlassDrawable**：液态玻璃悬浮窗绘制
- 🆕 **SuccessMarkView**：成功标记视图
- 🆕 **MemoryGraphScreen**：记忆图谱 UI
- 🔧 设置页面拆分为独立组件（6 个 Settings* 文件）
- 🔧 新增工作区界面（WorkAreaScreen/FileListScreen/FileEditorScreen）
- 🔧 AgentEngine 核心逻辑大幅扩展（+783 行）
- 🔧 FloatingWindowService 增强（+837 行）
- 🔧 新增 BUGS/ 目录管理已知问题
- 🔧 新增 3 个测试脚本（adb_priority_test/all_prompts_test/retry_rate_limited）

### v0.1.30 (30)

- UI 全面优化：Material 3 Expressive 视觉标准落地
- 运动系统：新增 Motion.kt / MotionHelpers.kt，缓动曲线与弹簧动画
- 触觉反馈：全局 rememberHapticClick() 组件
- 页面转场：AnimatedContent 对称路径过渡
- 视觉层次：Color 语义化 token，深色模式全面适配
- 代码结构：HomeScreen 状态提升、Composable 复用
- 权限雷达：优化位置与视觉对比

### v0.1.6 (6)

- 自动递增版本号机制
- 新增真实环境场景库（10+ 移动端页面快照）
- 测试引擎支持 AGNES 模型 prompt 验证
- 修复步骤描述解析逻辑（字段优先级：description > action > text > step）

### v0.1.4 (4)

- 新增自启动与电池优化权限
- 实现厂商自启动管理页面跳转（小米/华为/OPPO/VIVO/三星/魅族）
- 权限雷达 UI 优化

### v0.1.0 (Initial)

- 核心 Agent 引擎：观察-决策-执行循环
- 无障碍服务集成
- 多模型链路聚合
- 数据脱敏与敏感页检测
- 悬浮窗实时进度
- 测试引擎框架

## 贡献指南

欢迎贡献代码！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'feat: 添加新功能'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- 遵循 [Kotlin 官方编码规范](https://developer.android.com/kotlin/style-guide)
- 使用 4 空格缩进
- 提交信息遵循 Conventional Commits 格式
- UI 变更需遵循 Material 3 设计标准

## 许可协议

本项目基于 [MIT License](LICENSE) 开源。

## 联系方式

- 创建 Issue 讨论技术问题
- 提交 Pull Request 贡献代码

## 致谢

- Material Design 设计团队
- Jetpack Compose 社区
- Google ML Kit OCR
- Shizuku 项目
- 所有开源贡献者