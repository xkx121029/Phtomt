# 主题皮肤轴交接说明

记录「皮肤（skin）」这一层是怎么落地的、令牌在两套皮肤下各取什么值、新增第三套皮肤要动哪些文件，以及哪些数字是刻意保持固定的。只写事实与判断依据，不含路线图。

配套材料：

- 视觉证据帧与模拟器局限：[`docs/ui-shots/README-graphite-theme.md`](ui-shots/README-graphite-theme.md)
- 改造前的 UI 计划：[`docs/ui-refresh-plan.md`](ui-refresh-plan.md)

---

## 1. 主题的四条轴

设置在 `Settings`（`app/src/main/java/com/phoneagent/data/prefs/AppSettings.kt`）：

| 轴 | 字段 | 取值 | 作用范围 |
| --- | --- | --- | --- |
| 皮肤 | `skin` | `legacy` / `graphite` | 色板、字体层级、M3 形状、全部布局令牌、玻璃材质 |
| 配色 | `palette` | 既有 `ThemePalette` 枚举 | 仅 `legacy` 皮肤下生效；`graphite` 有自己的四套色板 |
| 风格 | `uiStyle` | 既有 `UiStyle` 枚举 | 拾取 M3 Shapes；`graphite` 优先覆盖 |
| 深色/对比度 | `darkTheme` / `contrast` | 布尔 | 两套皮肤共用 |

持久化：`Keys.THEME_SKIN = stringPreferencesKey("theme_skin")`，默认值 `"legacy"`。读取用 `ThemeSkin.of(raw)`，未知字符串回落到 `legacy`，因此旧版本数据与脏数据都不会崩。

皮肤轴的接入点：

- `PhoneAgentTheme(..., skinId: String)`（`ui/theme/Theme.kt`）用 `ThemeSkin.of(skinId)` 解析，并下发 `LocalThemeSkin`、`LocalAppMetrics`。
- `MainActivity` 只负责把 `settings.skin` 透传进来，不做判断。
- `AppTheme.skin` / `AppTheme.metrics`（`ui/theme/Color.kt`）是 Composable 侧的统一读取口。

---

## 2. 令牌对照表

### 2.1 色板

石墨是独立的一套 `AppColors`，不复用任何旧色值，定义在 `ui/theme/Graphite.kt`：
`GraphiteLightAppColors`、`GraphiteDarkAppColors`、`GraphiteLightContrastAppColors`、`GraphiteDarkContrastAppColors`（对比度版用 `.copy()` 覆盖差异项）。

| 语义 | 经典（浅色） | 石墨（浅色） | 石墨（深色） |
| --- | --- | --- | --- |
| 品牌主色 `brand` | 既有品牌绿 | `#3A4ED8` 靛蓝 | `#9BA8FF` |
| 品牌容器 `brandContainer` | 既有浅容器 | `#DDE1FB` | `#262E6E` |
| 页面底 `surfaceBase` | 既有纸色 | `#F2F3F5` | `#101214` |
| 抬升面 `surfaceRaised` | 介于底与卡片之间 | `#FFFFFF`（**比页面亮**） | `#1A1D21` |
| 下沉面 `surfaceSunken` | — | `#E8EAED` | `#0A0C0E` |
| 强描边 `outlineStrong` | — | `#5F6670` | `#9298A2` |
| 弱描边 `outlineSoft` | — | `#DCDFE3` | `#33383F` |
| 暖辅助 `accentWarm` | — | `#9A5B1E` | `#E0A462` |
| 冷辅助 `accentCool` | — | `#1C6B62` | `#6FC7BB` |
| 成功 / 警告 / 错误 | 既有状态色 | `#1E6E4B` / `#8A5A08` / `#C0362C` | `#6FD3A2` / `#E8B75E` / `#FF8A7E` |
| 玻璃底 `glassTint` | 既有 | `#B8F2F3F5`（约 72% 不透明） | `#B814171A` |
| 玻璃降级 `glassFallback` | 既有 | `#F2F2F3F5` | `#F014171A` |
| 玻璃描边 `glassBorder` | 既有 | `#223A4ED8` | `#339BA8FF` |
| 玻璃高光 `glassSheen` | 既有 | `#00FFFFFF`（零高光） | `#00FFFFFF` |

