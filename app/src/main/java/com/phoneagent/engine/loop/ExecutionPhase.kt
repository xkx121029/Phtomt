package com.phoneagent.engine.loop

import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.VerifyResult
import com.phoneagent.engine.execution.VerifyRules
import com.phoneagent.engine.runtime.EngineLimits.MAX_SAY_STREAK
import com.phoneagent.engine.runtime.ExecutionFeedback
import com.phoneagent.engine.runtime.LoopGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.math.ceil

/** 执行阶段的界面出口：日志 / 悬浮窗 / 状态 / 协作面板 / 对话气泡 / 结束提示 */
internal class ExecutionUiHooks(
    val log: (AgentLog.Level, String) -> Unit,
    val pushFloating: (String, String) -> Unit,
    val state: () -> AgentState,
    val setState: (AgentState) -> Unit,
    val showFloatingInteraction: (String, String, String) -> Unit,
    val setNeedsUser: (Boolean) -> Unit,
    val emitHintRequest: (String) -> Unit,
    val awaitUserHint: suspend () -> String,
    val pushThinking: (String?, String?) -> Unit,
    val showDone: (String) -> Unit,
    val setAgentRunning: (Boolean) -> Unit,
    val addConversation: (String, String) -> Unit,
)

/** 执行阶段的留档出口：步骤记录 / 历史 / 截图 / 进度 / 检查点 / 异常记忆 */
internal class ExecutionRecordHooks(
    val recordStep: (Int, AgentAction, String, String, String, String) -> Unit,
    val recordsIntoHistory: (Int, AgentAction, VerifyResult) -> Unit,
    val stepShotCapture: suspend (Int, AgentAction, Boolean) -> Unit,
    val recordProgress: (Int, AgentAction) -> Unit,
    val saveCheckpoint: suspend () -> Unit,
    /** 取走并清空上一条 shell 输出（原 `lastShellOutput` 的读+清） */
    val takeShellOutput: () -> String,
    /** 异常经验命中后的效果回写（原 `currentAnomalyEntry` 的读 + recordUse + 清空） */
    val recordAnomalyUse: suspend (Boolean) -> Unit,
    /** 用户指导沉淀为异常记忆（原 `profileLearner.learnAnomalySolution` 的整段 runCatching） */
    val learnSolution: suspend (ScreenSnapshot, String, String, String) -> Unit,
    val pageMemoryOnIntent: (AgentIntent) -> Unit,
    val pageMemoryOnStepResult: (Boolean) -> Unit,
)

/** 执行阶段的设备/端侧代办出口：转译、执行、重观察、确认门、纯端侧动作、浏览器通道 */
internal class ExecutionDeviceHooks(
    /** 取走上一步视觉坐标（原 `lastVisualCoordinate` 的读 + 清空） */
    val takeVisualCoordinate: () -> Pair<Int, Int>?,
    val translate: (AgentIntent, ScreenSnapshot, Pair<Int, Int>?) -> IntentTranslator.TranslationResult,
    val fillMissingParam: suspend (
        IntentTranslator.TranslationResult,
        AgentIntent,
        ScreenSnapshot,
        com.phoneagent.data.prefs.AppSettings.Settings,
    ) -> IntentTranslator.TranslationResult,
    val execute: suspend (AgentAction, ScreenSnapshot) -> VerifyResult,
    val reobserve: suspend () -> ScreenSnapshot,
    val ensureShellApproval: suspend (AgentAction) -> Boolean,
    val awaitActionConfirmation: suspend (AgentAction) -> Boolean,
    val handleRemember: suspend (String, Int, AgentAction) -> Unit,
    val handleDeviceQuery: suspend (Int, AgentAction, MutableList<ChatMessageDto>) -> Unit,
    /** 系统直连子操作：端侧按能力族 + op 分派，不操控屏幕，结果回注下一轮 */
    val handleDeviceOps: suspend (Int, AgentAction, MutableList<ChatMessageDto>) -> Unit = { _, _, _ -> },
    val handleSay: (Int, AgentAction) -> Unit,
    val handleShowAgent: (Int, AgentAction) -> Unit,
    val browserHandles: (String) -> Boolean,
    val handleBrowse: suspend (Int, AgentIntent, MutableList<ChatMessageDto>) -> Pair<AgentAction, VerifyResult>,
    /** 用户指导后的重新决策（引擎内已含看门狗与"再试一次"，返回值语义与原文一致） */
    val decideWithUserHint: suspend (MutableList<ChatMessageDto>, String) -> AgentIntent?,
    val actionMode: () -> ActionMode,
    val idempotencyDone: (ScreenSnapshot) -> Boolean,
    /** 协助文案里的无障碍断线提示（原 `AgentAccessibilityService.instance == null` 判定） */
    val a11yDisconnectedNote: () -> String,
)

