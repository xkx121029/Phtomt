# 更新日志

本文件记录 Happy Phone Agent (Phtomt) 的全部 noteworthy 变更。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [v0.1.325] — 2026-09-19

修 Agent 页点输入框弹出键盘后，输入区「升得太高、中间留白」的问题。

### 修复

- **键盘上方空出一整条底部导航栏**
  - 根因：`Scaffold` 的 `bottomBar` 用 `WindowInsets.isImeVisible` 判断键盘是否弹出，
    而该可见位在部分机型 / ROM 上不可靠（键盘已经弹出，它仍是 `false`）→ 底部导航栏没有收起
  - 于是 `Scaffold` 的 innerPadding 里仍带着导航栏高度，输入区又用 `imePadding()` 整段避让 IME，
    两者相加 = **导航栏高度 + 键盘高度** —— 输入区被多抬起一截，多出来的那块正是那条空白
  - 现改用 **IME 实际占位高度**判断：`WindowInsets.ime.getBottom(LocalDensity.current) > 0`
    - 它与输入区的 `imePadding()` 同源：只要输入区被抬起，导航栏在同一帧必定让位，
      不会再出现「抬了却没让位」的错位
  - 连带修复协助浮层（`AgentAssistSheet`）：它同样在 `Scaffold` 内容里做 IME 避让，一并受益

---

## [v0.1.324] — 2026-09-19

修复「Agent 一直卡在观察屏幕」：观察这一步里藏着两处没有上限的阻塞调用，卡住时界面既不动也不报错。

### 修复

- **视觉分析没有看门狗**：视觉链路（截图 → 端侧 3B / 云端视觉）全部发生在 `cloudDecide` 里，
  而状态切到「正在思考下一步」是在视觉分析**之后** —— 于是视觉一慢，界面就一直停在「观察屏幕」
  - 云端视觉 `visionDescribe/visionLocate` 走 OkHttp 同步请求（读超时 120s，且带 2~5 次重试）
  - 端侧 3B `detectControls` 单次 20s，同一步原本可能被调用**两次**（第 1 步失败后第 3 步又重试同一服务、同一张图）
  - 新增 `withVisionWatchdog(WATCHDOG_VISION_MS = 25s)`：视觉调用放到独立协程 await，
    超时立刻放弃视觉描述、只用无障碍元素树继续决策；`visionLocate`（hint 目标定位）同样纳入（20s）
  - 这一步开始前先把状态切到「正在识别屏幕内容」，用户能看见 AI 在做什么，而不是干等「观察屏幕」
- **同一步不重复调用外挂视觉**：`triedOnDevice3b` 记录本步是否已经找过外挂，避免第 3 步兜底再白等 20s
- **无障碍截图可能永不回调**：`takeScreenshot` 若被系统限流/内部错误，协程会永久挂起
  （悬浮窗已隐藏、循环停在观察阶段）→ 加 `SCREENSHOT_TIMEOUT_MS = 2.5s` 超时兜底，超时按「无截图」继续

### 说明

- 看门狗超时是**降级**而非失败：本步没有视觉描述，仍按元素树决策，任务不会中断。

---

## [v0.1.323] — 2026-09-19

修跑马灯色带没贴到屏幕物理顶边（落在状态栏下方）的问题。

### 修复

- **色带顶部落在状态栏下沿**
  - 根因：`statusBarHeight()` 只依赖 `resources.getIdentifier("status_bar_height", "dimen", "android")`，
    该资源名在相当一部分 ROM / 高版本系统上取不到，**返回 0**
  - 返回 0 时 `y = -statusBarHeight()` 退化成 `y = 0`，窗口顶正好落在状态栏下沿 —— 色带自然就到不了屏幕边
  - 现取值顺序：**WindowInsets**（API 30+，最可靠）→ 系统资源名 → 兜底 `dp(24)`
    （宁可多覆盖一点，也不能返回 0）

### 修复 · 连带

