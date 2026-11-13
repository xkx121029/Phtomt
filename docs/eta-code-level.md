# Eta 系统级工具是怎么跑通的：代码级拆解与本项目差距

> 性质：**只读分析**。本次没有修改本项目任何源码，也没有执行 `git add / commit`。
> Eta 源码根：`C:\Users\linch\AppData\Local\Temp\eta-src\Mangi-11-Eta-16893be`（下文 Eta 路径均相对 `app/src/main/kotlin/io/github/mangi/eta/`）
> 本项目根：`d:\xkx\xkx_appproj\happy_phone agent`（下文路径均相对 `app/src/main/java/com/phoneagent/`）
>
> 分级：**A** 只是写法不同、不用改；**B** 影响成功率、该改；**C** 影响可维护性、可以后改。

---

## 0. 先给结论

把两端代码按同一条链路（声明 → 请求 → 执行 → 回注 → 下一轮）对齐后，结论和"Eta 回读做得更好"相反：

**本项目的回读比 Eta 更细**（闹钟四级降级链、亮度 5×400ms 轮询、勿扰/开关逐项回读、NFC 读不到就明确报失败）。差距不在"读没读"，而在**读到的结论在链路上被两次抹平**：

1. `ExecutionCore` 把 `ClockOutcome.Done(verified=false)` 统一映射成 `VerifyResult(success=true)`，未确认的闹钟在历史里变成"✅ 已确认成功"。
2. `StepTexts.lastStepResult()` 回注时只用 `action.type(reason)`，`StepRecord.detail`（里面就有"回读 40%，期望 50%"和下一步建议）没有进提示词。

此外有三条结构性差异让 Eta 的首次成功率更高：

- **Eta 把参数约束放在协议层**（JSON Schema 的 enum / minimum / maximum / required），模型想输出非法参数都难；本项目把约束放在 Markdown 表的一行自然语言里（`device_control` 全部 op 共用一句"参数放 args（level/stream/mode/action/seconds/enable）"），非法值是执行到端侧才被发现。
- **Eta 的工具表按可用性裁剪**：不可用的工具压根不在 schema 里；本项目把 `shell / terminal_op / a11y / personal_read` 一直列在意图表里，只靠一段文字说"不要调用"。
- **Eta 工具层从不重试**：一次调用只执行一次，未生效就带着 `before/after/expected` 回注给模型，由模型改参数或换路；本项目外层对同一动作最多重跑 3 次（间隔 900ms），对闹钟这类非幂等变更会重复派发。

---

## 1. 工具是怎么声明和注册的

### 1.1 Eta：Kotlin DSL 拼手写 JSON Schema，四层转换后进模型上下文

**第 1 层：schema 构造器。** `agent/model/AgentToolSchema.kt:18-31` 只有一个 `function(name, description, parameters)`，产出标准 `{"type":"function","function":{...}}`；`AgentToolSchema.kt:7-16` 的 `coordinateSpace()` 说明连"坐标空间"这种公共枚举也是手工维护的描述文本。

**第 2 层：逐工具声明。** `agent/model/AgentDeviceToolCatalog.kt:20-90` 是一串 `.put(function(...))`，参数类型由辅助构造器表达：

- `set_alarm`（`AgentDeviceToolCatalog.kt:33-49`）：`hour`/`minute` 用 `integer(desc, 0, 23)` / `integer(desc, 0, 59)`，`repeat_days` 用 `stringArray(..., "mon"... "sun")` 枚举，`vibrate` 用 `boolean(默认 true)`，必填通过末尾的 `"hour", "minute"` 传入。
- `set_timer`（`:50-60`）：`duration_seconds` 为 `integer(1, 86_400)`，`required` 只有它。
- `set_volume`（`:78-89`）：`stream` 是 `enumString`，六个值写死在 schema；`percent` 是 `integer(0, 100)`；必填 `"stream", "percent"`。
- `media_control`（`:65-77`）：`action` 枚举 `play/pause/play_pause/next/previous/stop`。

构造器本体在 `AgentDeviceToolCatalog.kt:299-348`：`properties()` 组 `{"type":"object","properties":{...},"required":[...]}`，`integer(min,max)` 自动写 `minimum/maximum`，`string(max)` 自动写 `maxLength`，`enumString()` 自动写 `enum`。**类型、范围、枚举、必填、默认值全部在 schema 里，不靠模型自觉。**