/** 执行阶段的收尾出口：任务记忆终态 / 模板 / 检查点 / 提炼 / 引擎停止 */
internal class ExecutionFinishHooks(
    /** 终态写入：status 取 [TaskMemoryEntry] 的常量，note 为附加结论（null = 原样调用） */
    val finishTaskMemory: (String, String?) -> Unit,
    val learnTemplate: suspend () -> Unit,
    val clearCheckpoint: suspend () -> Unit,
    /** 完成后的模板询问（原 `reusedTemplateId` + `pendingTemplateTask/Plan` + showDone 的整段判定） */
    val onTaskCompleted: (String) -> Unit,
    val distillMemories: suspend (String) -> Unit,
    val stopEngine: () -> Unit,
)

/** 执行阶段持有的连续计数（仍在引擎里按任务归零，阶段只读写） */
internal class ExecutionCounters(
    val earlyDoneRejections: () -> Int,
    val setEarlyDoneRejections: (Int) -> Unit,
    val sayStreak: () -> Int,
    val setSayStreak: (Int) -> Unit,
    val consecutiveFailures: () -> Int,
    val setConsecutiveFailures: (Int) -> Unit,
    val invalidCommandStreak: () -> Int,
    val setInvalidCommandStreak: (Int) -> Unit,
)

/**
 * 执行阶段（阶段 B4）：意图 → 转译 → 门控 → 执行+验证重试 → 留档/回注 → 收尾判定。
 *
 * 从 AgentEngine 主循环原样迁入，`continue` / `return` 统一收成 [StepOutcome]：
 * - Continue = 本步结束，进入下一轮观测
 * - Return   = 任务已明确结束（完成 / 放弃 / 用户离开），循环壳直接返回
 * - Fatal    = 需要按失败收尾（循环壳写失败终态并停止）
 *
 * 行为等价要点（逐条对应原 runInner）：
 * - 浏览器通道先于转译层分流，且直接 return，不进后续留档（原文是 `continue`，
 *   但留档与截图在分流分支内已自行完成）
 * - 提前完成拦截门槛：有计划 = 规划步数 60%，无计划 = 3；连续 3 次拒绝后按完成处理
 * - GIVE_UP 与 FINISH 都落到 TASK_DONE，但放弃走失败口径、不学模板、不弹保存确认
 * - 结构性错误不重试；点击类动作不在外层重跑（判据在 [VerifyRules]）
 * - 引导后的意图要再过动作模式门控与不可逆/自写命令确认门，不能成为越权旁路
 * - 引导失败/完成时只补一条失败留档后回到观测，绝不重放接管前的旧失败动作
 */