- **跑马灯文字会被状态栏压住**（此前被上面那个 0 掩盖）
  - `MarqueeView` 高度 = `marqueeHeightDp + statusBarHeight`，而文字原本在**整个高度**里居中；
    状态栏高度修正为真实值后，文字中心正好落进状态栏区域
  - `MarqueeView` 新增 `setTopInset(px)`：色带背景仍从屏幕物理顶铺下来，
    **文字只在状态栏以下的可用高度里居中**；悬浮窗在创建与尺寸变化时传入

---

## [v0.1.322] — 2026-09-19

优化悬浮窗顶部跑马灯（`MarqueeView`），修掉三处一直存在的显示缺陷。

### 修复

- **文字回到入场位时抽动**：文字画在 `x = offset`，完全离开左侧应满足 `offset < -(textWidth)`，
  原判据是 `offset < -w` —— 文字比屏幕窄时会提前回位，于是文字**还在屏内就跳回入场位**
  - 现改为 `offset < -(textWidth + gap)`，保证完全滚出后才接上下一段
- **相位色从未生效**：`Paint` 中 shader 优先级高于 color，原先给文字设了渐变 shader 后，
  `setText(text, marqueeColor(phase))` 传入的相位色被无声覆盖
  - 现文字改用相位色纯色（底色渐变继续承担彩色视觉），相位色这才真正随状态变化
- **状态更新把文字钉在原地**：AI 状态每秒更新数次，原 `setText` 每次都 `offset = 0f`，
  文字反复从头开始，看上去像卡住不动
  - 现更新文字不重置滚动相位；内容与颜色都没变时直接跳过，不做无谓重绘

### 优化

- **短文本居中静止**：文字窄于可视区时不再空转一整圈，同时停掉每帧重绘（省电）
- **单帧最大推进时长** 限制为 0.1 秒：视图不可见一段时间后恢复时，文字不会瞬移一大段
- 清理死代码：未使用的 `bitmap` / `shader` 字段与 `buildGradient()`、未使用的 `kotlin.math.min` 导入

---

## [v0.1.321] — 2026-09-19

需要协助 / 答疑时改为从页面下方浮入协助浮层。

### 新增

- **`AgentAssistSheet`**：底部协助浮层，承载「选项 + 输入框」的完整交互
  - 从页面下方浮入（`slideInVertically` 由下向上 + 淡入，与全站出现方向一致），退场反向
  - 面板加 `animateContentSize()`，文案换行 / 内容增减时高度平滑过渡，不「跳一下」
  - 两种场景共用同一副骨架，仅出口不同：
    - **答疑**（计划有歧义）：问题 + 选项（点一下即答）+ 自由输入 + 「提交」
    - **协助**（敏感页只读 / 动作连续未生效）：原因 + 「已手动处理」+ 指导输入 + 「告诉 AI」
  - 浮层出现时输入区让位（`if (shownAssist == null)`），否则同屏会出现两个输入框
  - 退场动画期间 `assist` 已为 null，缓存最后一次内容（`shownAssist`），避免面板先空掉再滑走
  - 视觉遵循三层圆角口径：浮层容器 = `Card`，输入框与选项 = `Tile`，出口按钮 = `Chip`

### 变更

- **`PlanClarifyItem` 去掉选项列表与手写输入**：这两处职责已由浮层承担，卡片只保留「问题原文 + 一句指路」
  - 同屏出现两套入口（卡片里一套、浮层里一套）会让人不知道该点哪个
  - 连带移除其 `onAnswer` 参数，调用点同步更新

### 说明

- 悬浮窗（桌面覆盖层）侧的 assist 交互未改动：`FloatingWindowService` 的 `clarify` / `guide` 面板仍独立工作，
  与 App 内浮层是两条并行入口（一个在桌面、一个在应用内）

---

## [v0.1.320] — 2026-09-19

修三个界面问题：顶部渐隐遮挡内容、无障碍提示重复。

### 修复

