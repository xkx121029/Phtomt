package com.phoneagent.agent

import android.accessibilityservice.AccessibilityService
import com.phoneagent.a11y.ActionExecutor
import com.phoneagent.a11y.AgentAccessibilityService
import com.phoneagent.ai.AiClient
import com.phoneagent.ai.ChatMessageDto
import com.phoneagent.ai.ContentPart
import com.phoneagent.ai.GlmDefaults
import com.phoneagent.data.prefs.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.phoneagent.decision.LocalDecisionEngine
import com.phoneagent.execution.VerifiedClickExecutor
import com.phoneagent.floating.FloatingWindowService
import com.phoneagent.memory.AnomalyMemoryEngine
import com.phoneagent.memory.MemoryStore
import com.phoneagent.memory.ProfileLearner
import com.phoneagent.model.AgentAction
import com.phoneagent.model.AgentLog
import com.phoneagent.model.AgentMetrics
import com.phoneagent.model.AgentState
import com.phoneagent.model.ActionType
import com.phoneagent.model.Clarification
import com.phoneagent.model.ClarificationOption
import com.phoneagent.model.ConversationMessage
import com.phoneagent.model.PlanResponse
import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.StepRecord
import com.phoneagent.model.TaskPlan
import com.phoneagent.model.UiElement
import com.phoneagent.network.CloudAgent
import com.phoneagent.perception.PageAnnotator
import com.phoneagent.perception.effectiveLabel
import com.phoneagent.screen.ScreenSharingService
import com.phoneagent.security.DataSanitizer
import com.phoneagent.security.SensitivePageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Agent 引擎：编排“观察 → 本地/云端决策 → 带验证执行 → 记录”的 ReAct 循环。
 *
 * 集成（对应 HPAv2.0 计划文档）：
 * - 第 2 层：页面标注（页面类型/上下文/优先级）
 * - 第 3 层：页面指纹（执行前后验证）
 * - 第 4 层：带验证执行
 * - 第 5 层：端侧决策优先（弹窗/加载/异常/完成不调云端）
 * - 第 6 层：稳健 JSON 解析 + 重试
 * - 第 10 层：异常记忆（命中复用）
 * - 第 11 层：多任务队列
 * - 第 12 层：安全（敏感页只读 / 数据脱敏）
 */