M3 `ColorScheme` 由 `graphiteLightScheme` / `graphiteDarkScheme` 显式构建（`Graphite.kt`），要点：

- 五级容器显式给出，浅色向白抬升、深色小幅度向白抬升，`surfaceContainerLow` 才稳定读作「比页面靠前」。
- `secondaryContainer` 不用暖沙色，而是「靛蓝容器 × 0.55（浅）/ × 0.5（深）+ 底色」的冷调混色。原因：M3 把它当分段控件、筛选芯片的**选中填充**用，铺暖沙会和石墨的整体语言打架；暖色只留在 `secondary`（图标、次级强调）。
- `scrim` 固定 `Color.Black`（见第 4 节）。

### 2.2 字体层级

经典沿用原有 `Typography`。石墨用 `GraphiteTypography`（`Graphite.kt`），差别只有三条：

| 项 | 经典 | 石墨 |
| --- | --- | --- |
| 正文 | 16sp / 行高 26sp | 15sp / 行高 24sp |
| 小标题 | 沿用 | 16sp / 行高 22sp |
| 小标签 | 沿用 | 11sp，字距 0.4~0.5sp |
| 层级手段 | 字号 + 颜色 | 字号收紧，靠字重（标题 Bold、正文 Normal）拉开 |

所有字号仍是 `sp`，跟随系统字体缩放；行高用 `sp` 比例给出，放大字号不会压字。

### 2.3 形状

- M3 `Shapes`：经典走 `UiStyle.shapesFor(style)`；石墨走 `GraphiteShapes`（`extraSmall 6 / small 8 / medium 10 / large 14 / extraLarge 20`），`UiStyle.shapesFor(style, skin)` 里石墨优先。
- 组件圆角读 `AppTheme.metrics`，不是读 `AppRadii`（`AppRadii` 只是经典值的镜像）：

| 令牌 | 经典 | 石墨 | 用途 |
| --- | --- | --- | --- |
| `cardRadius` | 24dp | 16dp | 大卡片、分组容器 |
| `itemRadius` | 20dp | 12dp | 列表项、气泡主体 |
| `tileRadius` | 14dp | 10dp | 图标底盘、内嵌块 |
| `chipRadius` | 10dp | 8dp | 芯片、小按钮 |
| `heroRadius` | 28dp | 20dp | 首屏大卡、底部导航浮岛 |
| `overlayRadius` | 32dp | 24dp | 浮层 / 抽屉 |
| `sectionRadius` | 28dp | 18dp | 设置页分区 |
| `hairline` | 1dp | 1dp | 发丝分隔 |

### 2.4 间距

`AppSpacing`（2 / 4 / 8 / 12 / 16 / 20 / 24 / 32dp）是**与皮肤无关**的栅格；皮肤只影响页面级节奏：

| 令牌 | 经典 | 石墨 | 用途 |
| --- | --- | --- | --- |
| `pagePad` | 20dp | 24dp | 页面左右留白 |
| `sectionGap` | 24dp | 28dp | 分区之间 |
| `cardPad` | 16dp | 18dp | 卡片内边距 |
| `headerInset` | 12dp | 10dp | 页眉与内容的首屏净空 |

### 2.5 材质

