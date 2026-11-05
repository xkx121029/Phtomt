package com.phoneagent.debug.logtransfer

/**
 * 极简 JSON 编解码（仅覆盖本功能自己的扁平对象）。
 *
 * 刻意不引 org.json / kotlinx.serialization：
 * - org.json 在 JVM 单测里是 android.jar 桩实现（`isReturnDefaultValues = true`），序列化结果为 null；
 * - 本功能只需要「一层扁平对象 + 字符串/数字/布尔/null」，超集解析器只会带来无关的失败面。
 */
object LogJson {

    /** 字符串转义：覆盖引号、反斜杠、控制字符与 U+2028/2029（PC 侧按标准 JSON 解析） */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch < ' ' || ch.code == 0x7F) {
                    sb.append("\\u").append(hex4(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /** 拼一个扁平 JSON 对象；[fields] 的值为 String / Int / Long / Boolean / null */
    fun obj(vararg fields: Pair<String, Any?>): String = buildString {
        append('{')
        fields.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append('"').append(escape(k)).append("\":")
            append(value(v))
        }
        append('}')
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + escape(v) + "\""
        is Boolean -> v.toString()
        is Int -> v.toString()
        is Long -> v.toString()
        else -> "\"" + escape(v.toString()) + "\""
    }

    private fun hex4(code: Int): String = Integer.toHexString(code).padStart(4, '0')

    /**
     * 解析扁平 JSON 对象；结构不合法或含嵌套/数组时返回 null（本功能不产生这类数据，
     * 认不出就当作坏行丢弃，不让一条坏日志毁掉整段历史）。
     */
    fun parseFlatObject(line: String): Map<String, Any?>? {
        val text = line.trim()
        if (text.length < 2 || text.first() != '{' || text.last() != '}') return null
        val out = LinkedHashMap<String, Any?>()
        var i = 1
        val end = text.length - 1
        while (i < end) {
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end) break
            if (text[i] != '"') return null
            val (key, nextKey) = readString(text, i, end) ?: return null
            i = nextKey
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end || text[i] != ':') return null
            i++
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end) return null
            when {
                text[i] == '"' -> {
                    val (v, next) = readString(text, i, end) ?: return null
                    out[key] = v
                    i = next
                }
                text[i] == 't' || text[i] == 'f' -> {
                    val literal = if (text[i] == 't') "true" else "false"
                    if (!text.startsWith(literal, i)) return null
                    out[key] = literal == "true"
                    i += literal.length
                }
                text[i] == 'n' -> {
                    if (!text.startsWith("null", i)) return null
                    out[key] = null
                    i += 4
                }
                else -> {
                    val start = i
                    if (text[i] == '-' || text[i].isDigit()) i++ else return null
                    while (i < end && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' || text[i] == 'E' || text[i] == '+' || text[i] == '-')) i++
                    val raw = text.substring(start, i)
                    out[key] = raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: return null
                }
            }
            while (i < end && text[i].isWhitespace()) i++
            if (i < end && text[i] == ',') {
                i++
                continue
            }
            if (i < end) return null
        }
        return out
    }

    /** 读取一个 JSON 字符串（起点必须是引号），返回 (内容, 引号后一位下标) */
    private fun readString(text: String, start: Int, end: Int): Pair<String, Int>? {
        if (start >= end || text[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < end) {
            val ch = text[i]
            when {
                ch == '"' -> return sb.toString() to i + 1
                ch == '\\' -> {
                    if (i + 1 >= end) return null
                    when (val esc = text[i + 1]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 5 >= end) return null
                            val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return null
    }
}
