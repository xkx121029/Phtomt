# 工作台 UI 焕新：信息架构与视觉基座

本轮目标：新用户冷启动 → 跑起第一个任务，入口不超过两层；视觉全部走令牌；
Agent / Coding 两套气质可切换。约束：Material 3 Expressive、emilkowalski/skills 交互标准、
动效只自下而上、弹层用应用内嵌入式、文案全中文、拒绝紫渐变/玻璃卡/漂浮光斑。

## 一、入口收敛

### 现状（改动前）

- 冷启动要翻三层：主页主操作卡 → 设置主页 → 「云端模型」页 → 填 Key → 返回 → 另找「权限」页开无障碍。
- 设置主页 6 组 12 个可见条目，另有 3 个二级页（模型库 / 编码环境 / 快速开始），`SettingsPage` 枚举 15 项。
- 两条线（模型配置、权限）互不知道对方状态：模型没配但无障碍已开，状态条仍只有两格静态文字。

### 改动后

- 新增 **快速开始**（`SettingsPage.QUICK_START`）：一页三步，原地给状态与下一步。
  1. 选服务商预设 → 填 API Key → 「测试连接」（失败原因就地中文化，不再抛英文异常原文）；
  2. 无障碍授权（就地给「去开启」，截图/悬浮窗标注为可选并给入口）；
  3. 三个可直接执行的示例任务，点「试试」即起跑并切回 Agent 页。
- 入口层级：主页主操作卡 / 工作台空态 / 设置主页状态条 / Agent 失败卡 → 快速开始，均为**一跳**。
  原路径（设置主页 → 云端模型）保留给多端点管理，不再是新用户的必经之路。
- 设置主页状态条修正：模型「就绪」现在同时要求 `apiKey` 与 `model`（此前只看模型名，
  预设自带模型名会让新用户误以为已配好）；未就绪时右侧直接给「开始配置」直达快速开始。
- 设置主页分组维持 6 组，不再增加层级；`SettingsPage` 只新增快速开始一项，不新增可见分类。

### 已合并的页（本轮）

- 「主题」+「界面效果」+「选项卡与工作台」→ **外观与导航**（`SettingsPage.APPEARANCE`）单页三个分区，
  用分段控件切换，不竖向拼成长页（三段都带实时预览，竖拼会互相抢刷新且页面过长）。
  设置主页可见条目 12 → 10，`SettingsPage` 枚举 16 → 14；工作台配置面板里原先两个入口合并为一个。
- 「软件更新」暂不并入「关于与帮助」：它是带网络检查、频道选择与安装触发的一整套流程，
  并进关于页只能把「检查更新」按钮搬过去、设置项仍要留，收益接近零而改动面很大。
  因此保持两个入口，都留在「关于与更新」组里（同一组内，不再分散）。

## 二、视觉基座

### 已有令牌（沿用）

| 类别 | 入口 | 说明 |
|---|---|---|
| 色彩 | `AppColors` / `MaterialTheme.colorScheme` | 语义色 + 五级容器色调，由 `surfaceBase` 位移生成 |
| 字阶 | `AppTypography` | 全无衬线 |
| 圆角 | `AppRadii` | Chip10 / Inline12 / Tile14 / Bubble18 / Item20 / Card24 / Hero28 / Overlay32 / Header32 |
| 间距 | `AppSpacing` | Xxs2 / Xs4 / Sm8 / Md12 / Lg16 / Page20 / Xl24 / Xxl32 |
| 动效 | `Motion.kt` | Instant100 / Fast160 / Normal200 / Slow280 / Emphasis400 / Pulse900，`motionSettings()` 统一缩放 |
| 框线 | `AppBorders` / `appBorderStroke` / `appBorder` | 标准 / 无框线两档 |

### 本轮新增

| 令牌 | 取值 | 用途 |
|---|---|---|
| `AppSize.TouchTarget` | 48dp | 最小可点区域，独立图标按钮下限 |
| `AppSize.Bar` | 56dp | 顶栏高度 |
| `AppSize.Hairline` | 1dp | 发丝分隔线 |
| `AppSize.IconSm` / `Icon` / `IconTile` / `IconAction` | 18 / 24 / 40 / 32dp | 图标与图标底板 |
| `AppSize.Rail` | 264dp | 宽屏设置分类栏 |
| `AppSize.PanelMax` | 460dp | 嵌入式面板最大高度（超出内滚） |
| `AppSize.OverlayMax` | 480dp | 浮层最大宽度 |
| `AppSpacing.Page` | 20dp | 页面左右栅格边距 |
| `AppSpacing.Xxl` | 32dp | 整页留白 |
| `AppElevation` | Flat0 / Raised1 / Card3 / Sheet8 / Overlay16 | 只有真正盖住内容的层才用；`appElevation()` 在工作台风格下自动 no-op |

