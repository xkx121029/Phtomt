# Bug 排查报告：核心逻辑模块

- 项目：Happy Phone Agent（Android / Kotlin）
- 排查日期：2026-08-15
- 排查范围：AgentEngine / AgentPrompt(s) / ShellCommands / VerifiedClickExecutor / PageAnnotator / PageFingerprint / LocalDecisionEngine / AiClient / AiDecision / ChatModels / GlmDefaults / CloudAgent / AgentAction / AgentState / ScreenSnapshot / UiElement / MemoryStore / SensitivePageDetector
- 说明：本次为只读排查（未修改任何业务代码）。BUG 均经逻辑验证，非猜测。

---

## 第一部分：按严重程度分级

### 一、严重 / 高危

| 编号 | 严重度 | 文件 | 行号 | 问题概述 |
|------|--------|------|------|----------|
| BUG-01 | 高危 | perception/PageAnnotator.kt | 62-63 | 变量遮蔽：`it in it` 恒为 true，导致 hasPositive / hasDismiss 恒为 true，元素数 ≤8 的普通页面被误判为弹窗页（dialog_overlay） |
| BUG-02 | 高危 | agent/AgentEngine.kt | 936 | `when` 分支重复：`ActionType.TYPE_TEXT, ActionType.TYPE_TEXT` 重复标签，Kotlin 编译器报 "Duplicate label in 'when' expression"，阻断编译（或第二个条件永远不可达的死代码） |
| BUG-03 | 高危 | agent/AgentEngine.kt | 921-933 | 四向滑动终点坐标越界：`y ± screenHeight()`、`x ± screenWidth()` 未 clamp，终点落在屏幕外，AccessibilityService.dispatchGesture 对越界手势直接返回 false / onCancelled，滑动动作不生效 |

### 二、一般 / 中危

| 编号 | 严重度 | 文件 | 行号 | 问题概述 |
|------|--------|------|------|----------|
| BUG-04 | 中危 | ai/AiClient.kt | 364-406 | chatStream 流式中断后重试：`full` StringBuilder 在重试循环外，前一轮已累积内容会与新内容拼接，造成规划/文档文本重复 |
| BUG-05 | 中危 | perception/PageAnnotator.kt L96 + decision/LocalDecisionEngine.kt L66-71 | — | 倒计时广告处理与系统 Prompt 铁律矛盾：Prompt 禁止点击"跳过"，但 contextHint 引导 AI 点击跳过、本地决策直接点击"跳过"，行为不一致且有误触底层元素风险 |
| BUG-06 | 中危 | security/SensitivePageDetector.kt | 47-49 | 脱敏顺序缺陷：身份证（18 位）先被银行卡正则 `\d{16,19}` 贪婪匹配，导致身份证脱敏不完整，仍泄露多位明文数字 |

### 三、较低 / 低危

| 编号 | 严重度 | 文件 | 行号 | 问题概述 |
|------|--------|------|------|----------|
| BUG-07 | 低危 | agent/AgentEngine.kt | 83-84 | 死代码：anomalyEngine / profileLearner 实例化但从未使用，"异常记忆复用（第 10 层）"未接入决策流程 |
| BUG-08 | 低危 | memory/MemoryStore.kt | 85-97 | recordUse 非原子读-改-写（load→modify→save），并发调用会丢失更新 |
| BUG-09 | 低危 | ai/AiClient.kt | 565-571 | ByteArrayOutputStream 未关闭（close() 为 no-op，影响极小，属资源管理不规范） |
| BUG-10 | 低危 | agent/AgentEngine.kt | 218-222、109 | _logs 日志列表与 translateCache 无限增长，长时间运行占用内存 |
| BUG-11 | 低危 | agent/ShellCommands.kt | 172 | toPixel：像素值 ≤1 的坐标被误判为比例（0~1）换算成整个屏幕尺寸 |
| BUG-12 | 低危 | decision/LocalDecisionEngine.kt | 63 | handleDialog 命中正向按钮（允许/同意）时 reason 仍固定为"关闭弹窗"，语义错误 |
| BUG-13 | 低危 | network/CloudAgent.kt | 16-19 | verify() 方法为死代码，未被 AgentEngine 调用（实际使用 VerifiedClickExecutor） |

---

