@file:Suppress("MagicNumber")
package com.phoneagent.device.ops

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日历事件读写能力（`calendar_op` 族）。
 *
 * 从 [DeviceOpsChannel] 拆出：与闹钟同一个思路——日历 Provider 的读写权限只有系统/日历应用持有，
 * 以本应用身份直接调 ContentResolver 会被拒，必须以注入通道（shell）身份执行 `content` 命令。
 *
 * 三条安全约束都集中在这里：
 * 1. 没有注入通道时不假装成功，直接给中文原因 + 替代路线（[noChannel]）；
 * 2. 写入/更新/删除后必须回查，命令有回执不等于数据真的变了；
 * 3. 时间解析只到分钟精度，并列出支持的写法，减少模型"猜格式"的负担。
 */
internal class CalendarOps(
    private val shell: suspend (String) -> String?,
    /** 注入通道不可用时的人话诊断（由引擎注入，说明卡在装组件/起 Shizuku/授权的哪一环） */
    private val channelAdvice: () -> String,
) {

    private val results = OpsResults

    /**
     * 注入通道缺口的失败文案：保留原句（含替代路线），再追加端侧实测的通道诊断。
     * 诊断为空时不加括号，保持与旧文案完全一致（不改变无引擎装配场景下的表现）。
     */
    private fun noChannel(prefix: String): OpsResult {
        val advice = runCatching { channelAdvice() }.getOrDefault("").trim()
        return results.fail(prefix + if (advice.isNotEmpty()) "（$advice）" else "")
    }

    suspend fun run(op: String, args: Map<String, String>): OpsResult = when (op) {
        "list" -> list(args)
        "create" -> create(args)
        "update" -> update(args)
        "delete" -> delete(args)
        else -> results.fail("calendar_op 只支持 list/create/update/delete")
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "MaxLineLength")
    private suspend fun list(args: Map<String, String>): OpsResult {
        val days = (OpsArgs.long(args, "days") ?: 30L).coerceIn(1L, 365L)
        val limit = (OpsArgs.int(args, "limit") ?: 20).coerceIn(1, 100)
        val query = args["query"]?.trim().orEmpty()
        val now = System.currentTimeMillis()
        val until = now + days * 24 * 3600_000L
        val out = shell(
            "content query --uri $CALENDAR_URI --projection _id:title:dtstart:dtend " +
                "--where ${ShellQuote.of("dtstart>=$now AND dtstart<=$until")} --sort ${ShellQuote.of("dtstart ASC")}",
        ) ?: return noChannel(
            "读日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动查看。",
        )
        val rows = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("No result") && !it.startsWith("Error") }
            .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .take(limit)
            .map { formatRow(it) }
            .toList()
        if (rows.isEmpty()) {
            return results.ok("未来 $days 天没有${if (query.isEmpty()) "" else "标题含「$query」的"}日程。")
        }
        return results.ok("未来 $days 天的日程（${rows.size} 条）：\n" + rows.joinToString("\n") { "- $it" }.take(4000))
    }

    /** 把 `Row: 0 _id=.., title=..` 这类原始行压成「时间 · 标题 · id」 */
    private fun formatRow(line: String): String {
        fun field(key: String): String? =
            Regex("$key=([^,]+)").find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val id = field("_id") ?: "?"
        val title = field("title") ?: "（无标题）"
        val start = field("dtstart")?.toLongOrNull()?.let { formatTime(it) } ?: "时间未知"
        val end = field("dtend")?.toLongOrNull()?.let { formatTime(it) }
        return "$start${if (end != null) " ~ $end" else ""} · $title（id=$id）"
    }

    fun formatTime(ms: Long): String = CALENDAR_TIME.format(Date(ms))

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun create(args: Map<String, String>): OpsResult {
        val title = args["title"]?.trim().orEmpty()
        if (title.isEmpty()) return results.fail("calendar_op create 需要 title（事件标题）")
        val startText = args["start"]?.trim().orEmpty()
        if (startText.isEmpty()) return results.fail("calendar_op create 需要 start（如「2026-11-05 09:00」）")
        val startMs = parseTime(startText)
            ?: return results.fail("读不懂 start=$startText；请写成「YYYY-MM-DD HH:mm」或毫秒时间戳")
        val endMs = args["end"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            parseTime(text) ?: return results.fail("读不懂 end=$text；请写成「YYYY-MM-DD HH:mm」或毫秒时间戳")
        } ?: (startMs + (OpsArgs.long(args, "duration_min") ?: 60L) * 60_000L)
        val calendarId = OpsArgs.long(args, "calendar_id") ?: 1L
        val allDay = OpsArgs.bool(args, "all_day") ?: false
        val binds = buildList {
            add("calendar_id:i:$calendarId")
            add("title:s:${ShellQuote.of(title)}")
            add("dtstart:l:$startMs")
            add("dtend:l:$endMs")
            add("allDay:i:${if (allDay) 1 else 0}")
            add("eventTimezone:s:${ShellQuote.of(java.util.TimeZone.getDefault().id)}")
        }.joinToString(" --bind ")
        val out = shell("content insert --uri $CALENDAR_URI --bind $binds")
            ?: return noChannel("写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动新建。")
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return results.fail("日历 Provider 拒绝写入：${out.take(200)}。可改走 open_app 打开日历应用手动新建。")
        }
        // 写入命令有回执不等于事件真的在库里：回查同名同开始时间的那条
        val row = findEvent("title=${ShellQuote.of(title)} AND dtstart=$startMs")
            ?: return results.unconfirmed(
                "日历写入命令已执行，但回查不到「$title」这条日程，无法确认是否真的写入。" +
                    "请打开日历应用看一眼当天有没有这条；也可以先用 calendar_op list 确认。",
            )
        val newId = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1)
        return results.ok(
            "已新建日程「$title」：${formatTime(startMs)} ~ ${formatTime(endMs)}" +
                "（calendar_id=$calendarId，回查 id=${newId ?: "?"}）",
        )
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "MaxLineLength")
    private suspend fun update(args: Map<String, String>): OpsResult {
        val id = args["event_id"]?.trim().orEmpty()
        if (id.isEmpty()) return results.fail("calendar_op update 需要 event_id（从 calendar_op list 结果里取）")
        val binds = buildList {
            args["title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add("title:s:${ShellQuote.of(it)}") }
            args["start"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val ms = parseTime(text) ?: return results.fail("读不懂 start=$text；请写成「YYYY-MM-DD HH:mm」")
                add("dtstart:l:$ms")
            }
            args["end"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val ms = parseTime(text) ?: return results.fail("读不懂 end=$text；请写成「YYYY-MM-DD HH:mm」")
                add("dtend:l:$ms")
            }
            OpsArgs.bool(args, "all_day")?.let { add("allDay:i:${if (it) 1 else 0}") }
        }
        if (binds.isEmpty()) {
            return results.fail("calendar_op update 至少要给一个要改的字段（title/start/end/all_day）")
        }
        // 记录「改完之后应该读到什么」，用于写后回查
        val expects = buildList {
            args["title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add("title=$it") }
            args["start"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                parseTime(text)?.let { add("dtstart=$it") }
            }
            OpsArgs.bool(args, "all_day")?.let { add("allDay=${if (it) 1 else 0}") }
        }
        val out = shell(
            "content update --uri $CALENDAR_URI --where ${ShellQuote.of("_id=$id")} " +
                "--bind ${binds.joinToString(" --bind ")}",
        ) ?: return noChannel(
            "写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动改这条日程。",
        )
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return results.fail("没有找到 id=$id 的日程，或日历 Provider 拒绝写入。先用 calendar_op list 确认 id。")
        }
        // 命令有回执不等于字段改了：按 _id 回查，逐个核对本次提交的值
        val row = findEvent("_id=$id")
            ?: return results.unconfirmed(
                "更新命令已执行，但回查不到 id=$id 的日程，无法确认是否生效。" +
                    "若列表里也没有这条，说明它已被删除；否则请打开日历应用手动改一次。",
            )
        val missing = expects.filterNot { row.contains(it) }
        return if (missing.isEmpty()) {
            results.ok("已更新日程 id=$id（回查 ${expects.joinToString("、")} 均已生效）")
        } else {
            results.unconfirmed(
                "日程已改动，但回查 ${missing.joinToString("、")} 与提交值不一致，可能被日历应用拒绝。" +
                    "请打开日历应用核对该日程。",
            )
        }
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun delete(args: Map<String, String>): OpsResult {
        val id = args["event_id"]?.trim().orEmpty()
        if (id.isEmpty()) return results.fail("calendar_op delete 需要 event_id（从 calendar_op list 结果里取）")
        val out = shell("content delete --uri $CALENDAR_URI --where ${ShellQuote.of("_id=$id")}")
            ?: return noChannel(
                "删日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动删除这条日程。",
            )
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return results.fail("没有找到 id=$id 的日程，或日历 Provider 拒绝删除。先用 calendar_op list 确认 id。")
        }
        // 删除同样要看结果：按 _id 再查一次，还能查到就是没删掉
        if (findEvent("_id=$id") != null) {
            return results.unconfirmed(
                "删除命令已执行，但回查 id=$id 的日程还在，删除没有生效。请打开日历应用手动删除这条。",
            )
        }
        return results.ok("已删除日程 id=$id（回查已不存在）")
    }

    /** 回查单条日历事件：命中返回原始行；读不到或不存在返回 null */
    // 早退让「读不到 / Provider 报错 / 没有匹配行」三种空结果各自直白返回
    @Suppress("ReturnCount")
    private suspend fun findEvent(where: String): String? {
        val out = shell(
            "content query --uri $CALENDAR_URI --projection _id:title:dtstart:dtend:allDay " +
                "--where ${ShellQuote.of(where)}",
        ) ?: return null
        if (out.contains("Error", ignoreCase = true)) return null
        return out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("_id=") && !it.startsWith("No result") }
    }

    /**
     * 时间解析：纯数字按毫秒时间戳；否则依次尝试几种常见写法。
     * 只到分钟精度——日历事件的秒级误差没有意义，少给模型一种"猜格式"的负担。
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun parseTime(raw: String): Long? {
        val text = raw.trim()
        text.toLongOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "MM-dd HH:mm", "M月d日 HH:mm", "M月d日",
        )
        for (p in patterns) {
            val fmt = SimpleDateFormat(p, Locale.getDefault()).apply { isLenient = false }
            val parsed = runCatching { fmt.parse(text) }.getOrNull() ?: continue
            if (parsed != null) return parsed.time
        }
        // 只给了「HH:mm」：按今天算，已过去则顺延到明天
        runCatching {
            SimpleDateFormat("HH:mm", Locale.getDefault()).apply { isLenient = false }.parse(text)
        }.getOrNull()?.let { t ->
            val cal = Calendar.getInstance().apply {
                time = t
                val now = Calendar.getInstance()
                set(Calendar.YEAR, now.get(Calendar.YEAR))
                set(Calendar.DAY_OF_YEAR, now.get(Calendar.DAY_OF_YEAR))
                if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
        return null
    }

    private companion object {
        /** 日历事件 Provider（AOSP / 绝大多数厂商共用这一份 authority） */
        private const val CALENDAR_URI = "content://com.android.calendar/events"

        /** 日历结果里的时间展示格式：到分钟即可，秒级没有意义 */
        private val CALENDAR_TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