| 令牌 | 经典 | 石墨 | 说明 |
| --- | --- | --- | --- |
| `glassBlur` | 36dp | 20dp | Haze 主模糊半径 |
| `glassBlurCompact` | 24dp | 16dp | 紧凑浮板（底部导航等） |
| `glassNoise` | 0.04 | 0 | 玻璃噪点 |
| `glassSheen` | `true` | `false` | 顶部斜高光 |
| `navShadow` | 12dp | 0dp | 底部浮岛投影（石墨改用描边定形） |
| `sectionAccentAlpha` | 0.06 | 0.03 | 设置页分区底色的强调透明度 |
| `sectionBarWidth` | 4dp | 0dp | 分区左侧色条（石墨去掉色条，靠留白分组） |
| `roundIconActions` | `true` | `false` | 图标操作按钮是否圆形 |
| `backdropWash` | `false` | `true` | 页面底部是否叠一层品牌色晕 |

石墨玻璃的定位是「盖在内容上的一块实心板」：低模糊 + 零噪点 + 无高光 + 72% 底色，不假装透明薄膜。经典仍是薄膜。

### 2.6 动效

| 令牌 | 经典 | 石墨 |
| --- | --- | --- |
| `staggerMs` | 45ms | 24ms（列表入场错峰） |

时长与缓动曲线仍用 `DurationFast/Normal/Instant`、`EaseOut` 等公共令牌，不随皮肤变。`motionSettings().reduceMotion` 的降级在两套皮肤下都生效（入场位移归零）。

---

## 3. 新增第三套皮肤要改的文件

按改动顺序，前四步是必须的：

1. `ui/theme/ThemeSkin.kt`
   - `enum class ThemeSkin` 增加一项（`id` 用小写稳定串，会写进 DataStore，**以后不要改**）；
   - 新增 `XxxMetrics: AppMetrics`，逐项给出几何与材质值；
   - `metricsFor(skin)` 增加分支。
2. 新色板文件（可仿 `ui/theme/Graphite.kt` 单独建文件）
   - 四套 `AppColors`（浅 / 深 / 浅对比 / 深对比，后两者可以用 `.copy()` 覆盖差异项）；
   - `xxxLightScheme` / `xxxDarkScheme`；
   - 新 `Typography` 与 `Shapes`（若字体、形状与经典一致，可以直接复用，不必新建）。
3. `ui/theme/Theme.kt`
   - `resolveBasePalette` 的 `when`；
   - `schemeFor` 的 `when`（浅/深 × 普通/对比四分支）；
   - `Typography` / `Shapes` 的选择；
   - `glassTintPreview`（设置页玻璃样片的取色）。
4. `ui/theme/UiStyle.kt`
   - `shapesFor(style, skin)`：新皮肤若有独立形状，在这里优先返回。
5. 设置页的呈现（不改也能跑，但用户会看不到入口或样片不对）
   - `ui/settings/SettingsTheme.kt`：皮肤分区遍历 `ThemeSkin.entries`，新增枚举项会自动出现；若该文件里有按皮肤分支的说明文案，补一条。
   - `ui/settings/SettingsThemeSwatches.kt`：`SkinSwatch` 增加一种样片画法（现在只有 `GraphiteSwatchBody` / `LegacySwatchBody`）。
   - `ui/settings/SettingsDesign.kt`：`SettingsTone.Warm` 等按皮肤分流的取色（现在只有石墨特判）。
   - `ui/settings/SettingsHome.kt`：外观摘要文案（现在石墨显示「石墨」，其余显示配色名）。
6. `app/src/test/java/com/phoneagent/ui/theme/ThemeSkinTest.kt`
   - 默认皮肤、未知值回落、令牌抽样数值、四套色板的正文对比度 ≥ 4.5:1。

**不需要改**：`AppSettings`（存的是字符串）、`MainActivity`（只透传 `skinId`）、`ui/components/*`（读 `AppTheme.metrics`）、detekt 基线（除非你改动了已有函数签名，见第 5 节）。

---

## 4. 刻意保持固定的值

下列内容**不接主题色板**，都是踩过的判断，改动前先读理由：