- **顶部渐隐遮罩常驻导致遮挡内容**
  - `TopFadeScrim` 高 56dp 且叠在列表区上层，而 `LazyColumn` 的 `top` padding 只有约 8dp，
    于是列表首项（空态图标、第一张卡的上半截）一出现就被压掉 56dp
  - 它本意是"内容滚到顶部被截断时柔化边界"，未滚动时不该存在：
    现由滚动位置驱动（`firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0`），
    经 `animateFloatAsState` 淡入淡出，静止在顶部时完全不绘制
  - `TopFadeScrim` 新增 `alpha` 参数（0 时直接不组合，不占布局）
- **无障碍未开启提示重复**
  - 空态判据 `showEmpty` 不含 `items`（只看 `traces.isEmpty()` 等），
    因此 `AgentEmptyState` 的引导文案与 `AgentTimelineMapper` 产出的 `Notice` 会同屏出现
  - 修复：空态时过滤掉任务流里的 `Notice`（空态的引导更完整，保留它）；非空态仍由 `Notice` 提示
  - 自动跟随滚动改用过滤后的列表，避免索引越界

---

## [v0.1.319] — 2026-09-19

统一 Agent 页的出现动效方向与卡片口径。

### 变更 · 动效方向

- **出现动效全站统一为「由下向上」**
  - `animateListItem` 去掉缩放（原 0.97 → 1），只保留淡入 + 纵向位移 24dp → 0
    - 理由：纵向位移与缩放松缩是两种观感，叠在同一元素上会互相打架
  - 4 处展开 / 收起（顶栏菜单、步骤卡「原始数据」、文档卡、助手文本卡）改为从底部展开：
    `expandVertically(expandFrom = Alignment.Bottom)` / `shrinkVertically(shrinkTowards = Alignment.Bottom)`
  - 步骤卡状态徽标（未生效 / 已生效）由纯淡入淡出改为「由下向上」滑入 + 淡入
  - 运行状态条入场同样改为「由下向上」（位移 it / 3）

### 变更 · 协调性

- **任务流条目统一圆角与描边**：同一列里此前混用 `AppRadii.Item`(20dp) 与 `AppRadii.Card`(24dp)，且部分卡片无描边
  - 统一为 `Item` + `1dp outlineSoft`：完成卡、失败卡、需协助卡、计划卡、空态警告块
  - 口径固定为三层：**任务流条目 = `Item`(20dp)**、**嵌套块 = `Tile`(14dp)**、**浮层 = `Card`(24dp)**
  - 顶栏下拉菜单属浮层，保持 `Card` 不变

---

## [v0.1.318] — 2026-09-18

补齐意图回显覆盖：此前有一整类意图在 Agent 页是隐形的。

### 修复

- **端侧决策的意图在 Agent 页完全不可见**（真实缺口，非罕见路径）
  - `localDecision.decide()` 命中时走直通分支（`fromLocal = true`），**不经过 `cloudDecide`**，因而不写 `StepTrace`
  - 而任务流以 trace 为骨架（`AgentTimelineMapper.buildRuns` 首行即 `if (traces.isEmpty()) return emptyList()`），
    于是这一步只留下 `StepRecord`、却挂不上列表 —— 用户体感是"AI 没动，页面自己变了"
  - 新增 `recordLocalStepTrace()`：端侧决策同样留档，以 `visionSource = "端侧决策"` 标注来源（`LOCAL_DECISION_SOURCE`）

### 新增

- **失败原因回显**：`StepRecord` 新增 `detail`（执行 / 转译结果说明），三处 `recordStep` 调用点均传入 `verify.detail`
  - 步骤卡片在「未生效」之外补上「原因：…」（红色），成功时弱化为执行层说明
  - 与 `shellOutput` 内容相同时不重复展示
- 卡片新增「端侧」徽标，区分"AI 想的"与"端侧规则直接做的"

### 变更

- **状态徽标平滑过渡**：`未生效 / 已生效` 改用 `AnimatedContent` + 淡入淡出切换，不再是瞬间跳变

---

## [v0.1.317] — 2026-09-18