**第 3 层：能力裁剪（两次）。**

- `agent/tool/AgentToolRequirements.kt:130-140`：`project(tools, rootAvailable)` 直接 `continue` 掉 `RootRequirement.REQUIRED` 的工具；`:142-164` 的 `projectUnprivileged` 还会**改 schema 内容**（`press_key` 的 `PASTE` 从 enum 里删掉）。
- `agent/tool/AgentToolCapabilities.kt:57-72`：再按权限/机型裁一次；`:34-55` 的 `unavailableCode(name)` 是唯一判据，`:64-67` 甚至会把 `get_device_state` 的 `target` 枚举里不支持的项删掉。

**第 4 层：运行时可见性合并。** `agent/model/AgentModelClient.kt:183-204` 的 `toolsForRound()`：系统提示与工具表是请求前缀，**按轮重算会废掉 prompt cache**，所以只有 Root 状态变化才整体重建，其余情况"只增不减"。同一份规则在 `:218-229` 又被包进 `ToolExecutor`：真正执行前再查一次 `unavailableCode`，不可用就回 `{ok:false, code:<UNAVAILABLE>, message:"该工具当前不可用（…），本次未执行"}`。**可见 ≠ 可用**，但两者用同一张表判定。

### 1.2 本项目：静态 Markdown 意图表 + 一份未上桌的 CapabilitySpec

- 模型拿到的"工具表"是提示词里的 Markdown 表格：`engine/prompt/PromptBodies.kt:96-145`（CN）/ `:146-195`（EN），列只有 `intent | 含义 | 必填字段`。由 `engine/prompt/PromptCatalog.kt:17-42` 以 `sys.intents` 区块静态拼装，**不随设备能力变化**（`AgentPrompts.kt:107-139` 另起两行文字说明哪些能力不可用）。
- 已有一份完整的参数声明：`domain/model/CapabilitySpec.kt:86-107` 的 `CapabilityParam`（`required / options / min / max / pattern / defaultValue`），`DEVICE_CONTROL` 在 `:648-669` 声明了 `op` 的 15 个枚举、`level` 的 0~100、`stream` 的 5 个选项、`seconds` 的 5~1800。**但这份声明只服务于技能目录/校验，没有参与提示词生成。**
- 与模型之间没有 function calling：`core/ai/AiClient.kt:748` 是全项目唯一发送 `tools` 的地方，且只发生在 `probeAbility` 的 `noop_ping` 探测（`:694-718`）。真实的决策请求是纯文本 `messages`，模型输出 JSON intent，靠 `AgentResponseParser` + `intentTranslator` 解析。

### 1.3 差距

| Eta 做法 | 本项目现状 | 差距 | 分级 |
|---|---|---|---|
| schema 带 `enum/minimum/maximum/required`，非法参数在协议层被约束 | `PromptBodies.kt:105` 一行文字涵盖 15 个 op、7 个参数，没有范围、没有枚举对应关系 | `device_control` 首轮极易漏 `level` 或写错 `stream`；靠失败后回注再补一轮 | **B** |
| 不可用工具从 schema 移除，调用时二次判据返回明确 code | 意图表始终完整；只在 `AgentPrompts.kt:124-131` 加两行"不要调用" | 模型会去调用表里存在但本机不可用的意图，多撞一次墙 | **B** |
| 工具描述里写清"什么时候不该用"（如 `set_alarm` 的"系统不接受直达操作时可能只打开时钟页面"） | 意图表也写了类似提示（`PromptBodies.kt:100-105`），质量不差 | 无实质差距 | A |
| 文本 JSON intent 协议 vs 原生 function calling | 项目已有解析修复 + 缺参补全，改协议成本高、收益不确定 | 不重写 | **A** |
| 参数声明单一来源（schema） | `CapabilitySpec` 与 `PromptBodies` 两份真相，长期会漂移 | 构造性维护风险 | **C** |

---

## 2. 工具结果怎么回给模型

### 2.1 Eta：统一信封 + 原生 tool 消息，失败信息可执行

**信封。** `agent/tool/DeviceMutationResult.kt:7-32` 的 `observed()` 一次生成：

```
ok / tool / user_id / scope / before / after / expected /
command_accepted / changed / verified / status
```

