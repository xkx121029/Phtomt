package com.phoneagent.feature.test

import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.PromptLang
import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
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
        var actionType: String? = null
        var method: String? = null

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
                // 兼容 agnes 的 action 字段与 phantom 的 type 字段
                actionType = first["type"]?.jsonPrimitive?.contentOrNull
                    ?: first["action"]?.jsonPrimitive?.contentOrNull
                val target = (first["target"] as? JsonObject)
                method = target?.get("method")?.jsonPrimitive?.contentOrNull
                if (actionType == null) errors += "缺少动作字段 (type/action)"
                val confStr = first["confidence"]?.jsonPrimitive?.contentOrNull
                if (confStr != null) {
                    val v = confStr.toDoubleOrNull()
                    if (v != null && (v < 0.0 || v > 1.0)) errors += "confidence 超出 0~1"
                }
            } else {
                errors += "响应不是 JSON 对象/数组"
            }
        }

        // 动作类型校验
        if (actionType != null) {
            if (case.expectedAction.isNotEmpty() && actionType !in case.expectedAction) {
                errors += "动作=$actionType 不在期望集合 ${case.expectedAction}"
            }
            if (actionType in case.forbiddenAction) errors += "出现禁止动作=$actionType"
        }
        // 寻址方式校验
        if (method != null) {
            if (case.expectedMethod != null && method != case.expectedMethod) {
                errors += "method=$method 应为 ${case.expectedMethod}"
            }
            if (case.forbiddenMethod != null && method == case.forbiddenMethod) {
                errors += "使用了禁止 method=$method"
            }
        }
        // 动作合并校验
        if (case.expectArray == true && !isArray) errors += "期望返回 JSON 数组，实际为对象"
        if (case.expectArray == false && isArray) errors += "禁止返回 JSON 数组，实际为数组"

        return TestResult(
            case = case,
            passed = errors.isEmpty(),
            rawOutput = raw,
            actionType = actionType,
            errors = errors,
            latencyMs = latencyMs,
        )
    }
}