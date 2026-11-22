package com.phoneagent.core.ai

import kotlinx.serialization.json.Json
import okio.BufferedSource

/**
 * 流式解析：把一段 SSE 流消费成「增量文本 + 累积正文 + 用量帧」。
 *
 * 帧格式只有两种口径——OpenAI 兼容的 `data:` 帧，以及协议适配器（Claude / Gemini）的原生帧；
 * 决策链路与规划链路都走这一份，避免两处逐行解析各自漂移。
 */
internal class AiStreamPump(private val json: Json) {

    /**
     * 消费一段 SSE 流：增量文本交给 [onDelta]，正文累积到 [full]，[onUsage] 非空时解析 usage 帧。
     *
     * `chatForAction`（决策）与 `chatStream`（规划/思考）原先各写一份逐行解析，两处逐行逻辑完全同构，
     * 只在"是否解析 usage"上有差异。抽成一处后，SSE 帧格式（adapter 原生帧 / OpenAI data 帧）的
     * 解析口径只有一份；各调用点的重试、401/403/404 快速失败、空正文回退等策略仍留在调用点。
     *
     * @return 本次流是否出现过思考内容（reasoning_content）
     */
    // 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    internal fun pumpSse(
        source: BufferedSource,
        adapter: ChatProtocolAdapter?,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)? = null,
        /**
         * 每处理完一帧后询问「是否已拿到可解析的完整结果」；返回 true 立即停止消费，
         * 不必等服务端关闭流（决策链路的增量解析据此提前结束，省掉结尾网络尾巴）。
         */
        shouldStop: () -> Boolean = { false },
    ): Boolean {
        var sawReasoning = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank() || !line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (adapter != null) {
                // 协议适配器路径：Claude/Gemini 的 SSE 帧；终止帧由适配器自己判定
                if (pumpAdapterFrame(adapter, payload, full, onDelta, onUsage)) break
            } else if (pumpOpenAiFrame(payload, full, onDelta, onUsage)) {
                sawReasoning = true
            }
            if (shouldStop()) break
        }
        return sawReasoning
    }

    /** 协议适配器的 SSE 帧；返回 true 表示命中终止帧，调用方应停止消费 */
    private fun pumpAdapterFrame(
        adapter: ChatProtocolAdapter,
        payload: String,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)?,
    ): Boolean {
        if (adapter.isTerminal(payload)) return true
        val content = adapter.extractDelta(payload)
        if (content.isNotEmpty()) {
            onDelta(content)
            full.append(content)
        }
        if (onUsage != null) adapter.extractUsageFrame(payload)?.let(onUsage)
        return false
    }

    /** OpenAI data 帧；返回 true 表示本帧出现过 reasoning 内容 */
    private fun pumpOpenAiFrame(
        payload: String,
        full: StringBuilder,
        onDelta: (String) -> Unit,
        onUsage: ((Usage) -> Unit)?,
    ): Boolean {
        val chunk = runCatching { json.decodeFromString<StreamChunk>(payload) }.getOrNull()
        val delta = chunk?.choices?.firstOrNull()?.delta
        val content = delta?.content.orEmpty()
        val reasoning = delta?.reasoning_content.orEmpty()
        // 思考内容优先展示（边思考边输出）
        val display = if (content.isNotEmpty()) content else reasoning
        if (display.isNotEmpty()) onDelta(display)
        // 完整正文只累积 content（用于最终解析 JSON）
        if (content.isNotEmpty()) full.append(content)
        if (onUsage != null) {
            // 末块可能携带 usage（stream_options.include_usage）
            runCatching { json.decodeFromString<ChatResponse>(payload).usage }
                .getOrNull()?.let(onUsage)
        }
        return reasoning.isNotEmpty()
    }

    /** 合并分帧到达的用量统计（如 Anthropic 的 message_start / message_delta 各带一半） */
    internal fun combineUsage(a: Usage?, b: Usage): Usage {
        if (a == null) return b
        val prompt = maxOf(a.promptTokens, b.promptTokens)
        val completion = maxOf(a.completionTokens, b.completionTokens)
        return Usage(prompt, completion, prompt + completion)
    }
}
