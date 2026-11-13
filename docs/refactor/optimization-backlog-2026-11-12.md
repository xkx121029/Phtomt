# 可优化 / 可重构点清单（2026-11-12）

> 范围：`app/`、`provider/`、`env/` 及构建脚本。所有数字均为当次在工作区实测，
> 采集命令附在每条结论后，便于复核与验收。
>
> 本文只列**有证据支撑**的问题，不写风格偏好。每条给出：现象 → 证据 → 建议动作 → 验收标准 → 风险。

---

## 0. 全局判断

项目工程质量总体是**高于同类个人项目**的：单一版本号真值、基线只减不增、资产指纹闸门、
唯一判据（意图转译 / 通道选择 / 视觉分流）都做得比大多数商业项目更严谨。

真正的问题集中在三处，且互相纠缠：

| 根问题 | 表现 | 影响 |
|---|---|---|
| **规模失控的少数文件** | 5 个文件 ≥1500 行，最大 3286 行 | 改动风险高、无法并行开发、Review 失效 |
| **门面类靠纯委托撑起体量** | `AgentEngine` 3256 行中 76 处是 `fun x = y.z()` 纯转发 | 文件大 ≠ 逻辑多，真实复杂度被掩盖，且这层没有测试价值 |
| **测试覆盖与代码重要性倒挂** | 1189 个 `@Test`，但 5 个最大文件全部无对应单测 | 最该被保护的地方恰恰没有安全网 |

**因此优先级排序不是「先清 detekt」，而是「先给最重要的大文件建立安全网，再拆」。**
在无测试的情况下直接拆 3000 行文件，是最容易把项目拆坏的做法。

---

## 1. P0：测试安全网（拆任何大文件之前必须做）

### 1.1 五个最大文件全部无单测

| 文件 | 行数 | 函数数 | 有单测 |
|---|---|---|---|
| [FloatingWindowService.kt](../../app/src/main/java/com/phoneagent/overlay/FloatingWindowService.kt) | 3286 | 93 | 否 |
| [AgentEngine.kt](../../app/src/main/java/com/phoneagent/engine/AgentEngine.kt) | 3256 | 134 | 否（仅 `AgentEngineHeadlessTest` 覆盖主循环壳） |
| [DeviceOpsChannel.kt](../../app/src/main/java/com/phoneagent/device/ops/DeviceOpsChannel.kt) | 1793 | 73 | 否 |
| [ExecutionCore.kt](../../app/src/main/java/com/phoneagent/engine/execution/ExecutionCore.kt) | 1640 | 49 | 否 |
| [AiClient.kt](../../app/src/main/java/com/phoneagent/core/ai/AiClient.kt) | 1506 | 48 | 否 |

采集方式：

```powershell
# 行数 ≥700 的文件，逐个比对 src/test 下是否有同名 Test 类
Get-ChildItem app\src\main\java -Recurse -Filter *.kt |
  Where-Object { ([IO.File]::ReadAllLines($_.FullName)).Count -ge 700 } |
  ForEach-Object { '{0} {1}' -f $_.BaseName, ([IO.File]::ReadAllLines($_.FullName)).Count }
```

对比：`ExecutionPhase.kt`(842) / `IntentTranslator.kt`(918) / `HtmlToMarkdown.kt`(1299) 都有测试，
说明团队**有能力**写这类测试，只是这几个文件还没补。

**建议动作**（按性价比排序）：

1. `AiClient` 最值得先补 —— 它是纯 HTTP + JSON 解析，`OkHttp` 可用 `MockWebServer` 拦截，
   无 Android 运行时依赖。覆盖点：SSE 分帧、`extractBalancedJson`、`parseAgentIntent` 容错、重试退避。
2. `DeviceOpsChannel` 次之 —— 但它是 `suspend fun run(op)` 单入口 + 14 个 when 分支，
   依赖 `Shizuku`/`adb` 返回值。可先抽出「命令构造」为纯函数再测（见 2.2）。
3. `FloatingWindowService` / `ExecutionCore` 依赖 `Context`/无障碍服务，纯 JVM 不可测。
   这两处**不要试图补单测**，应走「先拆出纯逻辑，再测拆出来的部分」。

**验收**：`AiClient` 新增测试覆盖 SSE 分帧与 JSON 容错两类失败路径，`./gradlew :app:testDebugUnitTest` 通过。

