# AgentEngine 主循环重构（阶段 B）：StepOutcome 契约与三段外迁

本文记录「把 700 余行内联主循环拆成 观察 / 决策 / 执行 三个阶段 + 循环壳」这一轮重构的
阶段划分、契约设计、迁移理由与行为等价依据。目标是让后来人能回答三个问题：

1. 为什么主循环要这么拆（而不是留在 AgentEngine 里）；
2. 每一段代码现在在哪、边界在哪；
3. 凭什么判断「拆完之后行为没变」。

> 阶段 D（UI 玻璃视觉语言相关调整）不在本轮范围内，UI 代码本轮零改动。

---

## 1. 重构前的问题

`AgentEngine.runInner` 里原先是一个约 770 行的 `while`，把「读屏 → 本地/云端决策 → 转译 → 执行 →
验证重试 → 留档 → 收尾」全部内联。它带来三个具体问题：

| 问题 | 表现 |
|---|---|
| **控制流二义** | 同一个 `continue` 既表示「本步结束」，也表示「本步不计步、重试」；同一个 `return` 既表示「任务完成/放弃」，也表示「护栏失败收尾」。源码只差一个关键字，含义完全不同，是最容易改错的地方 |
| **无法回归** | 主循环强依赖 `Context` / 无障碍服务 / MediaProjection 等 Android 运行时，纯 JVM 单测构造不出引擎，任何主循环改动只能靠真机手测 |
| **状态散落** | 跨步状态（步数、连续失败计数、无障碍在线标记、最近快照）由 20 多个 `var` 分散持有，谁在什么时机改它们没有单一入口 |

因此本轮重构的验收标准是：**主循环的每个分支都能在 headless（纯 JVM）环境被断言**，
并且「本步之后循环怎么走」只有一个显式答案。

---

## 2. 阶段划分与落点

| 阶段 | 内容 | 落点 | 状态 |
|---|---|---|---|
| **A** | 运行时协作者抽离：状态仓、UI 桥、任务生命周期/收尾、遥测、护栏、步骤文案、执行反馈 | `engine/runtime/`（`EngineStore`、`UiBridge`、`TaskLifecycle`、`TaskFinalizer`、`TelemetryRecorder`、`LoopGuard`、`StepTexts`、`ExecutionFeedback`、`EngineLimits`、`UserGate`） | 完成 |
| **B0** | 测试基建：引擎可在 headless 构造；阶段依赖改为可注入回调 | `EngineStore` 注入、`engine/runtime/AgentEngineHeadlessTest` | 完成 |
| **B1** | 观察配套下沉：标注 + semanticId 回填 + 截图决策/复用 | `engine/runtime/StepObserver` | 完成 |
| **B2** | 契约与循环壳：`StepOutcome` / `StepSession` / `StepPhase` / `TaskRunner`，观察段外迁 | `engine/loop/`（`StepOutcome`、`StepSession`、`StepRunner`、`TaskRunner`、`ObservationPhase`） | 完成 |
| **B3** | 决策段外迁：宏注入 / 端侧优先 / 云端决策 / 技能归一化 / 动作模式门控 | `engine/loop/DecisionPhase` | 完成 |
| **B4** | 执行段外迁：浏览器分流 / 转译补参 / 执行与验证重试 / 留档回注 / 完成与放弃收尾 | `engine/loop/ExecutionPhase` | 完成 |
| **收尾** | `TaskRunner` + `StepRunner` 接管 `runInner`；清理迁移后失效的 import 与构造参数 | `AgentEngine.runInner` | 完成 |

主循环最终形态（`AgentEngine.runInner` 尾部）：

```kotlin
val stepRunner = StepRunner(
    observe = StepPhase { session ->
        val outcome = observationPhase.run(session)
        if (outcome == StepOutcome.Continue) {
            a11ySeenAlive = session.a11ySeenAlive
            lastSnapshot = session.snapshot
        }
        outcome
    },
    decide = decisionPhase,
    execute = executionPhase,
)
taskRunner.run(maxSteps) { s ->
    val session = StepSession(task, plan, s, settingsVal, lang, messages)
        .also { it.a11ySeenAlive = a11ySeenAlive }
    stepRunner.run(session)
}
```

即：`TaskRunner` 只负责「什么时候跑下一步、这一步之后怎么走」；`StepRunner` 只负责
「一轮里三段怎么串、什么时候短路」；三段各自只负责自己的业务。

---

