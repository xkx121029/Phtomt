// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
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
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.domain.rules.DangerousTargetWords
import com.phoneagent.domain.rules.EngineRules
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.execution.CompletionEvidence
import com.phoneagent.engine.execution.DegradationTrigger
import com.phoneagent.engine.execution.IntentTranslator
import com.phoneagent.engine.execution.InputCommitRules
import com.phoneagent.engine.execution.PendingInput
import com.phoneagent.engine.execution.SensitiveInput
import com.phoneagent.engine.execution.VerifyResult
import com.phoneagent.engine.execution.VerifyRules
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.engine.runtime.EngineLimits.MAX_SAY_STREAK
import com.phoneagent.engine.runtime.ExecutionFeedback
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.runtime.TaskNoticeCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.math.ceil

/** 完成前复核的等待时长：够 toast/snackbar 淡出，又不至于让收尾明显变慢 */
private const val FINISH_RECHECK_SETTLE_MS = 700L

/** 执行阶段的界面出口：日志 / 悬浮窗 / 状态 / 协作面板 / 对话气泡 / 结束提示 */
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
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
    /** 收尾出口：摘要 / 终态 / 失败原因。终态由执行阶段显式给出，展示层不再从文案猜 */
    val showDone: (String, TaskStatus, String) -> Unit,
    val setAgentRunning: (Boolean) -> Unit,
    val addConversation: (String, String) -> Unit,
)

