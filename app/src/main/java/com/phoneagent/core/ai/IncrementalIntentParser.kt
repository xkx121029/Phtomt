package com.phoneagent.core.ai

/**
 * 流式决策 JSON 的增量扫描器（Eta 对标结论之二）。
 *
 * 原来的决策链路是「把整个正文收完再解析」：模型吐完最后一个 `}` 之后，
 * 还要等服务端把流关闭（有时还要等结尾的 usage 帧）才能开始解析，白等一段网络尾巴。
 * 这里逐帧扫描正文，一旦第一个顶层 `{...}` 闭合就交给调用方尝试解析；
 * 解析成功即可停止消费，省掉流尾巴。
 *
 * 只做「找出已闭合的 JSON 对象」这件纯解析工作，不认识业务字段，
 * 因此不会改变提示词契约，也不影响解析失败时的纠错重发路径。
 *
 * 状态跨帧保留：字符串里的花括号、转义字符、嵌套对象都能正确跳过。
 */
internal class IncrementalIntentParser {

    /** 已扫描到的正文位置（正文是只增的 StringBuilder，位置单调递增） */
    private var pos = 0

    /** 当前正在收的顶层对象起点；-1 表示还没遇到 `{` */
    private var start = -1
    private var depth = 0
    private var inString = false
    private var escaping = false

    /** 每次流尝试开始时调用：正文被清空，扫描状态必须一起归零 */
    fun reset() {
        pos = 0
        start = -1
        depth = 0
        inString = false
        escaping = false
    }

    /**
     * 从 [text] 的当前扫描位置继续往后找，返回下一个已闭合的顶层 JSON 对象。
     * 没有闭合对象时返回 null（调用方下一帧再来）。
     */
    fun nextObject(text: CharSequence): String? {
        while (pos < text.length) {
            val c = text[pos]
            pos++
            if (start < 0) {
                if (c == '{') {
                    start = pos - 1
                    depth = 1
                    inString = false
                    escaping = false
                }
                continue
            }
            if (escaping) {
                escaping = false
                continue
            }
            if (c == '\\') {
                escaping = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val obj = text.subSequence(start, pos).toString()
                        start = -1
                        return obj
                    }
                }
            }
        }
        return null
    }
}