### 动效规则

- 页面切换、面板进出：`slideInVertically` + `fadeIn`，位移不超过屏高的 1/12；禁止水平滑入与缩放弹出。
- 所有时长经 `motionSettings().scaledDuration(...)`，跟随系统「减少动效」。
- 状态变化即时：按钮点击有按压反馈，保存与测试连接有进行中/成功/失败的原地文案。

## 三、风格切换

- 工作台壳层一套（`WorkbenchScreen`）：顶栏只有「任务抽屉」+「溢出菜单」，视图切换在菜单内。
- 视图状态沿用 `AppSettings.workbenchView`（持久化，记住上次所在视图）。
- **待引擎线提供**：当前/最近一次任务的类型（agent / coding）信号。
  现有 `TaskSession` 无类型字段，编码会话状态也只在 UI 内部持久化，无法可靠判断"该切哪一屏"。
  拿到该信号后：新任务开始时自动切到对应视图；用户手动切换后进入锁定，直到下次任务类型变化。

## 四、阶段一落地：首屏「常用」面板（本次）

### 改了什么

- 设置主页状态条下方新增 **常用设置** 面板，三块内容都可在首屏直接操作：
  1. **任务执行方式**（保守 / 均衡 / 自由）——点胶囊即保存；选「自由」时后果说明直接出现在控件下方（红色）。
  2. **主模型 / 线路**——首屏直接显示当前线路与模型；点整行进模型库挑主模型，选完回设置主页。
  3. **浮层显示**——桌面悬浮窗开关 + 顶部跑马灯 / 任务卡状态行 / 绿色边缘光效三个复选框。
     仍写 `step_text_placement`（both / marquee / card）与 `edge_lighting` 原键，不新增存储键；
     取消最后一个复选框时就地提示「至少保留一处显示」并保留原勾选。
- 同步删除二级页里的重复入口：
  - 「任务行为」页移除动作模式分段控件，只留步数、提示词、执行文档；
  - 「外观与导航 → 效果」页移除桌面悬浮窗开关、逐步任务文字两个复选框与边缘光效开关，
    只留点击光标、光效标定与跑马灯样式这些低频精调项。
- 宽屏设置左栏新增「常用设置」一站，平板不必回手机式主页才能改高频项。

### 入口数量与层级深度对比

| 高频项 | 改前 | 改后 | 结果 |
|---|---|---|---|
| 任务执行方式 | 设置主页 → 任务行为（2 次点击，层级 2） | 首屏胶囊直接点（0 次跳转，层级 1） | 点击 2 → 0 |
| 浮层显示 | 设置主页 → 外观与导航 → 效果（3 次点击，层级 3） | 首屏勾选/开关（0 次跳转，层级 1） | 点击 3 → 0 |
| 主模型 / 线路 | 设置主页 → 云端模型 → 职责行 → 模型库（4 次点击，层级 3） | 首屏可见；换模型 = 点行 → 选中（2 次点击，层级 2） | 点击 4 → 2 |

- 同一设置的写入口：动作模式、桌面悬浮窗、两处逐步文字、边缘光效各 **1 处**
  （`grep` 只有 `SettingsQuickPanel.kt` 写入；二级页只读或只做标定）。
- 设置主页可见条目仍为 10 个入口 + 1 块常用面板，总层级不变；变化在于高频项从「二级页里的控件」
  变成「首屏控件」，低频项维持二级页。
- 危险项下沉：自由模式后果写在常用面板内；全自动审批、清空记忆、恢复默认仍在各自页面并写明后果。

### 截图

- `docs/ui-shots/phase1-01-app.png`：改前首页（对照）
- `docs/ui-shots/phase1-02-settings.png`：设置主页首屏 + 常用面板
- `docs/ui-shots/phase1-03-settings-groups.png`：常用面板与六组入口衔接
- `docs/ui-shots/phase1-04-agent-page.png`：任务行为页已无动作模式入口
- `docs/ui-shots/phase1-05-effects-page.png`：效果页只留光标 / 标定 / 跑马灯样式
- `docs/ui-shots/phase1-06-free-mode-warning.png`：自由模式选中态与后果说明
- `docs/ui-shots/phase1-07-keep-one-hint.png`：取消最后一个复选框时的就地提示

## 五、阶段二落地：纯色面与尺寸令牌（本次）

### 改了什么

- **去掉玻璃质感**：[Glass.kt](../../app/src/main/java/com/phoneagent/ui/components/Glass.kt) 的浮层面改为
  不透明 `surfaceRaised` + 一道 `outlineSoft` 发丝描边，删除背景模糊、tint 叠色与顶部受光高光。
  顶栏、输入区、悬浮导航栏全部由「磨砂玻璃」变为实色工具面板，滚动内容不再从下方透出。