参考 Aether 的回显手法，重做「进行中」状态的观感：把过程与结果在视觉上分开。

### 变更

- **进行中状态去卡片化**（`LiveStatusItem`）：不再铺卡片底色与边框，改为「一行弱化文字 + 1dp 细分线」
  - 借鉴点：Aether 的 `AgentWorkingStatusHeader` 用次要色文字 + 一条 1dp 细分线，让「还在进行的过程」不与「已完成的结果卡片」争夺视觉权重
  - 步骤卡（结果）保持卡片形态不变，两者层次因此拉开
- **新增「已工作 N 秒」**：`AgentState` 新增 `startedAtMillis`（任务启动时写入），经 `LiveStatus` 透出，UI 用 `produceState` 每秒刷新
  - 不足 1 秒不显示；超过 1 分钟显示「N 分 N 秒」
- **思考中改用微光扫过文字**（`ShimmerStatusText`）：取代原先的常驻脉冲圆点
  - 借鉴点：Aether 的 `ShimmerStatusText` 用 `Brush.linearGradient` 扫过文字表达"正在生成"，行程随文字长度伸缩、扫完停顿再重来
  - 仅在 `THINKING` 阶段启用；`reduceMotion` 时降级为静态文字（其余阶段本就是弱化静态文字）

### 移除

- `LiveStatusItem` 的脉冲圆点与按阶段取色的 `dotColor`（"正在活动"的语义已由微光承担）
- 连带清理失效 import（`CircleShape` / `EaseInOut` / `tween`）

### 说明

- 未逐行照搬 Aether（GPL-3.0）：只借鉴「过程弱化、结果成卡」的层次处理与微光手法，颜色、间距、动效参数全部走本项目已有令牌（`onSurfaceRaised` / `outlineSoft` / `AppSpacing`）

---

## [v0.1.316] — 2026-09-18

Agent 页回显增强：把 AI 正在生成的内容、以及命令的真实执行证据都展示到任务流里，并重排步骤卡片的信息层级。

### 新增

- **实时思考回显**：AI 决策的流式输出此前只推给悬浮窗，现在同步累积到 `AgentEngine.decisionStream` 并在 Agent 页展示
  - `AgentTimelineItem.LiveStatus` 新增 `streaming` 字段，实时状态卡下方贴出正在生成的内容（取尾部 10 行，像终端 tail）
  - 重试时同步清零（避免两遍文本叠加）；决策完成后清空，正文已落 `StepTrace` 的「原始数据」可回看
  - `AgentScreen` 对 `decisionStream` 做 150ms 节流，避免逐字重建 LazyColumn
- **命令输出回显**：`StepRecord` 新增 `shellOutput`，`AgentEngine` 以 `pendingShellOutput` 把本步输出精确关联到本步记录
  - 步骤卡片新增「命令与输出」块：上为实际执行的命令（`action.command`），下为 stdout / stderr；失败时显示可读原因
  - 此前 `lastShellOutput` 只回传给 AI，用户完全看不到 AI 跑了什么、拿到了什么

### 变更

- **步骤卡片信息层级重排**（`StepCallItem`）：一级「动作人话 + 生效状态」→ 二级「依据」→ 三级「命令与输出」→ 四级「元信息 + 原始数据」
  - 置信度从顶行 `StatusPill` 下移进元信息行（`耗时 · tokens · 置信度`），顶行只留"做了什么、成没成"

---

## [v0.1.315] — 2026-09-18

界面精简：Agent 升为主界面，App 内去掉玻璃材质，悬浮窗降为可选能力。

### 变更

- **主界面改为 Agent**：底部导航顺序调整为 Agent / 主页 / 记忆 / 设置，启动后默认进入 Agent
  - `HomeScreen` 保留为第二个 Tab，其快捷入口下标同步（Agent → 0）
  - 全局返回手势：非 Agent Tab 一律回到 Agent（原为回主页）