`verified = commandAccepted && after != null && matchesExpected`（`:18`），`status` 三态 `verified | unconfirmed | failed`（`:25`）。未验证时补两个字段（`:27-30`）：`code`（默认 `STATE_CHANGE_UNCONFIRMED`）+ `message`="操作请求已提交，但未确认目标状态；请先检查状态，不要直接重复操作"。

**时钟类**另走 `agent/phone/PhoneOperation.kt:101-110` 的 `changed(tool, verified)`：`ok=verified`、`status` 三态、未确认时 `code=OPERATION_UNCONFIRMED` + "请求已提交但结果未确认，请先查询状态，不要重复创建"。参数错误则在 `:41` 抛 `PhoneOperationFailure`，由 `:43-97` 的 `text/integer/time` 给出 `INVALID_ARGUMENT` + 具体字段（如 `"$key 超出允许范围"`）。

**回注方式。** `agent/model/AgentLoop.kt:188-197`：本轮有 toolCall 就 `executeToolBatch` → `AgentConversationCodec.toolResultMessage(...)` → `appendMessage` → `round += 1` → `continue`。结果以 **`role=tool` 的原生消息**回到同一段对话，模型下一轮直接读结构，不需要再做一次"这段中文是什么意思"的解析。执行器自身异常也会被兜成信封：`AgentLoop.kt:411-422` 捕获异常 → `{ok:false, code:"TOOL_ERROR", message}`；被拒绝的调用走 `:431-457` 的 `rejectedToolOutcome`。

**GUI 类**另有一层动作后摘要：`agent/tool/AgentAfterActionSummary.kt:13-28` 把动作后的新界面（新 `observation_id` + 精简节点）塞进 `after`，并在 `screen_changed=false` 时明确写"不要原样重复"。

### 2.2 本项目：三态文案 + 一段独立 user 消息，细节在第二处

- 三态渲染在 `engine/runtime/StepTexts.kt:20-29`：

  ```kotlin
  val action = last.action?.run { "${type}${reason?.let { "($it)" } ?: ""}" } ?: "-"
  return when {
      last.isConfirmed -> "✅ 已确认成功: $action"
      last.verificationResult == "failed" -> "❌ 未生效: $action"
      else -> "⚠️ 已发送但未确认: $action"
  }
  ```

  **没有用 `StepRecord.detail`**（字段定义见 `domain/model/PhantomModels.kt:81-86`，注释写明"这里是这一步为什么成/不成"）。
- 系统直连另有完整回执：`engine/AgentEngine.kt:2239-2268` `handleDeviceOps()` 把 `OpsResult.Ok/Fail` 的正文作为**一条独立 user 消息**注入（`:2262-2263`），同时 `recordStep(..., detail = "直连结果：${text.take(200)}")`（`:2256-2260`）。
- 历史状态在 `AgentEngine.kt:2527-2530`：`isConfirmed = verification == VerifyRules.STATUS_VERIFIED`。

### 2.3 差距（本节含两个 P0）

| Eta 做法 | 本项目现状 | 差距 | 分级 |
|---|---|---|---|
| `status` 三态 + `ok=verified`，未确认绝不报成功 | `ExecutionCore.kt:610-613` 把**所有** `ClockOutcome.Done` 映射为 `VerifyResult(true, ...)`，丢掉 `outcome.verified` | 未确认的闹钟在历史里显示"✅ 已确认成功"，模型据此结束任务，用户看到的是假成功 | **B / P0** |
| 结果里带 `before/after/expected/message` | `StepTexts.kt:22` 只拼 `type(reason)`，`detail` 未参与 | 模型拿不到"回读 40%，期望 50%"，只能凭猜重试 | **B / P0** |
| 失败一律走同一条 `role=tool` 通道 | 系统直连的正文是**另一条 user 消息**，而 `EngineLimits.MAX_DIALOG_TURNS = 6`（`engine/runtime/EngineLimits.kt:23`）会截断早期历史 | 长任务里失败原因会被截掉，只剩"⚠️ 未确认: DEVICE_OPS" | **B** |
| 信封是 JSON，字段可机读 | 回注是中文自然语言，没有 code | 模型只能语义理解，无法稳定引用 code | **A**（项目走文本协议，改回 JSON 收益低） |

**可落地改法（P0，两个文件，不动消息文案）：**

