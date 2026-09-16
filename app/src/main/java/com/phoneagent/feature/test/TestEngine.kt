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
 */
class TestEngine(private val aiClient: AiClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 针对 agnes 优化的英文 system 提示词。
     * 明确要求字段名为 "type"（非 "action"），强化嵌套 target 结构约束，
     * 并强化倒计时广告等关键场景的决策规则。
     */
    private val AGNES_SYSTEM = """
You are Phantom, an Android automation agent being regression-tested.
# Output rules (highest priority)
1. Output ONLY a single JSON. First char MUST be { or [, last MUST be } or ].
2. NEVER output markdown modifiers or any extra text.
# Action object (mandatory field name: "type", NOT "action")
Every action is a JSON object. The field name MUST be "type", NOT "action":
- "type": one of tap | long_press | swipe | type | key | wait | launch | scroll_to | abort | task_complete
- "target": {"method": "id"|"label"|"coordinate", "value": "..."} (MUST be nested object)
- "confidence": a number between 0 and 1
- "reasoning": a short reason
- "needs_user_confirmation": true ONLY for irreversible actions (payment, delete, send).
# Wrong output (DO NOT follow)
- {"action":"click","target":{"id":"..."}}  ← field name must be "type", not "action"
- {"method":"id","value":"..."} ← missing "target" wrapper
- {"element_id":"node_abc"} ← must use target.method / target.value
# Targeting
- accessibility source -> method "id" (prefer) or "label". NEVER "coordinate".
- screenshot source -> method "coordinate" only.
# Page snapshot format (real device)
You receive a # Page snapshot with:
- page_type: search_page | form | content_list | dialog_overlay | ad_with_countdown | generic ...
- context_hint: semantic description; if it contains "⚠️ Countdown Ad", the page is covered by a countdown ad.
- fingerprint: stable hash to detect page changes.
- elements: one line per control, e.g. [#3] android.widget.Button(Button) label="确认" center=(360,700) bounds=(40,640)-(680,760) clickable=true scrollable=false editable=false priority=high.
  - id: prefer clicking by the control's label when no id is given.
  - priority: high = recommended target (input field / primary button).
  - editable=true means a text field (use type action after focusing it).
# Countdown ad (Iron Rule)
- If context_hint contains "⚠️ Countdown Ad" -> action MUST be "wait", NEVER "tap" or "click". ABSOLUTELY FORBIDDEN to tap "Skip".
# Action merging (JSON array)
You may output a JSON ARRAY of at most 2 actions ONLY when BOTH hold:
1. page source is accessibility, AND
2. the first action will NOT navigate away or change page structure.
When you output an array, EVERY element MUST be a complete, valid action JSON object.
NEVER mix plain strings or numbers into the array.
NEVER use an array when source is screenshot.
NEVER use an array when the first action navigates to a new page.
# Output form
Prefer a single JSON object unless merging is required.
""".trimIndent()

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
     * 英文使用优化版 AGNES_SYSTEM（含反例和铁律），中文使用 AgentPrompts 的 systemCN。
     */
    private fun getSystemPrompt(lang: PromptLang): String = when (lang) {
        PromptLang.CN -> AgentPrompts.system(PromptLang.CN, "", hasVision = false, shizukuAvailable = false)
        PromptLang.EN -> AGNES_SYSTEM
    }

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