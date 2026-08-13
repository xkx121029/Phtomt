package com.phoneagent.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneagent.ai.GlmDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentStore by preferencesDataStore(name = "agent_settings")

/**
 * 应用设置：AI API 配置与 Agent 运行参数。
 * 使用 DataStore 持久化，配置后无需重新编译即可切换任意 OpenAI 兼容 API。
 */
class AppSettings(private val context: Context) {

    data class Settings(
        val apiBaseUrl: String = GlmDefaults.BASE_URL,
        val apiKey: String = "",
        val model: String = GlmDefaults.MODEL,
        val hasVision: Boolean = false,
        val temperature: Double = 0.4,
        /** 最大运行步数，0 表示不设限 */
        val maxSteps: Int = 0,
        val attachScreenshot: Boolean = true,
        val systemPrompt: String = "",
        /** 提示词语言：CN / EN */
        val promptLanguage: String = "CN",
        // ---- 视觉模型：主模型不支持图片时，用此模型描述截图 + 定位点击坐标 ----
        val visionBaseUrl: String = GlmDefaults.BASE_URL,
        val visionModel: String = GlmDefaults.VISION_MODEL,
        val visionApiKey: String = "",
        val visionEnabled: Boolean = true,
        // ---- 思考模型：规划/重规划等复杂任务时使用（如开启 thinking 的 glm-4.7-flash） ----
        val reasonBaseUrl: String = GlmDefaults.BASE_URL,
        val reasonModel: String = GlmDefaults.REASON_MODEL,
        val reasonApiKey: String = "",
        /** 链路聚合：启用后思考模型参与规划等复杂任务（可选增强，默认关闭） */
        val enableChain: Boolean = false,
        /** 模型链路顺序：main / vision / reason（图形化拖动自定义） */
        val chainOrder: List<String> = listOf("main", "vision", "reason"),
    )

    private object Keys {
        val BASE_URL = stringPreferencesKey("api_base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val HAS_VISION = booleanPreferencesKey("has_vision")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val SCREENSHOT = booleanPreferencesKey("attach_screenshot")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val PROMPT_LANGUAGE = stringPreferencesKey("prompt_language")
        val VISION_BASE_URL = stringPreferencesKey("vision_base_url")
        val VISION_MODEL = stringPreferencesKey("vision_model")
        val VISION_API_KEY = stringPreferencesKey("vision_api_key")
        val VISION_ENABLED = booleanPreferencesKey("vision_enabled")
        val REASON_BASE_URL = stringPreferencesKey("reason_base_url")
        val REASON_MODEL = stringPreferencesKey("reason_model")
        val REASON_API_KEY = stringPreferencesKey("reason_api_key")
        val ENABLE_CHAIN = booleanPreferencesKey("enable_chain")
        val CHAIN_ORDER = stringPreferencesKey("chain_order")
    }

    val settings: Flow<Settings> = context.agentStore.data.map { prefs ->
        Settings(
            apiBaseUrl = prefs[Keys.BASE_URL] ?: GlmDefaults.BASE_URL,
            apiKey = prefs[Keys.API_KEY] ?: "",
            model = prefs[Keys.MODEL] ?: GlmDefaults.MODEL,
            hasVision = prefs[Keys.HAS_VISION] ?: false,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.4,
            maxSteps = prefs[Keys.MAX_STEPS] ?: 0,
            attachScreenshot = prefs[Keys.SCREENSHOT] ?: true,
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: "",
            promptLanguage = prefs[Keys.PROMPT_LANGUAGE] ?: "CN",
            visionBaseUrl = prefs[Keys.VISION_BASE_URL] ?: GlmDefaults.BASE_URL,
            visionModel = prefs[Keys.VISION_MODEL] ?: GlmDefaults.VISION_MODEL,
            visionApiKey = prefs[Keys.VISION_API_KEY] ?: "",
            reasonBaseUrl = prefs[Keys.REASON_BASE_URL] ?: GlmDefaults.BASE_URL,
            reasonModel = prefs[Keys.REASON_MODEL] ?: GlmDefaults.REASON_MODEL,
            reasonApiKey = prefs[Keys.REASON_API_KEY] ?: "",
            visionEnabled = prefs[Keys.VISION_ENABLED] ?: true,
            enableChain = prefs[Keys.ENABLE_CHAIN] ?: false,
            chainOrder = (prefs[Keys.CHAIN_ORDER] ?: "main;vision;reason")
                .split(";").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    suspend fun update(settings: Settings) {
        context.agentStore.edit { prefs ->
            prefs[Keys.BASE_URL] = settings.apiBaseUrl.trim().trimEnd('/')
            prefs[Keys.API_KEY] = settings.apiKey.trim()
            prefs[Keys.MODEL] = settings.model.trim()
            prefs[Keys.HAS_VISION] = settings.hasVision
            prefs[Keys.TEMPERATURE] = settings.temperature
            prefs[Keys.MAX_STEPS] = settings.maxSteps
            prefs[Keys.SCREENSHOT] = settings.attachScreenshot
            prefs[Keys.SYSTEM_PROMPT] = settings.systemPrompt
            prefs[Keys.PROMPT_LANGUAGE] = settings.promptLanguage
            prefs[Keys.VISION_BASE_URL] = settings.visionBaseUrl.trim().trimEnd('/')
            prefs[Keys.VISION_MODEL] = settings.visionModel.trim()
            prefs[Keys.VISION_API_KEY] = settings.visionApiKey.trim()
            prefs[Keys.REASON_BASE_URL] = settings.reasonBaseUrl.trim().trimEnd('/')
            prefs[Keys.REASON_MODEL] = settings.reasonModel.trim()
            prefs[Keys.REASON_API_KEY] = settings.reasonApiKey.trim()
            prefs[Keys.VISION_ENABLED] = settings.visionEnabled
            prefs[Keys.ENABLE_CHAIN] = settings.enableChain
            prefs[Keys.CHAIN_ORDER] = settings.chainOrder.joinToString(";")
        }
    }
}