**风险**：低。纯新增测试，不改生产代码。

---

### 1.2 覆盖率只出报告、不设阈值

CI 里 `createDebugUnitTestCoverageReport` 只上传 artifact，没有 `jacocoTestCoverageVerification`。
配合 1.1 的现状，覆盖率数字会长期停在低位且无人负责。

**建议动作**：不设全局阈值（大量逻辑必须真机跑，全局阈值只会逼人造假）。
改为**按包设阈值**，只对纯逻辑包生效：

| 包 | 建议阈值 | 理由 |
|---|---|---|
| `domain/rules` | 70% | 纯函数，无 Android 依赖 |
| `engine/execution`（转译与规则部分） | 60% | 已有 16 个测试文件打底 |
| `core/text` | 60% | `HtmlToMarkdown` 已测 |
| 其余 | 不设 | 依赖运行时，强设阈值只会产生无意义测试 |

**验收**：`./gradlew :app:jacocoTestCoverageVerification` 可在低于阈值时失败。

**风险**：中。需要在 `build.gradle.kts` 里配 `violationRules`，且首次可能因现状不达标而失败，
建议先跑一次记录基线值，再设成「不低于当前值」。

---

## 2. P1：结构性问题（先拆这三处，收益最大）

### 2.1 `AgentEngine`：3256 行里有一层纯转发门面

实测证据：

```powershell
$p='app\src\main\java\com\phoneagent\engine\AgentEngine.kt'
# 纯委托函数：fun x(...) = delegate.y(...)
([regex]::Matches([IO.File]::ReadAllText($p),'(?m)^\s{4}(?:private |internal )?(?:suspend )?fun \w+\([^)]*\)\s*(?::\s*[^=\n]+)?=\s*\w+\.\w+\(')).Count
# => 76

# StateFlow 转发属性：val x: StateFlow<T> get() = _x.asStateFlow()
([regex]::Matches([IO.File]::ReadAllText($p),'(?m)^\s{4}val \w+.*get\(\)\s*=')).Count
# => 22
```

也就是说 **3256 行里约 98 处是「把已外迁的协作者再转发一遍」**，真正的编排逻辑主要在
`engine/loop/`（`StepRunner` / `ObservationPhase` / `DecisionPhase` / `ExecutionPhase`）。
阶段 A→B 的重构（见 [phase-b-step-runner.md](phase-b-step-runner.md)）已经把逻辑搬走了，
但外层的门面没有随之收窄。

典型链条（三层转发，零新增语义）：

```
AgentEngine.isMostlyChinese  →  AiTools.isMostlyChinese  →  EngineRules.isMostlyChinese
```

**建议动作**（分两步，不要一步到位）：

1. **先删转发**：把 `AiTools.isMostlyChinese` 这类「只是转发到 domain」的中间层删掉，
   调用方直接用 `EngineRules.isMostlyChinese`。`AiTools` 里 60 行处仅此一行是纯转发。
2. **再收窄门面**：`AgentEngine` 保留「主循环入口 + 状态暴露」，其余 76 个纯转发函数
   由调用方直接持有对应协作者（`uiBridge` / `telemetry` / `lifecycle` / `planningFlow`）。
   UI 层通过 Koin 注入协作者，而不是全走 `AgentEngine`。

   > 注意 `AiTools` 本身只有 93 行、仅 1 处纯转发（`isMostlyChinese`），它不是一个需要拆分的大类，
   > 只是它恰好构成了上面那条三层链条的中间一环。

**验收**：

- `AgentEngine` 行数降到 1500 以下；
- 纯委托函数计数 `= 0`（用上面那条命令验证）；
- `./gradlew :app:testDebugUnitTest` 全绿；
- 真机跑一遍 L1–L5 冒烟（主循环无回归）。

**风险**：中高。改动面广，但**每步都是机械替换**，风险可控。
必须在 1.1 的 `AgentEngineHeadlessTest` 就绪后再做。

---

### 2.2 `DeviceOpsChannel`：1793 行是 14 个领域混在一个 when 里

实测 `run()` 入口只有 21 行，但分发到 14 个分支，每个分支背后是一个完整领域：