- **悬浮窗降为可选能力**（不再当作缺失权限来提示）
  - 设置页「运行参数」新增「启用桌面悬浮窗」开关（`floatingWindowEnabled`，默认开）
  - `AgentEngine.maybeStartFloating` 同时校验「开关开启 + 已授权」
  - Agent 页移除三处悬浮窗引导：任务流顶部 Notice、空态警告卡片、顶栏菜单的权限状态与「去授权」按钮
  - `AgentTimelineItem.NoticeKind` 只保留 `ACCESSIBILITY`（真正的硬前置）；顶栏菜单现只展示待执行队列

### 移除

- **App 内玻璃材质**：底部导航栏与记忆图谱容器的 `liquidGlass` 改为实色 / 半透明底
  - 删除 `ui/components/LiquidGlass.kt`（`liquidGlass` / `LiquidGlassCard` / `liquidGlassSurface`，改后全项目零引用）
  - `overlay/LiquidGlassDrawable.kt` **保留**：桌面悬浮窗视觉不变
- 连带清理无用 import（`LocalContext` / `liquidGlass` / `Color` 等）

### 说明

- 悬浮窗与边缘光效是两个独立开关：关闭悬浮窗不影响边缘光效（后者仍需悬浮窗权限，因为它是系统悬浮层绘制）

---

## [v0.1.314] — 2026-09-18

接入 Termux 作为执行通道，并把它"图形界面做不到"的命令行能力纳入意图转译层。

### 新增

- **`device/shell/TermuxBridge.kt`**：Termux 执行通道
  - 目标组件 `com.termux/.app.RunCommandService`（action `com.termux.RUN_COMMAND`），结果走 **PendingIntent 回传**（Termux 官方源码注释指出：结果目录文件方式在 `allow-external-apps` 未开时会永久挂起）
  - 暴露 `isInstalled()` / `hasRunCommandPermission()` / `isAvailable()` / `probe()` / `executeShell()`
  - 回传 Bundle 按键名子串防御性解析，兼容 Termux 版本间的 key 差异
- **`TermuxResultReceiver` + `TermuxResultRelay`**：结果回传接收器与挂起请求登记表（自收自发，`exported=false`）
- **`fetch` 意图（纳入转译层）**：AI 只说"取哪个地址的正文"，命令由端侧拼装
  - `IntentType.FETCH` + `TermuxFetchStrategy`：`uri` → `raw curl -sL --max-time 20 -- <url>`，地址字符白名单过滤
  - Termux 不可用时转译层直接给出可读失败，引导 AI 改走 UI 意图
- **执行通道四态**：`AUTO` / `ADB` / `SHIZUKU` / `TERMUX`；AUTO 顺序为 无线 ADB → Shizuku → Termux（第三顺位兜底）
- **能力页 Termux 卡片**：三项前置条件（已安装 / 已授权 / 已开启 allow-external-apps）+ 分步引导与实跑探测

### 变更

- `executeShellAction` 新增 Termux 工具链判定：`curl`/`python`/`jq` 等命令在 adb shell 中通常不存在，按命令名直接走 Termux，不受通道偏好影响
- 抽出 `finishShellResult`，shell 输出回传逻辑单点收口（原 `runRealShell` 尾部内联）
- 提示词（中英同步）：意图表与规划提示词新增 `fetch`；`situationalExtras` 在"任务需从网络取数 + 本机有 Termux"时按需注入 fetch 用法与边界，其余任务不增加任何篇幅

### 说明

- Termux 是**普通应用权限**的 Linux 环境，与无线 ADB / Shizuku 的系统级 shell 不同：可跑 curl/python/文本处理，**不能**执行 `am` / `pm` / `settings`
- 前置条件：Termux 侧 `~/.termux/termux.properties` 设 `allow-external-apps=true`，并授予本 App `com.termux.permission.RUN_COMMAND` 权限
- 本环境无法跑 Gradle / 无真机，需真机验证 Termux 回传链路（`probe()` 返回 `__HPA_TERMUX_OK__` 即通）

---

