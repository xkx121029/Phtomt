# Happy Phone Agent — Bug 排查报告

> 现象：AI 请求用户接管（悬浮窗 / AI Agent 页显示“需要你的协助，Agent 已暂停”）后，用户手动完成后点击“已手动处理”，任务本应继续，却直接退出。
> 排查时间：2026-08-18
> 排查对象：`happy_phone agent` Android Kotlin 工程

---

## 第一部分：Bug 定级总览

### 一、严重、高危

| 编号 | 组件 | 位置 | 问题摘要 | 影响 |
|------|------|------|----------|------|
| BUG-001 | AgentEngine | `SELF_DISMISS_HINT` 常量（第170行）→ 云端决策链路（第794-813行） | “已手动处理”发出的语义信号文本 `[[自处理]]已手动处理完成…` 中的“已手动处理完成”强烈诱导 AI 判定任务已结束，返回 `task_done`，任务被正常收尾为 DONE | 用户点击“已手动处理”后 AI 认为任务已完成，Agent 立即结束，任务不继续 —— 即“点击即退”的直接根因 |

### 二、一般、中危

| 编号 | 组件 | 位置 | 问题摘要 | 影响 |
|------|------|------|----------|------|
| BUG-002 | FloatingWindowService | `showHintInput()` “指导 AI”按钮（第740-744行） | “指导 AI”按钮无空文本防护，留空点击仍调用 `onInteraction("hint","")`，被 `provideUserHint` 的 `isBlank` 直接丢弃 | 用户留空点击“指导 AI”时信号被静默丢弃，`awaitUserHint().first()` 永久挂起，“Agent 已暂停”再也无法被解除（静默卡死） |
| BUG-003 | AgentEngine | `_userHintResult.tryEmit`（第556、563行） | `tryEmit` 返回值被忽略；`MutableSharedFlow(extraBufferCapacity=8)` 在无订阅或缓冲满时静默丢弃信号 | 极端时序下用户响应丢失 → Agent 挂起；且与 BUG-001 叠加时无法通过“已手动处理”续跑 |

### 三、较低、低危

| 编号 | 组件 | 位置 | 问题摘要 | 影响 |
|------|------|------|----------|------|
| BUG-004 | AgentEngine | guide 接管分支（第794-813行，尤其 804-813 外部回落） | 当 `decideWithUserHint` 返回 `null` 或 `task_done` 时，`action` 未被更新，代码回落到“再执行一次旧的失败动作”，造成无效重试一回合 | 引导失败时浪费一步且日志误导；非崩溃，但放大了 BUG-001 的感知 |
| BUG-005 | AgentEngine | `awaitUserHint()` 与敏感页只读分支（第707-716行） | 敏感页分支收到非空 `SELF_DISMISS_HINT` 后 `continue`，若页面仍被判定敏感会再次进入接管，反复重提示 | 页面持续敏感时出现接管循环，非致命但体验差 |

---

## 第二部分：问题代码位置、修复建议与描述

### BUG-001（严重·高危）：SELF_DISMISS_HINT 诱导 AI 下发 task_done，导致“点击即退”

**位置与证据：**

- 常量定义 [AgentEngine.kt:170](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L170)
  ```kotlin
  const val SELF_DISMISS_HINT = "[[自处理]]已手动处理完成，请继续观察当前页面并重新决策下一步"
  ```