## 3. `StepOutcome` 契约

```kotlin
sealed interface StepOutcome {
    data object Continue : StepOutcome                    // 本步已处理完，进入下一轮
    data object Return   : StepOutcome                    // 退出循环，任务终态已由阶段落定
    data class  Retry(val delayMs: Long = 0) : StepOutcome // 本步不计步：回退步数 + 等待后进入下一轮
    data class  Fatal(
        val reason: String,
        val failureKind: AgentState.FailureKind = UNKNOWN,
        val taskMemoryNote: String = "",
    ) : StepOutcome                                       // 护栏失败：置任务记忆失败 + 停止引擎
}
```

### 3.1 为什么用返回值而不是异常

- 这四种结果都是**预期内的控制流**（广告已关闭、敏感页等待、网络抖动、护栏触发），
  不是 bug；用异常表达会污染调用栈、诱导统一 catch，也让「预期分支」和「真异常」混为一谈。
- 异常无法携带「不计步」「任务记忆写什么结论」这类业务信息，只能靠副作用传递。
- 返回值让每个阶段的分支都能被单测直接断言 `assertEquals(StepOutcome.Continue, ...)`。

### 3.2 为什么 `Retry` 要带 `delayMs`

唯一来源是云端决策链路异常：原先循环里手写 `step--; delay(1500); continue`。
把「不计步」收进类型后，读者不再需要盯着 `step--` 才能确认这一步没消耗配额；
`delayMs` 让等待时长跟着结果走，而不是藏在循环壳里。

### 3.3 为什么 `Fatal` 不带 UI 文案

不同护栏的文案、是否弹悬浮窗、是否写协作面板并不一致（例如「敏感页接管超限」只写日志，
「无障碍掉线」还要写协作面板与 `needsUser`）。这些语境只有触发阶段知道。
约定：**阶段负责把界面写成它想要的样子并写日志**，`Fatal` 只携带诊断信息
（`reason` / `failureKind`）与任务记忆结论（`taskMemoryNote`）。
循环壳只做机械动作：置任务记忆失败 → `stop()`。

### 3.4 `StepSession` 的字段归属

阶段之间不共享局部变量，产物统一放会话：

| 字段 | 写入方 | 用途 |
|---|---|---|
| `task` / `plan` / `step` / `settingsVal` / `lang` / `messages` | 构造时 | 整轮不变；`messages` 是任务级对话历史，执行段追加回注 |
| `a11ySeenAlive` | 观察段（跨步存活，构造时由引擎回填） | 识别「任务中途掉线」 |
| `snapshot` / `annotated` / `semanticSnapshot` / `screenshot` / `pageMemoryHint` | 观察段 | 决策与执行复用，避免重复读屏/重复截图 |
| `intent` / `fromLocal` | 决策段 | 执行段据此转译与执行 |

### 3.5 `TaskRunner` 的循环语义（逐条等价）

```kotlin
while (maxSteps <= 0 || step < maxSteps) {
    awaitResume()                  // 搁置期间不白耗步数
    if (!isActive()) return step   // 用户取消：直接返回，不算「达到最大步数」
    step++
    onStepStart(step)
    when (val o = runStep(step)) {
        Continue -> Unit
        Return   -> return step
        Retry    -> { step--; onStepRolledBack(step); if (o.delayMs > 0) delay(o.delayMs) }
        Fatal    -> { onFatal(o); return step }
    }
}
onMaxSteps()
```

对应迁移前的四处细节：取消检查在 `step++` 之前；`Retry` 回退步数后再等待；
`Fatal` 立即返回、不落最大步数收尾；`maxSteps <= 0` 表示不限。

---

## 4. 可注入边界：hooks 而不是 AgentEngine 引用

三段全部是 `internal class ... : StepPhase`，与 `AgentEngine` 之间只通过**分组回调类**通信：

| 阶段 | 回调类 | 代表出口 |
|---|---|---|
| 观察 | `ObservationHooks` | 日志、悬浮窗、协作面板、`needsUser`、等待用户提示 |
| 决策 | `DecisionHooks` + `MacroRelay` | 日志、决策/模式/技能三类失败状态、页面记忆、宏接力 |
| 执行 | `ExecutionUiHooks` / `RecordHooks` / `DeviceHooks` / `FinishHooks` / `Counters` | 状态流、留档、转译补参、执行、确认门控、收尾 |

设计理由：