## [v0.1.313] — 2026-09-18

砍掉工作区模块，AI 的 `write_doc` 能力保留，生成结果改为在 Agent 页任务流内嵌预览。

### 新增

- **`feature/document/DocumentEngine.kt`**：承接原 `WorkAreaEngine` 中真正需要的那部分
  - 只做两件事：文档正文落盘到私有目录 `documents/`；把最近一次结果推成 `StateFlow<DocResult>`
  - 暴露 `writeDocument(content, fileName)` / `result` / `dismiss()` / `error`
- **Agent 页文档预览**：`AgentTimelineItem.DocPreview` + `AgentTimelineMapper` 新增 `doc` 入参
  - `AgentItems.kt` 新增 `DocPreviewItem`：文件名 + 默认展开的 Markdown 正文（限高 420dp 内滚、可展开/收起/关闭）
  - `AgentScreen` 的 `when(item)` 穷尽分支同步补齐

### 移除

- **工作区 UI 全量删除**：底部「工作区」Tab、`ExtrasPage.FileList` / `FileEditor` 二级页及其返回栏、`ui/workspace/` 8 个文件（WorkAreaScreen / FileListScreen / FileEditorScreen / WorkDisplayPanel / WorkFilesEntry / WorkGenerateCard / WorkLogStream / WorkPreviewCard）
- **`feature/workspace/WorkAreaEngine.kt`**：文件列表、编辑器、流式生成、AI 改写、操作日志等纯 UI 状态一并删除
- 主页「工作区」快捷入口；Tab 下标重排为 主页 0 / Agent 1 / 记忆 2 / 设置 3

### 变更

- `AgentEngine.executeWriteDoc`：落盘后提示语改为「文档已生成，可在 Agent 页查看」；`run()` 开头新增 `documentEngine?.dismiss()`，新任务不带上一份文档残留
- 提示词（中英同步）：意图表、独占路由铁律、规划/决策提示词中的「工作区」措辞统一改为「生成文档，结果在 Agent 页预览」
- 同步更新 README 目录树与模块说明、`杂项/项目完整流程说明.md` 的包表与 UI 结构图

---

## [v0.1.310] — 2026-09-16

对应提交 `74efefb`（重构区间 `2e906f3..74efefb`）。

本轮为纯结构调整：行为零变化（UI 参数、控制流、提示词、导出文案均未改动），目标是「文件职责单一、包名即层级」。

### 重构

- **UI 超大页面瘦身**：7 个 Compose 页面（537~1119 行）拆为约 30 个职责单一文件，根页面只保留入口与 Tab 定义
  - `ui/skill/SkillManagerScreen.kt` 1119 → 133 行：拆出 `SkillsTab` / `SkillEditorDialog` / `McpTab` / `WirelessAdbTab` / `PromptsTab`
  - `ui/debug/DebugScreen.kt` 1098 → 234 行：拆出 `DebugTabs` / `CapabilityStrip` / `DebugFormatters` + `panels/`（StepShot / Metrics / Chat / Log / History / Steps / Timeline）
  - `ui/workspace/WorkAreaScreen.kt` 688 → 171 行：拆出 `WorkFilesEntry` / `WorkGenerateCard` / `WorkPreviewCard` / `WorkLogStream` / `WorkDisplayPanel`
  - `ui/memory/MemoryGraphScreen.kt` → 184 行：拆出 `MemoryGraphCanvas` / `MemoryStats` / `MemoryLists`
  - `ui/home/HomeScreen.kt` 580 → 298 行：拆出 `HomeHero` / `PermissionRadarCard` / `HomeCards`
  - `ui/agent/AgentScreen.kt` 577 → 121 行：拆出 `AgentText` / `PlanPanel` / `AgentCards`
  - `ui/test/TestScreen.kt` 556 → 332 行：拆出 `TestPresetCard` / `TestResultViews`