1. `engine/execution/ExecutionCore.kt:610-613`

   ```kotlin
   is ClockOutcome.Done -> com.phoneagent.engine.execution.VerifyResult(
       outcome.verified, outcome.message, "", "",
       channel = outcome.kind.id, trace = trace,
   )
   ```

   注意：`ClockFallback.kt:372-386` 的 `Unreadable` 分支已经返回 `Done(verified = false)`，消息也已写明"无法确认…没有重复派发"，所以只需把 `verified` 透传，不需要改文案。

2. `engine/runtime/StepTexts.kt:20-29`

   ```kotlin
   val detail = last.detail.trim().replace('\n', ' ').take(160).ifBlank { null }
   val body = if (detail == null) action else "$action — $detail"
   ```

   这一条同时解决 `handleDeviceOps` 的 detail 已存在却不进提示词的问题。`detail` 目前最长 200 字（`AgentEngine.kt:2259`），截 160 足够，且不会显著增加上下文。

---

## 3. 模型这一侧怎么被约束

### 3.1 Eta：schema + 一段"硬规则"系统消息，零 few-shot

- **两层描述**：每个工具一段自然语言 description（写清用途 + 失败模式，见 `AgentDeviceToolCatalog.kt:36` "系统不接受直达操作时可能只打开时钟页面"），外加一条超长 system 消息 `agent/model/AgentPromptBuilder.kt:48-115`。
- **硬性流程约束逐条存在**（同一段消息内）：

  - `:82` "失败、部分完成或结果尚未确认时明确说明，不把尝试执行当成成功"；
  - `:91` "界面发生变化或需要操作控件时重新观察"；
  - `:97` "调用节点工具时必须把该节点与同一次观察的 observation_id 一起传回，过期就重新观察"；
  - `:103` "**任何工具返回 ACTION_OUTCOME_UNKNOWN 或 DIRECTION_MISMATCH 时，必须先重新观察，禁止直接重放动作**"；
  - `:106-108` "先读 after 判断是否生效：screen_changed=false…不要原样重复"；
  - `:112-113` "ACCESSIBILITY_UNAVAILABLE… 说明动作未执行，不要改用坐标或 Shell 重放 GUI 动作"。

- **few-shot 数量：0**。全仓库提示词只有 schema 和短句规则，没有示例对话。
- 工具执行前还有 schema 级门槛：`AgentToolRequirements.requiresFreshObservation(name)`（`:116`）让执行器能拒绝"没有新观察就操作"的调用。

### 3.2 本项目：铁律 + 意图表 + 三态 + 失败处理，同样零完整 few-shot

- 铁律：`PromptBodies.kt:23-32`（"只输出纯 JSON"、"每步一个意图"、"禁止凭空猜一个意图来试试"等 7 条）。
- 三态：`PromptBodies.kt:744-749`（✅ 继续 / ⚠️ 本步先确认，不要重复发送 / ❌ 换方式）。
- 失败处理：`PromptBodies.kt:758-762`（1~2 次换方式，3 次 `give_up`；⚠️ 不计失败）。
- 完成判定：`PromptBodies.kt:45-50`（必须有页面证据才 `finish`）。
- 可用性：`AgentPrompts.kt:115-139` 两行"按实测，不可用就别调用"。
- 没有完整 few-shot；唯一示例藏在缺参补全的追问里（`AgentEngine.kt:1447` 的 `例如 tap 需补 target:{...}`）。

### 3.3 差距

| Eta 做法 | 本项目现状 | 差距 | 分级 |
|---|---|---|---|
| 每个工具"schema 约束 + 描述里的失败模式" | 意图表每行一句；失败模式写在表内（质量不低） | 缺的是**结构性**约束，不是文案 | A |
| 约束写在不可编辑的附加 system 消息里 | 铁律/三态/失败处理同样独立于用户角色设定 | 无差距 | A |
| 不可用能力从表里消失 | 只加两行文字 | 模型仍会调用不存在的通道 | **B** |
| 三态里写"重新观察/禁止重放" | 三态写"先确认结果（wait 或读取当前页面）" | 对 `device_control` 这类**没有页面可确认**的动作，`wait`/读页面都不适用，模型只能重发 | **B** |

**改法：**

