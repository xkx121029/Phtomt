package com.phoneagent.engine.decision

import com.phoneagent.core.ai.GlmDefaults
import com.phoneagent.core.ai.VisionRouting
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.engine.ReasoningConfig
import com.phoneagent.engine.VisionConfig

/**
 * 决策链路模型配置（重构批次 8）：规划与每步决策共用的模型选路——
 * 主模型是否带图（VisionRouting 三态解析）、视觉模型端点、思考模型端点。
 * 从 AgentEngine 原样迁入，行为不变；规划流与决策循环共用同一份配置口径。
 */
internal class DecisionConfig {
    /** 主模型是否具备识图能力（AUTO 听模型库真实探测，ON/OFF 是用户覆盖）。 */
    fun mainSeesImage(s: AppSettings.Settings): Boolean =
        VisionRouting.resolve(
            VisionRouting.modeFromKey(s.mainVisionMode),
            VisionRouting.probedVisionOf(s.catalog, s.apiBaseUrl, s.model),
        )

    /**
     * 视觉模型配置：仅在视觉模型启用时生效；视觉 API Key 为空则回退主模型 Key。
     *
     * @param force 元素树稀疏/为空时为 true：此时元素树已经不足以支撑决策，视觉是唯一信息来源，
     *   不能因为「视觉总开关」关着就放弃这一路（分会话里表现为 AI 对着完全不可见的页面空转）
     */
    fun visionConfig(settingsVal: AppSettings.Settings, force: Boolean = false): VisionConfig? {
        if (!force && !settingsVal.visionEnabled) return null
        val model = settingsVal.visionModel.trim()
        if (model.isBlank()) return null
        val baseUrl = settingsVal.visionBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
        val apiKey = settingsVal.visionApiKey.trim().ifBlank { settingsVal.apiKey }
        if (apiKey.isBlank()) return null
        return VisionConfig(baseUrl, model, apiKey)
    }

    /** 思考模型配置：
     *  - mainThinking 开启：主模型自身支持思考，规划等复杂任务直接用主模型 + thinking
     *  - 否则 enableChain 开启且配了 reasonModel：用独立思考模型
     *  - 都未满足：返回 null（规划用主模型普通模式） */
    fun reasoningConfig(settingsVal: AppSettings.Settings): ReasoningConfig? {
        // 主模型思考能力开启 → 主模型即思考模型（同一 API 配置）
        if (settingsVal.mainThinking) {
            val baseUrl = settingsVal.apiBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
            val apiKey = settingsVal.apiKey.trim()
            if (apiKey.isBlank()) return null
            return ReasoningConfig(baseUrl, settingsVal.model.trim().ifBlank { GlmDefaults.MODEL }, apiKey)
        }
        if (!settingsVal.enableChain) return null
        val model = settingsVal.reasonModel.trim()
        if (model.isBlank()) return null
        val baseUrl = settingsVal.reasonBaseUrl.trim().ifBlank { GlmDefaults.BASE_URL }
        val apiKey = settingsVal.reasonApiKey.trim().ifBlank { settingsVal.apiKey }
        if (apiKey.isBlank()) return null
        return ReasoningConfig(baseUrl, model, apiKey)
    }
}