- **UI 重复实现收敛**
  - `EmptyHint` 三份 → 统一到 `ui/components/Components.kt`（Debug 侧改名 `DebugEmptyHint`，规避同包顶层重名）
  - `MetricCard` / `StatCard` 结构同构 → 合并为 `StatTile(title, value, unit, accent)`
  - 手搓 `Card(shape = …)` → 收敛到 `Components.kt` 的 `SectionCard(title, count, countColor, onClear)`
  - 新增 `ui/components/Formatters.kt`：`formatClock` / `formatFileTime` / `formatSize` 三处同构实现归一
  - `ui/theme/Theme.kt` 新增 `AppSpacing`（4 / 8 / 12 / 16dp），替换页面内硬编码间距
  - `ui/agent/AgentScreen.kt` 的 `isMostlyChinese` 副本删除，改调 `domain.rules.EngineRules.isMostlyChinese`
- **全量搬包重分层**：148 个主源文件 `package` 与所在目录 100% 对齐，顶层包即未来 Gradle 模块边界
  - `core/`：`ai`(4) · `security`(1) · `text`(1，HumanTranslator) · `notify`(3)
  - `domain/`：`model`(9) · `rules`(3，EngineRules / ShellCommands / LocalDecisionEngine)
  - `data/`：`prefs`(1) · `store`(5，原 `memory` / `task` / `debug` / `mcp` / `prompt` 五处持久化收敛) · `export`(1)
  - `device/`：`a11y`(2) · `shell`(14，原 `shizuku` + `shizuku/adb` 扁平化) · `screen`(2) · `vision`(2)
  - `engine/`：根(2，AgentEngine / AgentPrompts) · `execution`(5) · `perception`(3) · `network`(1) · `prompt`(1)
  - `overlay/`：原 `floating` 全部 5 个
  - `feature/`：`task`(1) · `skill`(5) · `mcp`(6) · `workspace`(1) · `adskip`(2) · `edge`(2) · `test`(4)
  - `ui/`：原结构 + 新增 `ui/model`（PermissionRadar）
- **日志导出逻辑外迁**：新增 `data/export/LogExporter.kt`
  - 自 `MainViewModel` 迁出约 160 行文件导出实现（文本日志 / 分任务 JSON / 诊断报告）
  - `MainViewModel` 仅保留 3 个薄委托方法，UI 调用点零改动
  - 导出文案、字段、排版逐字保留；落盘参数抽为 `downloadValues()` 统一
  - `formatLogTimestamp` 随之迁入数据层，避免 `data → ui` 反向依赖

### 移除

- `engine/AgentPrompt.kt`（原 `agent/AgentPrompt.kt`）：全项目零引用死代码
- `app/build.gradle.kts`：未使用的 `androidx.navigation.compose` 依赖（全项目零引用）

### 测试

- 新增 `data/export/LogExporterTest.kt`（10 个用例）：空日志、指定任务无匹配、系统日志归组、多任务批量、诊断报告翻译 + 脱敏路径
- 测试源集补齐跨包 import（23 个测试类）

### 兼容性

- `AndroidManifest.xml` 5 处组件路径同步：`.device.a11y.AgentAccessibilityService` / `.device.screen.ScreenSharingService` / `.overlay.FloatingWindowService` / `.feature.edge.EdgeLightingService` / `.device.shell.AdbPairingReceiver`
- 跨仓库与跨组件的不透明标识符一律未改：`com.phoneagent.floating.STOP`、`com.phoneagent.edge.*`、`com.phoneagent.action.RECEIVE_PAIRING_CODE`、`com.phoneagent.agent_progress_overlay`，以及外挂 APK 契约包名 `com.phoneagent.ondevice`
- `proguard-rules.pro` 使用 `com.phoneagent.**` 通配符，keep 规则不受搬包影响
- `res/xml/accessibility_service_config.xml` 的 `settingsActivity` 指向 `com.phoneagent.ui.MainActivity`，`ui/` 位置未变

### 基础设施