class AgentEngine(
    private val settings: AppSettings,
    private val aiClient: AiClient,
    private val appContext: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val cloudAgent = CloudAgent(aiClient)
    private val localDecision = LocalDecisionEngine()
    private val memory = MemoryStore(appContext)
    private val anomalyEngine = AnomalyMemoryEngine(memory)
    private val profileLearner = ProfileLearner(memory)

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> get() = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<AgentLog>>(emptyList())
    val logs: StateFlow<List<AgentLog>> get() = _logs.asStateFlow()

    private val _conversation = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversation: StateFlow<List<ConversationMessage>> get() = _conversation.asStateFlow()

    private val _metrics = MutableStateFlow(AgentMetrics())
    val metrics: StateFlow<AgentMetrics> get() = _metrics.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<StepRecord>>(emptyList())
    val executionHistory: StateFlow<List<StepRecord>> get() = _executionHistory.asStateFlow()

    // ---- 规划/澄清/批准流程 ----
    private val _planPhase = MutableStateFlow<PlanPhase>(PlanPhase.Idle)
    val planPhase: StateFlow<PlanPhase> get() = _planPhase.asStateFlow()
    /** 规划阶段的流式输出文本（边思考边显示） */
    private val _planStream = MutableStateFlow("")
    val planStream: StateFlow<String> get() = _planStream.asStateFlow()
    private var pendingTask = ""
    private var activePlan: TaskPlan? = null
    private val translateCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 若文本为中文则原样返回；否则调用 AI 翻译成简体中文（带缓存） */
    suspend fun translateText(text: String): String {
        if (text.isBlank() || isMostlyChinese(text)) return text
        translateCache[text]?.let { return it }
        val settingsVal = settings.settings.first()
        val prompt = "请把下面内容翻译成简体中文。只输出译文本身，不要任何修饰或解释：\n\n$text"
        val messages = listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = prompt))))
        val translated = aiClient.chatText(
            baseUrl = settingsVal.apiBaseUrl,
            apiKey = settingsVal.apiKey,
            model = settingsVal.model,
            messages = messages,
            temperature = 0.1,
        ).getOrDefault("").ifBlank { text }
        translateCache[text] = translated
        return translated
    }

    /** 粗略判断文本是否以中文为主（含中文字符比例 > 30%） */
    private fun isMostlyChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        return cjk.toFloat() / text.length > 0.3f
    }

    // ---- 第 11 层：多任务队列 ----
    private val _taskQueue = MutableStateFlow<List<String>>(emptyList())
    val taskQueue: StateFlow<List<String>> get() = _taskQueue.asStateFlow()

    // ---- 第 9 层：用户协作 ----
    private val _userHintRequest = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userHintRequest: SharedFlow<String> get() = _userHintRequest.asSharedFlow()
    private val _needsUser = MutableStateFlow(false)
    val needsUser: StateFlow<Boolean> get() = _needsUser.asStateFlow()
    private val _userHintResult = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var lastSnapshot: ScreenSnapshot = ScreenSnapshot()
    private var currentLang = PromptLang.CN
    private var consecutiveFailures = 0

    // 温度 v0.1 文档：按场景固定，不开放给用户自选
    private companion object {
        /** 歧义检测 + 规划 */
        const val PLANNING_TEMPERATURE = 0.3
        /** 每步决策（正常） */
        const val DECISION_TEMPERATURE = 0.1
        /** 失败 3 次后重规划 */
        const val REPLAN_TEMPERATURE = 0.5
    }

    /** 若已授予悬浮窗权限则启动悬浮窗 */
    private fun maybeStartFloating() {
        if (android.provider.Settings.canDrawOverlays(appContext)) {
            FloatingWindowService.start(appContext)
        }
    }

    /** 实时推送进度到悬浮窗 */
    private fun pushFloating(status: String, phase: String, queryLabel: String? = null, queryContent: String? = null) {
        val s = _state.value
        FloatingWindowService.update(
            status = status,
            task = s.task ?: "Happy Agent",
            reasoning = s.lastAction?.reasoning ?: s.message ?: status,
            step = s.stepCount,
            total = 0,
            phase = phase,
            queryLabel = queryLabel,
            queryContent = queryContent,
        )
    }

    /** 清空悬浮窗中的提问内容 */
    private fun clearFloatingQuery() {
        val s = _state.value
        FloatingWindowService.update(
            status = s.message ?: "执行中",
            task = s.task ?: "Happy Agent",
            reasoning = s.lastAction?.reasoning ?: s.message ?: "执行中",
            step = s.stepCount,
            total = 0,
            phase = s.phase.name,
        )
    }

    /** 上一步执行结果文本（✅/⚠️/❌），供决策 Prompt 使用 */
    private fun lastStepResultText(): String {
        val last = _executionHistory.value.lastOrNull() ?: return ""
        val action = last.action?.run { "${type}${reason?.let { "($it)" } ?: ""}" } ?: "-"
        return when {
            last.isConfirmed -> "✅ 已确认成功: $action"
            last.verificationResult == "failed" -> "❌ 未生效: $action"
            else -> "⚠️ 已发送但未确认: $action"
        }
    }

    private fun log(level: AgentLog.Level, message: String, detail: String? = null) {
        _logs.value = _logs.value + AgentLog(System.currentTimeMillis(), level, message, detail)
    }

    /** 记录完整 API 请求/响应（用于调试页日志，可展开查看全文） */
    private fun apiLog(request: String, response: String, latencyMs: Long) {
        val summary = "API 调用 · ${latencyMs}ms"
        val detail = "═══ 请求 ═══\n$request\n\n═══ 响应 ═══\n$response"
        _logs.value = _logs.value + AgentLog(System.currentTimeMillis(), AgentLog.Level.API, summary, detail)
    }

    private fun addConversation(role: String, content: String, hasImage: Boolean = false) {
        _conversation.value = _conversation.value +
            ConversationMessage(role, content, System.currentTimeMillis(), hasImage)
    }

    fun clearDebug() {
        _logs.value = emptyList()
        _conversation.value = emptyList()
        _metrics.value = AgentMetrics()
        _executionHistory.value = emptyList()
    }

    /** 提交任务到队列并开始处理 */
    fun start(task: String) {
        if (task.isBlank()) return
        _taskQueue.value = _taskQueue.value + task
        log(AgentLog.Level.INFO, "任务已加入队列：$task")
        if (job?.isActive != true) {
            job = scope.launch { processQueue() }
        }
    }

    // ==================== 规划 / 澄清 / 批准 ====================

    /** 开始规划：AI 流式思考并检测歧义。不直接执行。 */
    fun startPlanning(task: String) {
        if (task.isBlank()) return
        pendingTask = task
        _planStream.value = ""
        _planPhase.value = PlanPhase.Planning
        log(AgentLog.Level.INFO, "开始规划任务：$task")
        scope.launch {
            try {
                val content = cloudPlanStream(task, null) { delta -> _planStream.value += delta }
                _planPhase.value = parsePlanResponse(content)
                if (_planPhase.value is PlanPhase.Error) {
                    log(AgentLog.Level.ERROR, "规划失败：$content")
                    pushFloating("规划失败", "ERROR", "规划失败", content.take(200))
                }
                // 规划完成后，需要用户交互时推送内容到悬浮窗
                when (val phase = _planPhase.value) {
                    is PlanPhase.Clarifying -> {
                        pushFloating("需要澄清", "OBSERVING", "需要澄清", phase.clarification.question)
                    }
                    is PlanPhase.AwaitingApproval -> {
                        val plan = phase.plan
                        val summary = plan?.steps?.joinToString("\n") { s ->
                            "${s.description}" + (s.intent?.let { " → $it" } ?: "")
                        }?.take(200) ?: "计划已生成"
                        pushFloating("等待批准", "OBSERVING", "需要批准", summary)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                _planPhase.value = PlanPhase.Error("规划出错：${e.message}")
                log(AgentLog.Level.ERROR, "规划异常：${e.message}")
            }
        }
    }

    /** 用户回答澄清问题后重新规划（流式） */
    fun answerClarification(option: ClarificationOption) {
        val task = pendingTask
        if (task.isBlank()) return
        clearFloatingQuery()
        _planStream.value = ""
        _planPhase.value = PlanPhase.Planning
        scope.launch {
            try {
                val content = cloudPlanStream(task, option.label) { delta -> _planStream.value += delta }
                _planPhase.value = parsePlanResponse(content)
                if (_planPhase.value is PlanPhase.Error) {
                    log(AgentLog.Level.ERROR, "重新规划失败：$content")
                }
            } catch (e: Exception) {
                _planPhase.value = PlanPhase.Error("重新规划出错：${e.message}")
                log(AgentLog.Level.ERROR, "重新规划异常：${e.message}")
            }
        }
    }

    /** 用户批准计划后开始执行 */
    fun approvePlan() {
        val plan = activePlan
        val task = pendingTask
        if (task.isBlank()) return
        clearFloatingQuery()
        _planPhase.value = PlanPhase.Approved(plan)
        log(AgentLog.Level.INFO, "计划已批准：${plan?.steps?.size ?: 0} 步")
        if (job?.isActive != true) {
            job = scope.launch {
                runCatching { run(task, plan) }
                    .onFailure { e ->
                        log(AgentLog.Level.ERROR, "任务执行异常：${e.message}")
                        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.ERROR, message = "执行异常：${e.message}")
                        FloatingWindowService.stop(appContext)
                    }
            }
        }
    }

    fun cancelPlanning() {
        clearFloatingQuery()
        _planPhase.value = PlanPhase.Idle
        pendingTask = ""
        activePlan = null
    }

    private suspend fun cloudPlanStream(task: String, answer: String?, onDelta: (String) -> Unit): String {
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        val profile = memory.loadProfile().takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.key}:${it.value}" } ?: ""
        val prompt = AgentPrompts.planning(lang, task, profile, "")
        val text = if (answer.isNullOrBlank()) prompt else "$prompt\n\n用户已选择澄清项：$answer"
        val messages = listOf(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = text))))
        // 链路聚合：规划等复杂任务优先使用思考模型（开启 thinking），未配置则回退主模型
        val reason = reasoningConfig(settingsVal)
        val planBase = reason ?: ReasoningConfig(settingsVal.apiBaseUrl, settingsVal.model, settingsVal.apiKey)
        val startNano = System.nanoTime()
        var content = aiClient.chatStream(
            baseUrl = planBase.baseUrl,
            apiKey = planBase.apiKey,
            model = planBase.model,
            messages = messages,
            // 温度 v0.1 文档：歧义检测+规划合并 = 0.3
            temperature = PLANNING_TEMPERATURE,
            onDelta = onDelta,
            // 思考模型开启深度思考
            thinking = reason != null,
        ).getOrElse { throw it }
        // 流式只产出了思考内容、正文为空时，回退到非流式，避免解析失败
        if (content.isBlank()) {
            content = aiClient.chat(
                baseUrl = planBase.baseUrl,
                apiKey = planBase.apiKey,
                model = planBase.model,
                messages = messages,
                temperature = PLANNING_TEMPERATURE,
            ).getOrElse { throw it }
        }
        val latencyMs = (System.nanoTime() - startNano) / 1_000_000
        apiLog(text, content, latencyMs)
        return content
    }

    private fun parsePlanResponse(content: String): PlanPhase {
        return try {
            val obj = extractJsonObject(content)
            // 手动解析 JSON，兼容 steps 为字符串数组或对象数组
            val root = json.parseToJsonElement(obj).jsonObject
            val needsClarification = root["needs_clarification"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

            if (needsClarification) {
                val clarObj = root["clarification"]?.jsonObject
                val question = clarObj?.get("question")?.jsonPrimitive?.contentOrNull ?: ""
                val opts = clarObj?.get("options") as? kotlinx.serialization.json.JsonArray
                val options = opts?.mapNotNull { opt ->
                    val o = opt as? JsonObject ?: return@mapNotNull null
                    com.phoneagent.model.ClarificationOption(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
                        description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        isDefault = o["is_default"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                    )
                } ?: emptyList()
                log(AgentLog.Level.AI, "需要澄清：$question")
                PlanPhase.Clarifying(com.phoneagent.model.Clarification(question = question, options = options))
            } else {
                val planObj = root["plan"]?.jsonObject
                if (planObj != null) {
                    val stepsRaw = planObj["steps"] as? kotlinx.serialization.json.JsonArray
                    val steps = if (stepsRaw != null) com.phoneagent.model.parseTaskSteps(stepsRaw) else emptyList()
                    val estimatedTime = planObj["estimated_time_seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val confidence = planObj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val plan = com.phoneagent.model.TaskPlan(steps = steps, estimatedTimeSeconds = estimatedTime, confidence = confidence)
                    activePlan = plan
                    log(AgentLog.Level.AI, "规划完成：${plan.steps.size} 步")
                    PlanPhase.AwaitingApproval(plan)
                } else {
                    PlanPhase.Error("规划结果无法解析")
                }
            }
        } catch (e: Exception) {
            PlanPhase.Error("规划解析失败：${e.message}")
        }
    }

    private fun extractJsonObject(content: String): String {
        val trimmed = content.trim()
        // 处理 ```json ... ``` 或 ``` ... ``` 代码块包裹
        if (trimmed.startsWith("```")) {
            val firstNewline = trimmed.indexOf("\n")
            val lastFence = trimmed.lastIndexOf("```")
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim()
            }
        }
        // 处理中间出现代码块的情况（前有文字+```json）
        val codeBlockStart = trimmed.indexOf("```json")
        if (codeBlockStart >= 0) {
            val afterMarker = trimmed.indexOf("\n", codeBlockStart)
            val blockEnd = trimmed.indexOf("```", afterMarker)
            if (afterMarker > 0 && blockEnd > afterMarker) {
                return trimmed.substring(afterMarker + 1, blockEnd).trim()
            }
        }
        // 兜底：提取第一个 { 到最后一个 }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
        FloatingWindowService.stop(appContext)
        log(AgentLog.Level.WARN, "任务已停止")
    }

    /** 用户协作者：提供下一步指导 */
    fun provideUserHint(hint: String) {
        if (hint.isBlank()) return
        clearFloatingQuery()
        _userHintResult.tryEmit(hint)
    }

    fun dismissUser() {
        _needsUser.value = false
        clearFloatingQuery()
        _userHintResult.tryEmit("")
    }

    private suspend fun processQueue() {
        while (coroutineContext.isActive && _taskQueue.value.isNotEmpty()) {
            val task = _taskQueue.value.first()
            _taskQueue.value = _taskQueue.value.drop(1)
            run(task, null)
        }
        _state.value = _state.value.copy(isRunning = false, phase = AgentState.Phase.IDLE)
    }

    private suspend fun run(task: String, plan: TaskPlan? = null) {
        val settingsVal = settings.settings.first()
        val lang = runCatching { PromptLang.valueOf(settingsVal.promptLanguage) }.getOrDefault(PromptLang.CN)
        currentLang = lang
        val messages = mutableListOf<ChatMessageDto>().apply {
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.system(lang, settingsVal.systemPrompt, settingsVal.hasVision)))))
            add(ChatMessageDto(role = "system", content = listOf(ContentPart(type = "text", text = AgentPrompts.capabilitiesLang(lang, settingsVal.hasVision)))))
        }

        val maxSteps = settingsVal.maxSteps
        var step = 0
        _state.value = AgentState(isRunning = true, task = task, phase = AgentState.Phase.OBSERVING)
        maybeStartFloating()
        pushFloating("开始执行", "OBSERVING")
        while (maxSteps <= 0 || step < maxSteps) {
            step++
            if (!coroutineContext.isActive) return
            _state.value = _state.value.copy(
                phase = AgentState.Phase.OBSERVING,
                stepCount = step,
                hasAccessibility = AgentAccessibilityService.isServiceEnabled(appContext),
            )
            pushFloating("正在观察屏幕", "OBSERVING")

            // 1. 观察 + 标注
            val snapshot = observe()
            lastSnapshot = snapshot
            val annotated = PageAnnotator.annotate(snapshot)
            val screenshot = if (settingsVal.attachScreenshot) ScreenSharingService.instance?.captureFrame() else null
            log(AgentLog.Level.INFO, "第 $step 轮观察：${snapshot.elements.size} 个元素，页面类型=${annotated.pageType}")

            // 2. 安全：敏感页只读
            if (SensitivePageDetector.isSensitive(snapshot)) {
                log(AgentLog.Level.WARN, "检测到敏感页面，拒绝执行动作，需用户介入")
                pushFloating("敏感页面", "ERROR", "需要指导", "检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后输入提示继续。")
                _needsUser.value = true
                _userHintRequest.tryEmit("检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后输入提示继续。")
                val hint = awaitUserHint()
                if (hint.isBlank()) { stop(); return }
                continue
            }

            // 3. 端侧决策优先
            val localAction = localDecision.decide(snapshot)
            var action: AgentAction?
            var fromLocal = false
            if (localAction != null) {
                action = localAction
                fromLocal = true
                log(AgentLog.Level.AI, "端侧决策：${action.type}（${action.reason}）")
            } else {
                action = cloudDecide(task, snapshot, annotated, messages, screenshot, settingsVal)
            }

            if (action == null) { log(AgentLog.Level.ERROR, "决策为空，停止"); stop(); return }
            // 规范化文档动作词汇（task_complete/abort → task_done 等）
            action = action.copy(type = ActionType.ALIAS[action.type] ?: action.type)
            addConversation(if (fromLocal) "local" else "assistant", action.toString())

            // 4. 执行 + 验证
            _state.value = _state.value.copy(phase = AgentState.Phase.ACTING, lastAction = action, message = action.reason ?: "")
            pushFloating(action.reasoning ?: action.reason ?: "正在执行", "ACTING")
            if (action.type == ActionType.TASK_DONE) {
                log(AgentLog.Level.INFO, "任务完成：${action.summary ?: "-"}")
                recordStep(step, action, "verified_success", "", "")
                _state.value = _state.value.copy(phase = AgentState.Phase.DONE, message = action.summary ?: "任务完成", isRunning = false)
                pushFloating(action.summary ?: "任务完成", "DONE")
                FloatingWindowService.stop(appContext)
                return
            }

            val verify = executeWithVerify(action, snapshot)

            // 验证失败 → 重试最多 3 次，然后请求用户协作
            var verified = verify.success
            var times = 1
            while (!verified && times < 3 && coroutineContext.isActive) {
                times++
                log(AgentLog.Level.WARN, "动作未生效（第 $times 次重试）：${action.type}")
                repeat(3) { delay(300) }
                val v2 = executeWithVerify(action, observe())
                verified = v2.success
            }
            consecutiveFailures = if (verified) 0 else consecutiveFailures + 1
            if (!verified) {
                recordStep(step, action, "failed", verify.beforeFingerprint, verify.afterFingerprint)
                log(AgentLog.Level.ERROR, "动作 3 次未生效：${action.type}，请求用户介入")
                pushFloating("需要指导", "ERROR", "需要指导", "动作「${action.type}」连续未能改变页面，请选择：手动接管 / 告诉 AI 怎么做。")
                _needsUser.value = true
                _userHintRequest.tryEmit("动作「${action.type}」连续未能改变页面，请选择：手动接管 / 告诉 AI 怎么做。")
                val hint = awaitUserHint()
                if (hint.isNotBlank()) {
                    // 用户指导 → 云端重新决策
                    val guided = cloudAgent.decideWithUserHint(settingsVal.apiBaseUrl, settingsVal.apiKey, settingsVal.model, messages, hint).getOrNull()
                    if (guided != null && guided.type != ActionType.TASK_DONE) {
                        action = guided
                        continue
                    }
                } else {
                    stop(); return
                }
            }

            log(AgentLog.Level.INFO, "执行动作：${action.type}（${if (verified) "已验证生效" else "待确认"}）")
            recordsIntoHistory(step, action, verify)

            // 记录上下文供多轮参考
            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "执行了 ${action.type}" +
                (action.reason?.let { "（$it）" } ?: "")))))
            messages.add(ChatMessageDto(role = "assistant", content = listOf(ContentPart(type = "text", text = action.type))))
            delay(400)
        }
        _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = "达到最大步数限制")
        log(AgentLog.Level.WARN, "达到最大步数限制，自动停止")
        pushFloating("达到最大步数限制", "ERROR")
        stop()
    }

    private suspend fun cloudDecide(
        task: String,
        snapshot: ScreenSnapshot,
        annotated: com.phoneagent.perception.AnnotatedPage,
        messages: MutableList<ChatMessageDto>,
        screenshot: android.graphics.Bitmap?,
        settingsVal: AppSettings.Settings,
    ): AgentAction? {
        // 数据脱敏后再发送
        val safeText = DataSanitizer.sanitize(snapshot.toAiText())
        val planSteps = activePlan?.steps?.mapIndexed { i, s -> "${i + 1}. ${s.description}" }?.joinToString("\n")
        val planNote = if (!planSteps.isNullOrBlank()) "\n\n## 已批准的执行计划\n$planSteps" else ""
        // 视觉链路：主模型不支持图片输入时，先用递归视觉模型描述截图，再让主模型基于文本决策
        val visionCfg = visionConfig(settingsVal)
        var pageText = safeText
        if (screenshot != null && visionCfg != null) {
            log(AgentLog.Level.INFO, "视觉模型描述截图…（${visionCfg.model}）")
            val desc = aiClient.visionDescribe(
                baseUrl = visionCfg.baseUrl,
                apiKey = visionCfg.apiKey,
                model = visionCfg.model,
                screenshot = screenshot,
                task = task,
            ).getOrNull()
            if (!desc.isNullOrBlank()) pageText += "\n\n## 视觉描述（截图）\n$desc"
        }
        val userText = AgentPrompts.decision(
            lang = currentLang,
            task = task,
            stepIndex = _state.value.stepCount,
            totalSteps = (_state.value.stepCount).coerceAtLeast(1),
            currentStep = if (!planSteps.isNullOrBlank()) "按计划执行下一步" else "根据当前页面执行下一步",
            lastStepResult = lastStepResultText(),
            consecutiveFailures = consecutiveFailures,
            contextHint = annotated.contextHint,
        ) + planNote + "\n\n## 当前页面\n$pageText"
        val userMsg = ChatMessageDto(role = "user", content = mutableListOf(ContentPart(type = "text", text = userText)))
        addConversation("user", userText, hasImage = screenshot != null)

        _state.value = _state.value.copy(phase = AgentState.Phase.THINKING, message = "正在思考下一步...")
        pushFloating("正在思考下一步", "THINKING")
        val startNano = System.nanoTime()
        // 主模型不收截图（只收视觉描述后的文本），避免不支持图片的模型报错
        val result = aiClient.chatForAction(
            baseUrl = settingsVal.apiBaseUrl,
            apiKey = settingsVal.apiKey,
            model = settingsVal.model,
            messages = messages + userMsg,
            screenshot = null,
            // 温度 v0.1 文档：每步决策 = 0.1；失败 3 次进入重规划 = 0.5
            temperature = decisionTemperature(),
        )
        val latencyMs = (System.nanoTime() - startNano) / 1_000_000
        val decision = result.getOrElse { err ->
            log(AgentLog.Level.ERROR, "AI 调用失败：${err.message}")
            _state.value = _state.value.copy(phase = AgentState.Phase.ERROR, message = err.message ?: "AI 调用失败")
            return null
        }
        apiLog(userText, decision.rawContent.ifBlank { "（无正文，可能为错误）" }, latencyMs)
        recordMetrics(decision)
        var action = decision.action
        // 视觉定位：主模型基于视觉描述选择了目标，用视觉模型给出点击比例坐标
        if (screenshot != null && visionCfg != null) {
            val targetText = action.target?.value
            if (!targetText.isNullOrBlank()) {
                log(AgentLog.Level.INFO, "视觉模型定位目标：$targetText")
                val pos = aiClient.visionLocate(
                    baseUrl = visionCfg.baseUrl,
                    apiKey = visionCfg.apiKey,
                    model = visionCfg.model,
                    screenshot = screenshot,
                    targetText = targetText,
                ).getOrNull()
                if (pos != null) {
                    action = action.copy(target = com.phoneagent.model.ActionTarget(method = "coordinate", value = "${pos.first},${pos.second}"))
                }
            }
        }
        return action
    }

    /** 测试主模型连接是否可用（用于设置保存前的校验） */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> {
        val messages = listOf(
            ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请只回复：OK"))),
        )
        return aiClient.chat(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim(),
            messages = messages,
            temperature = 0.1,
        )
    }

    /** 视觉模型配置：仅在视觉模型启用时生效；视觉 API Key 为空则回退主模型 Key */
    private fun visionConfig(settingsVal: AppSettings.Settings): VisionConfig? {
        if (!settingsVal.visionEnabled) return null
        val model = settingsVal.visionModel.trim()
        if (model.isBlank()) return null
        val baseUrl = settingsVal.visionBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
        val apiKey = settingsVal.visionApiKey.trim().ifBlank { settingsVal.apiKey }
        if (apiKey.isBlank()) return null
        return VisionConfig(baseUrl, model, apiKey)
    }

    /** 思考模型配置：链路聚合开启时才参与规划/重规划；关闭则规划回退主模型 */
    private fun reasoningConfig(settingsVal: AppSettings.Settings): ReasoningConfig? {
        if (!settingsVal.enableChain) return null
        val model = settingsVal.reasonModel.trim()
        if (model.isBlank()) return null
        val baseUrl = settingsVal.reasonBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
        val apiKey = settingsVal.reasonApiKey.trim().ifBlank { settingsVal.apiKey }
        if (apiKey.isBlank()) return null
        return ReasoningConfig(baseUrl, model, apiKey)
    }

    /** 每步决策温度：失败越频繁越鼓励换思路（对应温度文档第三节） */
    private fun decisionTemperature(): Double =
        if (consecutiveFailures >= 3) REPLAN_TEMPERATURE else DECISION_TEMPERATURE

    private suspend fun executeWithVerify(action: AgentAction, snapshot: ScreenSnapshot): com.phoneagent.execution.VerifyResult {
        val service = AgentAccessibilityService.instance ?: return com.phoneagent.execution.VerifyResult(false, "无障碍服务不可用", "", "")
        val executor = ActionExecutor(service)
        val verifier = VerifiedClickExecutor(executor)
        // 规范化文档动作词汇
        val type = ActionType.ALIAS[action.type] ?: action.type
        val target = resolveTarget(action, snapshot)
        val (x, y) = resolvePoint(action, target)
        if (x == null || y == null) return com.phoneagent.execution.VerifyResult(false, "无法定位动作目标", "", "")

        return when (type) {
            ActionType.CLICK, ActionType.TAP -> verifier.executeAndVerify(snapshot, action) { executor.click(x, y).isSuccess() }
            ActionType.LONG_CLICK, ActionType.LONG_PRESS -> verifier.executeAndVerify(snapshot, action) { executor.longClick(x, y).isSuccess() }
            ActionType.SWIPE -> {
                val (ex, ey) = swipeEndpoints(x, y, action.direction)
                verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, ex, ey, action.durationMs ?: 400).isSuccess() }
            }
            ActionType.SWIPE_UP -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x, y - screenHeight()).isSuccess() }
            ActionType.SWIPE_DOWN -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x, y + screenHeight()).isSuccess() }
            ActionType.SWIPE_LEFT -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x - screenWidth(), y).isSuccess() }
            ActionType.SWIPE_RIGHT -> verifier.executeAndVerify(snapshot, action) { executor.swipe(x, y, x + screenWidth(), y).isSuccess() }
            ActionType.SCROLL, ActionType.SCROLL_TO -> verifier.executeAndVerify(snapshot, action) { executor.scroll(target, action.direction ?: action.text ?: "up").isSuccess() }
            ActionType.TYPE_TEXT, ActionType.TYPE_TEXT -> verifier.executeAndVerify(snapshot, action) { executor.typeText(action.text ?: "", target).isSuccess() }
            ActionType.KEY -> handleKey(executor, action.keycode ?: "BACK")
            ActionType.LAUNCH -> executeNoVerify(executor) { executor.launchApp(action.packageName ?: "").isSuccess() }
            ActionType.BACK -> executeNoVerify(executor) { executor.back().isSuccess() }
            ActionType.HOME -> executeNoVerify(executor) { executor.home().isSuccess() }
            ActionType.RECENTS -> executeNoVerify(executor) { executor.recents().isSuccess() }
            ActionType.WAIT -> { delay(action.durationMs ?: 1000); com.phoneagent.execution.VerifyResult(true, "等待完成", "", "") }
            ActionType.REFRESH -> com.phoneagent.execution.VerifyResult(true, "刷新", "", "")
            else -> com.phoneagent.execution.VerifyResult(false, "未知动作", "", "")
        }
    }

    /** 解析动作目标元素：优先 elementIndex，其次 target.method=id/label */
    private fun resolveTarget(action: AgentAction, snapshot: ScreenSnapshot): UiElement? {
        action.elementIndex?.let { idx -> snapshot.elements.firstOrNull { it.index == idx } }?.let { return it }
        val t = action.target ?: return null
        return when (t.method) {
            "label" -> snapshot.elements.firstOrNull {
                val label = it.effectiveLabel() ?: return@firstOrNull false
                label.contains(t.value, ignoreCase = true)
            }
            "id" -> snapshot.elements.firstOrNull {
                (it.viewId ?: "").endsWith(t.value, ignoreCase = true)
            }
            else -> null
        }
    }

    /** 解析点击坐标：目标元素中心 > 比例坐标 > 直接 x/y */
    private fun resolvePoint(action: AgentAction, target: UiElement?): Pair<Int?, Int?> {
        if (target != null) return target.centerX to target.centerY
        action.x?.let { if (action.y != null) return action.x to action.y }
        val t = action.target ?: return null to null
        if (t.method == "coordinate") {
            val parts = t.value.split(",").map { it.trim().toFloatOrNull() }
            if (parts.size == 2 && parts[0] != null && parts[1] != null) {
                return (parts[0]!! * screenWidth()).toInt() to (parts[1]!! * screenHeight()).toInt()
            }
        }
        return null to null
    }

    private fun swipeEndpoints(x: Int, y: Int, direction: String?): Pair<Int, Int> {
        val dist = screenHeight()
        return when (direction) {
            "up" -> x to (y - dist).coerceAtLeast(0)
            "down" -> x to (y + dist).coerceAtMost(screenHeight())
            "left" -> (x - dist).coerceAtLeast(0) to y
            "right" -> (x + dist).coerceAtMost(screenWidth()) to y
            else -> x to y
        }
    }

    private suspend fun handleKey(executor: ActionExecutor, keycode: String): com.phoneagent.execution.VerifyResult {
        return when (keycode.uppercase()) {
            "BACK" -> executeNoVerify(executor) { executor.back().isSuccess() }
            "HOME" -> executeNoVerify(executor) { executor.home().isSuccess() }
            "RECENT", "RECENTS" -> executeNoVerify(executor) { executor.recents().isSuccess() }
            "ENTER" -> com.phoneagent.execution.VerifyResult(true, "回车（假定键盘已确认）", "", "")
            else -> com.phoneagent.execution.VerifyResult(false, "未知按键 $keycode", "", "")
        }
    }

    private suspend fun executeNoVerify(executor: ActionExecutor, block: suspend () -> Boolean): com.phoneagent.execution.VerifyResult {
        val ok = block()
        delay(500)
        return com.phoneagent.execution.VerifyResult(ok, if (ok) "已执行" else "执行失败", "", "")
    }

    private fun recordMetrics(decision: com.phoneagent.ai.AiDecision) {
        val prev = _metrics.value
        val requestCount = prev.requestCount + 1
        val latencySum = prev.avgLatencyMs * prev.requestCount + decision.elapsedMs
        val avgLatency = if (requestCount > 0) latencySum / requestCount else 0
        val tps = if (decision.elapsedMs > 0) decision.completionTokens.toDouble() / decision.elapsedMs * 1000.0 else 0.0
        _metrics.value = prev.copy(
            totalTokens = prev.totalTokens + decision.totalTokens,
            promptTokens = prev.promptTokens + decision.promptTokens,
            completionTokens = prev.completionTokens + decision.completionTokens,
            lastLatencyMs = decision.elapsedMs,
            avgLatencyMs = avgLatency,
            tokensPerSec = tps,
            requestCount = requestCount,
        )
    }

    private fun recordStep(step: Int, action: AgentAction, verification: String, before: String, after: String) {
        val rec = StepRecord(
            step = step,
            action = action,
            verificationResult = verification,
            beforeFingerprint = before,
            afterFingerprint = after,
            isConfirmed = verification == "verified_success",
        )
        _executionHistory.value = _executionHistory.value + rec
    }

    private fun recordsIntoHistory(step: Int, action: AgentAction, verify: com.phoneagent.execution.VerifyResult) {
        recordStep(step, action, if (verify.success) "verified_success" else "unverified", verify.beforeFingerprint, verify.afterFingerprint)
    }

    private suspend fun awaitUserHint(): String {
        _needsUser.value = true
        val hint = _userHintResult.first()
        _needsUser.value = false
        return hint
    }

    private suspend fun observe(): ScreenSnapshot {
        val a11y = AgentAccessibilityService.instance
        val snapshot = a11y?.captureScreen() ?: ScreenSnapshot(missingAccessibility = true)
        _state.value = _state.value.copy(
            hasAccessibility = a11y != null,
            hasScreenshot = ScreenSharingService.instance?.captureFrame() != null,
        )
        return snapshot
    }

    private fun screenWidth(): Int = lastSnapshot.screenWidth.takeIf { it > 0 } ?: 1080
    private fun screenHeight(): Int = lastSnapshot.screenHeight.takeIf { it > 0 } ?: 2400
}

private fun ActionExecutor.Result.isSuccess(): Boolean = this is ActionExecutor.Result.Success

/** 规划流程阶段 */
sealed class PlanPhase {
    /** 空闲，未开始规划 */
    data object Idle : PlanPhase()
    /** 正在向 AI 请求规划 */
    data object Planning : PlanPhase()
    /** 需要用户澄清歧义 */
    data class Clarifying(val clarification: Clarification) : PlanPhase()
    /** 计划已生成，等待用户批准 */
    data class AwaitingApproval(val plan: TaskPlan) : PlanPhase()
    /** 已批准，开始执行 */
    data class Approved(val plan: TaskPlan?) : PlanPhase()
    /** 规划失败 */
    data class Error(val message: String) : PlanPhase()
}

/** 视觉模型配置（截图描述 + 点击坐标定位） */
private data class VisionConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/** 思考模型配置（规划/重规划等复杂任务，开启深度思考） */
private data class ReasoningConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)