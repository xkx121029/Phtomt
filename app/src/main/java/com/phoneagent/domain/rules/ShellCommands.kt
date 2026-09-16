package com.phoneagent.domain.rules.ShellCommands

/**
 * AI 友好命令解析器。
 *
 * 将 AI 输出的短命令（如 "tap 500 800"）解析为完整 ADB 命令。
 * AI 不再需要记忆 ADB 语法，只需使用本系统定义的命名命令。
 */
object ShellCommands {

    /** 尝试解析命令，返回实际 ADB 命令字符串；无法解析时返回 null。
     *  坐标参数支持三种格式：
     *  - 比例：0.04 0.5 （0~1，乘以屏幕宽高）
     *  - 百分比：4% 50% （除以 100 后乘以屏幕宽高）
     *  - 像素：500 800 （>1 的数值原样使用）
     */
    fun resolve(command: String, screenWidth: Int = 1080, screenHeight: Int = 2400): String? {
        val w = if (screenWidth > 0) screenWidth else 1080
        val h = if (screenHeight > 0) screenHeight else 2400
        val trimmed = command.trim()
        // 空命令
        if (trimmed.isEmpty()) return null
        // raw 前缀 → 直接透传（兜底）
        if (trimmed.startsWith("raw ", ignoreCase = true))
            return trimmed.removePrefix("raw ").trim().ifEmpty { null }

        // 按空格分割命令名和参数
        val parts = trimmed.split(" ", limit = 2)
        val name = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        return when (name) {
            // ====== 点击 ======
            "tap" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                "input tap $x $y"
            }
            "lp", "long_press" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                "input swipe $x $y $x $y 1500"
            }
            "dt", "double_tap" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                "input tap $x $y\ninput tap $x $y"
            }

            // ====== 滑动 ======
            "sw", "swipe" -> {
                val coords = args.trim().split("\\s+".toRegex())
                if (coords.size < 4) return null
                val x1 = toPixel(coords[0], w) ?: return null
                val y1 = toPixel(coords[1], h) ?: return null
                val x2 = toPixel(coords[2], w) ?: return null
                val y2 = toPixel(coords[3], h) ?: return null
                "input swipe $x1 $y1 $x2 $y2 400"
            }
            "su", "swipe_up" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                val dist = (h * 0.25f).toInt().coerceAtLeast(100)
                "input swipe $x $y $x ${(y - dist).coerceIn(0, h - 1)} 400"
            }
            "sd", "swipe_down" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                val dist = (h * 0.25f).toInt().coerceAtLeast(100)
                "input swipe $x $y $x ${(y + dist).coerceIn(0, h - 1)} 400"
            }
            "sl", "swipe_left" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                val dist = (w * 0.25f).toInt().coerceAtLeast(100)
                "input swipe $x $y ${(x - dist).coerceIn(0, w - 1)} $y 400"
            }

            "sr", "swipe_right" -> {
                val (x, y) = parseCoord(args, w, h) ?: return null
                val dist = (w * 0.25f).toInt().coerceAtLeast(100)
                "input swipe $x $y ${(x + dist).coerceIn(0, w - 1)} $y 400"
            }

            // ====== 按键 ======
            "key" -> {
                val key = args.trim().ifEmpty { return null }
                val code = when (key.uppercase()) {
                    "BACK" -> 4
                    "HOME" -> 3
                    "RECENTS", "RECENT", "APP_SWITCH" -> 187
                    "ENTER" -> 66
                    "MENU" -> 82
                    "VOLUME_UP" -> 24
                    "VOLUME_DOWN" -> 25
                    "POWER" -> 26
                    "DEL", "DELETE" -> 67
                    "SPACE" -> 62
                    "CLEAR" -> 28
                    "WAKEUP" -> 224
                    else -> key.toIntOrNull() ?: return null
                }
                "input keyevent $code"
            }
            "back" -> "input keyevent 4"
            "home" -> "input keyevent 3"
            "recents" -> "input keyevent 187"

            // ====== 文字 ======
            "text", "type" -> {
                val text = args.trim().removeSurrounding("\"").removeSurrounding("'")
                if (text.isEmpty()) return null
                "input text ${escapeText(text)}"
            }

            // ====== 应用 ======
            "am" -> {
                val intent = args.trim()
                if (intent.isEmpty()) return null
                "am start -n $intent"
            }
            "stop", "force_stop" -> {
                val pkg = args.trim()
                if (pkg.isEmpty()) return null
                "am force-stop $pkg"
            }
            "dump", "list_activities" -> {
                val pkg = args.trim()
                if (pkg.isEmpty()) return null
                "dumpsys package $pkg | grep \"Activity\" | grep -v filter"
            }
            "launch" -> {
                val pkg = args.trim()
                if (pkg.isEmpty()) return null
                "monkey -p $pkg 1"
            }

            // ====== 系统 ======
            "brightness" -> {
                val value = args.trim().toIntOrNull()
                if (value == null || value !in 0..255) return null
                "settings put system screen_brightness $value"
            }
            "screenshot" -> "screencap -p /sdcard/hpa_screenshot.png"
            "wifi_on" -> "svc wifi enable"
            "wifi_off" -> "svc wifi disable"
            "clip", "clipboard" -> "content read --uri content://clipboard"

            // ====== 信息 ======
            "info", "current_activity" -> "dumpsys window windows | grep -E \"mCurrentFocus|mFocusedApp\""
            "window_size" -> "wm size"
            "battery" -> "dumpsys battery | grep level"
            "date" -> "date"

            // 未知命令：若形如裸包名（含点、无空格），自动补全为实际动作 launch
            else -> {
                if (trimmed.contains('.') && !trimmed.contains(" ")) "monkey -p $trimmed 1" else null
            }
        }
    }

    /** 解析友好命令为 [命令名, 参数] 元组；空命令或 raw 透传命令返回 null。
     *  供 Shizuku 不可用时执行层将友好命令翻译为等价的无障碍动作。 */
    fun parse(command: String): Pair<String, String>? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        // raw 透传命令无法翻译为无障碍动作
        if (trimmed.startsWith("raw ", ignoreCase = true)) return null
        val parts = trimmed.split(" ", limit = 2)
        return parts[0].lowercase() to (parts.getOrNull(1)?.trim() ?: "")
    }

    /** 解析 "x y" 双坐标（比例/百分比/像素）为像素坐标；无效返回 null。
     *  供 Shizuku 不可用时无障碍动作换算坐标。 */
    fun coord(input: String, w: Int, h: Int): Pair<Int, Int>? {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) return null
        val x = toPixel(parts[0], w) ?: return null
        val y = toPixel(parts[1], h) ?: return null
        return x to y
    }

    /** 解析 "x1 y1 x2 y2" 四坐标（比例/百分比/像素）为像素列表；无效返回 null。
     *  供 Shizuku 不可用时无障碍滑动换算坐标。 */
    fun coords(input: String, w: Int, h: Int): List<Int>? {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 4) return null
        val out = mutableListOf<Int>()
        for (i in parts.indices) {
            val dim = if (i % 2 == 0) w else h
            out.add(toPixel(parts[i], dim) ?: return null)
        }
        return out
    }

    /** 解析 "x y" 格式的坐标（支持比例/百分比/像素），返回像素坐标 */
    private fun parseCoord(input: String, w: Int, h: Int): Pair<Int, Int>? {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) return null
        val x = toPixel(parts[0], w) ?: return null
        val y = toPixel(parts[1], h) ?: return null
        return x to y
    }

    /** 单个坐标值 → 像素：百分比(4%)、比例(0.04)、像素(500) */
    private fun toPixel(raw: String, dim: Int): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.endsWith("%")) {
            val pct = s.dropLast(1).toFloatOrNull() ?: return null
            return (pct / 100f * dim).toInt().coerceIn(0, dim - 1)
        }
        val f = s.toFloatOrNull() ?: return null
        // 严格小于 1 才视为比例；等于 1 视为像素坐标（避免 x=1 的像素点被误判成全屏比例）
        return if (f < 1.0f) (f * dim).toInt().coerceIn(0, dim - 1) else f.toInt().coerceIn(0, dim - 1)
    }

    /** 转义 input text 中的特殊字符 */
    private fun escapeText(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "\\ ")
    }

    /** 返回可用于提示词的完整命令文档 */
    fun promptDoc(lang: String): String = when (lang) {
        "CN" -> """# 友好命令（command 字段使用）
| 命令 | 参数 | 说明 |
|------|------|------|
| tap | x y | 点击 |
| lp | x y | 长按 1500ms |
| dt | x y | 双击 |
| sw | x1 y1 x2 y2 | 滑动 |
| su | x y | 上滑 400px |
| sd | x y | 下滑 400px |
| sl | x y | 左滑 400px |
| sr | x y | 右滑 400px |
| key | BACK/HOME/ENTER/数字 | 按键 |
| back | — | 返回键 |
| home | — | 主页键 |
| recents | — | 最近任务 |
| text | "文字" | 输入文字 |
| am | 包名/.Activity | 启动 Activity |
| stop | 包名 | 停止应用 |
| dump | 包名 | 列出 Activity |
| launch | 包名 | 启动应用 |
| brightness | 0~255 | 屏幕亮度 |
| screenshot | — | 截图到 /sdcard |
| info | — | 当前 Activity |
| wifi_on/wifi_off | — | WiFi 开关 |
| clip | — | 剪贴板内容 |
| raw | 完整ADB命令 | 直接透传（兜底） |

示例: {"type":"shell","command":"tap 500 800","reasoning":"点击搜索框","expected":"搜索框获焦","confidence":0.9}

注意：command 若直接写裸包名（如 com.tencent.mm）会被自动补全为实际动作 launch（启动该应用）。"""
        else -> """# Friendly Commands (use in command field)
| Command | Args | Description |
|---------|------|-------------|
| tap | x y | Tap at coordinates |
| lp | x y | Long press 1500ms |
| dt | x y | Double tap |
| sw | x1 y1 x2 y2 | Swipe |
| su | x y | Swipe up 400px |
| sd | x y | Swipe down 400px |
| sl | x y | Swipe left 400px |
| sr | x y | Swipe right 400px |
| key | BACK/HOME/ENTER/number | Key press |
| back | — | Back key |
| home | — | Home key |
| recents | — | Recents key |
| text | "text" | Input text |
| am | pkg/.Activity | Start Activity |
| stop | pkg | Force stop app |
| dump | pkg | List activities |
| launch | pkg | Launch app |
| brightness | 0~255 | Screen brightness |
| screenshot | — | Screenshot to /sdcard |
| info | — | Current activity |
| wifi_on/wifi_off | — | WiFi toggle |
| clip | — | Clipboard content |
| raw | raw ADB command | Passthrough (fallback) |

Example: {"type":"shell","command":"tap 500 800","reasoning":"tap search box","expected":"search focused","confidence":0.9}

Note: if command is a bare package name (e.g. com.tencent.mm), it is auto-prefixed with the actual action `launch` (launches the app)."""
    }
}