package com.phoneagent.feature.browser

import com.phoneagent.core.text.HtmlToMarkdown

/**
 * 浏览器不可逆操作护栏。
 *
 * 为什么单独一颗：[BrowserChannel] 在只读模式下要判"这一步点下去会不会造成真实后果"，
 * 提示词也要把同一张词表告诉 AI，单测还要逐词断言"该拦的拦、不该拦的别拦"。
 * 词表放这里 = 一处定义、三处引用，不可能漂移。
 *
 * 只收**不可逆**动作：支付、下单、提交订单、删除、发送、发布、注销、解绑、清空、转账这类
 * 一旦生效就无法撤回的操作。**刻意不收**「确认/确定/提交/继续」这类泛词——搜索、翻页、
 * 登录、同意条款的按钮全带这些字，收进去只读模式就废了。
 */
internal object BrowserGuard {

    /**
     * 中文不可逆词：contains 从宽匹配（"付款码"同样会命中「付款」）。
     * 不可逆操作的判定口径是"宁可误拦、不可漏放"，注释里不再承诺按词切分的细粒度匹配。
     */
    val WORDS_CN = listOf(
        "支付", "付款", "买单", "结算", "下单", "提交订单", "立即购买", "去支付", "确认支付",
        "转账", "充值", "提现", "删除", "移除", "发送", "发布", "注销", "解绑", "清空",
    )

    /** 英文不可逆词（小写匹配；含短语，故单独维护而不是复用空格分词工具） */
    val WORDS_EN = listOf(
        "payment", "checkout", "place order", "submit order", "buy now", "pay now", "withdraw",
        "transfer", "delete", "remove", "publish", "unsubscribe", "send",
    )

    /** 英文词的左词边界正则（大小写不敏感）：词首必须独立成词，否则 "border" 会被当成 order、"resend" 当成 send */
    private val EN_PATTERNS: List<Pair<String, Regex>> by lazy {
        WORDS_EN.map { w -> w to Regex("\\b${Regex.escape(w)}", RegexOption.IGNORE_CASE) }
    }

    /**
     * 选择器串里的危险词：`by=id` 时 AI 给的是 CSS 选择器，端侧在执行前看不到元素文字，
     * 只能先从选择器自身的命名上嗅一道（`#pay-btn` / `.checkout-submit`），
     * 剩下的交给注入脚本里的探针（见 [InteractScripts.click] 的 guard 分支）。
     * 匹配同样用左词边界；**刻意不收紧右边界**——`#deleteComment` 这类驼峰命名要保留从宽拦截。
     */
    private val SELECTOR_HINTS = listOf("pay", "checkout", "order", "buy", "submit", "delete", "send", "transfer")

    private val SELECTOR_PATTERNS: List<Regex> by lazy {
        SELECTOR_HINTS.map { Regex("\\b${Regex.escape(it)}", RegexOption.IGNORE_CASE) }
    }

    /** 命中返回命中的词（用于拒绝语），未命中返回 null */
    fun match(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isBlank()) return null
        WORDS_CN.firstOrNull { raw.contains(it) }?.let { return it }
        return EN_PATTERNS.firstOrNull { it.second.containsMatchIn(raw) }?.first
    }

    /** 是否属不可逆操作：AI 自报 或 文字命中词表（AI 自报是弱信号，仅作叠加） */
    fun isIrreversible(text: String?, needsConfirmation: Boolean = false): Boolean =
        needsConfirmation || match(text) != null

    /** CSS 选择器是否可疑（执行前唯一能做的判定） */
    fun selectorSuspicious(selector: String?): Boolean {
        val s = selector?.trim().orEmpty()
        if (s.isBlank()) return false
        if (match(s) != null) return true
        return SELECTOR_PATTERNS.any { it.containsMatchIn(s) }
    }

    /** 词表的 JS 数组字面量（插值进注入脚本，保证与端侧判定同一份表） */
    fun jsWords(): String =
        "[" + (WORDS_CN + WORDS_EN).joinToString(",") { HtmlToMarkdown.jsStr(it) } + "]"

    /** 词表的中文提示串（插值进提示词，杜绝提示词与端侧护栏各写一份） */
    fun promptWords(): String = WORDS_CN.joinToString("/")

    /** 词表的英文提示串 */
    fun promptWordsEn(): String = WORDS_EN.joinToString("/")
}