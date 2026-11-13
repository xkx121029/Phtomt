@file:Suppress("MagicNumber")
package com.phoneagent.device.ops

import java.util.Locale

/**
 * 系统直连能力的参数解析工具。
 *
 * 抽出来的目的：这类「从 `Map<String, String>` 里读一个 int/bool」的逻辑原先散在
 * [DeviceOpsChannel] 的 14 个领域分支里，既无法单测，也没法在新增能力时复用。
 * 这里全部是无副作用的纯函数，可直接在 JVM 单测里断言。
 */
internal object OpsArgs {

    /** 读整数：缺省、空白、非数字一律返回 null（不抛异常） */
    fun int(args: Map<String, String>, key: String): Int? =
        args[key]?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** 读长整数：同 [int]，但用于毫秒时间戳等超出 Int 的取值 */
    fun long(args: Map<String, String>, key: String): Long? =
        args[key]?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()

    /**
     * 读布尔：同时接受英文与中文写法。
     *
     * 之所以这么宽：模型经常把「开启勿扰」写成 `enable=开` / `enable=on` / `enable=1` 三种形态，
     * 严格只认 `true/false` 会让整条意图失败。无法识别时返回 null（由调用方决定默认值）。
     */
    fun bool(args: Map<String, String>, key: String): Boolean? {
        val raw = args[key]?.trim()?.lowercase() ?: return null
        return when (raw) {
            "true", "1", "on", "yes", "开", "开启" -> true
            "false", "0", "off", "no", "关", "关闭" -> false
            else -> null
        }
    }
}

/**
 * shell 命令里的单引号转义。
 *
 * 这是安全边界的一部分：AI 给的文字（文件名、联系人名）会拼进 shell 命令，
 * 不转义就等于把命令注入能力直接交给模型。
 */
internal object ShellQuote {

    /** 用单引号包裹，内部单引号按 POSIX 写法转义为 `'\''` */
    fun of(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** 字节数的人类可读格式化（设备存储/文件大小展示用） */
internal object OpsFormat {

    fun mb(bytes: Long): String = String.format(Locale.US, "%.0fMB", bytes / 1024.0 / 1024.0)

    fun gb(bytes: Long): String = String.format(Locale.US, "%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 文件名 glob → 正则。
 *
 * 约定与 Eta 的 glob 一致：`*` 不跨目录、`**` 跨目录、`?` 匹配单个非分隔符字符。
 * 纯函数，可直接单测。
 */
internal object GlobPattern {

    fun toRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i++
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c in "\\.[]{}()+^$|" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return Regex(sb.toString())
    }
}
