package com.phoneagent.feature.skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 技能缺参自动推断器：AI 调技能缺必填参数时，端侧先从上下文推断填入，减少向 AI 反复追问。
 *
 * 纯函数、零 Android 依赖。推断来源按优先级：
 * 1. [SkillInferContext.history] —— 本技能最近一次**成功**调用记录里的同名参数值（最可信）
 * 2. [SkillInferContext.lastResultText] —— 上一轮 MCP/shell 结果文本里的 `key=value` / `"key":"value"` / `key：value`
 * 3. [SkillInferContext.taskPrompt] —— 用户需求原文里的键值写法；select 类型严格匹配候选项
 * 4. [SkillInferContext.screenTexts] —— 当前页面控件文本（select 类型候选项精确命中）
 *
 * select 类型一律要求候选值 ∈ options（宁缺勿错）；推断不出的参数直接跳过，返回空 map 由调用方走原失败链路。
 */
object SkillParamInferencer {

    /** 从 argsJson 中取指定参数值（解析失败返回 null） */
    fun argOf(argsJson: String, name: String): String? = runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 推断全部缺失参数：只填缺失的键，全部推不出返回空 map */
    fun infer(ctx: SkillInferContext): Map<String, String> {
        if (ctx.missing.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (param in ctx.missing) {
            inferSingle(param, ctx)?.let { out[param.name] = it }
        }
        return out
    }

    /** 推断单个参数：推不出返回 null（select 一律要求候选值 ∈ options，宁缺勿错） */
    fun inferSingle(param: SkillParam, ctx: SkillInferContext): String? {
        candidates(param, ctx).forEach { raw ->
            val value = raw.trim().trim('"', '「', '」', '，', '。').takeIf { it.isNotBlank() } ?: return@forEach
            if (param.type == "select" && param.options.isNotEmpty() && value !in param.options) return@forEach
            return value
        }
        return null
    }

    /** 按「历史 → 上一步结果 → 任务原文 → 页面文本」顺序产出候选值（select 严格匹配） */
    private fun candidates(param: SkillParam, ctx: SkillInferContext): Sequence<String> = sequence {
        // 1. 历史：本技能最近成功记录的同名参数
        val hist = ctx.history.firstOrNull { it.success && it.skillId == ctx.skill.id }
            ?.let { argOf(it.argsJson, param.name) }
        if (hist != null) yield(hist)
        // 2. 上一轮结果文本：key=value / "key":"value" / key：value
        extractValue(ctx.lastResultText, param)?.let { yield(it) }
        when (param.type) {
            "select" -> {
                // 3/4. select 严格匹配候选项：上一步结果 → 任务原文 → 页面文本
                for (source in listOf(ctx.lastResultText, ctx.taskPrompt)) {
                    param.options.firstOrNull { source.contains(it) }?.let { yield(it) }
                }
                param.options.firstOrNull { opt -> ctx.screenTexts.any { it.trim() == opt } }?.let { yield(it) }
            }
            else -> {
                // 3. 任务原文里的显式键值写法（如「收件人=张三」）
                extractValue(ctx.taskPrompt, param)?.let { yield(it) }
            }
        }
    }

    /**
     * 从一段文本里抽取 [param] 的值：支持 `name=value`、`"name":"value"`、`name：value` 三种写法，
     * 参数名与展示标签（label）都当 key 试。值取到行尾/逗号/引号/句号为止，上限 120 字。
     */
    private fun extractValue(source: String, param: SkillParam): String? {
        if (source.isBlank()) return null
        for (key in listOf(param.name, param.label.takeIf { it.isNotBlank() && it != param.name })) {
            if (key == null) continue
            val regex = Regex(
                "[\\[\"「]?${Regex.escape(key)}[\\]\"」]?\\s*[:=：]\\s*[\"「]?([^\\n\"」，。]{1,120})",
                RegexOption.IGNORE_CASE,
            )
            regex.find(source)?.let { return it.groupValues[1] }
        }
        return null
    }
}

/** 推断上下文：引擎在缺参现场组装（全部为只读快照） */
data class SkillInferContext(
    val skill: Skill,
    val missing: List<SkillParam>,
    /** 用户需求原文 */
    val taskPrompt: String = "",
    /** 上一轮 MCP/shell/查询的返回文本 */
    val lastResultText: String = "",
    /** 当前页面可见控件文本（去重后的列表） */
    val screenTexts: List<String> = emptyList(),
    /** 本技能的最近调用记录（新在前，来自 SkillCallHistory） */
    val history: List<SkillCallRecord> = emptyList(),
)
