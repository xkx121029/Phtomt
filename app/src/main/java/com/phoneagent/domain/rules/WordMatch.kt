package com.phoneagent.domain.rules

import java.util.concurrent.ConcurrentHashMap

/**
 * 英文词匹配的唯一实现：**左词边界 + 大小写不敏感**。
 *
 * 为什么不能直接用 `contains`：英文里 `border` 含 `order`、`resend` 含 `send`、
 * `repayment` 含 `payment`——按子串判会把普通控件当成不可逆操作，用户就被天天弹窗。
 * 中文词没有这个问题（没有词边界），仍按子串从宽匹配。
 *
 * 右边界只认"下一个字符不是小写字母"：`#deleteComment`、`DeleteAccountButton` 这类驼峰命名要保留从宽
 * 拦截（大写开新词），而 `sender` → `send`、`postcode` → `post`、`confirmed` → `confirm` 这类同词延伸不算命中——
 * 英文界面上「Sender」「Postcode」是纯导航标签，误拦等于把每次点击都加一道门。
 * 安全方向上宁可多问一句、不可漏放一次，但"多问"要落在真危险上。
 */
internal object WordMatch {

    private val compiled = ConcurrentHashMap<List<String>, List<Pair<String, Regex>>>()

    /** @return 命中的原始词条；未命中或文本为空返回 null */
    fun hit(text: String?, words: List<String>): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() || words.isEmpty()) return null
        return patternsOf(words).firstOrNull { it.second.containsMatchIn(raw) }?.first
    }

    private fun patternsOf(words: List<String>): List<Pair<String, Regex>> =
        compiled.getOrPut(words) {
            // 大小写不敏感只作用于词条本身（(?i:...)），不作用于右边界断言：
            // 否则 (?![a-z]) 连大写也算小写，驼峰命名就拦不住了
            words.map { w -> w to Regex("\\b(?i:" + Regex.escape(w) + ")(?![a-z])") }
        }
}
