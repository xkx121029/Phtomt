package com.phoneagent.provider.a11y

/**
 * 活节点树上的目标定位判据（**纯逻辑，零 Android 依赖**）。
 *
 * 从 `ProviderActionExecutor.findNode / pickBy` 原样搬出来的判定部分：那边读活节点树、
 * 这里只做取舍。分开的原因是这一层**必须能被单测咬住**——判错的两个后果都在真机上很难复盘：
 * - 找错控件：`performAction` 点在邻居上还判成功（AI 用 elementIndex 选中无字图标、页面滚过一屏的现场）；
 * - 找不到：静默退化成按坐标猜，点击落点随弹窗/键盘漂移。
 *
 * 三条规则（顺序即优先级，改动等于改点击目标）：
 * 1. **三级降级**：viewId → 文字精确 → 文字包含，只有上一级完全没命中才启用下一级
 *    （否则「id 明明在、却被同文字的别的控件抢先」）；
 * 2. **候选排序**：可点击优先（那才是能按的）→ 面积最小（越具体越贴近视觉位置）→ 离快照位置最近
 *    （同一 viewId / 同一段文字会出现在列表的每一行上）；
 * 3. **没有位置兜底**：位置只用于**排序**，不单独授权一次点击；找不到标识就返回 null。
 *
 * 标签比对的口径：节点的**自身**文字/描述，或**后代文字拼出的派生标签**（[DerivedLabel]）命中即可。
 * 派生标签这一层不能少：可点击容器常常没有自己的文字（微信聊天列表行就是这样），
 * 只比自身文字，这类目标在活节点树上永远匹配不到。
 */
internal object NodeMatch {

    /** 一个节点参与匹配所需的全部事实（由调用方从活节点读出，本对象不碰 Android） */
    data class Facts(
        /** 完整资源名（形如 `com.demo:id/btn_ok`），按后缀匹配 */
        val viewId: String?,
        /** 自身文字 + 内容描述 + 派生标签（空白项已剔除） */
        val texts: List<String>,
        val clickable: Boolean,
        /** 屏幕坐标下的宽高（像素） */
        val width: Int,
        val height: Int,
        val centerX: Int,
        val centerY: Int,
    )

    private fun distSq(facts: Facts, hint: Pair<Int, Int>): Int {
        val dx = facts.centerX - hint.first
        val dy = facts.centerY - hint.second
        return dx * dx + dy * dy
    }

    /** 候选取舍的三级键：可点击 → 面积小 → 离提示点近；完全并列时取先出现的那个 */
    private fun rankOrder(hint: Pair<Int, Int>?) = compareBy<IndexedValue<Facts>>(
        { if (it.value.clickable) 0 else 1 },
        { it.value.width * it.value.height },
        { if (hint == null) 0 else distSq(it.value, hint) },
    )

    @Suppress("ReturnCount")
    private fun pick(nodes: List<Facts>, hint: Pair<Int, Int>?, predicate: (Facts) -> Boolean): Int? =
        nodes.withIndex().filter { predicate(it.value) }.minWithOrNull(rankOrder(hint))?.index

    /**
     * 在当前列表里按同一套候选排序取最优（落点审计用：候选已由调用方筛过）。
     *
     * @return 下标；候选为空返回 null
     */
    fun best(candidates: List<Facts>, hint: Pair<Int, Int>?): Int? = pick(candidates, hint) { true }

    /**
     * 按定位线索找目标控件。
     *
     * @param hintX/hintY 快照里那个元素的位置，用于在同名候选里就近取舍；负数表示没有提示
     * @return 命中节点在 [nodes] 中的下标；三级都不命中返回 null（**不许猜**）
     */
    fun find(nodes: List<Facts>, viewId: String?, label: String?, hintX: Int, hintY: Int): Int? {
        if (nodes.isEmpty()) return null
        val hint = if (hintX >= 0 && hintY >= 0) hintX to hintY else null

        // 逐级降级：只有上一级完全没命中才启用下一级
        viewId?.takeIf { it.isNotBlank() }?.let { id ->
            pick(nodes, hint) { (it.viewId ?: "").endsWith(id, ignoreCase = true) }?.let { return it }
        }
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            pick(nodes, hint) { n -> n.texts.any { it.equals(text, ignoreCase = true) } }?.let { return it }
            // 容器自身没有文字、标签由后代拼出来的情况，只能按「包含」兜底
            pick(nodes, hint) { n -> n.texts.any { it.contains(text, ignoreCase = true) } }?.let { return it }
        }
        return null
    }
}
