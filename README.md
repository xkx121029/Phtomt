# Happy Phone Agent (Phtomt)

> 一个基于 AI 的 Android 智能体应用，能够理解用户意图并自动操控手机完成复杂任务。

## 简介

**Happy Phone Agent** 是一款运行在 Android 设备上的 AI 智能体 App（应用名：Happy Agent）。用户只需用自然语言描述一个目标（例如"帮我订一份外卖"、"把微信消息全部标为已读"），应用会自动规划步骤、观察当前页面、做出决策并在授权后执行点击/滑动/输入等操作，协助用户完成日常手机任务。

## 核心特性

- 🤖 **AI 驱动决策** — 集成主流大语言模型，支持流式思考和智能规划
- 👁️ **视觉理解** — 支持视觉模型链路，识别页面控件语义和截图内容
- 🔗 **多模型链路聚合** — 主模型（决策）+ 视觉模型（截图描述/坐标定位）+ 思考模型（规划/重规划）可按需组合
- 🖱️ **无障碍服务集成** — 通过 AccessibilityService 读取屏幕元素并执行点击、滑动、输入等操作
- 🛡️ **安全防护** — 敏感页面自动检测与只读保护；手机号、身份证号、银行卡号自动脱敏
- 📍 **页面指纹验证** — 基于控件特征的页面指纹比对，确保每次操作真正生效
- ⚡ **端侧快速决策** — 内置本地决策引擎，自动处理弹窗/广告/加载等高频场景，减少云端调用
- 🪟 **悬浮窗实时进度** — 前台服务悬浮窗实时显示任务状态、步骤计数和 AI 思考过程
- 📋 **多任务队列** — 支持顺序执行多个任务，任务可随时取消和重新规划
- 🌐 **中文/英文双语提示词** — 可自由切换，内置自动翻译（AI 缓存去重）
- 📱 **Material 3 设计** — 严格遵循 Material 3 Expressive 视觉标准，支持深色模式

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
│       │   ├── agent/             # Agent 引擎 & 提示词
│       │   │   ├── AgentEngine.kt
│       │   │   ├── AgentPrompts.kt
│       │   │   └── AgentPrompt.kt
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
│       │   ├── execution/         # 带验证执行器
│       │   │   └── VerifiedClickExecutor.kt
│       │   ├── floating/          # 悬浮窗服务
│       │   │   ├── FloatingWindowService.kt
│       │   │   └── MarqueeView.kt
│       │   ├── memory/            # 记忆存储 & 异常学习
│       │   │   └── MemoryStore.kt
│       │   ├── model/             # 数据模型
│       │   │   ├── AgentAction.kt
│       │   │   ├── AgentState.kt
│       │   │   ├── AgentLog.kt
│       │   │   ├── PhantomModels.kt
│       │   │   ├── ScreenSnapshot.kt
│       │   │   └── UiElement.kt
│       │   ├── network/           # 云端 Agent 通信
│       │   │   └── CloudAgent.kt
│       │   ├── perception/        # 页面标注 & 指纹
│       │   │   ├── PageAnnotator.kt
│       │   │   └── PageFingerprint.kt
│       │   ├── screen/            # 屏幕截图服务
│       │   │   └── ScreenSharingService.kt
│       │   ├── security/          # 安全检测 & 脱敏
│       │   │   ├── SensitivePageDetector.kt
│       │   │   └── DataSanitizer.kt
│       │   ├── test/              # 测试引擎 & 场景
│       │   │   ├── TestEngine.kt
│       │   │   ├── TestModels.kt
│       │   │   ├── TestPresets.kt
│       │   │   └── RealScenes.kt
│       │   └── ui/                # UI 界面
│       │       ├── MainActivity.kt
│       │       ├── MainViewModel.kt
│       │       ├── agent/AgentScreen.kt
│       │       ├── components/
│       │       ├── debug/DebugScreen.kt
│       │       ├── home/HomeScreen.kt
│       │       ├── settings/SettingsScreen.kt
│       │       ├── test/TestScreen.kt
│       │       └── theme/
│       └── res/                    # 资源文件
├── gradle/
│   ├── libs.versions.toml          # 版本目录
│   └── wrapper/
├── tests/                          # AI 提示词测试脚本
├── 杂项/                           # 杂项文档
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
3. 在 **设置** 页面配置 AI 模型（API Base URL、API Key、Model Name）
4. 点击 **测试连接** 验证模型可用性
5. 开始使用！

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

### 提示词语言

支持中文/英文双语提示词，可在设置中切换。

## 架构设计

### Agent 执行循环 (ReAct)

```
观察屏幕 → 页面标注 → 端侧/云端决策 → 带验证执行 → 记录 → 循环
```

1. **观察**：通过无障碍服务获取屏幕所有可交互元素
2. **标注**：自动识别页面类型（弹窗/广告/加载/正常）和上下文
3. **决策**：端侧引擎优先处理高频场景，其余委托给 AI 模型
4. **执行**：通过无障碍服务执行动作，前后指纹比对确认生效
5. **记录**：保存执行历史供多轮对话参考

### 安全防护

- **敏感页面检测**：识别支付、个人信息等敏感页面，自动切换为只读模式
- **数据脱敏**：手机号（11位）、身份证号（18位）、银行卡号自动遮蔽
- **用户确认**：关键操作（如支付跳转）强制要求用户确认

## 版本历史

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
- 所有开源贡献者