| 领域 | 方法 | 行数 |
|---|---|---|
| 文件读写 | `fileRead` / `fileWrite` | 101 + 51 |
| 闹钟 | `alarmManage` / `updateAlarm` / `queryAlarms` | 96 + ~20 + ~25 |
| 日历 | `calendarList/Create/Update/Delete` | 40+40+51+20 |
| 虚拟屏 | `virtualDisplayOp` / `presentVirtual` | 83 + 26 |
| 设置写入 | `settingsOp` / `systemToggle` / `writeSystemSetting` | 56 + 70 + 22 |
| 亮度/勿扰/超时/旋转/深色 | 各自一个 suspend fun | 46–59 每个 |
| 剪贴板 / 内存 / 应用管理 / 终端 / 个人数据 | 各一个 | 47–52 每个 |

这 14 个领域**唯一的共同点**是「都用 shell 命令实现」，但命令构造、返回值解析、错误文案全在各自分支里。

**建议动作**：

1. 按领域拆成 `device/ops/file/`、`device/ops/clock/`、`device/ops/calendar/`、`device/ops/settings/`、
   `device/ops/app/` 等子包，每个子包一个类，`DeviceOpsChannel` 只保留 `run()` 分发。
2. 每个子类拆出**纯函数**：`buildCommand(op, args): String` 与 `parseOutput(raw): OpsResult`。
   这两层不依赖 Android，可以直接单测——**这是把 DeviceOps 从「不可测」变成「可测」的关键一步**。
3. 命令构造集中后，`ShellRules` 的通道决策才有唯一落点（目前命令字符串散落在 14 个分支里）。

**验收**：

- `DeviceOpsChannel.kt` 降到 300 行以内（仅分发 + 委托）；
- 每个子包都有 `buildCommand` / `parseOutput` 的单测；
- 真机验证：文件读写、闹钟、日历、设置开关各一条。

**风险**：中。`suspend` + `shell` 调用不便 mock，但「先抽纯函数再搬壳」能把风险压到最低。

---

### 2.3 `FloatingWindowService`：单个类 93 个函数，且状态字段堆在顶部

实测：93 个函数、106 个类级字段、3 个嵌套类型、0 个 `@Composable`（纯 View 系统实现）。

文件内已有 6 处 `// ====================` 区块注释，说明作者自己也感觉到了职责边界：

```
任务完成卡片（独立底部窗口）
降级提示细条（引擎 → 浮层）
降级提示细条        ← 与上一处重名，实际是两个不同阶段（声明 vs 实现）
拖动物理效果
拖动（仅头部）：点击展开 / 拖动移位 / 松手归位
对外更新
```

注意「降级提示细条」出现两次（330 行与 736 行），不是重复代码，而是
**字段声明区与实现区被其它内容隔开了 400 行**——这是典型的「一个文件里塞了多个窗口」。

**建议动作**：按窗口拆成独立控制器，`FloatingWindowService` 只做 `LifecycleService` 的壳：

| 拆出 | 内容 | 预估行数 |
|---|---|---|
| `NoticeBarController` | 降级提示细条的建窗/更新/退场/截图隐藏 | ~450 |
| `TaskCardController` | 任务完成卡片 | ~400 |
| `MarqueeController` | 跑马灯胶囊 + 底部偏移计算 | ~500 |
| `DragController` | 拖动物理、吸附、点击展开 | ~250 |
| `CaptureCoordinator` | 截图时隐藏/恢复所有悬浮窗的协调 | ~200 |

**验收**：

- `FloatingWindowService.kt` 降到 500 行以内；
- 每个 Controller 独立文件，通过构造参数接收 `WindowManager` 与 `handler`；
- 真机验证：任务跑一遍，观察细条、卡片、跑马灯、拖动、截图隐藏五条路径。

**风险**：中高。窗口生命周期与 `WindowManager` 强耦合，容易出现「拆完窗口泄漏」。
建议**第一个只拆 `NoticeBarController`**（边界最清晰、状态最少），验证模式可行后再继续。

---

## 3. P2：可测性缺陷（小改动、高回报）

### 3.1 `domain` 层混入 Android 依赖

`domain` 被定义为「纯领域模型与规则（无 Android 依赖）」，但实测有 2 处违反：

