package com.phoneagent.debug.logtransfer

/**
 * 引擎日志缓冲的增量计算。
 *
 * 引擎把日志放在一个**环形缓冲**（上限 800 条）里，每次变化整体替换 StateFlow 的值。
 * 直接 `takeLast(size - lastSize)` 在环形回绕时会漏条目，因此这里用「上次发出的最后一条」
 * 反查位置：找到它之后的所有条目才是新增。
 */
object EngineLogDelta {

    fun <T> after(list: List<T>, lastKey: String?, keyOf: (T) -> String): List<T> {
        if (list.isEmpty()) return emptyList()
        // 还没有基准（订阅时缓冲为空）：整份列表都是订阅之后产生的，不能丢
        if (lastKey == null) return list
        val idx = list.indexOfLast { keyOf(it) == lastKey }
        if (idx < 0) {
            // 上次那条已被环形挤出：整份当前缓冲都是这段时间里的新内容
            return list.subList(0, list.size)
        }
        return if (idx + 1 < list.size) list.subList(idx + 1, list.size) else emptyList()
    }
}