- `PromptBodies.kt:747`（CN）/ `:753`（EN）在 ⚠️ 一行补一句："（系统直连类动作改用 `device_info` 回读对应状态，不要重发同一条指令）"。
- 能力表裁剪见第 1.3 节 B 项；最小改法是在 `AgentPrompts.kt:applyAvailability` 之后，对 `sys.intents` 文本做一次行级过滤：`shizukuAvailable == false` 时删掉 `terminal_op / app_manage / personal_read / shell` 行，`hasVision == false` 时不动（视觉只是降级，不是不可用）。

---

## 4. 执行与校验的时序

### 4.1 Eta

```
模型输出 tool_calls
  → AgentLoop.kt:188 executeToolBatch
  → AgentModelClient.kt:218 ToolExecutor（执行前再查 unavailableCode）
  → 真正执行：先读 before，再调系统接口，再读 after
  → DeviceMutationResult.observed / PhoneOperation.changed 生成三态信封
  → AgentLoop.kt:191 以 role=tool 追加
  → round += 1，立即进入下一轮（同一段对话，不重新发 prompt、不重建工具表）
```

**"是否真的生效"在哪判断：工具内部。** 两个例子：

- 闹钟：`agent/phone/ClockOperations.kt:102-116`，`add_alarm` 成功后按 `alarm_id` 读列表，逐项比对 `hour/minute/repeat_mask/enabled/label`，全部相等才 `changed(..., true)`；拿不到条目直接 `changed(..., false)`（`:104`）。
- 亮度：`agent/tool/DisplaySoundControls.kt:90-109`，读 before → `cmd display set-brightness` → 读 after → `abs(after - percent) <= 1.0` 才算 verified，并把 `actual_percent` 放进结果（`:108`）。
- 音量/勿扰/开关：`agent/tool/DeviceStateMutations.kt` 与 `SystemStateControls` 用同一 `observed()` 模式，`before/after` 都在信封里。

**判断不出来时**：`PhoneOperation.changed(false)` 返回 `ok=false`，**不重试、不降级**，把"请先查询状态，不要重复创建"交给模型；UI 类则由 `AgentAfterActionSummary` 回注新界面让模型自己看。Eta 没有任何"同一动作自动重放"的代码路径。

### 4.2 本项目

```
观察 → 决策（JSON intent）→ 转译 IntentTranslator
  → ExecutionPhase.kt:392-408 device.execute(act, snapshot) 得 VerifyResult
  → 未 verified 且 shouldRetry → 最多 3 次，每次间隔 3×300ms
  → 记录 StepRecord（AgentEngine.kt:2527-2530）
  → 下一轮决策由 AgentEngine.kt:1668-1696 把 lastStepResultText 拼进 user 消息
```

- 系统直连（`device_control / device_info / settings_op`）不走向导层，直接 `handleDeviceOps`（`AgentEngine.kt:2239-2268`），执行结果正文单独注入 user 消息。
- 闹钟走向降级链：`engine/execution/ClockFallback.kt:69-107`（静默直达 → shell 写库 → 无障碍确认 → 打开页面手动），成功后**回读比对**（`:325-419`），读不到时返回 `Done(verified=false)`（`:372-386`）。
- 外层的 `shouldRetry`（`execution/VerifyRules.kt:76-77`）只看 `type/reason/attempt`：**不看 `VerifyResult.notDelivered`**。而 `VerifiedClickExecutor.kt:26-30` 的注释明确写着"只有'未交付'才允许换执行通道重试"——这个契约目前没有接进外层循环。

### 4.3 差距（本节含一个 P0）

| Eta 做法 | 本项目现状 | 差距 | 分级 |
|---|---|---|---|
| 工具层零重试，未生效交给模型 | 外层最多 3 次重跑，判据只看动作类型与文案 | 闹钟 `Manual/Failed` 与"已派发但读不到"都会被整条降级链再跑 2 次：重复开时钟页、重复派发 | **B / P0** |
| 用 `notDelivered` 语义区分"未交付"与"已交付未确认" | 字段存在、注释写了规则，`shouldRetry` 未使用 | 语义与实现脱节 | **B** |
| 校验在工具内部，读不到就 `ok=false` | 校验同样在工具内部（更细） | 无差距 | A |
| 回注后立即继续同一段对话 | 回注后重新拼一轮完整决策 prompt（含页面、记忆、文档） | 成本更高，但不影响正确性 | A |

