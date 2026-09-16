package com.phoneagent.feature.test

import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.PromptLang
import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.domain.model.IntentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 智能体 AI 标准化测试引擎。
 * 支持中英文双语提示词测试，包含标准预设方案与真实场景库。
 *
 * 判分口径与「转译层」[com.phoneagent.engine.execution.IntentTranslator] 的输入契约一致：
 * 只认 `intent` 意图 + 嵌套 `target:{by,value}`；沿用旧的 type/action、target.method
 * 或扁平 by/value 一律判错（这些写法转译层无法识别）。
 */
class TestEngine(private val aiClient: AiClient) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(
        TestConfig(
            baseUrl = "https://api.agnes-ai.cn/v1",
            model = "agnes-2.5-flash",
            // 默认留空，由用户在测试页填写，避免真实密钥随源码分发
            apiKey = "",
        ),
    )
    val config: StateFlow<TestConfig> = _config.asStateFlow()

    /** 测试语言：中文/英文 */
    private val _testLanguage = MutableStateFlow(PromptLang.EN)
    val testLanguage: StateFlow<PromptLang> = _testLanguage.asStateFlow()

    /** 测试分类筛选：null=全部，standard=标准预设，real=真实场景 */
    private val _testCategory = MutableStateFlow<String?>(null)
    val testCategory: StateFlow<String?> = _testCategory.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _summary = MutableStateFlow(TestRunSummary())
    val summary: StateFlow<TestRunSummary> = _summary.asStateFlow()

    /** 当前正在流式输出的文本（实时展示用） */
    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    fun updateConfig(baseUrl: String, model: String, apiKey: String) {
        _config.value = TestConfig(baseUrl.trim(), model.trim(), apiKey.trim())
    }

    fun setTestLanguage(lang: PromptLang) {
        _testLanguage.value = lang
    }

    fun setTestCategory(category: String?) {
        _testCategory.value = category
    }

    /**
     * 根据当前语言返回对应的测试系统提示词。
     * 两种语言都直接取 [AgentPrompts] 的真实线上提示词，保证"测什么就上什么"，
     * 不再维护一份容易与线上协议漂移的测试专用副本。
     */
    private fun getSystemPrompt(lang: PromptLang): String =
        AgentPrompts.system(lang, "", hasVision = false, shizukuAvailable = false)

    /** 获取当前语言的中文标签 */
    fun getLanguageLabel(lang: PromptLang): String = when (lang) {
        PromptLang.CN -> "中文提示词"
        PromptLang.EN -> "English Prompt"
    }

    fun reset() {
        _summary.value = TestRunSummary()
    }

    /** 运行一个预设方案（根据当前语言选择中英文提示词） */
    suspend fun runPreset(preset: TestPreset) {
        if (_running.value) return
        _running.value = true
        _summary.value = TestRunSummary(status = TestStatus.RUNNING, presetName = preset.name)
        _streamText.value = ""
        val cfg = _config.value
        val lang = _testLanguage.value
        val system = getSystemPrompt(lang)
        _streamText.value = "[使用 ${getLanguageLabel(lang)}·${preset.name}] 开始测试…\n"
        val results = mutableListOf<TestResult>()
        try {
            preset.cases.forEach { case ->
                val messages = listOf(
                    ChatMessageDto("system", listOf(ContentPart("text", system))),
                    ChatMessageDto("user", listOf(ContentPart("text", case.userPrompt))),
                )
                val startNano = System.nanoTime()
                // 流式调用：边思考边把增量文本实时推送到界面
                val streamBuf = StringBuilder()
                val raw = aiClient.chatStream(
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    model = cfg.model,
                    messages = messages,
                    temperature = 0.1,
                    onDelta = { delta ->
                        streamBuf.append(delta)
                        _streamText.value = streamBuf.toString()
                    },
                ).getOrElse { "" }
                val latencyMs = (System.nanoTime() - startNano) / 1_000_000
                _streamText.value = ""
                results += evaluate(case, raw, latencyMs)
            }
        } catch (e: Exception) {
            _streamText.value = ""
            _summary.value = results.let {
                TestRunSummary(
                    status = TestStatus.ERROR,
                    presetName = preset.name,
                    total = preset.cases.size,
                    passed = it.count { r -> r.passed },
                    results = it,
                    error = e.message ?: "未知错误",
                )
            }
            _running.value = false
            return
        }
        _streamText.value = ""
        _summary.value = TestRunSummary(
            status = TestStatus.DONE,
            presetName = preset.name,
            total = preset.cases.size,
            passed = results.count { it.passed },
            results = results,
        )
        _running.value = false
    }

    /** 对单个用例输出做校验 */
    private fun evaluate(case: TestCase, raw: String, latencyMs: Long): TestResult {
        val errors = mutableListOf<String>()
        val trimmed = raw.trim()

        var element: JsonElement? = null
        var isArray = false
        var intentType: String? = null
        var by: String? = null

        if (trimmed.isEmpty()) {
            errors += "空输出"
        } else {
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) errors += "首字符不是 { 或 ["
            if (!(trimmed.endsWith("}") || trimmed.endsWith("]"))) errors += "末字符不是 } 或 ]"
            if (trimmed.contains("```")) errors += "包含 Markdown 标记"
        }

        element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        if (element == null) {
            errors += "非法 JSON"
        } else {
            isArray = element is JsonArray
            val first = if (isArray) (element as JsonArray).firstOrNull() else element
            if (first is JsonObject) {
                // 字段名铁律：只认 intent；沿用旧的 type/action 即转译层无法识别
                intentType = first["intent"]?.jsonPrimitive?.contentOrNull
                if (intentType == null) {
                    errors += if (first["type"] != null || first["action"] != null) {
                        "使用了旧字段 type/action（新协议必须用 intent）"
                    } else {
                        "缺少意图字段 (intent)"
                    }
                } else if (intentType !in IntentType.ALL) {
                    errors += "未知意图=$intentType（不在转译层意图表内）"
                }

                // 统一必填字段（组A 严格校验）
                if (case.requireAllFields) {
                    if (first["reasoning"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) errors += "缺少 reasoning"
                    if (first["expected"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) errors += "缺少 expected"
                    if (first["confidence"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) errors += "缺少 confidence"
                }
                val conf = first["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                if (conf != null && (conf < 0.0 || conf > 1.0)) errors += "confidence 超出 0~1"

                // target 结构铁律：必须是嵌套对象且用 by/value
                val targetRaw = first["target"]
                when {
                    targetRaw == null -> {
                        if (first["by"] != null) errors += "target 必须是嵌套对象（禁止扁平 by/value）"
                        if (case.expectedBy != null || case.forbiddenBy != null) errors += "缺少 target"
                    }
                    targetRaw !is JsonObject -> errors += "target 必须是嵌套对象"
                    else -> {
                        by = targetRaw["by"]?.jsonPrimitive?.contentOrNull
                        val value = targetRaw["value"]?.jsonPrimitive?.contentOrNull
                        if (targetRaw["method"] != null) errors += "target 使用了旧字段 method（新协议必须用 by）"
                        when {
                            by == null -> errors += "target 缺少 by"
                            by !in LEGAL_TARGET_BY -> errors += "target.by 非法=$by"
                            value.isNullOrBlank() -> errors += "target 缺少 value"
                            by == "coordinate" && !isRatioCoordinate(value) ->
                                errors += "by=coordinate 的 value 必须是 0~1 比例坐标 x,y"
                        }
                    }
                }

                // 不可逆操作的确认标志（旧字段 needs_user_confirmation 不再认）
                if (case.requireConfirmation && first["needs_confirmation"]?.jsonPrimitive?.contentOrNull != "true") {
                    errors += "不可逆操作必须设 needs_confirmation=true"
                }
            } else {
                errors += "响应不是 JSON 对象/数组"
            }
        }

        // 意图校验
        if (intentType != null) {
            if (case.expectedIntent.isNotEmpty() && intentType !in case.expectedIntent) {
                errors += "意图=$intentType 不在期望集合 ${case.expectedIntent}"
            }
            if (intentType in case.forbiddenIntent) errors += "出现禁止意图=$intentType"
        }
        // 寻址方式校验
        if (by != null) {
            if (case.expectedBy != null && by != case.expectedBy) {
                errors += "target.by=$by 应为 ${case.expectedBy}"
            }
            if (case.forbiddenBy != null && by == case.forbiddenBy) {
                errors += "使用了禁止 target.by=$by"
            }
        }
        // 动作合并校验
        if (case.expectArray == true && !isArray) errors += "期望返回 JSON 数组，实际为对象"
        if (case.expectArray == false && isArray) errors += "禁止返回 JSON 数组，实际为数组"

        return TestResult(
            case = case,
            passed = errors.isEmpty(),
            rawOutput = raw,
            intentType = intentType,
            errors = errors,
            latencyMs = latencyMs,
        )
    }

    companion object {
        /** 转译层可识别的 target.by 取值 */
        private val LEGAL_TARGET_BY = setOf("id", "text", "hint", "coordinate")

        /** by=coordinate 的 value 必须形如 "0.5,0.2" 的比例坐标（0~1） */
        private fun isRatioCoordinate(value: String): Boolean {
            val parts = value.split(",").map { it.trim() }
            if (parts.size != 2) return false
            return parts.all { p -> p.toDoubleOrNull()?.let { it in 0.0..1.0 } == true }
        }
    }
}