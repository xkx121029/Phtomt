// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber", "MatchingDeclarationName")
package com.phoneagent.core.ai

import com.phoneagent.core.log.AppLog
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.rules.EngineRules
import kotlinx.serialization.json.Json

/**
 * 模型输出 → [AgentIntent]：五级兜底（直接解析 / 平衡花括号 / JSON 数组 / 本地修复 / 放弃）。
 *
 * 兜底顺序本身就是可靠性策略：本地修复只要几十微秒，比重发一次（再付首 token + 生成）便宜两个数量级，
 * 所以先修再谈重发。
 */
internal class AiIntentParser(private val json: Json) {

    /**
     * 从模型输出中解析 AgentIntent，带多级兜底解析 + 日志。
     * 兜底全部失败时构造 give_up（换为「放弃」，避免误报完成）；
     * 需要在失败时做「纠错重发」的调用方改用 [parseAgentIntentOrNull]。
     */
    internal fun parseAgentIntent(content: String): AgentIntent =
        parseAgentIntentOrNull(content) ?: AgentIntent(
            intent = "give_up",
            reason = "AI 输出无法解析为意图",
            reasoning = "解析失败",
        )

    /** 同 [parseAgentIntent]，但兜底全部失败时返回 null 而非 give_up（供纠错重发判定） */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    internal fun parseAgentIntentOrNull(content: String, allowRepair: Boolean = true): AgentIntent? {
        // Step 1：提取 JSON（支持 ```json 代码块包裹）后直接解析
        try {
            return json.decodeFromString(AgentIntent.serializer(), EngineRules.extractJsonObject(content))
        } catch (e: Exception) {
            AppLog.w("AiClient", "Step1 parse failed: ${e.message}, content: ${content.take(200)}")
        }
        // Step 2：平衡花括号提取第一个完整 JSON 对象（支持嵌套）
        @Suppress("TooGenericExceptionCaught")
        val balanced = extractBalancedJson(content)
        if (balanced != null) {
            try {
                return json.decodeFromString(AgentIntent.serializer(), balanced)
            } catch (e: Exception) {
                AppLog.w("AiClient", "Step2 balanced parse failed: ${e.message}, balanced: ${balanced.take(200)}")
            }
        }
        // Step 3：尝试 JSON 数组（意图合并）
        val arrayStart = content.indexOf('[')
        @Suppress("TooGenericExceptionCaught")
        val arrayEnd = content.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            try {
                val arr = json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(AgentIntent.serializer()),
                    content.substring(arrayStart, arrayEnd + 1),
                )
                if (arr.isNotEmpty()) return arr.first()
            } catch (e: Exception) {
                AppLog.w("AiClient", "Step3 array parse failed: ${e.message}")
            }
        }
        // Step 4：本地修复后再解析一次（补闭合括号 / 去尾逗号 / 去围栏 / 转义裸换行）。
        // 修复是纯端侧几十微秒的操作，比纠错重发便宜两个数量级（重发要再付一次首 token + 生成）；
        // 修不好才交给调用方纠错重发或 give_up 兜底。
        if (allowRepair) {
            @Suppress("TooGenericExceptionCaught")
            val repaired = IntentJsonRepair.repair(content)
            if (repaired != null && repaired != content) {
                try {
                    val parsed = json.decodeFromString(AgentIntent.serializer(), repaired)
                    DecisionStreamStats.onLocalRepair()
                    AppLog.i("AiClient", "本地修复后解析成功，省掉一次纠错重发")
                    return parsed
                } catch (e: Exception) {
                    AppLog.w("AiClient", "Step4 本地修复后仍解析失败：${e.message}")
                }
            }
        }
        // Step 5：全部失败 → 返回 null，由调用方决定纠错重发或 give_up 兜底
        AppLog.e("AiClient", "All parse steps failed. Raw content: ${content.take(300)}")
        return null
    }

    /** 平衡花括号提取第一个完整 JSON 对象（支持嵌套） */
    @Suppress("LoopWithTooManyJumpStatements", "ReturnCount")
    private fun extractBalancedJson(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until content.length) {
            val c = content[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return content.substring(start, i + 1) }
            }
        }
        return null
    }
}