- **间距 / 圆角收令牌**：设置包内 `padding`、`spacedBy`、`Spacer` 与 `RoundedCornerShape` 的裸 dp
  统一换成 `AppSpacing` / `AppRadii`，并把 3/6/10/14/18/28 这些零散值收进 4pt 刻度
  （`Xxs=2 / Xs=4 / Sm=8 / Md=12 / Lg=16 / Page=20 / Xl=24 / Xxl=32`）。
- **大字号修正**：首页「主模型」行原本模型名与右侧摘要抢宽度，1.35x 下会被省略；
  改为「模型名一行、线路一行」的竖排，模型名不再截断。
- **文案中文化**：底部 Tab `Coding` → `编码`，编码页标题、工作台视图名、设置摘要里的 `个 Tab` 一并改为中文。

### 量化对比

| 指标 | 改前 | 改后 |
|---|---|---|
| 设置包裸 dp 字面量（`\d.dp`，不含他人正在改的 SettingsQuickStart.kt） | 497 | 65 |
| 其中间距/圆角上下文 | 约 430 | 0（全部走 `AppSpacing` / `AppRadii`） |
| 浮层材质 | Haze 背景模糊 + 半透明 tint + 高光 | 不透明纯色面 + 发丝线 |
| 大字号 1.35x 模型名 | 被省略号截断 | 完整显示 |

剩余 65 处是组件固定尺寸（图标、指示条、预览框、标定尺）与调色板色块，不属于间距刻度，保留字面量。

### 自查

- 深色（默认玄青板）：`docs/ui-shots/phase2-01-settings-solid.png`
- 系统字体 1.35x：`docs/ui-shots/phase2-02-font135-settings.png`、`phase2-03-font135-quickpanel.png`
  —— 复选框标题、说明、分组卡片均无截断/压叠；仅线路 URL 因过长省略，属预期。
- 对比度：正文 `onSurface` / 次要文字 `onSurfaceVariant` / 错误文案 `error`，均为色板令牌，
  未出现自定义低对比灰字；装饰性箭头保留 45% 透明度，不承载信息。

## 六、阶段三落地：按压与长列表（本次）

### 改了什么

- **按下当帧给反馈**：新增 [InstantPress.kt](../../app/src/main/java/com/phoneagent/ui/theme/InstantPress.kt)，
  以 `IndicationNodeFactory` 实现「按下即铺色」——收到 `PressInteraction.Press` 的同一帧就把
  内容色 12% 平涂整块区域，抬手（Release / Cancel）立刻撤掉，中间没有任何插值。
  在 `ProvideMotionSettings` 里经 `LocalIndication` 全站注入，所有未显式指定 indication 的
  `.clickable` / `.toggleable` / M3 列表行自动生效；自带缩放的 `PressableScale` 仍走自己的
  `indication = null`，不会叠两层。
  系统 ripple 的问题是「可见浓度靠动画逐帧涨」，快速点击时整段反馈被截掉大半，观感像没点中。
- **动效时长清零**：[VoiceRecordField.kt](../../app/src/main/java/com/phoneagent/ui/voice/VoiceRecordField.kt)
  里最后两处裸时长 `tween(160)` / `tween(120)` 换成令牌——入场淡入走 `DurationNormal`（200ms），
  分区着色渐变走 `DurationFast`（160ms）。全 UI 层 `grep tween(` 不再出现字面量时长。
- **长列表懒加载**：
  - [SettingsModelCatalog.kt](../../app/src/main/java/com/phoneagent/ui/settings/SettingsModelCatalog.kt)
    由 `Column + verticalScroll + forEach` 改为 `LazyColumn`，页头、手填区、筛选区、每个端点的
    分组标题与模型行都成为独立条目，只组合视口内的行。
  - [SettingsComponents.kt](../../app/src/main/java/com/phoneagent/ui/settings/SettingsComponents.kt)
    的「导入模型」弹窗列表同样改为 `LazyColumn`（一个端点拉回上百个模型是常态）。

### 滚动帧时间实测

模拟器为软件光栅化（`ro.hardware.egl=emulation`、`debug.hwui.renderer=skiagl`），
刷新率 60Hz。设置首页上下慢速拖动 10 个来回，`dumpsys gfxinfo com.phoneagent`：

| 指标 | 实测 |
|---|---|
| 总帧数 | 933 |
| 50th 分位 | 17ms（≈59fps，满帧） |
| 90th 分位 | 36ms |
| 95th 分位 | 69ms |
| 99th 分位 | 129ms |
| Janky 帧占比 | 15.97% |
| Slow UI thread / Slow issue draw | 122 / 123 |

