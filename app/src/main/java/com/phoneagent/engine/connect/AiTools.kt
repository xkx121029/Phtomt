package com.phoneagent.engine.connect

import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ApiProtocol
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.core.ai.ModelAbility
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.rules.EngineRules
import kotlinx.coroutines.flow.first

/**
 * 引擎 AI 连接工具（重构批次 3）：面向设置页的工具型 AI 调用，与任务决策循环无关——
 * 翻译（用户可见文本中文化）、连接测试、模型列表拉取、模型能力探测。
 * 从 AgentEngine 原样迁入，行为不变。
 */
internal class AiTools(
    private val settings: AppSettings,
    private val aiClient: AiClient,
) {
    /** 翻译缓存：同一文本只翻一次 */
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
            protocol = protocolOf(settingsVal.apiBaseUrl, settingsVal),
        ).getOrDefault("").ifBlank { text }
        translateCache[text] = translated
        return translated
    }

    /** 粗略判断文本是否以中文为主（含中文字符比例 > 30%） */
    fun isMostlyChinese(text: String): Boolean = EngineRules.isMostlyChinese(text)

    /** 测试主模型连接是否可用（设置保存前校验用） */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String, protocol: ApiProtocol = ApiProtocol.OPENAI): Result<String> {
        val messages = listOf(
            ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "请只回复：OK"))),
        )
        return aiClient.chat(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim(),
            messages = messages,
            temperature = 0.1,
            protocol = protocol,
        )
    }

    /**
     * 拉取端点可用模型列表（设置页"获取模型"用）。
     * 只列表不探测：网关常返回上百条，逐条探测等于几百次请求。
     */
    suspend fun listModels(baseUrl: String, apiKey: String, protocol: ApiProtocol = ApiProtocol.OPENAI): Result<List<String>> =
        aiClient.listModels(baseUrl.trim(), apiKey.trim(), protocol)

    /** 真实请求探测单个模型的能力（识图 / 工具调用；文本能力用 Result 成败表达） */
    suspend fun probeModel(baseUrl: String, apiKey: String, model: String, protocol: ApiProtocol = ApiProtocol.OPENAI): Result<ModelAbility> =
        aiClient.probeAbility(baseUrl.trim(), apiKey.trim(), model.trim(), protocol)

    /** 端点协议解析：按归一化 URL 匹配模型库端点；未入库回落 OpenAI 兼容 */
    private fun protocolOf(baseUrl: String, settingsVal: AppSettings.Settings): ApiProtocol =
        ApiProtocol.fromId(
            settingsVal.endpoints.firstOrNull { it.id == AiClient.normalizeBaseUrl(baseUrl) }?.protocol,
        )
}
