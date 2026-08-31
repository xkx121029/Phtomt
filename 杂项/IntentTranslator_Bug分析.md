# IntentTranslator 转译层 Bug 分析与修复清单

> 分析时间：2026-08-27
> 对应模块：`com.phoneagent.execution.IntentTranslator`
> 关联文件：`AgentEngine.kt`、`IntentResolver.kt`、`CapabilityManager.kt`

---

## Bug 1：`reason` 与 `reasoning` 字段混用（高优先级）

### 现象
`AgentAction.reason` 和 `AgentAction.reasoning` 在转译时赋值混乱：
- `common` 对象用 `intent.reasoning` 初始化（第 55-62 行）
- 大部分 `copy()` 调用传 `reason = intent.reasoning`（如 tap、long_press、input）
- 部分 `copy()` 调用传 `reason = unified`（`unified = intent.reasoning ?: intent.reason`，如 swipe、press、wait）
- `open_app` 传 `reason = intent.reasoning`，但 `FINISH`/`GIVE_UP` 传 `reason = unified`

### 影响
引擎中 `action.reason` 用于显示给用户看的跑马灯文字（`action.reason ?: ""`），而 `action.reasoning` 用于 AI 决策追溯。两者混用导致：
- 用户看到的动作说明有时是 thinking 内容，有时是 reason 内容，不一致
- 调试日志中 `reason` 字段含义模糊

### 修复方案
统一规范：
- `reasoning`：保留给 AI 决策链追溯（传入 `common`，不覆盖）
- `reason`：用户可见的执行说明（由转译层根据意图类型填充简洁描述）

```kotlin
// translate() 主函数中，不要提前把 reasoning 放进 common.reason
// 改为在每个分支明确设置 reason
val common = AgentAction(
    type = baseType,
    reasoning = intent.reasoning,      // AI 思考过程
    expected = intent.expected,
    confidence = intent.confidence,
    needsUserConfirmation = intent.needsConfirmation,
    pageFingerprint = intent.pageFingerprint,
)

// 各分支统一：reason = 用户可见的简短说明
IntentType.TAP -> common.copy(
    type = ActionType.TAP, ...,
    reason = intent.reasoning ?: "点击${intent.target?.value ?: "目标"}"  // 明确填充
)
```

---

## Bug 2：`expected` 字段在多数意图中转译丢失（高优先级）

### 现象
只有 `translateOpenApp` 显式传递了 `expected = intent.expected`，其他所有意图分支都没有把 `intent.expected` 传递给 `AgentAction`：

| 意图 | expected 是否传递 |
|------|------------------|
| `open_app` | ✅ 已传递 |
| `tap` | ❌ 丢失 |
| `long_press` | ❌ 丢失 |
| `input` | ❌ 丢失 |
| `swipe` | ❌ 丢失 |
| `press` | ❌ 丢失 |
| `wait` | ❌ 丢失 |
| `scroll_to` | ❌ 丢失 |
| `write_doc` | ❌ 丢失 |
| `finish` | ❌ 丢失 |
| `give_up` | ❌ 丢失 |
| `open` | ❌ 丢失 |

### 影响
引擎执行后验证失败时，无法对比"预期结果"与"实际结果"，AI 也无法在重试时参考预期。

### 修复方案
在 `common` 对象构建时就包含 `expected`（已做），确保每个分支不丢失：

```kotlin
// 当前 common 已含 expected，但 copy() 时没有显式保留
// 需要在每个分支确认 expected 被保留，或改为：
common.copy(...).also { require(it.expected == intent.expected) }
```

实际上 Kotlin 的 `copy()` 不会丢失未指定的字段，所以 **当前代码 expected 是正确传递的**，此 bug 为误报。但建议添加单元测试验证。

---

## Bug 3：`ActionType.SCROLL_TO` 映射不一致（中优先级）

### 现象
- `IntentType.TO_ACTION` 映射：`SCROLL_TO → ActionType.SCROLL`
- `ActionType.ALIAS` 映射：`SCROLL_TO → ActionType.SCROLL`
- `executeWithVerify` 的 when 分支：`ActionType.SCROLL, ActionType.SCROLL_TO -> ...`

但是 `IntentTranslator.translateScrollTo()` 输出的是 `ActionType.SCROLL`，而引擎中同时存在 `ActionType.SCROLL_TO` 常量和处理逻辑。如果将来有代码路径直接输出 `SCROLL_TO`，会导致重复处理或逻辑混乱。

### 影响
当前不影响运行，但是隐患。`ActionType.SCROLL_TO` 常量在实际转译中永远不会被使用（因为翻译层已映射为 `SCROLL`），属于死代码。

### 修复方案
统一使用 `ActionType.SCROLL`，删除 `ActionType.SCROLL_TO` 常量及其在所有 when 分支中的匹配：

```kotlin
// ActionType.kt 中删除
const val SCROLL_TO = "scroll_to"

// AgentEngine.kt 中删除 SCROLL_TO 匹配
// 原来: ActionType.SCROLL, ActionType.SCROLL_TO -> ...
// 改为: ActionType.SCROLL -> ...
```

---

## Bug 4：`reason = unified` 在某些分支导致空字符串（中优先级）

### 现象
```kotlin
val unified = intent.reasoning ?: intent.reason
```
当 AI 输出 JSON 中既没有 `reasoning` 也没有 `reason` 时，`unified` 为 `null`。但 `AgentAction.reason` 是 `String?`，传递 `null` 后引擎显示为空白。

查看 `AgentEngine.kt:859`：
```kotlin
message = action!!.reason ?: "",
```
此处做了 null 保护，但 floating window 的 `pushFloating` 用的是 `action.reasoning ?: action.reason ?: "正在执行"`，三者优先级也不一致。