| 位置 | 值 | 为什么固定 |
| --- | --- | --- |
| `ui/voice/VoiceRecordField.kt` | 手势阈值 5 / 44 / 96 / 112 / 60dp | 手感与物理距离（上滑取消、横滑、长按判定），跟版式栅格无关；换皮肤不该改变用户肌肉记忆 |
| 同上 | 记录/预览/取消三态光晕 `#5B8DEF` / `#3FBB8F` / `#E65C53`，波形白点、白色提示字 | 全屏录音 HUD，存在几百毫秒、叠在任意页面上，语义固定为「蓝=录音 / 绿=预览 / 红=取消」。石墨深色的主色是浅靛蓝，白字压上去会失去对比度 |
| `ui/debug/panels/StepShotPanel.kt` | 截图分割线 / 中点 `Color.White`，控件框 `#00BFA5` | 直接烧进 `Bitmap` 的标注层（`android.graphics`，拿不到 `MaterialTheme`），且必须压在任意截图内容上可见 |
| `ui/memory/MemoryGraphCanvas.kt` + `ui/theme/Color.kt` 记忆图谱配色 | 节点分类色，节点标签/内高光 `Color.White` | 分类数据色等同于图表色系：两套皮肤下同一类目保持同色。节点填充固定，白字对比度因此也固定 |
| `ui/settings/SettingsVisual.kt` | `CalibrationGuideBorder = #FFD600` | 校准标尺，需要在任意壁纸 / 任意主题下刺眼可见，与页面其余部分不是一套语言 |
| `ui/settings/SettingsComponents.kt` | 跑马灯 8 色色板 | 用户挑选的**内容色**（等同画笔颜料），本身就是被选的颜色，不是界面语义色 |
| `ui/components/Markdown.kt` | `INLINE_LINK_COLOR = #0E7C66` | 只是非 Composable 调用的兜底默认值；UI 侧（`MarkdownPreview` 三个调用点）显式传 `colorScheme.primary`，石墨下走不到这个常量 |
| `ui/settings/SettingsComponents.kt`、`ui/onboarding/OnboardingScreen.kt` | `RoundedCornerShape(50)` | 百分比圆角的药丸形，不是 dp 常量，换皮肤不需要变 |

---

## 5. 已知约束

- **detekt 基线按函数签名记录**。给已有函数加参数（例如给 `rich()` 加 `linkColor`）会让 `config/detekt/baseline.xml` 里的条目失配，报成新问题；改签名时要么同步基线，要么把新增分支挪进新函数并让原函数复杂度降到阈值以下（本次 `rich()` 采用后者：拆出 `flushRun` / `spanStyleFor` / `appendInlineLink`）。
- 旧主题的几何值锁定在 `LegacyMetrics`，与改造前逐项一致；新增令牌时经典值必须填原来的数字，否则旧主题会出现整体位移。
- 布局令牌只在 `graphite` 下改变；`AppSpacing` 的栅格两套皮肤共用。

---

## 6. 真机待验清单（均未验证）

以下项目**没有**在真机上确认过，模拟器结果不能替代：

1. **页眉玻璃**：Haze 在真实 GPU 上的模糊半径与滚动跟随；`-gpu swiftshader_indirect` 下模糊与渐变不可信。
2. **品牌光晕**：石墨页眉叠加的径向品牌色晕，浅色下是否发灰、深色下是否过曝，以及长时间开启的耗电。
3. **动效帧率**：列表入场（经典 45ms / 石墨 24ms 错峰）、浮板入场的实际掉帧情况。
4. **API < 31 降级**：没有 `RenderEffect` 时走 `fallbackTint`，需确认页眉与浮岛在没有模糊的情况下仍可读、边界仍清楚。
5. **字体缩放**：1.3× 已在模拟器核对；2.0×（系统最大档）下页眉标题、胶囊按钮与设置行是否截断尚未看。

已跑过的验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest --tests "com.phoneagent.ui.*"`、默认 `:app:assembleDebug`（产物仍只含 `arm64-v8a`）。x86_64 变体只在显式传 `-PincludeX86_64=true` 时产出，仅用于模拟器视觉核对。
