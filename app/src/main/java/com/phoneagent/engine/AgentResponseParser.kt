package com.phoneagent.engine

import com.phoneagent.domain.model.Clarification
import com.phoneagent.domain.model.ClarificationOption
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.parseTaskSteps
import com.phoneagent.domain.rules.EngineRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 原始输出的解析层（纯逻辑，无 Android / Activity 依赖，可直接单测）。
 *
 * 拆分目的：AgentEngine 原先自己既做编排又做解析，"把模型吐出来的这段字变成引擎能用的对象"
 * 这件事夹在四千行里，既没法单测也看不清边界。这里只做一件事：**字符串进、模型对象出**。
 *
 * 不落任何副作用——日志与 `activePlan` 的写入留在调用方（AgentEngine.parsePlanResponse），
 * 所以这里可以不启动引擎、不接 Android 就断言"这段输出会被解析成什么"。
 * 与 [EngineRules] 的分工：那里放"判定规则"（要不要审核、温度多少），这里放"格式解析"。
 *
 * @see EngineRules.extractJsonObject 前置的 JSON 定位策略（代码块 / 反向搜索 / 首尾兜底）
 */
internal object AgentResponseParser {

    /** 与引擎内保持同一份配置：模型偶尔多吐字段，不应因此整条解析失败 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 单次提炼最多入库的条数：一次写太多会把记忆库刷成流水账 */
    private const val MAX_DISTILLED_MEMORIES = 3

    /** 模型没给 confidence 时的默认值 */
    private const val DEFAULT_DISTILL_CONFIDENCE = 0.7

    /**
     * 解析规划阶段的模型输出。
     *
     * 判定顺序与原先一致：先看澄清，再看纯对话，最后才当计划——纯对话若被判成"空计划"，
     * 用户会莫名其妙被要求批准一份零步计划。三种正常去向见 [planPhaseOf]，
     * 失败一律收敛成 [PlanPhase.Error]。
     */
    fun parsePlan(content: String): PlanPhase {
        // 整段解析（含分流）都包在守卫里：定位不到 JSON、JSON 不合法、字段类型与预期不符，
        // 一律收敛成 Error 而不是抛出——调用方在协程里，抛出去会被当成"规划异常"走另一套文案。
        val parsed = runCatching {
            planPhaseOf(json.parseToJsonElement(EngineRules.extractJsonObject(content)).jsonObject)
        }
        return parsed.getOrElse { PlanPhase.Error("规划解析失败：${it.message}") }
    }

    /**
     * 已定位到 JSON 根对象后的分流：
     * - `needs_clarification=true` → 反问用户（[PlanPhase.Clarifying]）
     * - `reply` 非空 → 纯对话，这次不必碰手机（[PlanPhase.Reply]）
     * - 有 `plan` → 拿到计划等批准（[PlanPhase.AwaitingApproval]）
     * - 都没有 → [PlanPhase.Error]
     */
    private fun planPhaseOf(root: JsonObject): PlanPhase {
        val needsClarification = root["needs_clarification"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        return if (needsClarification) clarifyingOf(root) else replyOf(root) ?: planOf(root)
    }

    private fun clarifyingOf(root: JsonObject): PlanPhase {
        val clarObj = root["clarification"]?.jsonObject
        val question = clarObj?.get("question")?.jsonPrimitive?.contentOrNull ?: ""
        val options = (clarObj?.get("options") as? JsonArray)?.mapNotNull(::clarificationOptionOf) ?: emptyList()
        return PlanPhase.Clarifying(Clarification(question = question, options = options))
    }

    /** 澄清选项：字段缺省一律给安全默认值，不因为模型少写一个 key 就整条丢 */
    private fun clarificationOptionOf(el: JsonElement): ClarificationOption? {
        val o = el as? JsonObject ?: return null
        return ClarificationOption(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
            description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            isDefault = o["is_default"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
        )
    }

    /**
     * 纯对话回复；没有回复返回 null 交给计划分支。
     * 兼容 `reply` 写成对象（`{"text": "..."}`）或裸字符串两种写法；
     * 空白串视作"没有回复"——否则用户会收到一条空气泡，而真正的计划被吞掉。
     */
    private fun replyOf(root: JsonObject): PlanPhase.Reply? {
        // 两种写法都认：对象 {"text": "..."} 与裸字符串。
        // 拆分前这里用 `?.jsonObject` 硬取，遇到裸字符串会先抛 IllegalArgumentException 被外层吞成
        // 「规划解析失败」——注释写了兼容两种写法，兜底那半句其实永远走不到。改用类型判定让兼容真正生效。
        val text = when (val replyEl = root["reply"]) {
            is JsonObject -> (replyEl["text"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> replyEl.contentOrNull
            else -> null
        }
        return if (text.isNullOrBlank()) null else PlanPhase.Reply(text)
    }

    /** 计划分支：手动解析 JSON，`steps` 兼容对象数组与字符串数组（见 [parseTaskSteps]） */
    private fun planOf(root: JsonObject): PlanPhase {
        val planObj = root["plan"]?.jsonObject ?: return PlanPhase.Error("规划结果无法解析")
        val stepsRaw = planObj["steps"] as? JsonArray
        val steps = if (stepsRaw != null) parseTaskSteps(stepsRaw) else emptyList()
        val estimatedTime = planObj["estimated_time_seconds"]
            ?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val confidence = planObj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        return PlanPhase.AwaitingApproval(
            TaskPlan(steps = steps, estimatedTimeSeconds = estimatedTime, confidence = confidence),
        )
    }

    /**
     * 解析一次记忆提炼的输出 `{"memories":[{content,category,confidence}]}`，最多取 3 条。
     *
     * 取"最外层花括号"而不是整段：模型习惯在 JSON 前后各写一句人话。
     */
    fun parseDistilledMemories(raw: String): List<DistilledMemory> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        // 没有成对的花括号就谈不上 JSON，直接空（避免把整段自然语言丢给解析器）
        return if (start < 0 || end <= start) {
            emptyList()
        } else {
            runCatching {
                val root = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
                root["memories"]?.jsonArray
                    ?.take(MAX_DISTILLED_MEMORIES)
                    ?.mapNotNull(::distilledMemoryOf)
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    /** 单条提炼产物：`content` 为空的条目直接丢掉（空记忆入库会污染后续注入） */
    private fun distilledMemoryOf(el: JsonElement): DistilledMemory? {
        val obj = el.jsonObject
        val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return if (content.isEmpty()) {
            null
        } else {
            DistilledMemory(
                content = content,
                category = obj["category"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: DEFAULT_DISTILL_CONFIDENCE,
            )
        }
    }

    /** 提炼结果条目 */
    data class DistilledMemory(val content: String, val category: String, val confidence: Double)
}
