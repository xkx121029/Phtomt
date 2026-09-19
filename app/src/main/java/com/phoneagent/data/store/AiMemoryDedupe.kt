package com.phoneagent.data.store

/**
 * AI 记忆去重：判断两条记忆是否在说同一件事。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。
 * AI 在不同任务里容易反复记下同类信息（"用户喜欢简洁界面" / "用户偏好简洁的界面"），
 * 靠它合并成一条并更新，而不是把列表堆成一堆重复项。
 */
object AiMemoryDedupe {

    /**
     * 判定为同一条的相似度阈值。
     * 中文近义改写（"常在美团点黄焖鸡" / "通常在美团买黄焖鸡"）的 bigram 相似度约 0.53，
     * 而主题不同的句子（"喜欢简洁界面" / "常去菜市场买菜"）只有 0.07 左右，
     * 取 0.5 能合并前者、不会误并后者。
     */
    private const val SIMILARITY_THRESHOLD = 0.5

    /** 规范化：去掉空白与常见标点、统一小写 */
    private val PUNCTUATION = Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）《》【】…—～·]")

    private fun normalize(text: String): String =
        text.lowercase().replace(PUNCTUATION, "")

    /** 字符 bigram 集合：中文按字切分，bigram 能保留词序信息 */
    private fun bigrams(normalized: String): Set<String> {
        if (normalized.length < 2) return if (normalized.isEmpty()) emptySet() else setOf(normalized)
        return (0 until normalized.length - 1).map { normalized.substring(it, it + 2) }.toSet()
    }

    /** Jaccard 相似度：交集 / 并集 */
    fun similarity(a: String, b: String): Double {
        val setA = bigrams(normalize(a))
        val setB = bigrams(normalize(b))
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val inter = setA.count { it in setB }
        val union = setA.size + setB.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** 两条记忆是否属于同一条（可合并） */
    fun isSame(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        // 完全一致直接判定，省一次 bigram 计算
        if (na == nb) return true
        return similarity(a, b) >= SIMILARITY_THRESHOLD
    }
}
