package com.phoneagent.engine.loop

import android.graphics.Bitmap
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.rules.LocalDecisionEngine
import com.phoneagent.engine.decision.DecisionPolicy
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.memory.PageMemoryRecorder
import com.phoneagent.engine.runtime.EngineLimits.MAX_MODE_DENY_STREAK
import com.phoneagent.engine.runtime.EngineLimits.MAX_SKILL_ERROR_STREAK
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.skill.SkillRunner
import com.phoneagent.feature.skill.SkillCompat
import com.phoneagent.feature.skill.SkillExecutionGateway
import kotlinx.coroutines.CancellationException

/** 决策阶段的外部出口（阶段 B3）：日志 / 悬浮窗 / 失败状态 / 通知 / 页面记忆 */
internal class DecisionHooks(
    val log: (AgentLog.Level, String) -> Unit,
    val pushFloating: (String, String) -> Unit,
    /** 决策链路连续异常：写 ERROR 相位并归类为 DECISION（失败卡据此给「重试」） */
    val markDecisionFatal: (String) -> Unit,
    /** 动作模式连续拒绝：写 ERROR 相位与放宽档位的提示 */
    val markModeFatal: (String) -> Unit,
    /** 技能连续被拒：写 ERROR 相位与原因 */
    val markSkillFatal: (String) -> Unit,
    val notifyLocalLoop: () -> Unit,
    val notifyNoProgress: () -> Unit,
    val pageMemoryOnIntent: (AgentIntent) -> Unit,
)

/** 宏接力状态（阶段 B3）：宏字段仍归引擎任务态，决策阶段只读/写它，不另存一份 */
internal class MacroRelay(
    val active: () -> Boolean,
    val next: (MutableList<ChatMessageDto>) -> AgentIntent?,
    val name: () -> String,
    val dispatched: () -> Int,
    val start: (SkillCompat.Normalized.Macro) -> Unit,
    val abort: () -> Unit,
)

/**
 * 决策阶段（阶段 B3）：宏注入 → 端侧/云端决策 → 死循环护栏 → 技能归一化 → 动作模式门控。
 *
 * 从 AgentEngine 主循环原样迁入，`continue` / `return` 统一收成 [StepOutcome]。
 * 浏览器通道的落地动作不放这里——它会产生 AgentAction、写执行历史、截图，属于执行阶段。
 *
 * 行为等价要点：
 * - 端侧决策连续达到阈值时**跳过本轮端侧判定**强制云端复核（阶段 B3 起真正干预，不再只发通知）
 * - 云端决策异常：本步不计步，[StepOutcome.Retry] 交给循环壳回退步数并等待
 * - 技能归一化的四类结果（Intent / Mcp / Vision / Macro / Error）保持原分支顺序；
 *   缺参推断仍在 Error 分支内先尝试
 * - 动作模式门控位于技能归一化之后、浏览器分流之前（覆盖 browse_*）
 */