| 文件 | 违规 import | 用途 |
|---|---|---|
| [AppPageIndex.kt](../../app/src/main/java/com/phoneagent/domain/model/AppPageIndex.kt#L3) | `android.provider.Settings` | 用 `Settings.ACTION_WIFI_SETTINGS` 等常量做深链 |
| [DebugModels.kt](../../app/src/main/java/com/phoneagent/domain/model/DebugModels.kt#L3) | `android.graphics.Bitmap` | 调试模型持有截图 |

```powershell
Select-String -Path app\src\main\java\com\phoneagent\domain\*.kt,
  app\src\main\java\com\phoneagent\domain\model\*.kt,
  app\src\main\java\com\phoneagent\domain\rules\*.kt -Pattern '^import android'
# => 2 处
```

影响：`domain` 层的单测无法在纯 JVM 跑（`Bitmap` 在单测里是 stub，任何方法调用都抛异常）。

**建议动作**：

- `AppPageIndex`：把 `Settings.ACTION_*` 常量值直接内联为字符串字面量
  （`"android.settings.WIFI_SETTINGS"`），这些值是 Android 平台常量，不会变。
- `DebugModels`：`Bitmap` 改为 `Any?` 或抽出 `ScreenshotRef` 接口，让 domain 不认识 `Bitmap`。

**验收**：`domain` 包下 `^import android` 计数为 0；`domain` 单测可在无 Robolectric 下运行。

**风险**：低。`AppPageIndex` 只改字面量；`DebugModels` 需检查调用方（仅 debug 用）。

---

### 3.2 意图与动作词表重叠 18 项，且 ALIAS 有 3 处自映射

实测两个词表的字面量交集：

```powershell
# IntentType 65 项，ActionType 38 项，交集 18 项：
# open, tap, long_press, swipe, wait, scroll_to, write_doc, remember, device_query,
# say, show_agent, set_alarm, set_timer, wait_for, back, home, refresh, shell
```

设计上这是**有意为之**（AI 说意图，端侧落到动作），但带来一个具体缺陷：
[AgentAction.kt](../../app/src/main/java/com/phoneagent/domain/model/AgentAction.kt#L178-L187) 的 `ALIAS` 共 **8 项，其中 3 项是自映射**：

```kotlin
KEY    to KEY,
LAUNCH to LAUNCH,
OPEN   to OPEN,
```

**自映射是无意义项**（`map[KEY] = KEY`）。它不会报错，但会让人误以为这三个 action 曾改过名，
或误以为 `ALIAS` 是「全量映射表」而不是「仅别名表」。

**建议动作**：删掉这 3 条自映射，只保留真正的别名（`TAP to CLICK`、`LONG_PRESS to LONG_CLICK`）。
补一行注释说明「ALIAS 只放改过名的，不是全量表」。

**验收**：`ALIAS` 中不存在 `k to k` 的项，条目数从 8 降到 5；
确认没有调用方依赖「遍历 `ALIAS` 即得到全量动作」。

**风险**：极低。

---

### 3.3 文件名与主类型不一致（2 处，detekt 也报了）

detekt 实测报 `MatchingDeclarationName` 2 条。具体情况：

| 文件名 | 实际主类型 | 问题 |
|---|---|---|
| `PhantomModels.kt` | `TaskPlan` / `TaskStep` / `StepRecord` / `ExecutionResult` / `RecoveryResult` | 「Phantom」这个词在代码里无任何对应物，是历史遗留名 |
| `ClockFallback.kt` | `ClockFallbackKind` / `ClockCapabilities` / `ClockLevel` / `ClockFallbackPolicy` / `ClockVerification` / `ClockOutcome` | 一个 831 行文件装 6 个公共类型 |

**建议动作**：`PhantomModels.kt` 按内容改名（如 `ExecutionModels.kt`）。
`ClockFallback.kt` 保持现有命名可接受（多类型同文件在 Kotlin 里常见），但 831 行建议按
「策略 / 校验 / 结果」拆分。

**验收**：`PhantomModels.kt` 不再存在；detekt `MatchingDeclarationName` 计数为 0。

**风险**：低（纯改名，IDE 可自动重构）。

---

## 4. P3：存量债务清理（机械、可分批）

### 4.1 detekt 实际剩余 180 条（不是 CI 注释里的 515）

CI 注释 [android-ci.yml#L106-L109](../../.github/workflows/android-ci.yml#L106-L109) 写着
「当前还有 515 条存量问题（MagicNumber 193、MaxLineLength 122、ReturnCount 61…）」，
但本地 `app/build/reports/detekt/detekt.xml` 实测只有 **180 条**：

| 规则 | 实际条数 |
|---|---|
| MagicNumber | 56 |
| ReturnCount | 28 |
| MaxLineLength | 25 |
| CyclomaticComplexMethod | 20 |
| LongMethod | 18 |
| LongParameterList | 16 |
| UnusedParameter | 5 |
| TooManyFunctions | 5 |
| 其余 6 类 | 各 1–2 |

最集中的文件（detekt XML 实测）：

| 文件 | 条数 |
|---|---|
| `ClockFallback.kt` | 18 |
| `AgentPrompts.kt` | 14 |
| `DeviceOpsChannel.kt` | 14 |
| `SettingsVisual.kt` | 13 |
| `SettingsComponents.kt` | 9 |
| `AgentEngine.kt` | 7 |
| `TaskDocRepository.kt` | 7 |
| `AiClient.kt` | 7 |

**建议动作**：先修正 CI 注释里的过时数字（515 → 180），否则下一个人会按错误基数规划。
然后按「文件」而不是「规则」清理——`ClockFallback` + `SettingsVisual` 两个文件就占 31 条。

**验收**：CI 注释与实测一致；清理后 `continue-on-error` 移除，`:app:detekt` 成为阻断闸门。

**风险**：低。

---

### 4.2 基线条目的 59% 是两类低价值规则

`config/detekt/baseline.xml` 共 1543 条，其中：

| 规则 | 条数 | 占比 |
|---|---|---|
| MaxLineLength | 482 | 31% |
| MagicNumber | 433 | 28% |

这两类合计 915 条（59%），且 `detekt.yml` 里已经把 `MagicNumber.ignoreNumbers` 放宽到
`['-1','0','1','2']`、`ignorePropertyDeclaration: true`、`ignoreAnnotated: ['Composable']`。

**问题**：基线数字看起来很大（1543），但真实结构性问题（`LargeClass` 5 条、
`CyclomaticComplexMethod` 76 条、`LongMethod` 110 条）只占约 12%。
用总数做「只减不增」的守卫，会让**修一条 `LargeClass` 和修一条超长行的价值被等同对待**。

**建议动作**：不改守卫机制（它工作正常），但在 `baseline-count.txt` 旁增加一个
**分类计数文件**（如 `baseline-breakdown.txt`），记录每类规则的条数。
这样「基线从 1543 降到 1400」能看出是清了噪音还是清了结构问题。

**验收**：新增分类计数并能被 `checkDetektBaseline` 校验一致性。

**风险**：低。

---

### 4.3 74 处宽泛异常捕获

```powershell
# Kotlin 语法：catch (e: Exception) / catch (e: Throwable)
# => 74 处
Get-ChildItem app\src\main\java -Recurse -Filter *.kt |
  Select-String -Pattern 'catch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)'
```

detekt 只报了 23 条 `TooGenericExceptionCaught`（其余在基线里）。
宽泛捕获本身在这个项目里**部分是合理的**——设备通道调用（Shizuku/adb/无障碍）的失败形态
确实难以枚举，必须兜住。

**Kotlin 空 catch 实测为 0 处。** 用 `catch\s*\([^)]*\)\s*\{\s*\}` 能搜到 6 处，
但全部是 `.kt` 文件里内嵌的 **JavaScript** 字符串（`BrowserJs.kt`、`InteractScripts.kt`、
`ReadScript.kt` 的注入脚本），不是 Kotlin 代码，不应整改。

**建议动作**：不做全局整改。保持现状，但要求新代码在 catch 里至少写一条 `AppLog`。

**验收**：不适用（无实际缺陷）。

**风险**：无。

---

### 4.4 `feature/voice` 是空目录

`app/src/main/java/com/phoneagent/feature/voice/` 下 0 个文件，
真正的语音实现位于 `ui/voice/`（`VoiceDemoActivity` / `VoiceRecordEngine` / `VoiceRecordField`）
与 `feature/assistant/HpaVoiceInteractionService.kt`。

这会导致按包名找代码的人白跑一趟。

**建议动作**：删除空目录。

**验收**：`feature/voice` 不存在。

**风险**：无。

---

## 5. 不建议动的部分（避免过度重构）

以下是看过代码后判断**不应重构**的地方，列出来是为了防止后来者「为了统一而统一」：

| 对象 | 为什么不动 |
|---|---|
| `PromptCatalog` / `PromptBodies`（60 区块、963 行） | 正文即数据，拆开只会增加装配复杂度。`sep` 是逐字节算出来的，动了就改提示词 |
| `CapabilitySpec.kt`（812 行、65 个意图声明） | 声明式注册表，平铺是最好的形态。拆成 5 个文件反而找不到全貌 |
| `IntentTranslator`（918 行、策略表） | 已有单测，且「一张表映射全部意图」是它的核心价值 |
| `GameRules`/`EngineRules` 等 domain 规则 | 纯函数、已测、无副作用 |
| `third_party/shizuku` 54 个文件 | vendor 代码，改了就失去与上游对比的能力 |
| `provider/`(29 文件) / `env/`(4 文件) | 规模合理，且是独立 APK，边界天然清晰 |

---

## 6. 建议执行顺序

```
第 1 步：AiClient 单测（1.1）                    —— 纯新增，零风险，打底
第 2 步：domain 去 Android 依赖（3.1）+ 小清理（3.2/3.3/4.4）
第 3 步：修正 CI 注释数字（4.1），让债务基数可信
第 4 步：DeviceOpsChannel 抽纯函数 + 拆子包（2.2） —— 收益最大且可测
第 5 步：AgentEngine 删转发层（2.1）              —— 依赖第 1 步的安全网
第 6 步：FloatingWindowService 拆第一个 Controller（2.3）
第 7 步：覆盖率阈值（1.2）+ 基线分类计数（4.2）
```

**每一步都可以独立提交、独立验证、独立回滚。** 不要合并成一个大 PR。

---

## 7. 验收总表

| 指标 | 当前 | 目标 |
|---|---|---|
| 行数 ≥1500 的文件 | 5 个 | ≤2 个 |
| 最大文件行数 | 3286 | ≤1500 |
| `AgentEngine` 纯委托函数 | 76 | 0 |
| `domain` 层 `import android` | 2 | 0 |
| detekt 实际剩余 | 180 | ≤50（并把 `continue-on-error` 去掉） |
| `ALIAS` 自映射 | 3 | 0 |
| 空目录 | 1 | 0 |
| 覆盖率阈值 | 无 | 3 个纯逻辑包有阈值 |

---

## 附：证据采集脚本

全部结论可用以下命令复核（PowerShell，仓库根执行）：

```powershell
# 1. 各模块规模
Get-ChildItem app\src\main\java\com\phoneagent -Recurse -Filter *.kt |
  Where-Object { $_.FullName -notmatch '\\build\\' } |
  ForEach-Object { [pscustomobject]@{L=([IO.File]::ReadAllLines($_.FullName)).Count; P=$_.FullName} } |
  Sort-Object L -Descending | Select-Object -First 20

# 2. AgentEngine 纯委托
([regex]::Matches([IO.File]::ReadAllText('app\src\main\java\com\phoneagent\engine\AgentEngine.kt'),
  '(?m)^\s{4}(?:private |internal )?(?:suspend )?fun \w+\([^)]*\)\s*(?::\s*[^=\n]+)?=\s*\w+\.\w+\(')).Count

# 3. domain 层 Android 依赖
Select-String -Path app\src\main\java\com\phoneagent\domain\*.kt,
  app\src\main\java\com\phoneagent\domain\model\*.kt,
  app\src\main\java\com\phoneagent\domain\rules\*.kt -Pattern '^import android'

# 4. 基线规则分布
(Select-String -Path config\detekt\baseline.xml -Pattern '<ID>([^<]+)</ID>' -AllMatches).Matches |
  ForEach-Object { ($_.Groups[1].Value -split ':')[0] } | Group-Object |
  Sort-Object Count -Descending | Select-Object -First 15

# 5. 本地 detekt 实际问题分布
(Select-String -Path app\build\reports\detekt\detekt.xml -Pattern 'source="detekt\.([A-Za-z]+)"' -AllMatches).Matches |
  ForEach-Object { $_.Groups[1].Value } | Group-Object | Sort-Object Count -Descending

# 6. 空 catch
Get-ChildItem app\src\main\java -Recurse -Filter *.kt |
  Select-String -Pattern 'catch\s*\([^)]*\)\s*\{\s*\}' -AllMatches
```
