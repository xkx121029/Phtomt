package com.phoneagent.core.ai

/**
 * 决策 JSON 的本地修复（Eta 对标结论之二）。
 *
 * 完整正文解析失败时，先花几十微秒做一次本地修复，再决定要不要纠错重发——
 * 重发意味着再等一整个首 token + 生成周期，而模型常见的失败模式其实很小：
 * - 结尾被截断（少一个 `}` / `]` / 引号）
 * - 尾逗号（`{"a":1,}`）
 * - Markdown 围栏或正文里的解释文字夹在 JSON 后面
 * - 字符串里带裸换行（JSON 不允许的控制字符）
 *
 * 这些都能在端侧确定性修补，不需要模型重答。修补只做「结构补全」，不猜测字段值；
 * 修不好就返回 null，交给调用方走原有的纠错重发。提示词契约完全不变。
 */
internal object IntentJsonRepair {

    /**
     * 返回修复后的候选 JSON 文本；内容里连 `{` 都没有时返回 null。
     * 不保证修完一定能解析——调用方解析成功才算数。
     */
    fun repair(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) return null
        var body = content.substring(start)
        // 去掉 JSON 后面残留的 Markdown 围栏（``` 之后的内容一律视为解释文字）
        val fence = body.indexOf("```")
        if (fence >= 0) body = body.substring(0, fence)

        val out = StringBuilder(body.length + 8)
        var inString = false
        var escaping = false
        var braces = 0
        var brackets = 0
        for (c in body) {
            if (escaping) {
                out.append(c)
                escaping = false
                continue
            }
            if (c == '\\') {
                out.append(c)
                escaping = true
                continue
            }
            if (c == '"') {
                inString = !inString
                out.append(c)
                continue
            }
            if (inString) {
                // 裸控制字符会让 kotlinx.serialization 直接失败，转成合法转义
                when (c) {
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> out.append(c)
                }
                continue
            }
            when (c) {
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
            }
            out.append(c)
        }
        val repaired = stripTrailingCommas(out.toString())
        val closed = buildString(repaired.length + 4) {
            append(repaired)
            if (inString) append('"')
            repeat(brackets.coerceAtLeast(0)) { append(']') }
            repeat(braces.coerceAtLeast(0)) { append('}') }
        }
        return closed.ifBlank { null }
    }

    /** 去掉 `}` / `]` 前的逗号（字符串内部的逗号不动） */
    private fun stripTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaping = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (escaping) {
                out.append(c)
                escaping = false
                i++
                continue
            }
            if (c == '\\') {
                out.append(c)
                escaping = true
                i++
                continue
            }
            if (c == '"') {
                inString = !inString
                out.append(c)
                i++
                continue
            }
            if (!inString && c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
