// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.feature.agentdoc

/**
 * 执行文档匹配器（纯函数，可单测）。
 *
 * 命中口径刻意保守：**至少要有一个真实信号**（前台包名 / 应用名 / 关键词 / 意图词）才参与注入，
 * 只靠 priority 不算命中——否则库里随便一篇文档都会挤进每个任务的上下文。
 *
 * 计分优先级：前台包名（最确定）> 应用名 > 关键词 > 意图词；priority 只作为同分时的排序加权。
 */
object DocMatcher {

    /** 单篇命中结果 */
    data class Hit(val meta: DocMeta, val score: Int)

    /**
     * 计算一篇文档与当前任务的匹配分；0 = 不命中。
     *
     * @param foregroundPackage 执行阶段的前台应用包名（规划阶段为 null）
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun score(meta: DocMeta, task: String, foregroundPackage: String? = null): Int {
        if (meta.id.isBlank() || task.isBlank()) return 0
        val pkg = foregroundPackage?.trim()?.lowercase().orEmpty()
        var score = 0
        var signal = false

        if (pkg.isNotEmpty()) {
            meta.packages.forEach { p ->
                val target = p.trim().lowercase()
                if (target.isNotEmpty() && (pkg == target || pkg.startsWith("$target."))) {
                    score += 60
                    signal = true
                }
            }
        }
        meta.apps.forEach { app ->
            val name = app.trim()
            if (name.isNotEmpty() && task.contains(name, ignoreCase = true)) {
                score += 40
                signal = true
            }
        }
        meta.keywords.forEach { kw ->
            val word = kw.trim()
            // 单字关键词太宽（"卡""码"会撞上大量无关任务），不计分
            if (word.length >= 2 && task.contains(word, ignoreCase = true)) {
                score += 8
                signal = true
            }
        }
        meta.intents.forEach { intent ->
            val word = intent.trim()
            if (word.length >= 2 && task.contains(word, ignoreCase = true)) {
                score += 6
                signal = true
            }
        }
        if (!signal) return 0
        return score + meta.priority.coerceIn(0, 100) / 10
    }

    /** 按分数取前 [limit] 篇；同分按 priority 高者优先，再按 id 稳定排序 */
    fun select(
        docs: List<DocMeta>,
        task: String,
        foregroundPackage: String? = null,
        limit: Int = 3,
    ): List<Hit> = docs
        .map { Hit(it, score(it, task, foregroundPackage)) }
        .filter { it.score > 0 }
        .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.meta.priority }.thenBy { it.meta.id })
        .take(limit.coerceAtLeast(1))
}