/** 执行阶段的留档出口：步骤记录 / 历史 / 截图 / 进度 / 检查点 / 异常记忆 */
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
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
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
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
    /** 本任务内已确认下发的副作用动作（幂等判据的唯一来源，见 [EngineRules.idempotencyVerdict]） */
    val committedEffects: () -> List<EngineRules.CommittedEffect>,
    /** 动作真的下发且验证成功后记账：后面的步骤据此才能判"这次是重复副作用"（[realLabel] 与判据同源） */
    val recordEffect: (AgentAction, Int, String?) -> Unit,
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
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
internal class ExecutionCounters(
    val earlyDoneRejections: () -> Int,
    val setEarlyDoneRejections: (Int) -> Unit,
    val sayStreak: () -> Int,
    val setSayStreak: (Int) -> Unit,
    val consecutiveFailures: () -> Int,
    val setConsecutiveFailures: (Int) -> Unit,
    val invalidCommandStreak: () -> Int,
    val setInvalidCommandStreak: (Int) -> Unit,
    /** 本任务最后一处已填入但尚未提交的输入（null = 没有待提交内容） */
    val pendingInput: () -> PendingInput?,
    val setPendingInput: (PendingInput?) -> Unit,
    /** 连续拒绝"输入未提交就完成"的次数，防死循环 */
    val inputCommitRejections: () -> Int,
    val setInputCommitRejections: (Int) -> Unit,
    /** 因"依据是瞬时提示"拒绝完成的次数，防死循环 */
    val completionRecheckRejections: () -> Int,
    val setCompletionRecheckRejections: (Int) -> Unit,
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
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList", "TooGenericExceptionCaught")
internal class ExecutionPhase(
    private val loopGuard: LoopGuard,
    private val currentPlan: () -> TaskPlan?,
    private val ui: ExecutionUiHooks,
    private val record: ExecutionRecordHooks,
    private val device: ExecutionDeviceHooks,
    private val finish: ExecutionFinishHooks,
    private val counters: ExecutionCounters,
    /** 「动作即观察」统计：与观察阶段共享同一实例，两端的复用/抓取才落在同一份口径上 */
    private val afterStats: AfterObservationStats = AfterObservationStats(),
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

    // 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition", "ReturnCount")
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
        var verified = false
        // 收尾时仍有未提交输入（拒绝耗尽后放行）→ 带上可归因结论写进任务记忆，别让"看起来成功"掩盖真因
        var uncommittedInputCaveat: String? = null
        // 密码类输入：本步之后的对话留档、执行记录与回注一律用占位符，正文只用于这次执行
        val sensitiveIntent = SensitiveInput.isPasswordInput(intent, session.semanticSnapshot)
        when (translation) {
            is IntentTranslator.TranslationResult.Command -> {
                val act = translation.action
                action = act
                ui.addConversation(
                    if (session.fromLocal) "local" else "assistant",
                    (if (sensitiveIntent) SensitiveInput.redactIntent(intent) else intent).toString(),
                )
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
                // 证据优先：当前页面已出现明确的"完成"证据时，即便提前宣称完成也直接放行，不再为凑步数空跑。
                // P0 修复（HAPP-5）：证据不再只是页面文字——页面上的「已完成」可能是任务还没做时的状态标签，
                // 所以要求「本任务内真的下发并确认过副作用动作」+「当前页面出现完成确认语」同时成立。
                // 放弃（GIVE_UP）不属"完成"，不适用证据放行，仍按原门槛拦截。
                val hasDoneEvidence = intent.intent != IntentType.GIVE_UP &&
                    device.committedEffects().isNotEmpty() && device.idempotencyDone(snapshot)
                // 输入闭环：填进输入框 ≠ 提交。凡是有提交语义的操作，完成前必须真的落到提交动作，
                // 否则判分读到的是空答案（真机失败：正文已输入答题框，直接 finish，页面从未提交）。
                val pendingInput = counters.pendingInput()
                if (act.type == ActionType.TASK_DONE && intent.intent != IntentType.GIVE_UP &&
                    pendingInput != null && step - pendingInput.step <= InputCommitRules.PENDING_INPUT_TTL_STEPS
                ) {
                    if (counters.inputCommitRejections() < InputCommitRules.MAX_FINISH_REJECTIONS) {
                        counters.setInputCommitRejections(counters.inputCommitRejections() + 1)
                        ui.log(
                            AgentLog.Level.WARN,
                            "完成前仍有未提交输入「${pendingInput.label}」，要求先提交再完成",
                        )
                        messages.add(
                            ChatMessageDto(
                                role = "user",
                                content = listOf(
                                    ContentPart(
                                        type = "text",
                                        text = "⚠️ 你只把内容填进了「${pendingInput.label}」，并没有提交，" +
                                            "当前页面读到的仍是未提交状态。请先执行真正的提交动作" +
                                            "（点提交/发送/保存按钮，或用 send/confirm 意图），" +
                                            "并确认页面出现提交后的结果（如答案已显示、列表里出现这条记录），" +
                                            "再输出 finish。若这个输入确实无需提交，用 say 说明理由后再完成。",
                                    ),
                                ),
                            ),
                        )
                        return StepOutcome.Continue
                    }
                    // 拒绝上限已到：放行完成，但不能当作干净成功——留下可归因的未提交结论
                    uncommittedInputCaveat =
                        "结束前输入「${pendingInput.label}」未确认提交（已连续拒绝 2 次后放行）"
                    ui.log(AgentLog.Level.ERROR, uncommittedInputCaveat.orEmpty())
                    TaskNoticeCenter.publishDegradation(
                        DegradationTrigger.UNCOMMITTED_INPUT,
                        detail = uncommittedInputCaveat.orEmpty(),
                        method = "按完成处理（未提交）",
                        source = "execution",
                    )
                    counters.setPendingInput(null)
                }
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
                // 完成证据分层：提示语是过渡态，不能当完成依据（真机失败：看到 toast「已加入播放队列」
                // 和绿勾就收尾，实际状态从未核对）。把依据再观察一次：消失的提示不算证据；
                // 页面上根本不存在的"提示语措辞"同样不算。每任务最多拒绝一次，避免死循环。
                if (act.type == ActionType.TASK_DONE && intent.intent != IntentType.GIVE_UP &&
                    counters.completionRecheckRejections() < CompletionEvidence.MAX_RECHECK_REJECTIONS
                ) {
                    val claim = act.summary.orEmpty()
                    // 等一拍再读：toast/snackbar 通常 2 秒内消失，立刻重读会把提示当成持久状态
                    kotlinx.coroutines.delay(FINISH_RECHECK_SETTLE_MS)
                    val fresh = device.reobserve()
                    val transient = (
                        CompletionEvidence.vanishedCitedEvidence(claim, snapshot, fresh) +
                            CompletionEvidence.vanishedHints(snapshot, fresh) +
                            CompletionEvidence.unverifiableHintCitations(claim, snapshot)
                        ).distinct()
                    if (transient.isNotEmpty()) {
                        counters.setCompletionRecheckRejections(counters.completionRecheckRejections() + 1)
                        val cited = transient.joinToString("、")
                        ui.log(AgentLog.Level.WARN, "完成依据是瞬时提示「$cited」，要求核对持久状态")
                        TaskNoticeCenter.publishDegradation(
                            DegradationTrigger.TRANSIENT_EVIDENCE,
                            detail = cited,
                            method = "要求重新核对持久状态",
                            source = "execution",
                        )
                        messages.add(
                            ChatMessageDto(
                                role = "user",
                                content = listOf(
                                    ContentPart(
                                        type = "text",
                                        text = "⚠️ 你用来判定完成的「$cited」是瞬时提示（提示语/绿勾），" +
                                            "再观察一次已经不在页面上了，它不能作为完成依据。" +
                                            "请看持久状态：列表里是否真的出现了这条记录、播放/收藏/提交后的控件是否变成新状态、" +
                                            "页面是否停在该任务的结果页。确认后在 finish 的 summary 里写清你看到的具体状态；" +
                                            "如果状态并没有达成，继续执行真正需要的动作。",
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
                        // 放弃收尾：终态显式给 ABORTED，展示层不再靠「放弃」二字猜；
                        // 原因单独带上，完成卡片能说清为什么停
                        ui.showDone("任务已放弃", TaskStatus.ABORTED, "已放弃：$doneSummary")
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
                    // 完成说明一并落库，作为会话承接时"上一轮任务"的结论。
                    // 若收尾时仍有未提交输入（拒绝耗尽后放行），把结论追加到记忆里：别让"成功"掩盖真因
                    val doneNote = uncommittedInputCaveat?.let { "$doneSummary（注意：$it）" } ?: doneSummary
                    finish.finishTaskMemory(TaskMemoryEntry.STATUS_SUCCESS, doneNote)
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
                // 不经过 GUI、不触发 shell 确认（命令由端侧拼装，AI 只选 op）。
                // 但不可逆子操作（file_write 覆盖文件 / app_manage 强停应用）必须先过确认门：
                // 这条分支在下面的通用确认门之前 return，不在这里拦就等于确认门永远走不到。
                if (act.type == ActionType.DEVICE_OPS) {
                    if (act.needsUserConfirmation && !device.awaitActionConfirmation(act)) {
                        val cancelReason = "用户取消了该操作"
                        ui.log(AgentLog.Level.WARN, "用户取消系统直连的不可逆操作，已跳过执行：${act.endpoint}/${act.op}")
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
                // 幂等保护（P0 修复，HAPP-5）：判据绑「本任务执行历史」，页面文字本身不算"已经做过"。
                // 旧实现只看页面是否出现「已完成/操作成功/已提交」，待办详情页的状态标签就让整条删除被跳过，
                // 动作压根没下发却记成 ✅。现在的取向：
                // - 本任务内确实下发并确认过同一动作 → 跳过，不重复副作用；
                // - 不可逆动作（删除/支付/下单）证据不足 → 交用户确认，既不静默成功、也不静默重放。
                // 判据的标签取**端侧真实控件文字**（[EngineRules.targetLabelOf]），不再只信模型自报的
                // target.value：坐标点击时 target 根本不填，英文界面时词表又只有中文，两者都会让判据静默失效。
                val realLabel = EngineRules.targetLabelOf(act, snapshot)
                val idempotency =
                    EngineRules.idempotencyVerdict(device.committedEffects(), act, act.type, snapshot, realLabel)
                if (idempotency is EngineRules.IdempotencyVerdict.Skip) {
                    val skipReason = "本任务第 ${idempotency.step} 步已下发并确认过同一动作「" +
                        (act.target?.value ?: act.type) + "」，本次不重复执行"
                    ui.log(AgentLog.Level.INFO, "幂等跳过：$skipReason")
                    record.recordsIntoHistory(step, act, VerifyResult(true, skipReason, "", ""))
                    record.stepShotCapture(step, act, true)
                    counters.setSayStreak(0)
                    return StepOutcome.Continue
                }
                val idempotencyConfirmReason =
                    (idempotency as? EngineRules.IdempotencyVerdict.NeedsConfirm)?.reason
                if (idempotencyConfirmReason != null) {
                    ui.log(AgentLog.Level.WARN, "幂等保护请求用户确认：$idempotencyConfirmReason")
                }
                // 自由模式：AI 自写命令在本任务首次执行前要向用户确认一次。
                // 拒绝不等于放弃任务——把"用户拒绝了这条命令"回注给 AI，让它换条路子继续干。
                if (!device.ensureShellApproval(act)) {
                    val cmd = EngineRules.authoredCommandOf(act).orEmpty()
                    val denied = "用户拒绝执行这条命令：$cmd。" +
                        "请不要重试同一条命令：改用转译层既有意图完成当前目标，或换一条用户更可能接受的命令。"
                    ui.log(AgentLog.Level.WARN, "用户拒绝 AI 自写命令，已跳过执行：$cmd")
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
                // 取消/超时则该步记跳过，并把用户的决定回注给 AI 换路子，而不是硬执行。
                // 按**目标标签**的危险词兜底不在这一层做了：判据已下沉到派发层
                // （[com.phoneagent.engine.execution.DangerousTargetGate]），那里拿得到活节点与落点探针；
                // 这一层只能读到模型自报的 target.value，坐标/提示词路径整条绕过（HAPP-35 P0-2）。
                // 唯一例外是 a11y 端点直调（`click_node`）：派发层闸门按 [ActionType] 收敛到
                // TAP/CLICK/长按/滑动，不含 A11Y_CALL，那条路上也没有"落点控件"可取——落点写在
                // `args["target"]` 里，只有这一层看得见（取数见 [AgentAction.operatedLabel]）。
                // 每个路径只有一处判据，不会弹两次。
                // 幂等保护也走这道门（[EngineRules.IdempotencyVerdict.NeedsConfirm]）：
                // 不可逆动作在"没做过但页面像做完了"或"做过但页面不再是完成态"时，必须先问用户。
                val hazard = hazardWordOf(act)
                if (hazard != null && !act.needsUserConfirmation) {
                    ui.log(
                        AgentLog.Level.WARN,
                        "端点直调目标「${act.operatedLabel}」命中危险词「$hazard」，转用户确认",
                    )
                }
                val confirmAction =
                    if (idempotencyConfirmReason != null) act.copy(reason = idempotencyConfirmReason) else act
                if ((act.needsUserConfirmation || hazard != null || idempotencyConfirmReason != null) &&
                    !device.awaitActionConfirmation(confirmAction)
                ) {
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
                // 结构性错误由执行层在构造结果时显式标注（[VerifyResult.structural]），
                // 不再从 reason 文案里匹配关键词：改一句提示语不该改变是否重试
                verified = verify.success
                var times = 1
                // 点击类动作不再交给外层重跑：ClickRunner 内部已把"活节点直点 → 手势点击 → 快照坐标 →
                // 滚动查找"逐级穷尽过一遍，外层再重跑只是把同一串动作重复执行（还可能造成重复副作用）
                while (!verified &&
                    VerifyRules.shouldRetry(
                        act.type, verify.delivery, verify.structural, times, coroutineContextIsActive(),
                    )
                ) {
                    times++
                    ui.log(AgentLog.Level.WARN, "动作未生效（第 $times 次重试）：${act.type}")
                    repeat(3) { kotlinx.coroutines.delay(300) }
                    val v2 = device.execute(act, device.reobserve())
                    verify = v2
                    verified = v2.success
                }
                // 幂等历史：只有这次真的下发且验证成功才记账。这是"跳过重复副作用"的唯一依据——
                // 页面文字不是（页面说「已完成」不代表我们做过），所以记在引擎自己这边。
                if (verified && EngineRules.isFinalSubmit(act, act.type, realLabel)) {
                    device.recordEffect(act, step, realLabel)
                }
            }
            is IntentTranslator.TranslationResult.Failed -> {
                val reason = translation.reason
                ui.log(AgentLog.Level.WARN, "意图转译失败：$reason")
                ui.addConversation(
                    "assistant",
                    (if (intent.intent == IntentType.INPUT) SensitiveInput.redactIntent(intent) else intent).toString(),
                )
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
                    target = intent.target?.let { com.phoneagent.domain.model.ActionTarget(method = it.by,
                        value = it.value) },
                )
                verify = VerifyResult(false, reason, "", "", notDelivered = true, structural = true)
                verified = false
            }
            is IntentTranslator.TranslationResult.MissingParam -> {
                // 绝大多数情况下已被 fillMissingParam 补全成 Command/Failed；此处仅作 sealed 穷尽兜底，按失败处理
                val reason = translation.reason
                ui.log(AgentLog.Level.WARN, "意图缺参未补全：$reason")
                ui.addConversation(
                    "assistant",
                    (if (intent.intent == IntentType.INPUT) SensitiveInput.redactIntent(intent) else intent).toString(),
                )
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
                    target = intent.target?.let { com.phoneagent.domain.model.ActionTarget(method = it.by,
                        value = it.value) },
                )
                verify = VerifyResult(false, reason, "", "", notDelivered = true, structural = true)
                verified = false
            }
        }

        // 密码正文到此为止：后面的留档、状态回显与提示词回注全部使用脱敏副本。
        // 判定同时看「意图目标是不是密码框」（翻译前就能确定）与「执行结果是否命中密码框」（重定位后才知道）。
        if (sensitiveIntent || verify.sensitiveInput) {
            action = SensitiveInput.redactAction(action)
        }

        counters.setConsecutiveFailures(if (verified) 0 else counters.consecutiveFailures() + 1)
        // 输入闭环：记录/清除"待提交输入"。填入时挂账，提交或离开当前页后销账；
        // 下一次宣称完成时据此判断"填了"到底有没有变成"交了"
        counters.setPendingInput(
            InputCommitRules.nextPending(
                current = counters.pendingInput(),
                action = action,
                intentType = intent.intent,
                verified = verified,
                step = step,
            ),
        )
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
                    // 同签名动作反复 = 页面卡死，已自动换打法；写进状态出口让用户知道现在怎么继续
                    TaskNoticeCenter.publishDegradation(
                        DegradationTrigger.PAGE_STUCK,
                        detail = "连续 $streak 步同签名动作「${action.type}」",
                        method = "换一种做法",
                        source = "execution",
                    )
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
        // 页面级止损：同签名熔断按"动作签名完全一致"计数，AI 换个措辞点同一个无效控件就绕过去了
        // （实测：心形按钮被连点 20+ 次直到步数耗尽）。这里改看页面指纹：只要页面连续多步没变，
        // 不论 AI 怎么描述目标，都按同一条死路处理。
        val pageStuck = loopGuard.onPageOutcome(
            fingerprint = if (VerifyRules.expectsPageChange(action.type)) {
                PageFingerprint.compute(session.snapshot)
            } else {
                null
            },
            skip = !VerifyRules.expectsPageChange(action.type),
        )
        when (pageStuck) {
            LoopGuard.PageStuckEscalation.SWITCH_APPROACH -> {
                val streak = loopGuard.pageStuckStreakValue
                ui.log(AgentLog.Level.WARN, "页面连续 $streak 步无变化，注入换入口提示")
                // 可归因：写清"卡在哪一页、卡了多少步、端侧是怎么处置的"
                TaskNoticeCenter.publishDegradation(
                    DegradationTrigger.PAGE_STUCK,
                    detail = "同一页面连续 $streak 步无推进（当前动作：${action.type}）",
                    method = "换入口/换策略",
                    source = "execution",
                )
                messages.add(
                    ChatMessageDto(
                        role = "user",
                        content = listOf(
                            ContentPart(
                                type = "text",
                                text = "⚠️ 当前页面已经连续 $streak 步没有任何变化，说明你走的这条路径无效。" +
                                    "换入口，不要再点同一个控件：改用 scroll_to 找别的可点控件、" +
                                    "by_hint 视觉定位、语义意图（send/confirm/search），或返回上一级重新进入；" +
                                    "确实找不到就 say 说明卡点。",
                            ),
                        ),
                    ),
                )
            }
            LoopGuard.PageStuckEscalation.ASK_USER -> {
                val streak = loopGuard.pageStuckStreakValue
                ui.log(AgentLog.Level.ERROR, "页面连续 $streak 步无变化，请求用户指导")
                TaskNoticeCenter.publishDegradation(
                    DegradationTrigger.PAGE_STUCK,
                    detail = "同一页面连续 $streak 步无推进（当前动作：${action.type}）",
                    method = "请求用户指导",
                    source = "execution",
                )
                messages.add(
                    ChatMessageDto(
                        role = "user",
                        content = listOf(
                            ContentPart(
                                type = "text",
                                text = "⚠️ 同一页面已连续 $streak 步没有变化，换入口的提示也没奏效。" +
                                    "请立刻改用与之前完全不同的方案；如果受限（无权限/控件不可点/需要登录），" +
                                    "用 say 向用户说明卡点并请求指导。",
                            ),
                        ),
                    ),
                )
            }
            LoopGuard.PageStuckEscalation.STOP -> {
                val streak = loopGuard.pageStuckStreakValue
                val stuckReason = "同一页面连续 $streak 步无推进，已判定此路径不通（当前动作：${action.type}）"
                ui.log(AgentLog.Level.ERROR, stuckReason)
                TaskNoticeCenter.publishDegradation(
                    DegradationTrigger.PAGE_STUCK,
                    detail = stuckReason,
                    method = "停止任务",
                    source = "execution",
                )
                record.recordStep(
                    step, action, VerifyRules.STATUS_FAILED, verify.beforeFingerprint, verify.afterFingerprint,
                    "$stuckReason：${verify.reason}",
                )
                finish.finishTaskMemory(TaskMemoryEntry.STATUS_FAILED, stuckReason)
                finish.stopEngine()
                return StepOutcome.Return
            }
            LoopGuard.PageStuckEscalation.NONE -> Unit
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
                record.recordStep(step, action, VerifyRules.STATUS_FAILED, verify.beforeFingerprint, verify.afterFingerprint,
                    verify.reason)
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
                            // 危险词那一层不在这里判：它已经下沉到派发层（[com.phoneagent.engine.execution.DangerousTargetGate]），
                            // 那里才有真实控件，引导路径走同一条派发流水线，天然也被覆盖。
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
                                val cmd = EngineRules.authoredCommandOf(guidedAction).orEmpty()
                                val denied = "用户拒绝执行这条命令：$cmd。请改用既有意图完成当前目标。"
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
                record.recordStep(step, action, VerifyRules.STATUS_FAILED, verify.beforeFingerprint, verify.afterFingerprint,
                    verify.reason)
                return StepOutcome.Continue
            }
        }

        // 用户指导后的重新执行可能换了动作：落档前再按执行结果补一次脱敏，避免密码被引导路径带回轨迹
        if (verify.sensitiveInput) action = SensitiveInput.redactAction(action)
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
        if (!verified && verify.structural) {
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
        // 非结构性失败按交付状态分成两类回注（不解析文案）：
        // 「没送达」→ 换通道/换落点值得再试；「已送达但没生效」→ 再点同一下就是重复副作用，该换入口/换策略。
        // 只回一句"执行了 X"，AI 分不清这两种，最常见的反应就是把同一下再点一遍。
        if (!verified) {
            val deliveryHint = VerifyRules.failureHint(verify.delivery, verify.reason, verify.structural)
            if (deliveryHint.isNotBlank()) {
                messages.add(
                    ChatMessageDto(
                        role = "assistant",
                        content = listOf(ContentPart(type = "text", text = deliveryHint)),
                    ),
                )
            }
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
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun captureAfterObservation(
        session: StepSession,
        before: ScreenSnapshot,
        action: AgentAction,
        verified: Boolean,
    ): AfterObservation? {
        if (!AfterObservationPolicy.shouldCapture(action.type, verified)) return null
        val t0 = System.nanoTime()
        val afterSnapshot = tryOrNull("动作后快照失败") { device.reobserve() } ?: return null
        afterStats.onCapture((System.nanoTime() - t0) / 1_000_000)
        // 元素树为空说明读屏没成功（无障碍掉线/副屏模式），这份快照不具备复用价值
        if (afterSnapshot.elements.isEmpty()) return null
        val after = AfterObservationPolicy.capture(
            before = before,
            after = afterSnapshot,
            actionType = action.type,
            step = session.step,
        )
        session.afterObservation = after
        afterStats.onCaptured()
        ui.log(
            AgentLog.Level.INFO,
            "动作后快照：元素 ${after.snapshot.elements.size} 个，差异已回注｜${afterStats.summaryText()}",
        )
        return after
    }

    /**
     * 重试判据需要「协程是否仍存活」：原文直接读 `coroutineContext.isActive`。
     * 这里用**当前协程上下文**同一语义（`kotlin.coroutines.coroutineContext` 在 suspend 函数内可用），
     * 保持"用户停止后不再重试"的行为不变。
     */
    private suspend fun coroutineContextIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    /**
     * a11y 端点直调的危险词兜底——**只覆盖 `click_node`**，其余路径由派发层的
     * [com.phoneagent.engine.execution.DangerousTargetGate] 判（那里有活节点与落点探针）。
     *
     * 为什么这一条走不掉：自由模式既不走 `intent=delete`、也不留 `tap` 的标签，而是
     * `a11y` + `click_node`，把落点写在 `args["target"]` 里。这条路上连 [AgentAction.target]
     * 都是空的，派发层也拿不到"手指落点下的控件"（它是端点直调，不经 ClickRunner），
     * 于是唯一看得见目标的只有这里——取数口径统一由 [AgentAction.operatedLabel] 提供。
     *
     * 刻意不含 TAP：那一层已由 [com.phoneagent.engine.execution.DangerousTargetGate] 按真实控件判过，
     * 两处都判会让用户对同一下点按看到两次确认。
     *
     * @return 命中的危险词；不需要额外确认时返回 null
     */
    private fun hazardWordOf(action: AgentAction): String? =
        if (action.type == ActionType.A11Y_CALL) DangerousTargetWords.hit(action.operatedLabel) else null
}