1. **避免循环引用**：阶段若直接持有 `AgentEngine`，引擎与阶段互相依赖，`internal` 边界失效，
   也无法在测试里只构造阶段。
2. **Android 依赖被挤到引擎侧的 lambda**：`readScreen`、`clickPoint`、`execute`、`reobserve`
   这些真正触碰无障碍/截屏/注入的调用，仍然是引擎提供的 lambda；阶段只调接口。
   于是阶段可以在纯 JVM 里用假实现跑通全部分支。
3. **状态收口**：步数、连续失败计数、敏感页接管计数等跨步状态由引擎任务态持有，
   阶段通过 `() -> Int` / `(Int) -> Unit` 读写，不在阶段内部另存一份（避免双份计数漂移）。

---

## 5. 测试基建

### 5.1 为什么不用 Robolectric

本项目的构建是**离线**的（`--offline`），本地 Gradle 缓存中没有 `org.robolectric` 与
`androidx.test` 构件，无法在没有网络的前提下引入。强行引入会让整个构建链在离线环境失败。

因此采用等价效果的另一条路径：**把 Android 依赖抽象成可注入接口**（见第 4 节），
并用 `mockk(relaxed = true)` + 假回调构造真实引擎：

- `AgentEngineHeadlessTest` 用 mock 的 `Context` / `AppSettings` / `AiClient` / `MemoryStore`
  直接 `AgentEngine(...)`，断言「引擎能在纯 JVM 构造、初始状态为空闲、空设置流不阻塞构造」。
- 阶段测试（`ObservationPhaseTest` / `DecisionPhaseTest` / `ExecutionPhaseTest`）直接构造阶段，
  用 Fake 回调记录所有出口调用，断言返回值与副作用。

### 5.2 测试矩阵

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `loop/TaskRunnerTest` | 6 | 取消、搁置、回退步数、`Fatal`、最大步数、`maxSteps<=0` |
| `loop/StepRunnerTest` | 4 | 三段短路顺序 |
| `loop/ObservationPhaseTest` | 6 | 广告自动关闭、敏感页接管超限、敏感页等待、无障碍掉线、副屏模式、页面记忆开关 |
| `loop/DecisionPhaseTest` | 8 | 端侧命中不调云端、云端异常 Retry、连续异常 Fatal、空决策 Fatal、技能归一化、动作模式拒绝、连续拒绝 Fatal、宏注入 |
| `loop/ExecutionPhaseTest` | 13 | 浏览器分流、提前完成拦截、完成达标、放弃失败口径、REMEMBER、SAY 超限、自写命令被拒、不可逆取消、执行成功留档、结构错误不重试、连续失败用户离开、用户指导后执行、转译失败回注 |
| `runtime/AgentEngineHeadlessTest` | 2 | headless 构造与初始状态 |

---

## 6. 行为等价依据

| 关键分支 | 迁移前 | 迁移后 | 保障 |
|---|---|---|---|
| 广告自动关闭被护栏拦下 | 不剔除广告内容，把真实页面交给 AI | 同左 | `ObservationPhaseTest`：护栏命中时只写 WARN 日志 |
| 敏感页连续接管超限 | 结束任务（失败） | `Fatal` | `ObservationPhaseTest`：`streak > MAX` → Fatal |
| 敏感页等待用户 | `continue`，本步入库 | `Continue` | 同文件：用户处理后继续 |
| 页面记忆采集时机 | 敏感页判定之后 | 同左（顺序在代码里显式） | 同文件：开关关闭时不采集 |
| 任务中途无障碍掉线 | 引导用户，离开即失败 | `Fatal` | 同文件；副屏模式例外 |
| 云端决策异常 | `step--` + `delay(1500)` + `continue` | `Retry(1500)` | `DecisionPhaseTest` 断言 delay 与计数 |
| 连续决策异常达阈值 | 结束任务（DECISION） | `Fatal(DECISION)` | 同文件 |
| 决策结果为空 | 结束任务 | `Fatal` | 同文件 |
| 技能归一化 | Intent / Mcp / Vision / Macro / Error 分支顺序 | 同左 | 同文件：归一化 Intent 覆盖 |
| 动作模式拒绝 | 计数、提示 AI 换档、达阈值结束 | `Continue` / `Fatal` | 同文件：拒绝与连续拒绝两例 |
| 完成判定不达标 | 拦截，不结束任务 | `Continue` | `ExecutionPhaseTest`：提前完成拦截 |
| `GIVE_UP` | 任务记忆写「已放弃：…」，失败收尾 | `Fatal` + `taskMemoryNote` | 同文件：放弃失败口径 |
| `SAY` 连续超限 | 结束任务 | `Fatal` | 同文件 |
| shell 命令确认门 | 未获用户许可不执行 | 同左 | 同文件：自写命令被拒 |
| 不可逆动作确认门 | 用户取消则不执行 | 同左 | 同文件：不可逆取消 |
| 外层重试 | 最多 3 次；点击类与结构性错误不重试 | 同左（判据在 `VerifyRules`） | 同文件：结构错误不重试 |
| 连续执行失败 3 次 | 暂停、要求用户介入；用户离开即失败 | 同左 | 同文件：连续失败用户离开 |
| 用户指导后的意图 | 仍过动作模式门与确认门 | 同左 | 同文件：指导后执行浏览器意图 |
| 动作后等待 | 统一 `delay(400)`，三类无副作用动作也等 | `VerifyRules.stepSettleMs` 区分 400/60ms | 判据可单测（`VerifyRules` 纯逻辑） |