- 结尾的 `$hint` 整段被注入 AI 上下文：
  - 直接注入 messages：[AgentEngine.kt:797](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L797) `"用户提示：$hint 请据此重新决策下一步动作。"`
  - 作为 `userHint` 传给云端：[AgentEngine.kt:799](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L799) → [CloudAgent.kt:30-34](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/network/CloudAgent.kt#L30-L34) `"用户提示：$userHint\n请据此重新决策下一步动作。"`
- `task_done` 收尾逻辑接受后正常退出：[AgentEngine.kt:759-766](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L759-L766)

**判定：**
点击“已手动处理”→ `dismissUser()`（[第559-564行](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L559-L564)）发出**非空** `SELF_DISMISS_HINT` → `awaitUserHint()` 正常返回（非空，不会走 `isBlank→stop`）→ 进入 guide 分支，把包含“已手动处理完成”的文本交给 AI → AI 把用户的“手动完成”解读为任务已达成，命中 [第759行](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L759) 的 `TASK_DONE` 收尾 `return`。因此从用户视角是“点一下任务就结束”。

**修复建议：**
1. 改写语义信号文本，去掉“已完成/完成”这类易被误判为收尾的词，突出“继续执行剩余步骤”。例如：
   ```kotlin
   const val SELF_DISMISS_HINT =
       "[用户接管] 用户已处理该环节，请重新观察当前页面继续执行剩余步骤；只有真正完成整项任务才输出 task_done"
   ```
2. 更稳妥：在 guide 分支对 `SELF_DISMISS_HINT` 单独处理——不直接喂给 `decideWithUserHint`，而是仅把“用户已接管当前步骤，请重新决策下一步”加入轻量上下文后 `continue`，回到 [第719行](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L719) 走 `cloudDecide` 完整观测；把 `decideWithUserHint` 仅保留给“用户输入具体指导文本”的 `provideUserHint` 场景。
3. 可选：把 `task_done` 收尾要求“亲眼看到结果证据”的强提示也注入到 `decideWithUserHint` 的 prompt，防止离线臆断任务完成。

---

### BUG-002（一般·中危）：悬浮窗“指导 AI”留空点击导致静默挂起

**位置：** [FloatingWindowService.kt:740-744](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/floating/FloatingWindowService.kt#L740-L744)
```kotlin
addBtn(hintBtnRow, "指导 AI", true) {
    val text = hintInput?.text?.toString()?.trim() ?: ""
    onInteraction?.invoke("hint", text)   // 文本为空也照样发送
    hintInput?.setText("")
}
```
而 [AgentEngine.kt:553-557](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L553-L557)：
```kotlin
fun provideUserHint(hint: String) {
    if (hint.isBlank()) return   // 空串直接吞掉，不发任何信号
    ...
    _userHintResult.tryEmit(hint)
}
```

**判定：** 同一场景下 [AgentScreen.kt:396-398](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/ui/agent/AgentScreen.kt#L396-L398) 的“指导 AI”有 `enabled = hint.isNotBlank()` 防护，悬浮窗侧**缺失**该防护。留空点击 → 空串被吞 → `awaitUserHint().first()` 永不返回 → “Agent 已暂停”永久挂起。

**修复建议：** 与 AgentScreen 对齐，在发送前拦截空文本，避免无谓发出：
```kotlin
addBtn(hintBtnRow, "指导 AI", true) {
    val text = hintInput?.text?.toString()?.trim() ?: ""
    if (text.isEmpty()) {
        onInteraction?.invoke("dismiss", "")   // 留空=已手动处理好，等价“已手动处理”
        hintInput?.setText("")
    } else {
        onInteraction?.invoke("hint", text)
        hintInput?.setText("")
    }
}
```
（也可在 `provideUserHint` 内把“空串”转义成 `SELF_DISMISS_HINT` 语义。）

---

### BUG-003（一般·中危）：`tryEmit` 返回值被忽略，SharedFlow 信号可能静默丢失

**位置：**
- 定义 [AgentEngine.kt:152](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L152) `_userHintResult = MutableSharedFlow<String>(extraBufferCapacity = 8)`（`replay=0`）
- 发送 [AgentEngine.kt:556](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L556) 与 [AgentEngine.kt:563](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L563)（均未检查返回）
- 接收 [AgentEngine.kt:1266-1271](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L1266-L1271) `_userHintResult.first()`

**判定：** `MutableSharedFlow(replay=0)` 在发送时刻若没有活动订阅者、或缓冲被占满时，值会被**丢弃**；`tryEmit` 返回 `false`。当前所有调用点都忽略返回值。正常时序下（`awaitUserHint` 先行订阅后用户才点击）不会丢失；但在悬浮窗/页面多处同时显示、或连续快速点击等边缘时序下存在掉信号风险，掉后表现为 Agent 挂起而非退出。

**修复建议：**
- 优先改用具象化的“最后一条”语义通道，杜绝丢消息：
  ```kotlin
  private val _userHintResult = Channel<String>(Channel.CONFLATED)
  // awaitUserHint: val hint = _userHintResult.receive()   // receive 亦保证挂起等待
  // dismissUser/provideUserHint: _userHintResult.trySend(...)（CONFLATED 至少保留最新一条）
  ```
- 或保留 SharedFlow 但：把 `replay` 改为 `1`，并检查 `tryEmit` 返回值，失败时记 `WARN` 日志并短暂 `withTimeout` 重发。

---

### BUG-004（较低·低危）：guide 引导返回 null/task_done 时回落重放旧失败动作

**位置：** [AgentEngine.kt:799-813](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L799-L813)。`guided` 为 `null` 或 `task_done` 时，`action` 与 `verify` 仍是接管前的旧失败值，接着走到 [第816-817行](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L816-L817) `recordsIntoHistory(step, action, verify)` 重复记账该失败动作（但不会 stop）。

**修复建议：** 在 `guided == null || TASK_DONE` 分支建议 `continue` 回到下一轮完整观测，而不是执行旧 action；至少记录更准确的日志避免误导。

---

### BUG-005（较低·低危）：敏感页只读分支持续被命中时反复进入接管

**位置：** [AgentEngine.kt:707-716](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L707-L716)。收到非空 `SELF_DISMISS_HINT` 后 `continue` 重新观测，若页面仍被判敏感会再次 `awaitUserHint()`。

**修复建议：** 引入敏感页接管的连续次数上限，超限给出提示并不再重复弹窗。

---

## 关于排查假设 (a/b/c)的结论

- **(a) isBlank 拦截：不是“点击即退”的根因。** `SELF_DISMISS_HINT` 是非常量长度的非空白字符串（含 `[[自处理]]` 与中文），[第714行](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L714) 与 [第795行](file:///d:/xkx/xkx_appproj/happy_phone%20agent/app/src/main/java/com/phoneagent/agent/AgentEngine.kt#L795) 的 `isBlank` 均判定为非空，因此不会走到 `stop(); return`。真正触发退出的链路是 BUG-001 的 AI `task_done` 收尾。
- **(b) SharedFlow 订阅缺失/信号丢失：正常点击时序下不丢失**（`awaitUserHint().first()` 在 UI 展示前已订阅），但 `tryEmit` 返回值被忽略是潜在隐患（BUG-003），表现为挂起而非退出。
- **(c) 协程 scope 取消：非根因。** `dismissUser()`/`provideUserHint()` 仅 `SupervisorJob` 作用域外的主线程回调里执行 `tryEmit`，不取消 `job`，不会中断挂起的 `first()`。
- **(d) AI 判定 task_done：根因。** 参见 BUG-001。
- **(e) 悬浮窗“指导 AI”留空点击：确凿缺陷，静默挂起。** 参见 BUG-002。

---

## 第三部分：文件更新日志

| 日期 | 文件 | 变更 |
|------|------|------|
| 2026-08-18 | `BUGS/BUGS_REPORT.md` | 首次创建：登记 BUG-001~BUG-005，完成“点击已手动处理→任务退出”根因定位 |