internal class DecisionPhase(
    private val localDecision: LocalDecisionEngine,
    private val loopGuard: LoopGuard,
    private val skillGateway: SkillExecutionGateway?,
    private val skillRunner: SkillRunner,
    private val pageMemoryRecorder: PageMemoryRecorder,
    private val macro: MacroRelay,
    private val hooks: DecisionHooks,
    private val cloudDecide: suspend (
        task: String,
        snapshot: ScreenSnapshot,
        annotated: com.phoneagent.engine.perception.AnnotatedPage,
        messages: MutableList<ChatMessageDto>,
        screenshot: Bitmap?,
        settingsVal: AppSettings.Settings,
        pageMemoryHint: String,
    ) -> AgentIntent?,
    private val clearImageCache: () -> Unit,
    private val invokeVisionAsk: suspend (
        Int,
        SkillCompat.Normalized.Vision,
        MutableList<ChatMessageDto>,
        Bitmap?,
        AppSettings.Settings,
    ) -> Unit,
    private val recordLocalTrace: (Int, AgentIntent) -> Unit,
    private val decisionFailureStreak: () -> Int,
    private val setDecisionFailureStreak: (Int) -> Unit,
    private val skillErrorStreak: () -> Int,
    private val setSkillErrorStreak: (Int) -> Unit,
    private val modeDenyStreak: () -> Int,
    private val setModeDenyStreak: (Int) -> Unit,
    private val actionMode: () -> ActionMode,
    private val completedSteps: () -> Int,
) : StepPhase {

    // inline：块里可能要调 suspend 的推参逻辑（inferMissingArgs），
    // 非 inline 的 lambda 在 suspend 函数里不允许出现挂起点
    private inline fun <T> tryOrNull(message: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hooks.log(AgentLog.Level.WARN, "$message：${e.message ?: e.javaClass.simpleName}")
            null
        }

    override suspend fun run(session: StepSession): StepOutcome {
        val messages = session.messages
        val annotated = session.annotated
            ?: return StepOutcome.Continue

        // 2.9 宏接力：宏还有未派发的步骤 → 本轮跳过 AI 决策，端侧直接把下一步注入为当前意图。
        //     派发前先收割上一步的执行结果（供 {{stepN}} 引用）；上一步明确失败即终止宏（失败即停），
        //     终止说明回注消息，宏状态清空，下一轮交还 AI 决策循环。
        //     注入的意图照常走归一化 → ActionPolicy 门控 → 转译层全链路，不绕过任何安全机制。
        var macroIntent: AgentIntent? = null
        if (macro.active()) {
            macroIntent = macro.next(messages)
            if (macroIntent == null) return StepOutcome.Continue // 宏已收尾/终止（说明已回注），回到正常决策循环
        }

        // 3. 端侧决策优先（输出的都是"意图"）
        // 跨步清理图片编码缓存：上一轮的截图早已回收，留着引用既不省事也占内存
        clearImageCache()
        // 端侧决策异常（规则引擎内部越界等）不能让整个任务崩掉：降级为空，直接走云端决策。
        // 用回填过 semanticId 的快照决策：端侧 tapIntent 的 by="id" 定位依赖 semanticId
        // 宏接力轮不需要任何决策（端侧已定好做什么），local/cloud 决策双双跳过
        // 死循环强制复核：连续端侧决策达到阈值时跳过本轮端侧判定，强制走一次云端
        val forceCloudReview = loopGuard.shouldForceCloudReview()
        val localIntent = if (macroIntent == null && !forceCloudReview) {
            tryOrNull("端侧决策失败") { localDecision.decide(session.semanticSnapshot) } as? AgentIntent
        } else {
            if (forceCloudReview) hooks.log(AgentLog.Level.WARN, "连续 ${loopGuard.localDecisionStreakValue} 次端侧决策，本步强制云端复核")
            null
        }
        var decidedIntent: AgentIntent? = null
        var fromLocal = false
        if (macroIntent != null) {
            decidedIntent = macroIntent
            hooks.log(AgentLog.Level.INFO, "宏「${macro.name()}」第 ${macro.dispatched()} 步注入：${macroIntent.intent} 参数=${macroIntent.args}")
        } else if (localIntent != null) {
            decidedIntent = localIntent
            fromLocal = true
            hooks.log(AgentLog.Level.AI, "端侧决策意图：${localIntent.intent}（${localIntent.reasoning ?: localIntent.reason}）")
            // 端侧决策不经过 cloudDecide，必须在此补一条 trace：
            // Agent 页任务流以 trace 为骨架，漏了这一步界面上就完全看不到（像"页面自己变了"）
            recordLocalTrace(session.step, localIntent)
        } else {
            // cloudDecide 内部含视觉链路与网络请求，异常面很宽：抛异常时降级为「等待重试」，
            // 而不是让异常穿透整个任务（既有的「正常返回 null → 决策为空停止」语义保持不变）
            var decisionThrew = false
            val decided = try {
                cloudDecide(
                    session.task,
                    session.snapshot,
                    annotated,
                    messages,
                    session.screenshot,
                    session.settingsVal,
                    session.pageMemoryHint,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                decisionThrew = true
                hooks.log(AgentLog.Level.WARN, "云端决策异常：${e.message ?: e.javaClass.simpleName}")
                null
            }
            // 护栏只统计本次新增的异常降级路径：连续异常说明链路真的坏了（如 API 地址错误、
            // 网络完全不可达），继续空转只会无限等待，需要收尾并告知用户
            setDecisionFailureStreak(if (decisionThrew) decisionFailureStreak() + 1 else 0)
            if (DecisionPolicy.isDecisionFailureFatal(decisionFailureStreak())) {
                hooks.log(AgentLog.Level.ERROR, "决策链路连续 ${decisionFailureStreak()} 次异常，停止任务")
                hooks.markDecisionFatal("AI 决策链路持续异常，已停止任务")
                hooks.pushFloating("决策链路异常，已停止", "ERROR")
                return StepOutcome.Fatal("决策链路连续异常", AgentState.FailureKind.DECISION)
            }
            if (decisionThrew) {
                // 决策链路异常（网络抖动/API 抖动）：本步**不计步**——异常等待原先照常 step++，
                // 连续抖动会把步数预算烧光、任务还没真正跑就到 maxSteps 收尾。
                // 回退步数与等待由循环壳按 Retry 处理
                return StepOutcome.Retry(delayMs = 1_500)
            }
            decidedIntent = decided
        }

        if (decidedIntent == null || decidedIntent.intent.isEmpty()) {
            hooks.log(AgentLog.Level.ERROR, "决策为空，停止")
            return StepOutcome.Fatal("决策为空")
        }
        var intent: AgentIntent = decidedIntent
        // 端侧连续多次决策 → 疑似死循环，主动反馈一次（v2.2.1 八）
        if (fromLocal) {
            if (loopGuard.onLocalDecision(true)) {
                hooks.log(AgentLog.Level.WARN, "连续 ${loopGuard.localDecisionStreakValue} 次端侧决策，可能死循环")
                hooks.notifyLocalLoop()
            }
        } else {
            loopGuard.onLocalDecision(false)
        }
        // 长时间无进展 → 主动反馈一次（v2.2.1 八）
        if (loopGuard.shouldNotifyNoProgress(completedSteps())) {
            hooks.log(AgentLog.Level.WARN, "任务长时间无进展，提醒用户")
            hooks.notifyNoProgress()
        }

        // 3.5 技能归一化：AI 的 intent 字段可以填技能名 / 技能 id（含 MCP 技能）。
        //     内置技能 → 归一化为等价意图，继续走原转译链路（原逻辑一字不改）；
        //     MCP 技能 → 就地调用，把返回结果作为"上一步结果"回注上下文后进入下一轮，不产生设备动作；
        //     未知 / 已停用 / 缺参 → 把中文原因回注给 AI 纠正，连续多次仍不改正即收尾，避免死循环。
        // 技能归一化异常（技能表解析/参数校验内部出错）不影响主流程：视为「无技能命中」继续转译
        val normalized = tryOrNull("技能归一化失败") { skillGateway?.normalize(intent) }
            as? SkillCompat.Normalized
        if (normalized != null) {
            when (normalized) {
                is SkillCompat.Normalized.Intent -> {
                    setSkillErrorStreak(0)
                    intent = normalized.intent
                }
                is SkillCompat.Normalized.Mcp -> {
                    setSkillErrorStreak(0)
                    skillRunner.invokeMcpSkill(session.step, normalized, messages)
                    return StepOutcome.Continue
                }
                is SkillCompat.Normalized.Vision -> {
                    setSkillErrorStreak(0)
                    invokeVisionAsk(session.step, normalized, messages, session.screenshot, session.settingsVal)
                    return StepOutcome.Continue
                }
                is SkillCompat.Normalized.Macro -> {
                    // 宏技能：先整宏预检（步骤技能存在且启用、意图类步骤过当前动作模式门控），
                    // 任一步被拒整宏不启动；预检通过后进入接力模式，由端侧按顺序派发每一步
                    // （每步照常走归一化 → ActionPolicy 门控 → 转译层全链路，不绕过任何安全机制）
                    setSkillErrorStreak(0)
                    val rejectReason = tryOrNull("宏预检失败") { skillRunner.macroPrecheckReason(normalized.skill) } as? String
                    if (rejectReason != null) {
                        setSkillErrorStreak(skillErrorStreak() + 1)
                        hooks.log(AgentLog.Level.WARN, "宏启动被拒：$rejectReason")
                        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ $rejectReason"))))
                        return StepOutcome.Continue
                    }
                    macro.start(normalized)
                    hooks.log(AgentLog.Level.INFO, "宏「${macro.name()}」预检通过，共 ${normalized.skill.steps.size} 步，开始接力执行")
                    hooks.pushFloating("执行宏：${macro.name()}（${normalized.skill.steps.size} 步）", "ACTING")
                    return StepOutcome.Continue
                }
                is SkillCompat.Normalized.Error -> {
                    // 缺参自动推断：因缺必填参数被拒时，端侧先从（历史成功参数/上一步结果/任务原文/页面文本）
                    // 推断补全并重归一化，命中即免去一轮向 AI 的补充追问；推断不出才落入原失败链路计数
                    val recovered = (tryOrNull("缺参推断失败") {
                        skillRunner.inferMissingArgs(intent, session.semanticSnapshot)
                    } as? SkillCompat.Normalized)
                        ?.takeIf { it !is SkillCompat.Normalized.Error }
                    when (recovered) {
                        is SkillCompat.Normalized.Mcp -> {
                            setSkillErrorStreak(0)
                            skillRunner.invokeMcpSkill(session.step, recovered, messages)
                            return StepOutcome.Continue
                        }
                        is SkillCompat.Normalized.Vision -> {
                            setSkillErrorStreak(0)
                            invokeVisionAsk(session.step, recovered, messages, session.screenshot, session.settingsVal)
                            return StepOutcome.Continue
                        }
                        is SkillCompat.Normalized.Intent -> {
                            setSkillErrorStreak(0)
                            intent = recovered.intent
                        }
                        else -> {
                            setSkillErrorStreak(skillErrorStreak() + 1)
                            // 宏步骤归一化被拒（如运行期技能被停用）= 失败即停：终止宏，交还 AI 决策循环
                            if (macro.name().isNotBlank()) {
                                hooks.log(AgentLog.Level.WARN, "宏「${macro.name()}」步骤被归一化拒绝，宏终止")
                                macro.abort()
                            }
                            hooks.log(AgentLog.Level.WARN, "技能调用被拒绝（第 ${skillErrorStreak()} 次）：${normalized.reason}")
                            if (skillErrorStreak() >= MAX_SKILL_ERROR_STREAK) {
                                hooks.markSkillFatal(normalized.reason)
                                hooks.pushFloating("技能不可用，已停止", "ERROR")
                                hooks.log(AgentLog.Level.ERROR, "连续 ${skillErrorStreak()} 次技能调用被拒绝，停止任务：${normalized.reason}")
                                return StepOutcome.Fatal("技能连续被拒")
                            }
                            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ ${normalized.reason}"))))
                            return StepOutcome.Continue
                        }
                    }
                }
            }
        }

        // 3.6 动作模式门控：三档权限（保守 / 均衡 / 自由）的**唯一检查点**。
        //     位置是刻意的——技能归一化之后（此时 intent 已是标准意图名），浏览器分流之前
        //     （browse_* 走独立通道、不进转译层，只有卡在这里才能把它一并覆盖）。
        //     转译层内部对 shell/a11y 另有自检，那只是纵深防御，不替代此处。
        val modeVerdict = ActionPolicy.allows(actionMode(), intent.intent)
        if (modeVerdict is ActionPolicy.Verdict.Denied) {
            setModeDenyStreak(modeDenyStreak() + 1)
            hooks.log(AgentLog.Level.WARN, "动作模式拒绝意图（第 ${modeDenyStreak()} 次）：${intent.intent}｜${modeVerdict.reason}")
            if (modeDenyStreak() >= MAX_MODE_DENY_STREAK) {
                val stopReason = "连续 ${modeDenyStreak()} 次尝试被「${actionMode().label}」动作模式拒绝，已停止任务。" +
                    "若这个任务确实需要这些动作，请在「Agent 页输入栏下方的动作模式」里放宽档位后重试。"
                hooks.markModeFatal(stopReason)
                hooks.pushFloating("动作模式不允许，已停止", "ERROR")
                hooks.log(AgentLog.Level.ERROR, stopReason)
                return StepOutcome.Fatal("动作模式连续拒绝")
            }
            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ ${modeVerdict.reason}"))))
            // 宏步骤被动作模式拒绝 = 失败即停：终止宏并交还 AI 决策循环（预检已挡掉绝大多数情况，
            // 这里兜住"任务中途用户切了档位"的运行期变化）
            if (macro.name().isNotBlank()) {
                hooks.log(AgentLog.Level.WARN, "宏「${macro.name()}」步骤被动作模式拒绝，宏终止")
                macro.abort()
            }
            return StepOutcome.Continue
        }
        setModeDenyStreak(0)

        // 页面记忆：此刻意图已定稿（技能归一化之后），记为待记路径边的动作
        hooks.pageMemoryOnIntent(intent)
        session.intent = intent
        session.fromLocal = fromLocal
        return StepOutcome.Continue
    }
}