- 单测基线不下滑：24 suites / 242 tests / 0 failed / 3 skipped（重构前 232 tests）
- `:app:lintDebug` / `:app:assembleDebug` / `:app:assembleRelease` 全部通过
- 打 tag `refactor-baseline` 锚定搬包前状态

---

## [v0.1.265] — 2026-09-13

### 新增
- **Release APK 签名构建**
  - 添加 `upload-keystore.jks`（自签名，有效期 10000 天）
  - `app/build.gradle.kts` 支持从 `upload-signing.properties` 加载签名配置
  - `upload-signing.properties` 加入 `.gitignore`（不提交到仓库）
  - Release 配置：R8 混淆 + arm64-v8a 单 ABI，包体大幅缩减
- **APK 上传 Release**：v0.1.265 release APK（约 10MB）发布到 Gitee / GitHub Release

### 新增
- **MCP 验证与使用规则** `app/src/main/java/com/phoneagent/mcp/McpRules.kt`
  - 服务器名（非空、无空格）、URL（须 http/https 且含有效主机）校验
  - 协议版本白名单（`2025-03-26`）、工具名合法性、参数必填/类型校验
- **MCP 服务器持久化** `app/src/main/java/com/phoneagent/mcp/McpStore.kt`
  - 基于 DataStore 存取服务器配置列表，配置变更后自动落盘
- **内置 MCP 市场** `app/src/main/java/com/phoneagent/mcp/McpMarketplace.kt`
  - 免费第三方源预设（经典工具类：filesystem/sqlite/github/notion；公共开放 API：天气/汇率/新闻/币价）
  - 支持分类筛选、关键词搜索、一键"选用"回填新增表单
- **MCP 信息解析增强** `app/src/main/java/com/phoneagent/mcp/McpClient.kt`
  - `describe()`：initialize 握手 + tools/list 枚举，返回 capabilities / serverInfo / 工具及结构化参数
  - `parseParamsFromSchema()`：从 JSON Schema 提取 type / required / enum / default / description
  - 记录最近一次请求/响应原文（JSON-RPC），供 UI 展示
- **MCP 信息结构化注入 Agent 提示词** `app/src/main/java/com/phoneagent/agent/AgentEngine.kt`
  - `mcpToolsPromptText()`：把已启用服务器的工具、参数、使用规则动态注入系统提示，无工具则不增加负担
- **MCP 模块 UI** `app/src/main/java/com/phoneagent/ui/skill/SkillManagerScreen.kt`
  - McpTab：市场选用、新增表单（含可选 Token）、请求信息 JSON 预览
  - 服务器卡片：启停开关、测试有效性、绑定为技能、删除、详情展开（协议版本/服务器元信息/能力/工具及参数）
  - 详情区展示最近请求/响应 JSON（等宽字体预览）
- **Agent 运行时广告过滤** `app/src/main/java/com/phoneagent/adskip/AdContentFilter.kt`
  - 识别"控件内含广告信息 + 跳过/关闭/× 按钮"的广告，直接返回可点击关闭按钮
  - 剔除广告相关元素，保证广告信息不回传给 AI
- **完整技能编辑器** `SkillEditorDialog` 全屏化
  - 支持名称 / ID（新建自动生成、编辑只读）/ 分类 / 说明 / 兼容旧命令意图
  - 结构化参数编辑：参数名 / 标签 / 类型（text·number·boolean·select）/ 必填 / 候选项 / 默认值 / 说明，可动态增删

### 增强
- `McpManager`：改为可变服务器列表（StateFlow 观察），新增 add/remove/setEnabled/replaceAll/describe/bindToolsToSkills
- `MainViewModel`：MCP 服务器观察、增删、启停、持久化、描述、请求/响应原文获取、市场条目查询
- `AppModule`：Koin 依赖注入接入 `McpStore`
- `AgentEngine`：决策循环集成广告过滤——识别到广告直接点击关闭并跳过本轮 AI 决策；无关闭按钮时以干净快照喂给 AI
- `AppModule` / `AgentEngine`：MCP 工具清单在任务开始时一次枚举并注入系统提示

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