**改法（P0，两处，均为小改）：**

1. `engine/execution/VerifyRules.kt:65-77` 增加系统变更类判据，并在 `shouldRetry` 里排除：

   ```kotlin
   private val SYSTEM_MUTATION_TYPES = setOf(ActionType.SET_ALARM, ActionType.SET_TIMER)

   fun shouldRetry(...) = active && attempt < MAX_ATTEMPTS &&
       !isStructuralError(reason) && !isClickLike(type) && type !in SYSTEM_MUTATION_TYPES
   ```

   理由与 `ExecutionCore.kt:614` 现有注释一致："禁止通道重试造成重复设置"。Eta 对应做法是根本没有工具级重试。

2. `engine/loop/ExecutionPhase.kt:400-402` 的重试条件加上"已交付不重放"：

   ```kotlin
   while (!verified && verify.notDelivered &&
       VerifyRules.shouldRetry(act.type, verify.reason, times, coroutineContextIsActive())
   ) { ... }
   ```

   注意这会改变所有默认 `notDelivered=false` 动作的行为（多数 GUI 动作默认 false）。若不想扩大改动面，可只做第 1 条，把 2 留到下一批；**不要两条都做一半**。

---

## 5. 错误恢复：谁处理、重试几次、加不加提示

### 5.1 Eta

| 错误类型 | 处理方 | 具体行为 |
|---|---|---|
| 参数缺失 / 非法 | 工具内部 | `PhoneOperation.text/integer/time`（`PhoneOperation.kt:43-97`）抛 `INVALID_ARGUMENT` + 字段名；执行器兜成 `{ok:false, code, message}` |
| 工具名不存在 / 被裁剪 | 执行器 | `AgentToolCapabilities.unavailableCode`（`:34-55`）→ `AgentModelClient.kt:218-229` 返回"该工具当前不可用（code），本次未执行" |
| 参数 JSON 被截断 | 执行器 | `AgentLoop.kt` 的截断调用会走 `rejectedToolOutcome("TRUNCATED_TOOL_CALL", ...)`（`:431-457`） |
| 执行抛异常 | 执行器 | `AgentLoop.kt:411-422` → `TOOL_ERROR` |
| 系统拒绝 / 通道不可用 | 工具内部 | 返回 `ok=false` + 可执行建议（如"请先检查状态，不要直接重复操作"） |
| 执行超时 | **模型请求层** | `agent/model/AgentModelRetry.kt:14-71`：只包模型请求，`MAX_RETRIES=3`，`BASE_DELAY_MS=2000`，`delay = BASE shl (retries-1)`（`:57`），不可重试错误立刻抛（`:48`），用尽后消息"已重试 3 次仍未恢复，已保留此前完成的工具结果"（`:50-53`） |
| 批次被中断 | 执行器 | `AgentToolBatchRecovery` 注入 `TOOL_INTERRUPTED` 占位结果，保证 tool_call 与 tool_result 一一对应 |

**关键点：Eta 工具层重试次数 = 0。** 重试只发生在"模型请求"这一层，且重试之间不追加提示——因为模型还没看到工具结果，没什么可提示的。工具失败后"下一次尝试"完全由模型自己根据信封决定。

### 5.2 本项目

| 错误类型 | 处理方 | 具体行为 |
|---|---|---|
| 参数缺失 | 决策层 + 一次补参请求 | `AgentEngine.kt:1439-1472` `fillMissingParam`：最多 3 轮（`EngineLimits.MAX_PARAM_REFILL`，`:13`），每轮把"缺哪个字段 + 例子 + 原意图"作为 user 消息单独问一次 |
| 参数非法 | 端侧校验 | `DeviceOpsChannel.kt:404-406`（亮度）、`:446-447`（音量）、`:655-657`（开关）、`:538`（勿扰）都返回"需要 X：范围/示例" |
| 权限不足 / 通道不可用 | 工具内部 | `DeviceOpsChannel.kt:670-674` 的 `noChannel()`、`:559-563` 的勿扰权限、`SystemStateControls` 同类文案；会写"怎么换路线 + 手动怎么做" |
| 结构性错误不重试 | 执行器 | `VerifyRules.kt:41-57` 用中文 `contains` 判定，`shouldRetry` 直接排除 |
| 动作未生效 | 执行器 | 最多 3 次（`VerifyRules.MAX_ATTEMPTS`），间隔 900ms（`ExecutionPhase.kt:405`） |
| 连续失败收尾 | 决策层 | `ExecutionPhase.kt:457` 累加 `consecutiveFailures`；`PromptBodies.kt:760` 规定 3 次 `give_up` |
| 同签名原地打转 | 决策层 | `ExecutionPhase.kt:460-467`：同签名连续 4 次注入纠偏，6 次升级协助 |
| 模型请求超时/限流 | 传输层 | `core/ai/AiClient.kt:48-72`：`MAX_RETRIES=5`、`MAX_NON_429_RETRIES=2`、`MAX_429_RETRIES=3`、退避封顶 `8s`；与 `WATCHDOG_DECIDE_MS=45s`（`EngineLimits.kt:26`）对齐 |
| 输出 JSON 不合法 | 决策层 | `AgentResponseParser` + 一次"纠错重发"（`AiClient` 决策路径） |

