package com.phoneagent.engine.execution

/**
 * 点击目标的危险词判定：标签落在不可逆语义里时，执行前必须过用户确认门。
 *
 * 为什么需要这一层：确认门本身是硬的（ExecutionPhase 里 awaitActionConfirmation + 120s 超时），
 * 但置位它的只有语义意图——`intent=delete/send/confirm` 与能力注册表的 needsConfirmation。
 * 模型完全可以用最通用的 `tap`（按文字定位）绕开这些意图去点「删除」：那条路上
 * needsUserConfirmation 是 false，门根本不会被问。所以这里按标签再做一次兜底判定，
 * 与意图无关——不管模型从哪条路走到这一下点击，只要标签是危险的就必须问用户。
 *
 * 词表取舍（可维护：只改下面三个常量）：
 * - 只收「点下去就可能丢数据 / 花钱 / 不可恢复」的词，不收可逆或纯导航的词
 *   （取消 / 关闭 / 返回 / 退出登录都能再来一次，登记它们等于给所有点击加确认）。
 * - 「删除并退出」「永久删除」「确认删除」已被「删除」子串覆盖，不重复登记。
 * - 「付款」覆盖「立即付款 / 去付款」；但**不收**笼统的「支付」，
 *   否则设置页的「支付方式」这类纯导航入口也会被确认。
 * - 「清空 / 清除」单独一档：它们绝大多数指向批量抹除（清空聊天记录 / 缓存 / 回收站，
 *   删掉就是删掉），但少数是可逆的界面复位（清空输入框、清除筛选条件）。
 *   命中后若标签里同时出现 [REVERSIBLE_CONTEXT] 里的语境词则放行，
 *   避免正常的输入流程被卡在确认上，同时「清空搜索历史」这类仍会被问——它不含语境词。
 */
internal object DangerousTargetWords {

    /** 命中即需确认：点下去数据或钱就回不来了 */
    private val IRREVERSIBLE = listOf(
        "删除", "卸载", "格式化", "恢复出厂", "注销", "转账", "付款", "确认支付", "立即支付",
        "提交订单", "立即购买",
    )

    /** 批量抹除词：默认要确认，同现可逆语境词时放行 */
    private val BULK_CLEAR = listOf("清空", "清除")

    /** 可逆语境：与批量抹除词同现时说明这一下只是复位界面，不是抹数据 */
    private val REVERSIBLE_CONTEXT = listOf("输入", "筛选", "条件", "草稿", "关键字")

    /**
     * @return 命中的危险词（供日志与确认框说明用）；标签安全或为空时返回 null
     */
    fun hit(targetLabel: String?): String? {
        val label = targetLabel?.trim().orEmpty()
        if (label.isEmpty()) return null
        IRREVERSIBLE.firstOrNull { label.contains(it) }?.let { return it }
        val clear = BULK_CLEAR.firstOrNull { label.contains(it) } ?: return null
        return if (REVERSIBLE_CONTEXT.any { label.contains(it) }) null else clear
    }
}