### 影响
AI 输出极简 JSON（不带 reasoning/reason）时，用户看不到任何执行说明。

### 修复方案
在 `translate()` 入口处保证 `reason` 不为 null：

```kotlin
val unified = intent.reasoning?.takeIf { it.isNotBlank() }
    ?: intent.reason?.takeIf { it.isNotBlank() }
    ?: intent.expected?.takeIf { it.isNotBlank() }
    ?: "执行操作"
```

---

## Bug 5：READONLY 模式过滤逻辑遗漏 `OPEN` 意图（低优先级）

### 现象
READONLY 过滤条件：
```kotlin
result.action.type != ActionType.WAIT && result.action.type != ActionType.WRITE_DOC
```
但 `ActionType.OPEN`（深链直达）在 READONLY 模式下仍然会被执行，而打开外部链接/应用属于副作用操作，应在只读模式下拒绝。

### 影响
READONLY 模式下用户仍可能被意外跳转到外部应用或网页。

### 修复方案
```kotlin
if (result is TranslationResult.Command && mode == Mode.READONLY &&
    result.action.type !in setOf(ActionType.WAIT, ActionType.WRITE_DOC, ActionType.OPEN)
) {
    // OPEN 在 READONLY 下也允许（纯查看，不写数据）
    // 或者根据产品策略决定是否允许
}
```

---

## Bug 6：`normalizeKey` 对未知按键无警告（低优先级）

### 现象
```kotlin
private fun normalizeKey(key: String?): String = when (key?.trim()?.uppercase()) {
    "BACK" -> "BACK"
    "HOME" -> "HOME"
    "ENTER", "OK", "CONFIRM" -> "ENTER"
    "RECENTS", "RECENT", "APP_SWITCH", "RECENT_TASKS" -> "RECENTS"
    else -> key?.trim()?.uppercase() ?: "BACK"  // 未知按键直接透传
}
```
AI 输出一个不存在的按键名（如 `"VOLUMEUP"`）时，会被原样透传给 `handleKey`，而 `handleKey` 中：
```kotlin
else -> com.phoneagent.execution.VerifyResult(false, "未知按键 $keycode", "", "")
```
会立即报错，但不记录日志，调试困难。

### 修复方案
```kotlin
else -> {
    Log.w(TAG, "未知按键: ${key ?: "null"}，默认使用 BACK")
    "BACK"
}
```

---

## Bug 7：`translateTap/LongPress/Input` 失败时返回 `Failed` 但引擎当作 `Command` 处理（关键）

### 现象
当 `resolveTargetIntent` 返回 `null`（目标定位失败）时，转译层返回 `TranslationResult.Failed`，引擎在 `isStructuralError = true` 后直接将失败原因作为 `action.reason` 并继续执行流程，但此时 `action.type` 被设置为 `intent.intent`（原始意图字符串，如 `"tap"`），而不是规范的 `ActionType.TAP`。

查看 `AgentEngine.kt:924-931`：
```kotlin
action = AgentAction(
    type = intent.intent,  // ← 问题：type="tap" 而非 ActionType.TAP="tap"
    reasoning = intent.reasoning,
    reason = reason,
    target = intent.target?.let { ActionTarget(method = it.by, value = it.value) },
)
```

虽然 `"tap"` 在 `ActionType.ALIAS` 中有映射到 `"tap"`，但在 `executeWithVerify` 中：
```kotlin
val type = ActionType.ALIAS[action.type] ?: action.type
```
`ALIAS["tap"]` 返回 `"tap"`（自身），所以不会崩溃。但如果 AI 输出非常见意图字符串（如 `"click"` 而非 `"tap"`），且 `ALIAS` 中没有映射，就会导致 `executeWithVerify` 的 when 分支无匹配，落到 `else` 返回 `VerifyResult(false, "未知动作", ...)`。

### 影响
- 转译失败后，`action.type` 保留原始意图字符串，可能不在 `ALIAS` 映射中
- AI 如果按照非标准名称输出意图（如用 `"click"` 代替 `"tap"`），会导致引擎无法处理

### 修复方案
在 `translate()` 入口处标准化意图类型，或在 `ActionType.ALIAS` 中补充常见别名：

```kotlin
// IntentType.TO_ACTION 已包含标准映射，但 AI 可能输出非标准字符串
// 在 translate() 入口处增加规范化：
val normalizedIntent = IntentType.TO_ACTION[intent.intent] ?: intent.intent
```

同时补充 ALIAS：
```kotlin
val ALIAS: Map<String, String> = mapOf(
    TAP to CLICK,
    "click" to CLICK,      // ← 补充常见别名
    LONG_PRESS to LONG_CLICK,
    "longclick" to LONG_CLICK,
    KEY to KEY,
    LAUNCH to LAUNCH,
    SCROLL_TO to SCROLL,
    "scroll" to SCROLL,    // ← 补充常见别名
    ABORT to TASK_DONE,
    TASK_COMPLETE to TASK_DONE,
    OPEN to OPEN,
)
```

---

## 修复优先级汇总

| Bug | 优先级 | 影响范围 | 修复难度 |
|-----|--------|---------|---------|
| Bug 1：reason/reasoning 混用 | 高 | 用户体验+调试 | 低 |
| Bug 4：reason 为空字符串 | 中 | 用户看不到执行说明 | 低 |
| Bug 7：意图别名缺失 | 高 | 引擎崩溃风险 | 低 |
| Bug 3：SCROLL_TO 死代码 | 低 | 代码整洁 | 低 |
| Bug 5：READONLY 遗漏 OPEN | 低 | 安全性 | 低 |
| Bug 6：未知按键无日志 | 低 | 调试体验 | 低 |