### 6.1 已知的唯一差异（零影响）

迁移前 `lastSnapshot` 在广告过滤之后就地赋值；迁移后由主循环在观察段返回 `Continue` 时统一回写。
差异只出现在「广告自动关闭后提前进入下一轮」这条路径：迁移后会多写一次 `lastSnapshot`，
写的是同一份屏幕快照，`ContextInjector` 读到的屏幕尺寸不变，因此不影响任何决策与提示词。
`a11ySeenAlive` 在迁移前是 `runInner` 内的局部 `var`，迁移后由会话承载并在同一位置回写，效果相同。

---

## 7. 验证记录

每段迁移后都执行「编译 + 相关测试 + 全量测试」，命令统一带 `--offline --no-build-cache`
（离线缓存 + 避免 Windows 下构建缓存的文件锁问题）：

```
.\gradlew.bat :app:compileDebugKotlin --offline --no-build-cache -q
.\gradlew.bat :app:testDebugUnitTest --offline --no-build-cache -q
```

| 节点 | 单测规模 | 结果 |
|---|---|---|
| 重构前基线 | 73 suites / 793 tests | 绿 |
| 阶段 A 完成 | 74 / 805 | 绿 |
| 阶段 B1 完成 | 76 / 829 | 绿 |
| 阶段 B2 / B3 / B4 完成 | 82 / 870 | 绿 |
| 收尾（清理未使用 import / 死构造参数 / 接入 `StepRunner`） | 82 / 870（failures=0, errors=0, skipped=3） | 绿 |

`AgentEngine.kt` 行数：**3459 → 2863**（-596，-17.2%）；`engine/loop/` 七个文件合计 1390 行。

---

## 8. 遗留风险与不处理项

1. **执行段的 hook 接线仍很长**（`AgentEngine` 里 `executionPhase` 的构造约 120 行）。
   这是「可注入边界」的代价：真实执行细节已在 `engine/execution/ExecutionCore`、`ClickRunner`、
   `IntentTranslator`，但 hook 的装配点必须在引擎里（它持有全部协作者）。
   若继续瘦身，下一步是把 hook 装配本身抽成一个 `EngineWiring` 文件，而不是再切分阶段。
2. **单测覆盖分支、不覆盖真实设备行为**。坐标命中、无障碍服务被系统回收、截图权限弹窗等
   仍依赖真机手测；本轮只保证「同样的输入走同样的分支」。
3. **`VerifyRules` 的文案匹配**仍是字符串 `contains`（已收口为常量，但新增失败文案必须同步登记）。
4. **UI（阶段 D）未动**。玻璃视觉语言与 Agent 页排版保持原样。

---

## 9. 后来人怎么改

- 新增一个「本步之后就结束」的分支：在对应阶段 `return StepOutcome.Continue`；
  需要退出任务就 `Return`；护栏触发用 `Fatal` 并自己先写好界面文案。
- **不要在 `runInner` 里加 `continue` / `return` 或新的 `while`**：主循环只允许出现在 `TaskRunner`。
- 阶段需要新的引擎能力时：在对应 hooks 类加一个回调，在 `AgentEngine` 里接线；
  不要在阶段里引用 `AgentEngine` 或 Android 单例。
- 新增阈值常量放 `engine/runtime/EngineLimits.kt`，便于与提示词/设置页对齐。
