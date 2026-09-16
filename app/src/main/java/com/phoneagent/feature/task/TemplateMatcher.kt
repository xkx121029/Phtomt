package com.phoneagent.feature.task.TemplateMatcher

/**
 * 模板匹配纯逻辑（无 Android / DataStore 依赖，可 JVM 单元测试）。
 *
 * 相似度采用 Jaccard（中文双字切分）：目标字符串 → bigram 集合 → 交并比。
 * 命中要求：相似度 ≥ [MIN_SIM] 且模板健康（enabled && failedStreak < 3）。
 */
object TemplateMatcher {

    /** 最小相似度阈值：低于该值不认为两个目标描述可复用同一模板 */
    const val MIN_SIM = 0.5

    /** 从候选模板中按目标相似度选出最匹配的健康模板；无命中返回 null */
    fun match(goal: String, templates: List<TaskTemplate>): TaskTemplate? {
        if (goal.isBlank()) return null
        val g = biGrams(goal)
        return templates
            .filter { it.enabled && it.failedStreak < 3 }
            .map { it to jaccard(g, biGrams(it.goal)) }
            .filter { it.second >= MIN_SIM }
            .maxByOrNull { it.second }
            ?.first
    }

    /** 中文字符串的 bigram（双字）切分；不足两个字符时整体作为单元素集合 */
    fun biGrams(s: String): Set<String> {
        val chars = s.filter { it.isLetterOrDigit() }
        if (chars.length < 2) return setOf(chars)
        return (0 until chars.length - 1).map { chars.substring(it, it + 2) }.toSet()
    }

    /** 两个集合的 Jaccard 相似度（交并比） */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }
}