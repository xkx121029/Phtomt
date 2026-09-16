package com.phoneagent.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoneagent.core.ai.GlmDefaults
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
        /** 视觉读图模式：CLOUD=仅云端 | LOCAL=仅本地OCR | AUTO=优先云端失败回退本地 */
        val visionMode: String = "AUTO",
        /** 启用外挂视觉 Agent（本地视觉 APK，端侧 3B 模型）：启用后视觉链优先走外挂，不可用再回落云端/本地 */
        val enableExternalVision: Boolean = true,
        /** 混合路由：启用后按任务复杂度分流——简单任务（元素树可读）用端侧 3B，复杂任务（元素稀疏/需理解）走云端视觉 */
        val smartVisionRoute: Boolean = true,
        // ---- 思考模型：规划/重规划等复杂任务时使用（如开启 thinking 的 glm-4.7-flash） ----
        val reasonBaseUrl: String = GlmDefaults.BASE_URL,
        val reasonModel: String = GlmDefaults.REASON_MODEL,
        val reasonApiKey: String = "",
        /** 链路聚合：启用后思考模型参与规划等复杂任务（可选增强，默认关闭） */
        val enableChain: Boolean = false,
        /** 主模型是否支持思考能力：开启后规划等复杂任务直接用主模型 + thinking，无需独立思考模型 */
        val mainThinking: Boolean = false,
        /** 模型链路顺序：main / vision / reason（图形化拖动自定义） */
        val chainOrder: List<String> = listOf("main", "vision", "reason"),
        // ---- 跑马光效标定 ----
        val edgeInsetTop: Int = 0,
        val edgeInsetBottom: Int = 0,
        val edgeInsetLeft: Int = 0,
        val edgeInsetRight: Int = 0,
        val cornerRadius: Int = 0,
        /** 屏幕边缘跑马光效光带粗细（dp，基准 20） */
        val edgeLightingWidth: Int = 20,
        val edgeLightingEnabled: Boolean = true,
        /** 是否启用 Shizuku shell 命令执行 */
        val shizukuEnabled: Boolean = true,
        /** 执行通道偏好：AUTO=无线ADB优先其次Shizuku | ADB=仅无线ADB | SHIZUKU=仅Shizuku */
        val executionChannel: String = "AUTO",
        /** 内置跳广告功能 */
        /** 悬浮窗跑马灯厚度（dp） */
        val marqueeHeight: Int = 26,
        /** 悬浮窗跑马灯渐变颜色（ARGB 列表，按顺序组成渐变） */
        val marqueeColors: List<Long> = listOf(0xFF4FA3FF, 0xFF9B5CFF, 0xFFFF6B9D),
        val autoSkipAds: Boolean = true,
        /** 执行审核：用独立的审核者 AI 复核执行者每一步动作是否基于当前页面证据，防止脑补（非链路聚合时用同主模型） */
        val enableReview: Boolean = true,
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
        val VISION_MODE = stringPreferencesKey("vision_mode")
        val EXTERNAL_VISION = booleanPreferencesKey("external_vision_enabled")
        val SMART_ROUTE = booleanPreferencesKey("smart_vision_route")
        val REASON_BASE_URL = stringPreferencesKey("reason_base_url")
        val REASON_MODEL = stringPreferencesKey("reason_model")
        val REASON_API_KEY = stringPreferencesKey("reason_api_key")
        val ENABLE_CHAIN = booleanPreferencesKey("enable_chain")
        val MAIN_THINKING = booleanPreferencesKey("main_thinking")
        val CHAIN_ORDER = stringPreferencesKey("chain_order")
        // 跑马光效标定
        val EDGE_INSET_TOP = intPreferencesKey("edge_inset_top")
        val EDGE_INSET_BOTTOM = intPreferencesKey("edge_inset_bottom")
        val EDGE_INSET_LEFT = intPreferencesKey("edge_inset_left")
        val EDGE_INSET_RIGHT = intPreferencesKey("edge_inset_right")
        val CORNER_RADIUS = intPreferencesKey("corner_radius")
        val EDGE_LIGHTING_WIDTH = intPreferencesKey("edge_lighting_width")
        val EDGE_LIGHTING_ENABLED = booleanPreferencesKey("edge_lighting_enabled")
        val SHIZUKU_ENABLED = booleanPreferencesKey("shizuku_enabled")
        val EXECUTION_CHANNEL = stringPreferencesKey("execution_channel")
        val AUTO_SKIP_ADS = booleanPreferencesKey("auto_skip_ads")
        val MARQUEE_HEIGHT = intPreferencesKey("marquee_height")
        val MARQUEE_COLORS = stringPreferencesKey("marquee_colors")
        val ENABLE_REVIEW = booleanPreferencesKey("enable_review")
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
            visionMode = prefs[Keys.VISION_MODE] ?: "AUTO",
            enableExternalVision = prefs[Keys.EXTERNAL_VISION] ?: true,
            smartVisionRoute = prefs[Keys.SMART_ROUTE] ?: true,
            enableChain = prefs[Keys.ENABLE_CHAIN] ?: false,
            mainThinking = prefs[Keys.MAIN_THINKING] ?: false,
            chainOrder = (prefs[Keys.CHAIN_ORDER] ?: "main;vision;reason")
                .split(";").map { it.trim() }.filter { it.isNotEmpty() },
            shizukuEnabled = prefs[Keys.SHIZUKU_ENABLED] ?: true,
            executionChannel = prefs[Keys.EXECUTION_CHANNEL] ?: "AUTO",
            edgeInsetTop = prefs[Keys.EDGE_INSET_TOP] ?: 0,
            edgeInsetBottom = prefs[Keys.EDGE_INSET_BOTTOM] ?: 0,
            edgeInsetLeft = prefs[Keys.EDGE_INSET_LEFT] ?: 0,
            edgeInsetRight = prefs[Keys.EDGE_INSET_RIGHT] ?: 0,
            cornerRadius = prefs[Keys.CORNER_RADIUS] ?: 0,
            edgeLightingWidth = prefs[Keys.EDGE_LIGHTING_WIDTH] ?: 20,
            edgeLightingEnabled = prefs[Keys.EDGE_LIGHTING_ENABLED] ?: true,
            autoSkipAds = prefs[Keys.AUTO_SKIP_ADS] ?: true,
            marqueeHeight = prefs[Keys.MARQUEE_HEIGHT] ?: 26,
            marqueeColors = (prefs[Keys.MARQUEE_COLORS]
                ?: "FF4FA3FF;FF9B5CFF;FF6B9D").split(";")
                .mapNotNull { it.trim().toLongOrNull(16) },
            enableReview = prefs[Keys.ENABLE_REVIEW] ?: true,
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
            prefs[Keys.VISION_MODE] = settings.visionMode
            prefs[Keys.EXTERNAL_VISION] = settings.enableExternalVision
            prefs[Keys.SMART_ROUTE] = settings.smartVisionRoute
            prefs[Keys.ENABLE_CHAIN] = settings.enableChain
            prefs[Keys.MAIN_THINKING] = settings.mainThinking
            prefs[Keys.CHAIN_ORDER] = settings.chainOrder.joinToString(";")
            prefs[Keys.SHIZUKU_ENABLED] = settings.shizukuEnabled
            prefs[Keys.EXECUTION_CHANNEL] = settings.executionChannel
            prefs[Keys.EDGE_INSET_TOP] = settings.edgeInsetTop
            prefs[Keys.EDGE_INSET_BOTTOM] = settings.edgeInsetBottom
            prefs[Keys.EDGE_INSET_LEFT] = settings.edgeInsetLeft
            prefs[Keys.EDGE_INSET_RIGHT] = settings.edgeInsetRight
            prefs[Keys.CORNER_RADIUS] = settings.cornerRadius
            prefs[Keys.EDGE_LIGHTING_WIDTH] = settings.edgeLightingWidth
            prefs[Keys.EDGE_LIGHTING_ENABLED] = settings.edgeLightingEnabled
            prefs[Keys.AUTO_SKIP_ADS] = settings.autoSkipAds
            prefs[Keys.MARQUEE_HEIGHT] = settings.marqueeHeight
            prefs[Keys.MARQUEE_COLORS] = settings.marqueeColors.joinToString(";") { it.toString(16) }
            prefs[Keys.ENABLE_REVIEW] = settings.enableReview
        }
    }
}