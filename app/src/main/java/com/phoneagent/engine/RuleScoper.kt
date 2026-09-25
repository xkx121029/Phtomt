package com.phoneagent.engine

import com.phoneagent.data.store.AiMemoryDedupe
import com.phoneagent.data.store.EvolvedRule

/**
 * 经验规则的作用域推断与命中选择。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测——这正是"自动注入且无用户确认"能被接受的前提：
 * 全部门槛（置信度 / 条数 / 字数 / 去重 / 容量）都是可枚举、可断言的上界，改大了单测立刻红。
 */
object RuleScoper {

    /** 置信度门槛：低于它一律不注入（宁可少给，也不把噪声当经验） */
    const val MIN_CONFIDENCE = 0.6

    /** 单次注入的条数上限 */
    const val MAX_SELECTED = 5

    /** 单次注入的总字数上限 */
    const val MAX_CHARS = 800

    /** 单条展示长度上限：防止一条超长文本吃掉整个预算 */
    const val PER_RULE_CHARS = 120

    /** 作用域推断结果 */
    data class Scope(val kind: String, val value: String = "")

    /**
     * 作用域自动推断：优先级 PACKAGE > KEYWORD > GLOBAL。
     *
     * - **PACKAGE**：任务执行期间最常出现在前台的应用就是这次经验的作用对象。
     *   要排除本 App 自己——任务在 Happy Agent 界面里起步，不排掉会把每条经验都钉在本 App 上。
     * - **KEYWORD**：拿不到前台应用（例如全是本 App 界面内完成的任务）时退一步，
     *   用任务文本里点名的已装应用名当关键词。
     * - **GLOBAL**：两个信号都没有，只好当作任何任务都成立。
     *
     * @param foregroundPkgCounts 执行期间前台包名 → 出现次数（由引擎逐步累计）
     * @param keywords 候选关键词表（引擎传入已装应用名）；为空则跳过 KEYWORD 一级
     */
    fun inferScope(
        task: String,
        foregroundPkgCounts: Map<String, Int>,
        selfPackage: String,
        keywords: List<String> = emptyList(),
    ): Scope {
        val top = foregroundPkgCounts
            .filterKeys { it.isNotBlank() && it != selfPackage }
            .entries
            // 次数降序；次数相同取包名字典序，保证同样输入永远得到同样结果（可单测）
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
        if (top != null) return Scope(EvolvedRule.SCOPE_PACKAGE, top.key)

        val hit = keywords.firstOrNull { it.isNotBlank() && task.contains(it, ignoreCase = true) }
        if (hit != null) return Scope(EvolvedRule.SCOPE_KEYWORD, hit)

        return Scope(EvolvedRule.SCOPE_GLOBAL)
    }

    /**
     * 一条规则在当下是否成立。
     *
     * 判定"当下"用的是**本步的前台包名 + 任务原文**（任务中途的用户指导已并入任务文本，见引擎）。
     */
    fun match(rule: EvolvedRule, foregroundPkg: String, taskText: String): Boolean = when (rule.scopeKind) {
        EvolvedRule.SCOPE_PACKAGE ->
            rule.scopeValue.isNotBlank() && rule.scopeValue.equals(foregroundPkg, ignoreCase = true)
        EvolvedRule.SCOPE_KEYWORD ->
            rule.scopeValue.isNotBlank() && taskText.contains(rule.scopeValue, ignoreCase = true)
        else -> true
    }

    /**
     * 选出当下命中且通过全部护栏的规则，按置信度降序（同分看命中次数，再同看最近使用）。
     *
     * 护栏：`confidence >= MIN_CONFIDENCE` → 模糊去重 → 最多 [MAX_SELECTED] 条、总字数 ≤ [MAX_CHARS]。
     * 条数/字数超限时**整体停止**而不是跳过继续塞后面的，保证上界严格成立。
     */
    fun select(rules: List<EvolvedRule>, foregroundPkg: String, taskText: String): List<EvolvedRule> {
        val ordered = rules
            .filter { it.content.isNotBlank() && it.confidence >= MIN_CONFIDENCE && match(it, foregroundPkg, taskText) }
            .sortedWith(
                compareByDescending<EvolvedRule> { it.confidence }
                    .thenByDescending { it.hitCount }
                    .thenByDescending { it.lastUsedAt },
            )
        val out = ArrayList<EvolvedRule>()
        val seen = ArrayList<String>()
        var used = 0
        for (rule in ordered) {
            if (out.size >= MAX_SELECTED) break
            // 同一件事换种说法只注入一条（与记忆去重同一套阈值，判定不漂移）
            if (seen.any { AiMemoryDedupe.isSame(it, rule.content) }) continue
            val len = rule.content.take(PER_RULE_CHARS).length
            if (used + len > MAX_CHARS) break
            out += rule
            seen += rule.content
            used += len
        }
        return out
    }

    /**
     * 注入用文本：一行一条。空清单返回空串，调用方据此完全不注入这一段
     * （区块标题里的"以页面为准"措辞住在提示词正文里，这里只出条目）。
     */
    fun brief(rules: List<EvolvedRule>): String =
        rules.joinToString("\n") { "- ${it.content.take(PER_RULE_CHARS)}" }
}