## 第二部分：问题代码位置、描述与修复建议

### BUG-01（高危）PageAnnotator.kt L62-63 变量遮蔽导致弹窗误判

文件：[PageAnnotator.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/perception/PageAnnotator.kt#L62-L64)

```kotlin
val hasPositive = labels.any { dialogPositive.any { it in it } }
val hasDismiss = labels.any { dialogDismiss.any { it in it } }
if (hasPositive && hasDismiss && snapshot.elements.size <= 8) return "dialog_overlay"
```

问题描述：
- 内层 `dialogPositive.any { it in it }` 中 lambda 参数 `it` 遮蔽外层 `labels.any` 的 `it`，`it in it` 实际是 `keyword.contains(keyword)`，恒为 true。
- 因此只要页面存在任一有标签的元素，`hasPositive` 与 `hasDismiss` 就恒为 true。
- 后果：**任何元素数 ≤ 8 且含标签的普通页面都被误判为 `dialog_overlay`**。
- 连锁影响：LocalDecisionEngine.decide()（第 27 行）与 AgentEngine（第 651 行）都会基于该误判类型进入弹窗处理，端侧决策会去点击页面中第一个含"允许/同意/确定/关闭/取消"等关键词的元素，导致误操作。

复现：任意元素数 ≤8 的普通页面（如设置二级页、聊天窗口），inferPageType 返回 "dialog_overlay"。

修复建议：把关键词与 label 比较（参考 L68 正确写法）：

```kotlin
val hasPositive = labels.any { label -> dialogPositive.any { k -> label.contains(k, ignoreCase = true) } }
val hasDismiss   = labels.any { label -> dialogDismiss.any { k -> label.contains(k, ignoreCase = true) } }
```

---

### BUG-02（高危）AgentEngine.kt L936 when 分支重复标签

文件：[AgentEngine.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L936-L939)

```kotlin
ActionType.TYPE_TEXT, ActionType.TYPE_TEXT -> {
    if (x == null || y == null) com.phoneagent.execution.VerifyResult(false, "无法定位输入框", "", "")
    else verifier.executeAndVerify(snapshot, action) { executor.typeText(action.text ?: "", target).isSuccess() }
}
```

问题描述：
- `ActionType.TYPE_TEXT` 被列出两次（ActionType 中该常量唯一，见 [AgentAction.kt L81](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/model/AgentAction.kt#L81)）。
- Kotlin `when` 表达式对重复条件会报编译错误 `Duplicate label in 'when' expression`；若编译器放行为警告，则第二个条件为永远不可达的死代码。
- 无论哪种情况，这里都暴露了笔误（很可能原本想写别名类型），属于必须修复的缺陷。

修复建议：删除重复的条件，只保留一个分支，并根据意图补充真正需要的动作类型：

```kotlin
ActionType.TYPE_TEXT -> { ... }
```

---

### BUG-03（高危）AgentEngine.kt L921-933 滑动终点坐标越界

文件：[AgentEngine.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L919-L934)

```kotlin
ActionType.SWIPE_UP -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x, y - screenHeight()).isSuccess() }
ActionType.SWIPE_DOWN -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x, y + screenHeight()).isSuccess() }
ActionType.SWIPE_LEFT -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x - screenWidth(), y).isSuccess() }
ActionType.SWIPE_RIGHT -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x + screenWidth(), y).isSuccess() }
```

问题描述：
- 滑动终点 `y - screenHeight()` 几乎恒为负值、`y + screenHeight()` 恒超过屏高、`x ± screenWidth()` 同理，全部落在屏幕外。
- ActionExecutor 通过 AccessibilityService.dispatchGesture 派发手势；系统对路径坐标越界的手势会判定无效，dispatchGesture 返回 false 或触发 onCancelled，滑动不生效。
- 而同一文件中的 `swipeEndpoints()`（L1038-1047）已对坐标做 coerceAtLeast/coerceAtMost 收敛，说明这里遗漏了同样的 clamp 处理。
- 仅在 Shizuku 不可用、走无障碍回退路径时触发；Shizuku 可用时 AI 使用 shell su/sd/sl/sr（终点约 25% 屏距，一般安全）。

修复建议：复用 swipeEndpoints 并对终点收敛到屏幕内，例如：

```kotlin
ActionType.SWIPE_UP -> verifier.executeAndVerify(snapshot, action) {
    executor.swipe(x, y, x, (y - screenHeight()).coerceAtLeast(0)).isSuccess()
}
// 其余三个方向同样用 coerceIn(0, screenHeight()/screenWidth()) 收敛终点
```

---

### BUG-04（中危）AiClient.kt chatStream 重试导致内容重复

文件：[AiClient.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/ai/AiClient.kt#L364-L406)

问题描述：
- `val full = StringBuilder()` 声明在 `for (attempt in 0 until MAX_RETRIES)` 循环之外。
- 当第 1 次流式读取中途异常（超时/断流）被 catch 后 `continue` 重试，`full` 中已累积第 1 轮的增量内容；第 2 轮成功后把完整内容再 append，最终 `full.toString()` 包含两轮重复文本。
- 规划/文档生成场景下，AI 正文重复会显著增加 JSON 解析失败概率、浪费 token、界面显示错乱。

修复建议：将 `full` 声明移入重试循环内（每次尝试重建），并在 catch 时清空已累积内容：

```kotlin
for (attempt in 0 until MAX_RETRIES) {
    val full = StringBuilder()   // 每次尝试独立累积
    ...
}
```

---

### BUG-05（中危）倒计时广告处理与 Prompt 铁律矛盾

文件：
- [PageAnnotator.kt L96](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/perception/PageAnnotator.kt#L96)：contextHint 输出"【⚠️ 疑似倒计时广告】请尽快点击跳过按钮"
- [LocalDecisionEngine.kt L66-71](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/decision/LocalDecisionEngine.kt#L66-L71)：handleAd 直接返回"点击跳过"动作

问题描述：
- 系统 Prompt（[AgentPrompts.kt L76-77 / L252-253](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentPrompts.kt#L76-L77)）明确铁律："疑似倒计时广告 → 必须输出 wait，绝对禁止 tap；禁止点击跳过或任何覆盖层按钮（原因：点击会误触底层元素）"。
- 但本地决策 `handleAd()` 与 contextHint 却直接引导点击"跳过"按钮，两者自相矛盾。
- 若设计上确需本地快速点击跳过，应同步修改 Prompt 铁律与 contextHint 文案，统一行为；否则保留只读 wait 策略。

修复建议：二选一并保持全局一致：
1. 移除 handleAd 的跳过点击，倒计时广告页统一输出 `wait` 动作；或
2. 保留本地点击，但同步更新 systemPrompt 铁律与 generateContextHint 文案，避免 AI 与端侧行为冲突。

---

### BUG-06（中危）SensitivePageDetector.kt 身份证脱敏不完整

文件：[SensitivePageDetector.kt](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/security/SensitivePageDetector.kt#L45-L50)

```kotlin
out = phoneRegex.replace(out) { it.value.take(3) + "****" + it.value.takeLast(4) }
out = bankRegex.replace(out) { it.value.take(4) + "****" + it.value.takeLast(4) }
out = idRegex.replace(out) { it.value.take(6) + "********" + it.value.takeLast(4) }
```

问题描述：
- 身份证 18 位（17 位数字 + 1 位校验，可能为 X）会在 `bankRegex`（`\d{16,19}`）步骤被贪婪匹配前 17 位数字，被替换为 `前4位****后4位`，剩余校验位保留，例如 `11010119900307123X` → `1101****07123X`，此时 `idRegex` 已无法再匹配，导致身份证仅隐藏 4 位，大量明文泄露。
- 部分以 `1[3-9]` 开头的身份证还可能被 `phoneRegex` 先行截断匹配，进一步破坏脱敏完整性。

修复建议：先执行 idRegex 再执行 bankRegex，并用零宽断言避免交叉匹配（如 `(?<!\d)(?:\d{17}[0-9Xx])(?!\d)` 优先），或将正则统一为"完整匹配整个数字串后按长度分流"：

```kotlin
out = idRegex.replace(out) { it.value.take(6) + "********" + it.value.takeLast(4) }
out = bankRegex.replace(out) { it.value.take(4) + "****" + it.value.takeLast(4) }
```

---

### BUG-07（低危）AgentEngine.kt 死代码：异常记忆/画像引擎未接线

文件：[AgentEngine.kt L83-84](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L83-L84)

- `anomalyEngine`、`profileLearner` 被实例化（并对应注释"第 10 层：异常记忆"），但全文件仅 `memory.loadProfile()` 被使用，两个引擎对象从未参与决策/失败处理逻辑。
- 影响：声明的"异常记忆复用"能力实际未生效；也增加了维护成本。
- 修复建议：在失败/用户介入流程中接入 `anomalyEngine.findSolution` / `profileLearner.learnAnomalySolution`，或删除这两个未使用对象。

---

### BUG-08（低危）MemoryStore.kt recordUse 非原子读改写

文件：[MemoryStore.kt L85-97](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/memory/MemoryStore.kt#L85-L97)

- `loadAnomalies()` → 修改列表 → `saveAnomalies()` 三步非原子，若多个协程并发调用会相互覆盖，丢失 hitCount/successCount 增量。
- 修复建议：对该段加互斥（如 Mutex），或在 DataStore.edit 事务内完成读改写。

---

### BUG-09（低危）AiClient.kt base64Image 流未关闭

文件：[AiClient.kt L565-571](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/ai/AiClient.kt#L565-L571)

- `ByteArrayOutputStream` 未通过 use/close 释放（close 为 no-op，实际泄漏可忽略，属规范问题）。
- 修复建议：`ByteArrayOutputStream().use { ... }`。

---

### BUG-10（低危）AgentEngine.kt 日志/翻译缓存无限增长

文件：[AgentEngine.kt L109](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L109)、[L218-222](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L218-L222)

- `_logs.value` 追加、`translateCache` 写入均无上限，长时间运行内存占用持续增长。
- 修复建议：日志列表保留最近 N 条（参考 WorkAreaEngine L427 的 takeLast(200)）；translateCache 增加大小上限或 LRU。

---

### BUG-11（低危）ShellCommands.kt toPixel 对 ≤1 像素值误判为比例

文件：[ShellCommands.kt L172](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/ShellCommands.kt#L172)

- `if (f <= 1.0f) (f * dim).toInt()... else f.toInt()`：合法的像素坐标 0 或 1 会被当作比例换算成整个屏幕，导致 tap/lp 点击错误位置。
- 修复建议：明确像素判定（如 `f > 1.0f` 为像素，或要求用户显式指定单位），避免 0/1 歧义。

---

### BUG-12（低危）LocalDecisionEngine.kt handleDialog 命中正向按钮时 reason 语义错误

文件：[LocalDecisionEngine.kt L63](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/decision/LocalDecisionEngine.kt#L53-L64)

- 命中"允许/同意/确定"等正向按钮时，reason 仍固定为"关闭弹窗"，与真实意图不符，影响日志与决策上下文。
- 修复建议：根据 target 来源区分 reason（正向按钮 → "点击确认按钮"；关闭按钮 → "关闭弹窗"）。

---

### BUG-13（低危）CloudAgent.kt verify 死代码

文件：[CloudAgent.kt L16-19](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/network/CloudAgent.kt#L16-L19)

- `verify()` 未被任何调用方使用（实际执行验证走 VerifiedClickExecutor）。
- 修复建议：删除或接入真实执行验证链路。

---

## 第三部分：文件更新日志

| 日期 | 文件 | 变更 |
|------|------|------|
| 2026-08-15 | BUGS/bug_report_core_logic.md | 新建。核心逻辑模块只读排查，报告 BUG-01 ~ BUG-13（高危 3、中危 3、低危 7）。未修改任何业务代码。 |

---

## 附：已排查但未发现问题的文件

- AgentPrompt.kt：正常。
- AgentPrompts.kt：Prompt 文案逻辑正常（BUG-05 为与本地决策的交叉矛盾，已在主体列出）。
- GlmDefaults.kt：正常。
- AiDecision.kt / ChatModels.kt：正常。
- AgentAction.kt / AgentState.kt / ScreenSnapshot.kt / UiElement.kt：正常。
- PageFingerprint.kt：`computeMeaningful` 用 `centerX / 10` 整数除法丢失精度，但对"页面是否变化"判断影响可忽略，未列为 BUG。
- VerifiedClickExecutor.kt：指纹对比验证逻辑正常。
