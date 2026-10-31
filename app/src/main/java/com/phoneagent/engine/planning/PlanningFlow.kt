package com.phoneagent.engine.planning

import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ApiProtocol
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.AgentResponseParser
import com.phoneagent.engine.ClarifyAnswered
import com.phoneagent.engine.PlanPhase
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.ReasoningConfig
import com.phoneagent.engine.runtime.EngineLimits.MAX_CLARIFY_EVENTS
import com.phoneagent.engine.runtime.EngineLimits.PLANNING_TEMPERATURE
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import com.phoneagent.engine.context.ContextInjector
import com.phoneagent.data.store.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 引擎规划流（重构批次 4）：歧义检测 / 澄清 / 计划生成（含纠错重发）/ 模板复用 / 批准执行。
 * 从 AgentEngine 原样迁入，行为不变；状态与事件流存 [EngineStore]，UI 推送经 [UiBridge]，
 * 上下文素材经 [ContextInjector]。与执行主循环的接缝以 lambda 注入（见构造参数）。
 */
internal class PlanningFlow(
    private val appContext: android.content.Context,
    private val settings: AppSettings,
    private val aiClient: AiClient,
    private val memory: MemoryStore,
    private val scope: CoroutineScope,
    private val store: EngineStore,
    private val ui: UiBridge,
    private val ctx: ContextInjector,
    /** 引擎侧钩子：shell 引导会话是否可用（规划提示词据此注入执行环境段） */
    private val shellChannelAvailable: suspend () -> Boolean,
    /** 引擎侧钩子：思考模型配置（null = 未配置，回退主模型） */
    private val reasoningCfg: (AppSettings.Settings) -> ReasoningConfig?,
    /** 引擎侧钩子：say 事件入流（纯对话回复实时出气泡） */
    private val emitSay: (text: String, runKey: String, step: Int) -> Unit,
    /** 引擎侧钩子：记下本对话内的一次纯对话问答（新建对话时清空） */
    private val rememberChatTurn: (question: String, answer: String) -> Unit,
    /** 引擎侧钩子：批准后启动执行（与队列 worker 共用 job 槽位） */
    private val launchTask: (task: String, plan: com.phoneagent.domain.model.TaskPlan?) -> Unit,
    /** 引擎侧钩子：当前设置快照（同步读缓存值，配置预检用） */
    private val settingsSnapshot: () -> AppSettings.Settings,
) {
    /**
     * 配置预检：返回阻断原因（null = 可以规划）。
     * 判定口径收口在 [AppSettings.Settings.configProblem]，与执行侧共用同一份。
     */
    private fun configProblem(): String? = runCatching { settingsSnapshot().configProblem }.getOrNull()

    /** 记下一次澄清往来（问题 + 选择），供 Agent 页在任务流里成组保留；新一轮输入时清空 */
    private fun rememberClarify(question: String, answer: String) {
        if (answer.isBlank()) return
        store.clarifyEvents.value = (store.clarifyEvents.value + ClarifyAnswered(
            id = store.clarifySeq.incrementAndGet(),
            question = question.trim().take(200),
            answer = answer.trim().take(200),
        )).takeLast(MAX_CLARIFY_EVENTS)
    }

    // ==================== 规划 / 澄清 / 批准 ====================

    /** 开始规划：AI 流式思考并检测歧义。不直接执行。 */
    fun startPlanning(task: String) {
        if (task.isBlank()) return
        // 规划同样要调模型：配置缺失时直接给出可执行的结论，而不是先转一圈再报「规划出错」
        val missing = configProblem()
        if (missing != null) {
            store.pendingTask = task
            store.planStream.value = ""
            store.planPhase.value = PlanPhase.Error(missing)
            ui.log(com.phoneagent.domain.model.AgentLog.Level.ERROR, "未开始规划：$missing")
            ui.pushFloating("配置未完成", "ERROR")
            return
        }
        store.pendingTask = task
        store.planStream.value = ""
        // 上一轮任务里 AI 说过的话先清掉：纯对话不会进 run()，靠它清就太晚了，
        // 新问题的回答会和上一轮的旧气泡同屏
        store.sayEvents.value = emptyList()
        // 澄清往来同样只属于它那一段规划流：新一轮输入作废上一轮的 问题 + 选择
        store.clarifyEvents.value = emptyList()
        store.planPhase.value = PlanPhase.Planning
        // 新一轮输入：作废上一轮算好的会话承接块（它对应的是上一轮的输入与历史）
        store.sessionContextCache = null
        ui.log(com.phoneagent.domain.model.AgentLog.Level.INFO, "开始规划任务：$task")
        scope.launch {
            // 模板命中（v2.2 6.5）：目标相似且健康 → 直接复用历史脚本，零云端规划调用
            val tmpl = runCatching { com.phoneagent.data.store.TaskStore.matchTemplate(appContext, task) }.getOrNull()
            if (tmpl != null && tmpl.plan.steps.isNotEmpty()) {
                store.activePlan = tmpl.plan
                store.reusedTemplateId = tmpl.id
                store.planStream.value = "【模板复用】已匹配历史模板「${tmpl.goal}」，脚本 ${tmpl.plan.steps.size} 步，免规划"
                runCatching { com.phoneagent.data.store.TaskStore.bumpExecution(appContext, tmpl.id) }
                store.planPhase.value = PlanPhase.AwaitingApproval(tmpl.plan)
                val summary = tmpl.plan.steps.joinToString("\n") {
                    "${it.description}" + if (it.intent.isNotBlank()) " → ${it.intent}" else ""
                }.take(300)
                ui.pushFloating("等待批准", "OBSERVING")
                ui.showFloatingInteraction("approve", "执行计划（模板复用）", summary)
                ui.log(com.phoneagent.domain.model.AgentLog.Level.INFO, "模板命中，免规划：${tmpl.goal}")
                return@launch
            }
            try {
                store.planPhase.value = planWithRepair(task, null, null) { delta -> store.planStream.value += delta }
                if (store.planPhase.value is PlanPhase.Error) {
                    ui.log(com.phoneagent.domain.model.AgentLog.Level.ERROR, "规划失败（含纠错重发后仍无法解析）")
                    ui.pushFloating("规划失败", "ERROR")
                }
                settlePlanPhase()
            } catch (e: Exception) {
                store.planPhase.value = PlanPhase.Error("规划出错：${e.message}")
                ui.log(com.phoneagent.domain.model.AgentLog.Level.ERROR, "规划异常：${e.message}")
            }
        }
    }

    /**
     * 规划阶段落定后的收尾。
     *
     * [PlanPhase.Reply] 是纯对话：不请求批准、不进入执行，直接把这句话呈现成任务流里的一条消息
     * （用户要的"问一句你好不该走批准流程"就落在这里）；其余阶段按需推悬浮窗交互。
     */
    private fun settlePlanPhase() {
        when (val phase = store.planPhase.value) {
            is PlanPhase.Reply -> {
                emitSay(phase.text, "r${store.currentTaskId}", 1)
                ui.pushFloating(phase.text.take(20), "THINKING")
                // 这轮是纯对话（没有执行）：记进本对话的往来，下一轮"再改一下"才有据可依
                rememberChatTurn(store.pendingTask, phase.text)
            }

            is PlanPhase.Clarifying -> {
                ui.pushFloating("需要澄清", "OBSERVING")
                ui.showFloatingInteraction(
                    "clarify", "需要澄清", phase.clarification.question,
                    phase.clarification.options.map { it.label },
                )
            }

            is PlanPhase.AwaitingApproval -> {
                val plan = phase.plan
                val summary = plan?.steps?.joinToString("\n") { s ->
                    "${s.description}" + (s.intent?.let { " → $it" } ?: "")
                }?.take(300) ?: "计划已生成"
                ui.pushFloating("等待批准", "OBSERVING")
                ui.showFloatingInteraction("approve", "执行计划", summary)
            }

            else -> Unit
        }
    }

    /** 用户回答澄清问题后重新规划（流式） */
    fun answerClarification(option: ClarificationOption) {
        val task = store.pendingTask
        if (task.isBlank()) return
        // 记下 AI 刚问的那句：planPhase 马上要切走，问题只能在这儿读最后一次；
        // Option/答案一起入流，任务流据此保留 问题 + 选择 这一组往来
        val question = (store.planPhase.value as? PlanPhase.Clarifying)?.clarification?.question.orEmpty()
        rememberClarify(question, option.label)
        ui.clearFloatingQuery()
        store.planStream.value = ""
        store.planPhase.value = PlanPhase.Planning
        scope.launch {
            try {
                store.planPhase.value = planWithRepair(task, question, option.label) { delta -> store.planStream.value += delta }
                if (store.planPhase.value is PlanPhase.Error) {
                    ui.log(com.phoneagent.domain.model.AgentLog.Level.ERROR, "重新规划失败（含纠错重发后仍无法解析）")
                }
                settlePlanPhase()
            } catch (e: Exception) {
                store.planPhase.value = PlanPhase.Error("重新规划出错：${e.message}")
                ui.log(com.phoneagent.domain.model.AgentLog.Level.ERROR, "重新规划异常：${e.message}")
            }
        }
    }

    /** 用户批准计划后开始执行 */
    fun approvePlan() {
        val plan = store.activePlan
        val task = store.pendingTask
        if (task.isBlank()) return
        ui.clearFloatingQuery()
        store.planPhase.value = PlanPhase.Approved(plan)
        ui.log(com.phoneagent.domain.model.AgentLog.Level.INFO, "计划已批准：${plan?.steps?.size ?: 0} 步")
        // run() 自带异常兜底且显式放行协程取消，这里不能再包 runCatching：
        // 它会吞掉 CancellationException，把用户的「停止」误报成「执行异常」，
        // 还会把 stop() 已经设好的 IDLE 状态覆盖掉
        launchTask(task, plan)
    }

    fun cancelPlanning() {
        ui.clearFloatingQuery()
        store.planPhase.value = PlanPhase.Idle
        store.pendingTask = ""
        store.activePlan = null
    }

    private suspend fun cloudPlanStream(
        task: String,
        question: String?,
        answer: String?,
        onDelta: (String) -> Unit,
        /** 纠错重发：上一轮规划输出的原文（无法解析为标准 JSON 时非空），见 planWithRepair */
        prevUnrecognized: String? = null,
    ): String {
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        val profile = memory.loadProfile().takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.key}:${it.value}" } ?: ""
        val prompt = AgentPrompts.planning(lang, task, profile, ctx.installedAppList())
        // 言行一致：规划时注入真实 shell 通道可用性（Shizuku 或无线 ADB），让规划与执行统一（shell 直接启动而非点击图标）
        val execContext = if (shellChannelAvailable()) {
            when (lang) {
                PromptLang.CN -> "\n\n# 执行环境（规划必须考虑）\n端侧已接管执行方式（选用 Shizuku/无障碍），屏幕 ${ctx.screenWidth()}x${ctx.screenHeight()}。第一类动作写 open_app + 应用名；点击页面内可见控件写 tap + target（by_id/by_text/by_hint）；坐标与通道都无需你操心。"
                PromptLang.EN -> "\n\n# Execution Environment (plan must consider)\nThe device handles execution (Shizuku/accessibility), screen ${ctx.screenWidth()}x${ctx.screenHeight()}. Write open_app + app name to open apps; tap in-page controls with tap + target (by_id/by_text/by_hint); coordinates and channel need no care."
            }
        } else ""
        // 环境上下文（时间/网络/电量/已安装应用数）+ 会话承接（上一轮任务）：规划阶段就要带上，
        // 否则"明天""再改一下"这类依赖时间与上下文的说法在规划时无从判断
        val context = AgentPrompts.environment(lang, ctx.envFacts()) + ctx.sessionContextText(task, lang)
        // 页面记忆：任务提到的应用若走过历史路径，规划时注入参考（空串不占上下文；开关关闭则跳过）
        val pageMemoryContext = if (settingsVal.pageMemoryEnabled) {
            runCatching { AgentPrompts.pageMemoryPlanningBlock(lang, ctx.pageMemoryPlanningLines(task, lang)) }.getOrDefault("")
        } else ""
        // 澄清后重规划：问题与答案一起注入——光给答案不给问题，AI 就不知道这句话在回答哪一问
        val text = when {
            answer.isNullOrBlank() -> "$prompt$execContext$context$pageMemoryContext"
            question.isNullOrBlank() -> "$prompt$execContext$context$pageMemoryContext\n\n用户已选择澄清项：$answer"
            else -> "$prompt$execContext$context$pageMemoryContext\n\n你上一轮向用户提问：$question\n用户已选择澄清项：$answer"
        } + if (prevUnrecognized.isNullOrBlank()) "" else
            "\n\n## 上一轮返回无法识别\n" +
            "你上一轮针对本任务返回的内容无法解析为标准 JSON。上一轮原文：\n" +
            "<prev_unrecognized>\n${prevUnrecognized.take(1500)}\n</prev_unrecognized>\n" +
            "请重新回答：严格只输出一个标准 JSON 对象（不要解释文字、不要 Markdown 代码块围栏），" +
            "字段与格式必须与系统提示中给定的规划 JSON 规范一致。"
        val messages = listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = text))))
        // 链路聚合：规划等复杂任务优先使用思考模型（开启 thinking），未配置则回退主模型
        val reason = reasoningCfg(settingsVal)
        val planBase = reason ?: ReasoningConfig(settingsVal.apiBaseUrl, settingsVal.model, settingsVal.apiKey)
        // 实时展示发送给 AI 的规划提示词
        ui.pushThinking(sent = text)
        val startNano = System.nanoTime()
        var content = aiClient.chatStream(
            baseUrl = planBase.baseUrl,
            apiKey = planBase.apiKey,
            model = planBase.model,
            messages = messages,
            // 温度 v0.1 文档：歧义检测+规划合并 = 0.3
            temperature = PLANNING_TEMPERATURE,
            // 边思考边把增量内容实时显示到悬浮窗 + 通知。
            // 重试时同样把「在等什么」写进规划流：规划期没有步数进度，用户唯一的信号就是这段文本
            onRetry = { reason, waitMs ->
                com.phoneagent.overlay.FloatingWindowService.resetThinking()
                store.planStream.value = if (waitMs > 0) {
                    "（$reason，${(waitMs / 1000).coerceAtLeast(1)} 秒后重试…）"
                } else {
                    "（$reason…）"
                }
            },
            onDelta = { d ->
                onDelta(d)
                ui.pushThinking(delta = d)
            },
            // 思考模型开启深度思考
            thinking = reason != null,
            protocol = protocolOf(planBase.baseUrl, settingsVal),
        ).getOrElse { throw it }
        // 流式只产出了思考内容、正文为空时，回退到非流式，避免解析失败
        if (content.isBlank()) {
            content = aiClient.chat(
                baseUrl = planBase.baseUrl,
                apiKey = planBase.apiKey,
                model = planBase.model,
                messages = messages,
                temperature = PLANNING_TEMPERATURE,
                protocol = protocolOf(planBase.baseUrl, settingsVal),
            ).getOrElse { throw it }
        }
        val latencyMs = (System.nanoTime() - startNano) / 1_000_000
        ui.apiLog(text, content, latencyMs)
        return content
    }

    /** 端点协议解析：按归一化 URL 匹配模型库端点；未入库回落 OpenAI 兼容 */
    private fun protocolOf(baseUrl: String, settingsVal: AppSettings.Settings): ApiProtocol =
        ApiProtocol.fromId(
            settingsVal.endpoints.firstOrNull { it.id == AiClient.normalizeBaseUrl(baseUrl) }?.protocol,
        )

    /**
     * 规划调用 + 解析，解析失败时纠错重发一次。
     *
     * 模型偶尔会输出「解释文字 + JSON」之外的完全无法识别的内容（截断、纯散文、半截 JSON），
     * 直接判 Error 会让用户白等一整轮。这里在判定失败后附上「上一轮信息无法识别」+ 原文重发一次，
     * 要求只输出标准 JSON；重发仍失败才收敛为 PlanPhase.Error（上限一次，不与模型死循环）。
     */
    private suspend fun planWithRepair(
        task: String,
        question: String?,
        answer: String?,
        onDelta: (String) -> Unit,
    ): PlanPhase {
        val content = cloudPlanStream(task, question, answer, onDelta)
        val first = parsePlanResponse(content)
        if (first !is PlanPhase.Error) return first
        ui.log(com.phoneagent.domain.model.AgentLog.Level.WARN, "规划输出无法解析为标准 JSON，附上原文纠错重发（仅一次）：${content.take(120)}")
        val repaired = cloudPlanStream(task, question, answer, onDelta, prevUnrecognized = content)
        // 重发仍失败 → 仍是 PlanPhase.Error，原样返回（上限一次，不与模型死循环）
        return parsePlanResponse(repaired)
    }

    /**
     * 解析规划输出并落副作用。
     *
     * 格式解析本身在 AgentResponseParser.parsePlan（纯逻辑、可单测）；这里只保留两件必须留在引擎里的事：
     * 写 `activePlan`（`approvePlan` 会读它）与写日志（要有任务上下文才有意义）。
     * 日志文案按解析结果反推，与拆分前逐字一致。
     */
    private fun parsePlanResponse(content: String): PlanPhase =
        AgentResponseParser.parsePlan(content).also { phase ->
            when (phase) {
                is PlanPhase.Clarifying -> ui.log(com.phoneagent.domain.model.AgentLog.Level.AI, "需要澄清：${phase.clarification.question}")
                is PlanPhase.Reply -> ui.log(com.phoneagent.domain.model.AgentLog.Level.AI, "纯对话回复：${phase.text.take(60)}")
                is PlanPhase.AwaitingApproval -> {
                    store.activePlan = phase.plan
                    ui.log(com.phoneagent.domain.model.AgentLog.Level.AI, "规划完成：${phase.plan.steps.size} 步")
                }

                else -> Unit
            }
        }
}
