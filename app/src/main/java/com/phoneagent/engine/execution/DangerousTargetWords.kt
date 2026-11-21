package com.phoneagent.engine.execution

/**
 * 点击目标的危险词判定：标签落在不可逆语义里时，执行前必须过用户确认门。
 *
 * 为什么需要这一层：确认门本身是硬的（ExecutionPhase 里 awaitActionConfirmation + 120s 超时），
 * 但置位它的只有语义意图——`intent=delete/send/confirm` 与能力注册表的 needsConfirmation。
 * 模型完全可以用最通用的 `tap`（按文字/控件 id 定位）绕开这些意图去点「删除」：那条路上
 * needsUserConfirmation 是 false，门根本不会被问。所以这里按标签再做一次兜底判定，
 * 与意图无关——不管模型从哪条路走到这一下点击，只要标签是危险的就必须问用户。
 *
 * 词表取舍（可维护：只改下面几个常量）：
 * - 只收「点下去就可能丢数据 / 花钱 / 发出去收不回」的词，不收可逆或纯导航的词
 *   （取消 / 关闭 / 返回 / 退出登录都能再来一次，登记它们等于给所有点击加确认）。
 * - 「删除并退出」「永久删除」「确认删除」已被「删除」子串覆盖，不重复登记；
 *   「确认下单」「立即下单」同理被「下单」覆盖。
 * - 「付款」覆盖「立即付款 / 去付款」；但**不收**笼统的「支付」，
 *   否则设置页的「支付方式」这类纯导航入口也会被确认。
 * - 分档的依据是「豁免语境不同」，所以是三张表而不是一张：
 *   - [IRREVERSIBLE]：点下去就回不来，没有语境例外。
 *   - [BULK_CLEAR]：绝大多数是批量抹数据，少数是界面复位（清空输入框）→ 由 [BULK_CLEAR_CONTEXT] 豁免。
 *     这张语境表里**没有**「记录/历史」：「清空聊天记录」「清除历史记录」必须继续被问。
 *   - [SUBMIT]：发出去、提交成功就产生真实后果（消息进对话、订单成立），但它们也大量出现在
 *     纯导航标签里（「发送记录」「结算中心」「购买明细」）→ 由 [SUBMIT_NAV_CONTEXT] 豁免。
 *     「发送草稿箱」**不在**豁免之列：草稿一发出去和朋友手滑发出去一样收不回。
 * - 英文词单独一档，按**词边界**匹配：`by=id` 的控件名常常是英文（`send` / `checkout`），
 *   纯中文表完全看不见它们；用词边界而不是子串，才不会把 `sender` / `postcode` / `payment`
 *   当成 `send` / `post` / `pay`。英文的导航豁免同口径（"Post settings" / "Send history"）。
 */
internal object DangerousTargetWords {

    /** 命中即需确认：点下去数据或钱就回不来了 */
    private val IRREVERSIBLE = listOf(
        "删除", "卸载", "格式化", "恢复出厂", "注销", "转账", "付款", "确认支付", "立即支付",
        "提交订单", "立即购买",
    )

    /** 批量抹除词：默认要确认，同现 [BULK_CLEAR_CONTEXT] 语境词时放行 */
    private val BULK_CLEAR = listOf("清空", "清除")

    /** 批量抹除的可逆语境：与抹除词同现时说明这一下只是复位界面，不是抹数据 */
    private val BULK_CLEAR_CONTEXT = listOf("输入", "筛选", "条件", "草稿", "关键字")

    /** 提交类词：一经确认即生效（消息发出 / 订单成立 / 钱划走），默认要确认 */
    private val SUBMIT = listOf("发送", "发布", "结算", "下单", "购买")

    /** 提交类的导航语境：这些标签指向"查看/设置/管理"，点下去不产生任何提交 */
    private val SUBMIT_NAV_CONTEXT = listOf(
        "记录", "明细", "设置", "中心", "状态", "历史", "详情", "说明", "规则", "列表", "管理",
    )

    /** 英文不可逆词：`by=id` 的英文控件名不会命中上面的中文表 */
    private val IRREVERSIBLE_EN = listOf(
        "send", "post", "publish", "pay", "checkout", "confirm", "delete", "purchase",
    )

    /** 英文导航语境：与中文侧同口径的豁免 */
    private val SUBMIT_NAV_EN = listOf(
        "setting", "settings", "record", "records", "history", "status", "detail", "details",
        "info", "preference", "preferences", "list",
    )

    /** 词边界匹配（大小写不敏感）：`sender` / `postcode` / `payment` 不算 `send` / `post` / `pay` */
    private val EN_WORDS = Regex("\\b(" + IRREVERSIBLE_EN.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val EN_NAV_WORDS = Regex("\\b(" + SUBMIT_NAV_EN.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    // ---- 浏览器通道（BrowserGuard）的词表：唯一定义点 ----
    /**
     * 浏览器严格档独有的词：网页里点下去就生效，但在 App 里属于纯导航
     * （「支付方式」是设置页入口），所以**不进** [IRREVERSIBLE]——浏览器侧不做语境豁免（宁可误拦），
     * 这几个词必须留在浏览器表里。
     */
    private val BROWSER_ONLY_CN = listOf("支付", "买单", "去支付", "充值", "提现", "移除", "解绑")

    private val BROWSER_ONLY_EN = listOf(
        "payment", "withdraw", "transfer", "remove", "unsubscribe", "place order", "submit order",
        "buy now", "pay now",
    )

    /**
     * 浏览器通道的中文词表全集：严格档在前（`BrowserGuard.match` 回报"命中哪个词"依赖顺序，
     * 既有用例按「支付」断言），再并上三档引擎词——引擎侧新增一个词，浏览器侧自动看见，
     * 不会再出现"两份表各改一半"。
     */
    val browserCn: List<String> = (BROWSER_ONLY_CN + IRREVERSIBLE + BULK_CLEAR + SUBMIT).distinct()

    /** 浏览器通道的英文词表全集（同上：严格档在前，再并上引擎侧英文词） */
    val browserEn: List<String> = (BROWSER_ONLY_EN + IRREVERSIBLE_EN).distinct()

    private fun containsAny(label: String, words: List<String>): Boolean = words.any { label.contains(it) }

    /**
     * @return 命中的危险词（供日志与确认框说明用）；标签安全或为空时返回 null
     */
    fun hit(targetLabel: String?): String? {
        val label = targetLabel?.trim().orEmpty()
        if (label.isEmpty()) return null
        // 可逆语境只豁免批量抹除与提交这两档：直接命中的危险词没有例外
        IRREVERSIBLE.firstOrNull { label.contains(it) }?.let { return it }
        BULK_CLEAR.firstOrNull { label.contains(it) }
            ?.takeUnless { containsAny(label, BULK_CLEAR_CONTEXT) }
            ?.let { return it }
        SUBMIT.firstOrNull { label.contains(it) }
            ?.takeUnless { containsAny(label, SUBMIT_NAV_CONTEXT) }
            ?.let { return it }
        if (EN_NAV_WORDS.containsMatchIn(label)) return null
        return EN_WORDS.find(label)?.groupValues?.get(1)?.lowercase()
    }
}
