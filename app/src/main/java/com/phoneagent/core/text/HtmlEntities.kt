// 本文件里的数字多为 HTML 实体码点、字符编码与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.core.text

/** 第三段之外的独立环节：HTML 实体解码（只认表内 32 个命名实体 + 数字实体）。 */
internal object HtmlEntities {

    /** 固定 32 个命名实体；未列入表的一律原样输出（不猜） */
    private val ENTITIES: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026", "laquo" to "\u00AB",
        "raquo" to "\u00BB", "ldquo" to "\u201C", "rdquo" to "\u201D", "lsquo" to "\u2018",
        "rsquo" to "\u2019", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "times" to "\u00D7", "divide" to "\u00F7",
        "middot" to "\u00B7", "bull" to "\u2022", "sect" to "\u00A7", "para" to "\u00B6",
        "dagger" to "\u2020", "frac12" to "\u00BD", "larr" to "\u2190", "rarr" to "\u2192",
        "harr" to "\u2194", "permil" to "\u2030",
    )

    @Suppress("LoopWithTooManyJumpStatements")
    internal fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val amp = s.indexOf('&', i)
            if (amp < 0 || amp == n - 1) {
                sb.append(s, i, n)
                break
            }
            sb.append(s, i, amp)
            val semi = s.indexOf(';', amp + 1)
            if (semi < 0 || semi - amp > 12) {
                sb.append('&')
                i = amp + 1
                continue
            }
            val rep = decodeOne(s.substring(amp + 1, semi))
            if (rep == null) {
                // 未列入表的命名实体原样输出，不猜
                sb.append('&')
                i = amp + 1
                continue
            }
            sb.append(rep)
            i = semi + 1
        }
        return sb.toString()
    }

    private fun decodeOne(body: String): String? = when {
        body.startsWith("#x") || body.startsWith("#X") ->
            codePoint(body.substring(2), 16)
        body.startsWith("#") ->
            codePoint(body.substring(1), 10)
        else -> ENTITIES[body.lowercase()]
    }

    /** 非法码点（越界 / 代理区）原样保留，不崩 */
    @Suppress("ReturnCount")
    private fun codePoint(digits: String, radix: Int): String? {
        val v = digits.toIntOrNull(radix) ?: return null
        if (v !in 1..0x10FFFF) return null
        if (v in 0xD800..0xDFFF) return null
        return String(Character.toChars(v))
    }
}