### 5.3 差距

| Eta 做法 | 本项目现状 | 差距 | 分级 |
|---|---|---|---|
| 工具层不重试 | 同一动作最多 3 次 | 对闹钟/勿扰/开关这类非幂等变更，重跑是重复副作用风险 | **B** |
| 失败只回"可执行的下一步"，不回机制说明 | 同样给中文可执行建议，质量相当 | 无差距 | A |
| 参数错误在 schema 层就被挡住，很少走到运行期 | 运行期才发现，再触发补参/重试 | 多一轮往返 | **B**（见 1.3） |
| 模型请求重试 3 次、2s 指数退避 | 5 次、非 429 只 2 次、封顶 8s | 策略不同但都有理由（本项目要对齐 45s 看门狗） | A |
| 重试之间不加提示（因为没执行） | 纠偏提示在"失败后下一轮"注入 | 语义不同，不是缺陷 | A |

**改法**同 4.3 的 P0；另建议把 `VerifyRules.kt:41-57` 的文案匹配改成"错误码优先、文案兜底"：在 `VerifyResult` 增加 `code: String = ""`，结构性错误用 `code` 判定（C 级，可后做）。

---

## 6. 对照总表与改动清单

### 6.1 总表

| # | 环节 | Eta 做法（证据） | 本项目现状（证据） | 差距性质 | 分级 |
|---|---|---|---|---|---|
| 1 | 声明 | 手写 JSON Schema + DSL，含 enum/min/max/required（`AgentDeviceToolCatalog.kt:33-89`, `:290-348`） | 静态 Markdown 表；`CapabilitySpec` 有完整声明但未用于提示词（`PromptBodies.kt:105`, `CapabilitySpec.kt:648-669`） | 参数约束不在协议层 | **B** |
| 2 | 注册/裁剪 | 两层裁剪 + 运行期复核（`AgentToolRequirements.kt:130-164`, `AgentToolCapabilities.kt:57-72`, `AgentModelClient.kt:218-229`） | 表不裁剪，仅两行文字提示（`AgentPrompts.kt:124-131`） | 模型会调用不存在的能力 | **B** |
| 3 | 结果信封 | `{ok,code,message,status,before,after,expected,verified}`（`DeviceMutationResult.kt:7-32`, `PhoneOperation.kt:101-110`） | 三态中文；`detail` 有但未进提示词（`StepTexts.kt:20-29`, `PhantomModels.kt:81-86`） | 细节丢失，模型无法据此修正 | **B / P0** |
| 4 | 生效判定 | 工具内比对 before/after，读不到→`ok=false` 不回退（`ClockOperations.kt:102-116`, `DisplaySoundControls.kt:90-109`） | 同样在工具内比对，更细（`DeviceOpsChannel.kt:410-425`, `ClockFallback.kt:372-386`） | 判定不弱，**映射丢失**（`ExecutionCore.kt:610-613`） | **B / P0** |
| 5 | 回注 | 原生 `role=tool`，同轮继续（`AgentLoop.kt:188-197`） | 拼进下一轮 user 消息；直连另发一条 user 消息（`AgentEngine.kt:1668-1696`, `:2262-2263`） | 多一次完整 prompt，且会被 6 轮截断 | A / **B**（截断） |
| 6 | 提示约束 | schema + 长硬规则，零 few-shot（`AgentPromptBuilder.kt:82-113`） | 铁律 + 意图表 + 三态 + 失败处理，零 few-shot（`PromptBodies.kt:23-32/744-762`） | 强度接近；缺"无页面可确认"分支 | **B** |
| 7 | 重试 | 工具层 0 次；模型请求 3 次 2s 退避（`AgentModelRetry.kt:14-71`） | 工具层 3 次 900ms；请求层 5 次/429 3 次/封顶 8s（`VerifyRules.kt:76-77`, `AiClient.kt:48-72`） | 系统变更被重复执行 | **B / P0** |
| 8 | 错误信息 | code + message + 期望/实际（`DeviceMutationResult.kt:26-31`） | 中文原因 + 替代做法，无 code（`DeviceOpsChannel.kt:421-425`） | 人读友好、机读弱 | A（可后改 C） |

