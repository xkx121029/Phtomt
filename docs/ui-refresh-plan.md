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

### 尚未合并的页（下一轮，需要动页面代码而非入口表）

- 「主题」+「界面效果」+「选项卡与工作台」→ 合并为「外观与导航」单页三个分区（12 → 10 条目）。
- 「软件更新」并入「关于与帮助」底部（12 → 9 条目）。
理由：这三页各自有独立的实时预览与全屏标定逻辑，直接拼接会让单页过长且互相抢刷新，
需要先把预览状态收进独立 composable 再合，属于下一屏工作。

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

## 四、跨线依赖

- 助理角色：已接入 `docs/assistant-role-contract.md` + `engine/runtime/AssistantRolePlan.kt`，
  按钮文案、说明、失败原因、跳转候选标签全部取自契约，UI 侧不再自写说法。
- 编码任务类型：见「三」，需要引擎线在会话上透出 kind。