internal class ExecutionPhase(
    private val loopGuard: LoopGuard,
    private val currentPlan: () -> TaskPlan?,
    private val ui: ExecutionUiHooks,
    private val record: ExecutionRecordHooks,
    private val device: ExecutionDeviceHooks,
    private val finish: ExecutionFinishHooks,
    private val counters: ExecutionCounters,
) : StepPhase {

    // 与 DecisionPhase 同一口径：块内可能调用 suspend 的补参逻辑，必须 inline
    private inline fun <T> tryOrNull(message: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ui.log(AgentLog.Level.WARN, "$message：${e.message ?: e.javaClass.simpleName}")
            null
        }

    override suspend fun run(session: StepSession): StepOutcome {
        val messages = session.messages
        val snapshot = session.snapshot
        val step = session.step
        val intent = session.intent ?: return StepOutcome.Fatal("决策未产出意图")

        // 3.7 浏览器通道分流：browse_* 是**与设备能力无关**的独立通道，先于转译层落地。
        //     它不走 IntentTranslator 的策略表（不受无障碍/Shizuku/无线 ADB 影响），
        //     也不产生 AgentAction：网页读写由本 App 的 WebView 用 DOM 脚本完成，
        //     结果作为"上一步结果"回注下一轮决策。
        if (device.browserHandles(intent.intent)) {
            val (browseAction, browseVerify) = device.handleBrowse(step, intent, messages)
            ui.log(
                AgentLog.Level.INFO,
                "执行动作：${browseAction.type}（${if (browseVerify.success) "已验证生效" else "待确认"}）",
            )
            record.recordsIntoHistory(step, browseAction, browseVerify)
            record.pageMemoryOnStepResult(browseVerify.success)
            record.stepShotCapture(step, browseAction, browseVerify.success)
            return StepOutcome.Continue
        }

        // 4. 转译：意图 → 内部命令（端侧按授权模式选通道/定位/算坐标，AI 无感知）。
        //    参数缺失（如 tap 没给 target、open_app 没给 app）时，端侧先向 AI 追问一次补全，再重转译，而非直接失败。
        //    转译异常（定位/坐标换算内部出错）按转译失败处理：交给既有失败链路回注 AI 纠正，而不是崩掉任务。
        //    视觉坐标"消费一次"：上一步 see/视觉定位产出的坐标只供本次转译使用，取走即清空。
        val carry = device.takeVisualCoordinate()
        var translation = tryOrNull("意图转译失败") {
            device.translate(intent, snapshot, carry)
        } ?: IntentTranslator.TranslationResult.Failed("意图转译异常，请重试")
        translation = tryOrNull("参数补全失败") {
            device.fillMissingParam(translation, intent, snapshot, session.settingsVal)
        } ?: translation

        var action: AgentAction = AgentAction(type = "")
        var verify = VerifyResult(false, "", "", "")
        var isStructuralError = false
        var verified = false
        when (translation) {
            is IntentTranslator.TranslationResult.Command -> {
                val act = translation.action
                action = act
                ui.addConversation(if (session.fromLocal) "local" else "assistant", intent.toString())
                ui.log(
                    AgentLog.Level.INFO,
                    "意图转译为动作：intent=${intent.intent} → action.type=${act.type} pkg=${act.packageName} " +
                        "cmd=${act.command} target=${act.target}",
                )
                ui.setState(
                    ui.state().copy(
                        phase = AgentState.Phase.ACTING,
                        lastAction = intent,
                        message = act.reason ?: "",
                    ),
                )
                ui.pushFloating(act.reasoning ?: act.reason ?: "正在执行", "ACTING")

                // 提前完成拦截门槛：有计划时 = 规划步数向上取整的 60%（不再叠加"至少 3 步"的下限，
                // 否则两三步的小任务会被强行拦截到凑步数）；无计划时保持 3 步的经验值
                val plan = currentPlan()
                val minDoneThreshold = if (plan != null && plan.steps.isNotEmpty())
                    ceil(plan.steps.size * 0.6).toInt()
                else
                    3
                // 证据优先：当前页面已出现明确的"完成"证据时（复用 EngineRules.idempotencyDone，
                // 与幂等保护同一口径；判定含稀疏守卫，普通列表页的历史"已完成"字样不算证据），
                // 即便提前宣称完成也直接放行，不再为凑步数空跑；无证据时才沿用上面的步数门槛照旧拦截。
                // 放弃（GIVE_UP）不属"完成"，不适用证据放行，仍按原门槛拦截。
                val hasDoneEvidence = intent.intent != IntentType.GIVE_UP && device.idempotencyDone(snapshot)
                if (act.type == ActionType.TASK_DONE && step < minDoneThreshold && !hasDoneEvidence) {
                    // 要求 AI 重新决策，并注入纠偏提示
                    counters.setEarlyDoneRejections(counters.earlyDoneRejections() + 1)
                    if (counters.earlyDoneRejections() >= 3) {
                        // 连续 3 次拒绝仍坚持完成 → 按完成处理，避免死循环
                        ui.log(AgentLog.Level.WARN, "AI 连续 3 次提前宣称完成，按完成处理避免死循环")
                        counters.setEarlyDoneRejections(0)
                    } else {
                        ui.log(
                            AgentLog.Level.WARN,
                            "AI 仅执行 $step 步即宣称完成（门槛 $minDoneThreshold 步），要求重新决策",
                        )
                        messages.add(
                            ChatMessageDto(
                                role = "user",
                                content = listOf(
                                    ContentPart(
                                        type = "text",
                                        text = "⚠️ 你当前仅执行了 $step 步（任务通常至少需要 $minDoneThreshold 步）。" +
                                            "请确认目标是否真的达成：只有亲眼在当前页面看到任务结果的明确证据才能输出 finish；" +
                                            "否则继续执行真正需要的关键步骤，不要凑数，也不要提前结束。",
                                    ),
                                ),
                            ),
                        )
                        return StepOutcome.Continue
                    }
                }
                if (act.type == ActionType.TASK_DONE) {
                    val doneSummary = act.summary ?: "任务完成"
                    // FINISH 与 GIVE_UP 都落到 TASK_DONE 动作（见转译层）：放弃绝不能按成功口径收尾
                    if (intent.intent == IntentType.GIVE_UP) {
                        ui.log(AgentLog.Level.WARN, "任务已放弃：$doneSummary")
                        record.recordStep(step, act, VerifyRules.STATUS_VERIFIED, "", "", verify.reason)
                        // phase 不得写成 DONE：settleTaskSession 按 phase==DONE 判「已完成」，
                        // 放弃必须落到失败口径（侧边栏状态=失败）
                        ui.setState(
                            ui.state().copy(
                                phase = AgentState.Phase.IDLE,
                                message = "已放弃：$doneSummary",
                                isRunning = false,
                            ),
                        )
                        ui.setAgentRunning(false)
                        // 放弃收尾：不学模板（learnTemplate）、不弹保存模板确认，收尾提示文案用「任务已放弃」
                        ui.showDone("任务已放弃")
                        // 任务记忆走失败口径，结论带「已放弃」前缀，会话承接时能看出是主动放弃
                        finish.finishTaskMemory(TaskMemoryEntry.STATUS_FAILED, "已放弃：$doneSummary")
                        return StepOutcome.Return
                    }
                    ui.log(AgentLog.Level.INFO, "任务完成：$doneSummary")
                    record.recordStep(step, act, VerifyRules.STATUS_VERIFIED, "", "", verify.reason)
                    ui.setState(
                        ui.state().copy(
                            phase = AgentState.Phase.DONE,
                            message = doneSummary,
                            isRunning = false,
                        ),
                    )
                    ui.setAgentRunning(false)
                    // 任务完成后的收尾：模板处理（复用模板回写健康；全新计划经用户确认才入库）+ 检查点清空
                    runCatching { finish.learnTemplate() }
                    runCatching { finish.clearCheckpoint() }
                    runCatching { finish.onTaskCompleted(doneSummary) }
                    // 任务记忆先收尾（目标已达成）：提炼再慢也不影响记忆页立刻变成「已完成」
                    // 完成说明一并落库，作为会话承接时"上一轮任务"的结论
                    finish.finishTaskMemory(TaskMemoryEntry.STATUS_SUCCESS, doneSummary)
                    // 记忆提炼放最后：完成提示先给到用户，提炼再慢也不影响「已完成」的观感
                    // （独立调用一次模型，不写 conversation，因此不污染主决策上下文）
                    runCatching { finish.distillMemories(doneSummary) }
                    return StepOutcome.Return
                }
                // 记忆写入：纯本地写库，不操作屏幕，不走通道/不截图/不重试
                if (act.type == ActionType.REMEMBER) {
                    device.handleRemember(session.task, step, act)
                    return StepOutcome.Continue
                }
                // 本机信息查询：同类端侧代办，读到的内容作为"上一步结果"回注下一轮决策
                if (act.type == ActionType.DEVICE_QUERY) {
                    device.handleDeviceQuery(step, act, messages)
                    return StepOutcome.Continue
                }
                // 系统直连（设备状态/设置/剪贴板/闹钟管理/副屏/记忆检索）：同样纯端侧代办，
                // 不经过 GUI、不触发 shell 确认（命令由端侧拼装，AI 只选 op）
                if (act.type == ActionType.DEVICE_OPS) {
                    device.handleDeviceOps(step, act, messages)
                    return StepOutcome.Continue
                }
                // AI 对用户说话：纯端侧呈现，不操作设备、不截图、不重试，
                // 也不落 StepRecord（否则同一句话既是一张步骤卡又是一条气泡）
                if (act.type == ActionType.SAY) {
                    if (counters.sayStreak() >= MAX_SAY_STREAK) {
                        counters.setSayStreak(0)
                        messages.add(
                            ChatMessageDto(
                                role = "user",
                                content = listOf(
                                    ContentPart(
                                        type = "text",
                                        text = "⚠️ 你已连续说了 $MAX_SAY_STREAK 次而没有执行任何动作。" +
                                            "请立刻输出真正的操作意图；只有确实需要向用户澄清关键信息时才可以再说一次。",
                                    ),
                                ),
                            ),
                        )
                        return StepOutcome.Continue
                    }
                    device.handleSay(step, act)
                    counters.setSayStreak(counters.sayStreak() + 1)
                    return StepOutcome.Continue
                }
                // 引导用户回 Agent 页看结果：同样纯端侧，但要真的把人带回来（拉起本应用 + 切页）
                if (act.type == ActionType.SHOW_AGENT) {
                    device.handleShowAgent(step, act)
                    counters.setSayStreak(0)
                    return StepOutcome.Continue
                }
                // 自由模式：AI 自写命令在本任务首次执行前要向用户确认一次。
                // 拒绝不等于放弃任务——把"用户拒绝了这条命令"回注给 AI，让它换条路子继续干。
                if (!device.ensureShellApproval(act)) {
                    val denied = "用户拒绝执行这条命令：${act.command}。" +
                        "请不要重试同一条命令：改用转译层既有意图完成当前目标，或换一条用户更可能接受的命令。"
                    ui.log(AgentLog.Level.WARN, "用户拒绝 AI 自写命令，已跳过执行：${act.command}")
                    messages.add(
                        ChatMessageDto(
                            role = "user",
                            content = listOf(ContentPart(type = "text", text = "⚠️ $denied")),
                        ),
                    )
                    record.recordsIntoHistory(step, act, VerifyResult(false, denied, "", ""))
                    record.stepShotCapture(step, act, false)
                    return StepOutcome.Continue
                }
                // 不可逆动作确认门：AI/转译层标记 needsUserConfirmation 的动作，执行前先问用户。
                // 取消/超时则该步记跳过，并把用户的决定回注给 AI 换路子，而不是硬执行
                if (act.needsUserConfirmation && !device.awaitActionConfirmation(act)) {
                    val cancelReason = "用户取消了该操作"
                    ui.log(AgentLog.Level.WARN, "用户取消不可逆操作，已跳过执行：${act.type}")
                    messages.add(
                        ChatMessageDto(
                            role = "user",
                            content = listOf(
                                ContentPart(
                                    type = "text",
                                    text = "⚠️ 用户拒绝执行该不可逆操作，请改用其他方案或询问用户，不要重复请求同一操作。",
                                ),
                            ),
                        ),
                    )
                    record.recordsIntoHistory(step, act, VerifyResult(false, cancelReason, "", ""))
                    record.stepShotCapture(step, act, false)
                    return StepOutcome.Continue
                }
                // 真的动手了：把连续说话计数清掉
                counters.setSayStreak(0)
                verify = device.execute(act, snapshot)
                // 对确定性错误（未知命令/命令为空/参数无效）不重试，立即失败促使 AI 重新决策。
                // 判据（哪些错误算结构性、哪些动作不参与外层重试）下沉 VerifyRules，可单测
                isStructuralError = VerifyRules.isStructuralError(verify.reason)
                verified = verify.success
                var times = 1
                // 点击类动作不再交给外层重跑：ClickRunner 内部已把"活节点直点 → 手势点击 → 快照坐标 →
                // 滚动查找"逐级穷尽过一遍，外层再重跑只是把同一串动作重复执行（还可能造成重复副作用）
                while (!verified &&
                    VerifyRules.shouldRetry(act.type, verify.reason, times, coroutineContextIsActive())
                ) {
                    times++
                    ui.log(AgentLog.Level.WARN, "动作未生效（第 $times 次重试）：${act.type}")
                    repeat(3) { kotlinx.coroutines.delay(300) }
                    val v2 = device.execute(act, device.reobserve())
                    verified = v2.success
                }
            }
            is IntentTranslator.TranslationResult.Failed -> {
                val reason = translation.reason
                ui.log(AgentLog.Level.WARN, "意图转译失败：$reason")
                ui.addConversation("assistant", intent.toString())
                ui.setState(
                    ui.state().copy(
                        phase = AgentState.Phase.ACTING,
                        lastAction = intent,
                        message = "转译失败：$reason",
                    ),
                )
                action = AgentAction(
                    type = intent.intent,
                    reasoning = intent.reasoning,
                    reason = reason,
                    target = intent.target?.let { com.phoneagent.domain.model.ActionTarget(method = it.by, value = it.value) },
                )
                verify = VerifyResult(false, reason, "", "")
                isStructuralError = true
                verified = false
            }
            is IntentTranslator.TranslationResult.MissingParam -> {
                // 绝大多数情况下已被 fillMissingParam 补全成 Command/Failed；此处仅作 sealed 穷尽兜底，按失败处理
                val reason = translation.reason
                ui.log(AgentLog.Level.WARN, "意图缺参未补全：$reason")
                ui.addConversation("assistant", intent.toString())
                ui.setState(
                    ui.state().copy(
                        phase = AgentState.Phase.ACTING,
                        lastAction = intent,
                        message = "转译失败：$reason",
                    ),
                )
                action = AgentAction(
                    type = intent.intent,
                    reasoning = intent.reasoning,
                    reason = reason,
                    target = intent.target?.let { com.phoneagent.domain.model.ActionTarget(method = it.by, value = it.value) },
                )
                verify = VerifyResult(false, reason, "", "")
                isStructuralError = true
                verified = false
            }
        }

        counters.setConsecutiveFailures(if (verified) 0 else counters.consecutiveFailures() + 1)
        // 命令成功执行后清零「无效命令」计数：成功说明链路已恢复正常，旧账不该累计
        if (verified) counters.setInvalidCommandStreak(0)
        // 决策循环熔断（真机 BUG 24 次因）：动作"成功"但同签名连续重复时，AI 会在同一位置打转
        // （实测：美团购物车面板元素树为空，AI 反复点同一个购物车容器 6+ 步，页面纹丝不动）。
        // 连续 4 次注入纠偏提示逼 AI 换方案；同签名出现 6 次则说明提示无效，升级为协助请求。
        if (verified && action.type != ActionType.WAIT && action.type != ActionType.SAY &&
            action.type != ActionType.REMEMBER && action.type != ActionType.DEVICE_QUERY &&
            action.type != ActionType.TASK_DONE
        ) {
            val sig = ExecutionFeedback.actionSignature(action)
            when (loopGuard.onVerifiedAction(sig)) {
                LoopGuard.RepeatEscalation.SWITCH_APPROACH -> {
                    val streak = loopGuard.sameSignatureStreak
                    val loopHint = "⚠️ 你已连续 $streak 步执行完全相同的动作「${action.type}」而页面没有推进。" +
                        "这个做法无效：请换一种完全不同的意图（如 scroll_to 滚动查找、see 看图确认、" +
                        "say 向用户说明障碍、open/press 返回上一级），不要重复同一动作。"
                    ui.log(AgentLog.Level.WARN, "决策循环：连续 $streak 步同签名动作「${action.type}」，注入纠偏提示")
                    messages.add(
                        ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = loopHint))),
                    )
                }
                LoopGuard.RepeatEscalation.ASK_USER -> {
                    val streak = loopGuard.sameSignatureStreak
                    val loopHint = "⚠️ 你已连续 $streak 步重复同一动作且毫无进展。立即停止该做法：" +
                        "要么换一种截然不同的方案，要么用 say 向用户说明当前卡点并请求指导。"
                    ui.log(AgentLog.Level.WARN, "决策循环：连续 $streak 步同签名动作「${action.type}」，注入纠偏提示")
                    messages.add(
                        ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = loopHint))),
                    )
                }
                LoopGuard.RepeatEscalation.NONE -> Unit
            }
        } else if (verified) {
            loopGuard.onVerifiedAction(null)
        }
        // 异常经验命中后回写使用效果（成功/失败计数），供后续按命中率判断是否还值得复用
        record.recordAnomalyUse(verified)
        // 步骤验证生效 → 记入长线工作记忆，供后续压缩历史后仍能感知进度
        if (verified) record.recordProgress(step, action)
        // 检查点持久化（v2.2 5.3）：每成功一步保存进度，中断后可查询/续传
        if (verified) record.saveCheckpoint()

        // 连续失败 ≥3 次才请求用户介入，避免单次动作失败频繁打断
        if (!verified && counters.consecutiveFailures() >= 3) {
            // 此处不先记失败留档：引导重试后无论成败都会走到下方统一留档，
            // 先记一遍会造成同一步双重留档；只有用户离开（本步直接 return）才补记
            ui.log(AgentLog.Level.ERROR, "动作 3 次未生效：${action.type}，请求用户介入")
            ui.pushFloating("需要指导", "ERROR")
            // 协助文案必须带上真实失败原因与服务状态（真机 BUG 23）：
            // 只说"连续未能改变页面"会把 wait/shell 这类本不期待页面变化的动作误报成异常，
            // 用户也无从判断是页面问题还是服务掉了
            val a11yState = device.a11yDisconnectedNote()
            val lastReason = verify.reason.ifBlank { "页面未按预期变化" }
            val stuckReason = "动作「${action.type}」连续 ${counters.consecutiveFailures()} 次未生效：$lastReason$a11yState。" +
                "请选择：手动接管 / 告诉 AI 怎么做。"
            ui.showFloatingInteraction("guide", "需要你的协助", stuckReason)
            ui.setNeedsUser(true)
            ui.emitHintRequest(stuckReason)
            // 同敏感页分支：协作原因需要落到 agentState.message，界面才能显示出来
            ui.setState(ui.state().copy(message = stuckReason))
            val hint = ui.awaitUserHint()
            if (hint.isBlank()) {
                // 用户离开：这一步在 return，不会再走到下方统一留档，先补一条失败记录
                record.recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint, verify.reason)
                finish.finishTaskMemory(TaskMemoryEntry.STATUS_FAILED, null)
                finish.stopEngine()
                return StepOutcome.Return
            }
            // 用户指导 → 加入上下文并让云端重新决策（失败自动重试一次）
            messages.add(
                ChatMessageDto(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = "用户提示：$hint 请据此重新决策下一步动作。"),
                    ),
                ),
            )
            ui.pushThinking("用户提示：$hint", null)
            // 用户给的指导沉淀为异常记忆：与 anomalyEngine.findSolution 配对，
            // 下次同页再卡可直接端侧复用，不必再麻烦用户（2026-08-15 BUG-07 收尾：接线而非删除）
            record.learnSolution(snapshot, action.type, stuckReason, hint)
            // 引导后决策同样有看门狗（BUG 24）：用户给完指导若云端挂起，任务会带着"需要你的协助"
            // 的假象永久卡住，用户无从判断。超时按"引导失败"处理，走下方既有的收尾/重试分支。
            val guided = device.decideWithUserHint(messages, hint)
            if (guided != null && guided.intent != IntentType.FINISH && guided.intent != IntentType.GIVE_UP) {
                // 直接执行引导后的意图，不重走决策（避免变卦 + 节省一次云调用）。
                // 浏览器意图走独立通道：这里不能 continue（后面的 recordsIntoHistory 要落档），
                // 故用「合成动作 + 验证结果」接进既有链路，让留档与截图照常发生。
                // 页面记忆：引导恢复的意图同样记为待记路径边的动作
                record.pageMemoryOnIntent(guided)
                // 引导回来的意图同样要过动作模式门控：用户给的"指导"不能成为越权的旁路
                val gDenied = ActionPolicy.allows(device.actionMode(), guided.intent) as? ActionPolicy.Verdict.Denied
                if (gDenied != null) {
                    ui.log(AgentLog.Level.WARN, "引导后意图被动作模式拒绝：${guided.intent}｜${gDenied.reason}")
                    messages.add(
                        ChatMessageDto(
                            role = "user",
                            content = listOf(ContentPart(type = "text", text = "⚠️ ${gDenied.reason}")),
                        ),
                    )
                    verify = VerifyResult(false, gDenied.reason, "", "")
                    verified = false
                } else if (device.browserHandles(guided.intent)) {
                    val (browseAction, browseVerify) = device.handleBrowse(step, guided, messages)
                    action = browseAction
                    verify = browseVerify
                    verified = browseVerify.success
                } else {
                    // 视觉坐标与常规路径同口径「消费一次」：引导后 AI 若给的是 by=hint 的视觉定位，
                    // 这里必须先取走上一步 see/视觉定位产出的坐标（取走即清空），否则会定位失败。
                    // 原先写死 null，等于引导路径永远拿不到视觉坐标。
                    val gCarry = device.takeVisualCoordinate()
                    val gTranslate = tryOrNull("引导后意图转译失败") {
                        device.translate(guided, device.reobserve(), gCarry)
                    } ?: IntentTranslator.TranslationResult.Failed("引导后意图转译异常")
                    when (gTranslate) {
                        is IntentTranslator.TranslationResult.Command -> {
                            val guidedAction = gTranslate.action
                            action = guidedAction
                            // 不可逆动作确认门与常规路径同口径：引导回来的动作若带 needsUserConfirmation，
                            // 执行前必须先问用户。原先这里直接执行，等于用户给一次指导就能越过确认门
                            // 执行删除/发送等不可逆操作（转译层会给 DELETE 等语义控件自动打上该标志）。
                            if (guidedAction.needsUserConfirmation && !device.awaitActionConfirmation(guidedAction)) {
                                ui.log(
                                    AgentLog.Level.WARN,
                                    "用户取消引导后的不可逆操作，已跳过执行：${guidedAction.type}",
                                )
                                messages.add(
                                    ChatMessageDto(
                                        role = "user",
                                        content = listOf(
                                            ContentPart(
                                                type = "text",
                                                text = "⚠️ 用户拒绝执行该不可逆操作，请改用其他方案或询问用户，不要重复请求同一操作。",
                                            ),
                                        ),
                                    ),
                                )
                                verify = VerifyResult(false, "用户取消了该操作", "", "")
                                verified = false
                                // 自写命令的首次确认在这里同样不能绕：引导路径若把它跳过，
                                // 等于「第一次自写命令」可以不被确认就执行
                            } else if (device.ensureShellApproval(guidedAction)) {
                                verify = device.execute(guidedAction, device.reobserve())
                                verified = verify.success
                                if (verified) {
                                    // 引导恢复成功同样要对齐常规路径的记账：清失败计数、记进度、存检查点
                                    // （原来漏了这一步，引导成功后进度不涨、失败计数也不清）
                                    counters.setConsecutiveFailures(0)
                                    record.recordProgress(step, action)
                                    record.saveCheckpoint()
                                } else {
                                    ui.log(AgentLog.Level.WARN, "引导后动作仍未生效：${guidedAction.type}")
                                }
                            } else {
                                val denied = "用户拒绝执行这条命令：${guidedAction.command}。请改用既有意图完成当前目标。"
                                messages.add(
                                    ChatMessageDto(
                                        role = "user",
                                        content = listOf(ContentPart(type = "text", text = "⚠️ $denied")),
                                    ),
                                )
                                verify = VerifyResult(false, denied, "", "")
                                verified = false
                            }
                        }
                        is IntentTranslator.TranslationResult.Failed -> {
                            verify = VerifyResult(false, gTranslate.reason, "", "")
                            verified = false
                            ui.log(AgentLog.Level.WARN, "引导后意图转译失败：${gTranslate.reason}")
                        }
                        is IntentTranslator.TranslationResult.MissingParam -> {
                            verify = VerifyResult(false, gTranslate.reason, "", "")
                            verified = false
                            ui.log(AgentLog.Level.WARN, "引导后意图缺参：${gTranslate.reason}")
                        }
                    }
                }
            } else {
                // 引导两次都失败 / AI 认为任务已完成：绝不再重放接管前的旧失败动作
                // （旧动作已验证失败，重放只会空转一回合并留下误导性留档，2026-08-18 BUG-004）。
                // 补一条失败留档后回到循环顶部，用全新观测重新决策
                ui.log(
                    AgentLog.Level.WARN,
                    "用户引导后仍未获得有效动作（${guided?.intent ?: "调用失败"}），重新观测页面而非重放旧动作",
                )
                record.recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint, verify.reason)
                return StepOutcome.Continue
            }
        }

        ui.log(AgentLog.Level.INFO, "执行动作：${action.type}（${if (verified) "已验证生效" else "待确认"}）")
        record.recordsIntoHistory(step, action, verify)
        // 页面记忆：动作是否真实生效（下一步换页时据此决定要不要记路径边）
        record.pageMemoryOnStepResult(verified)
        // 每步执行完：截图并写下执行说明，供本地调试板块展示（最新一步）
        record.stepShotCapture(step, action, verified)

        // 记录上下文供多轮参考
        messages.add(
            ChatMessageDto(
                role = "user",
                content = listOf(
                    ContentPart(
                        type = "text",
                        text = "执行了 ${action.type}" + (action.reason?.let { "（$it）" } ?: ""),
                    ),
                ),
            ),
        )
        // 结构错误（未知命令等）：明确告知 AI 命令无效及可用命令清单，促使下一轮纠正；
        // 连续 ≥2 次无效命令时升级：强制改用无障碍动作（id/label 定位），禁止继续 shell，避免 AI 一直编造不存在的命令
        if (!verified && isStructuralError) {
            counters.setInvalidCommandStreak(counters.invalidCommandStreak() + 1)
            val fix = ExecutionFeedback.structuralErrorHint(
                action.type,
                verify.reason,
                counters.invalidCommandStreak(),
            )
            messages.add(
                ChatMessageDto(role = "assistant", content = listOf(ContentPart(type = "text", text = fix))),
            )
        }
        // shell 查询命令的输出注入 AI 上下文（info/dump/clip 等）
        if (action.type == ActionType.SHELL) {
            val shellOutput = record.takeShellOutput()
            if (shellOutput.isNotBlank()) {
                messages.add(
                    ChatMessageDto(
                        role = "assistant",
                        content = listOf(ContentPart(type = "text", text = "shell 命令输出：\n$shellOutput")),
                    ),
                )
            }
        }
        // 无障碍端点直调的结果同样要回注：can_screenshot / capture_tree 这类端点本身就是"问一句话"，
        // 不回注等于 AI 问了却永远收不到答案，接下来只能靠猜
        if (action.type == ActionType.A11Y_CALL && verify.success && verify.reason.isNotBlank()) {
            messages.add(
                ChatMessageDto(
                    role = "assistant",
                    content = listOf(ContentPart(type = "text", text = "无障碍端点输出：\n${verify.reason}")),
                ),
            )
        }
        // 执行成功但有注意项（当前唯一来源：点击落点与目标不一致）同样要回注。
        // 成功路径此前只回报"执行了 CLICK"，AI 看不到"这一下其实点在了别的控件上"，
        // 下一步就会把并非本次操作带来的页面变化当成预期结果继续往下走
        if (verified && verify.note.isNotBlank()) {
            messages.add(
                ChatMessageDto(
                    role = "assistant",
                    content = listOf(ContentPart(type = "text", text = "⚠️ ${verify.note}")),
                ),
            )
        }
        // 界面稳定等待按动作类型取（见 VerifyRules.stepSettleMs）：
        // 说话/记忆/设备查询不会改页面，不必陪着空等 400ms
        kotlinx.coroutines.delay(VerifyRules.stepSettleMs(action.type))
        // 动作即观察：稳定之后顺手读一次元素树，把前后差异回注给下一步。
        // 差异并进同一条 assistant 动作结果里，避免历史里出现连续两条 assistant 消息。
        val after = captureAfterObservation(session, snapshot, action, verified)
        messages.add(
            ChatMessageDto(
                role = "assistant",
                content = listOf(
                    ContentPart(
                        type = "text",
                        text = buildString {
                            append(action.type)
                            if (after?.diffText?.isNotBlank() == true) {
                                append("\n动作后页面变化：").append(after.diffText)
                            }
                        },
                    ),
                ),
            ),
        )
        return StepOutcome.Continue
    }

    /**
     * 动作即观察（对标 Eta）：动作**真正交付**后抓一次轻量快照（只读元素树、不截图），
     * 把「动作前 → 动作后」的差异塞进本步动作结果；下一步观察据此跳过完整观察。
     *
     * 三个约束：
     * - 只有已验证交付、且会改变页面的动作才抓（`wait`/`say`/`remember` 等抓了也是白付一次读屏）
     * - 抓取放在 settle 之后：点击/输入后页面往往还在动画或加载，太早抓到的是中间态
     * - 抓到的树为空（无障碍掉线/副屏模式）时不产出快照，下一步照旧完整观察并截图
     *
     * 能否被下一步复用由 [AfterObservationPolicy.isReusable] 判定：稀疏页、加载中、
     * 强制视觉模式、快照过期都会回退完整观察，稀疏页的截图兜底不受影响。
     */
    private suspend fun captureAfterObservation(
        session: StepSession,
        before: ScreenSnapshot,
        action: AgentAction,
        verified: Boolean,
    ): AfterObservation? {
        if (!AfterObservationPolicy.shouldCapture(action.type, verified)) return null
        val t0 = System.nanoTime()
        val afterSnapshot = tryOrNull("动作后快照失败") { device.reobserve() } ?: return null
        AfterObservationStats.onCapture((System.nanoTime() - t0) / 1_000_000)
        // 元素树为空说明读屏没成功（无障碍掉线/副屏模式），这份快照不具备复用价值
        if (afterSnapshot.elements.isEmpty()) return null
        val after = AfterObservationPolicy.capture(
            before = before,
            after = afterSnapshot,
            actionType = action.type,
            step = session.step,
        )
        session.afterObservation = after
        AfterObservationStats.onCaptured()
        ui.log(
            AgentLog.Level.INFO,
            "动作后快照：元素 ${after.snapshot.elements.size} 个，差异已回注｜${AfterObservationStats.summaryText()}",
        )
        return after
    }

    /**
     * 重试判据需要「协程是否仍存活」：原文直接读 `coroutineContext.isActive`。
     * 这里用**当前协程上下文**同一语义（`kotlin.coroutines.coroutineContext` 在 suspend 函数内可用），
     * 保持"用户停止后不再重试"的行为不变。
     */
    private suspend fun coroutineContextIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive
}