### 6.2 按优先级排序的改动清单

**P0（直接影响"设了闹钟但没生效"这类失败，改动小、可单测）**

1. `engine/execution/ExecutionCore.kt:610-613`：`ClockOutcome.Done` 透传 `outcome.verified`。
2. `engine/runtime/StepTexts.kt:20-29`：把 `StepRecord.detail`（截 160 字）拼进三态行。
3. `engine/execution/VerifyRules.kt:65-77` + `engine/loop/ExecutionPhase.kt:400-402`：系统变更类动作不再自动重跑（二选一，建议先做 `VerifyRules` 那半条）。

**P1（提升首轮成功率，需要改提示词，改动面中等）**

4. `engine/prompt/PromptBodies.kt:105`（CN）与 EN 对应行：把 `device_control` 一行拆成"op → 必填参数"，内容直接取自 `CapabilitySpec.kt:651-669`；长期做法是让该表由 `CapabilitySpec` 生成。
5. `engine/AgentPrompts.kt:124-131`：按 `shizukuAvailable/hasVision` 对 `sys.intents` 做行级过滤，而不是只加一句"别调用"。
6. `device/ops/DeviceOpsChannel.kt:439-445`：`volume` 的 `stream` 不再是 `else -> STREAM_MUSIC`，改为 `else -> OpsResult.Fail("stream 只支持 music/ring/notification/alarm/call")`；同时把 `CapabilitySpec.kt:662-663` 的 `"call"` 与执行器 `"call"/"voice"` 对齐（Eta 用 `voice_call`，模型很可能照旧习惯输出 `voice_call`，目前会被静默当成媒体音量）。
7. `engine/prompt/PromptBodies.kt:747`：⚠️ 分支补充"系统直连用 `device_info` 回读"。

**P2（可维护性，可后做）**

8. `engine/prompt/PromptBodies.kt:96-195`：CN/EN 两份意图表改为同一数据源渲染，消除漂移。
9. `engine/execution/VerifyRules.kt:41-57`：结构性错误从中文 `contains` 改为错误码优先。
10. `PromptBodies.kt:95-145` 与 `CapabilitySpec.kt` 合并为单一真相（与 4 同一个方向，属重构）。

### 6.3 明确不建议改的（A 级）

- **不重写为原生 function calling**：项目已有意图表、解析修复、缺参补全、可用性投影，改协议要重做四层，收益不确定。
- **不改中文失败文案**：两端都证明"可执行的下一步"比"错误码"更有效；Eta 的 `code` 只是补充。
- **不改请求层重试参数**：现有 5 次/封顶 8s 是为 45s 看门狗算过的，直接照抄 Eta 的 3 次/2s 反而可能更差。
- **不改回读实现**：本项目的亮度轮询、勿扰 `zen_mode` 回读、NFC 诚实失败都比 Eta 更细，问题在映射层不在采集层。

---

## 7. 协作提醒：当前工作区已被改动的文件

执行 `git status --porcelain` 的结果里，与本主题相关的只有 `device/provider/ProviderManager.kt`（通道可用性判定）。本报告**没有**修改它，后续若按其改法动通道逻辑，需先与该改动线的负责人确认。

其余已修改文件均为 UI / 文档 / CI / 配置，与本报告的 P0–P2 改动目标不重叠。本报告的四个核心目标文件（`ExecutionCore.kt`、`StepTexts.kt`、`VerifyRules.kt`、`PromptBodies.kt`）在读取时均为干净状态。

再次说明：本次只读，未提交任何 git 变更。