结论：稳态满帧（p50 = 17ms 即一帧预算），长尾卡顿来自模拟器软件绘制
（Slow issue draw 与 Slow UI thread 数量基本相等，说明瓶颈在渲染线程而非组合/测量）。
模型库页当前只有 2 条模型，页高不足一屏、滚不动，因此该项只能做功能核对与代码核对，
不做帧数结论。

### 截图

- `docs/ui-shots/phase3-01-catalog-lazy.png`：模型库页改为 LazyColumn 后的渲染结果
- `docs/ui-shots/phase3-02-press.png`：按住执行方式胶囊（`PressableScale` 自身路径）
- `docs/ui-shots/phase3-03-press-row.png`：按住「主模型」整行——按下瞬间整块铺色，不等 ripple

## 七、阶段四落地：回归与一致性验收（本次）

### 改了什么

| # | 改动 | 为什么 | 证据 |
|---|---|---|---|
| 1 | 导航形态改由「宽 + 高」共同判定：手机横屏走左侧收起侧栏（`landscapePhone`），不再套用底部条 | 横屏时底部条 100dp + 输入板 200dp + 页眉 120dp ≈ 411dp，1024×476dp 的可视区里任务流被压成一条缝 | `phase4-04-landscape-agent.png`：任务流、输入框、两枚开关完整可见 |
| 2 | 侧栏形态下底部净空返回 `systemNavInset` | 竖屏底部条已不存在，净空却仍是「条高 + 间距 ×2」，横屏手势胶囊会压住输入区最后一行开关 | 同上图：开关行距手势条留有一指宽 |
| 3 | 横屏隐藏「模型 / 授权」两枚转盘卡（`shortScreen`） | 横屏纵向只有 ~476dp，这两张卡把任务流挤到屏外；它们的信息在设置首页「运行状态」里已有 | 同上图 |
| 4 | 关于页「使用说明」文案改口径 | 原文「执行中会先给计划，你确认后才动手」与 `plan_before_act` 默认关闭矛盾，属于用户可见的错误承诺 | `phase4-06-about-credits.png` 同页「使用说明」分区 |
| 5 | 连接失败增加不动产中文化：`unable to resolve host` / `no address associated with hostname` → 「域名解析失败：手机当前没有可用网络，连上网后重试」 | 关网测试时原文直接弹 `Unable to resolve host …: No address associated with hostname`，半截英文等于没解释 | `phase4-09-offline.png` |

### 逐项自查

| 状态 | 截图 | 结论 |
|---|---|---|
| 深色 | `phase4-01-dark-settings.png` | 分区底色与文字对比度正常，分隔线不发光；无半透明叠层 |
| 大字号 1.35x | `phase4-02-font135-settings.png`、`phase4-03-font135-agent.png` | 「任务执行方式」三段选择、浮层三个复选项、输入栏两枚开关均未截断；设置项副标题换行后行高正常 |
| 横屏（手机 1024×476dp） | `phase4-04`、`phase4-05` | 修复后无压叠：左侧收起侧栏 + 单列内容；修复前底部条与输入板叠加导致任务流只剩一条缝（已在上表说明） |
| 平板（1600×2560 @240dpi ≈ 1066×1706dp） | `phase4-07`、`phase4-08` | 宽屏走展开侧栏；设置页为「列表 - 详情」两栏，右栏独立滚动，未出现内容拉伸或错位 |
| 无网络 | `phase4-09` | 错误提示为中文且给出下一步；不再出现英文技术串 |

### 文案巡检

- 全部用户可见文案为中文；唯一保留的英文是产品名、模型名与 API 地址，属专有名词。
- 检查并修正了一处与功能默认值不符的说明（上表第 4 条）。
- 未发现「AI 味」空话；设置项副标题均为「做什么 / 关掉会怎样」的具体描述。

### 遗留观察（未改，属其他线或全局浮层）

1. **全局悬浮 AI 按钮遮住致谢行右侧「打开」**：该按钮由全局浮层绘制在最上层，固定在右上角；在 `phase4-06-about-credits.png` 里压住第一条致谢的按钮。整行可点，功能不受影响，但视觉上属遮挡。它不归本次界面改动范围，未动。
2. **`PromptSnapshotTest` 单测失败**：失败原因是引擎线未提交的 `AgentPrompts.kt` / `PromptBodies.kt` 改动（提示词快照与断言不一致），非本次界面侧改动，未修改。
3. **横屏设置页右侧边缘**：`phase4-05-landscape-settings.png` 中右上角悬浮按钮压住卡片圆角一小块，同上第 1 条，属全局浮层。

## 八、跨线依赖

- 助理角色：已接入 `docs/assistant-role-contract.md` + `engine/runtime/AssistantRolePlan.kt`，
  按钮文案、说明、失败原因、跳转候选标签全部取自契约，UI 侧不再自写说法。
- 编码任务类型：见「三」，需要引擎线在会话上透出 kind。
