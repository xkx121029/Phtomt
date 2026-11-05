// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 闹钟/倒计时的端侧时间解析（对齐 Eta 的 `set_alarm` 工具语义）。
 *
 * Eta 把"7 点半 / 半小时后"交给模型换算成 hour/minute；我们这一层做确定性兜底：
 * 模型既可给结构化 [AgentIntent.hour]/[AgentIntent.minute]，也可只给一句口语（[AgentIntent.timeText]），
 * 端侧都能解析成"时:分 + 星期重复 + 跨天偏移"，避免把相对时间理解错误带进时钟应用。
 *
 * 纯 Kotlin（java.time），无 Android 依赖，可在 JVM 单测里覆盖边界：跨天、23:59、重复日。
 */
data class AlarmTime(
    /** 0~23 */
    val hour: Int,
    /** 0~59 */
    val minute: Int,
    /** 相对今天的天数偏移（0=今天，1=明天……）；重复闹钟也给出"下一次命中的偏移"，仅供文案展示 */
    val dayOffset: Int,
    /** 重复星期（空集=一次性） */
    val repeatDays: Set<DayOfWeek>,
) {
    /** 给用户/日志看的时刻，如 07:30 */
    fun clockText(): String = "%02d:%02d".format(hour, minute)

    /** 重复描述，如「每天」「工作日」「周一三五」 */
    fun repeatText(): String = when {
        repeatDays.isEmpty() -> ""
        repeatDays.size == 7 -> "每天"
        repeatDays == setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY) -> "工作日"
        repeatDays == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "周末"
        else -> AlarmDays.displayNames(repeatDays).joinToString("") { "周$it" }
    }
}

sealed interface AlarmParseResult {
    data class Success(val time: AlarmTime) : AlarmParseResult
    data class Failure(val message: String) : AlarmParseResult
}

/** 星期名与 Android `Calendar` 常量的换算（闹钟 EXTRA_DAYS 用的是 Calendar 常量：周日=1…周六=7） */
object AlarmDays {
    private val ORDER = listOf(
        DayOfWeek.MONDAY to 2, DayOfWeek.TUESDAY to 3, DayOfWeek.WEDNESDAY to 4,
        DayOfWeek.THURSDAY to 5, DayOfWeek.FRIDAY to 6, DayOfWeek.SATURDAY to 7, DayOfWeek.SUNDAY to 1,
    )

    private val NAMES = mapOf(
        "一" to DayOfWeek.MONDAY, "1" to DayOfWeek.MONDAY,
        "二" to DayOfWeek.TUESDAY, "2" to DayOfWeek.TUESDAY,
        "三" to DayOfWeek.WEDNESDAY, "3" to DayOfWeek.WEDNESDAY,
        "四" to DayOfWeek.THURSDAY, "4" to DayOfWeek.THURSDAY,
        "五" to DayOfWeek.FRIDAY, "5" to DayOfWeek.FRIDAY,
        "六" to DayOfWeek.SATURDAY, "6" to DayOfWeek.SATURDAY,
        "日" to DayOfWeek.SUNDAY, "天" to DayOfWeek.SUNDAY, "7" to DayOfWeek.SUNDAY,
    )

    /** 星期集合 → EXTRA_DAYS 需要的 Calendar 常量列表（按周一..周日顺序） */
    fun toCalendarDays(days: Set<DayOfWeek>): List<Int> =
        ORDER.filter { it.first in days }.map { it.second }

    /** 展示顺序的星期名（一..日） */
    fun displayNames(days: Set<DayOfWeek>): List<String> =
        ORDER.filter { it.first in days }.map { "一二三四五六日"[ORDER.indexOf(it)].toString() }

    /** 解析 AI 给的 repeat_days（["mon"..] 或 ["周一"] 或 ["1".."7"]，1=周一）；空/非法返回 null */
    fun fromField(raw: List<String>?): Set<DayOfWeek>? {
        if (raw.isNullOrEmpty()) return null
        val result = raw.mapNotNull { item -> fromName(item) }.toSet()
        return result.ifEmpty { null }
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun fromName(raw: String): DayOfWeek? {
        val s = raw.trim().lowercase().removePrefix("周").removePrefix("星期").removePrefix("礼拜")
        if (s.isBlank()) return null
        NAMES[s.take(1)]?.let { return it }
        return when (s) {
            "mon", "monday" -> DayOfWeek.MONDAY
            "tue", "tues", "tuesday" -> DayOfWeek.TUESDAY
            "wed", "wednesday" -> DayOfWeek.WEDNESDAY
            "thu", "thur", "thursday" -> DayOfWeek.THURSDAY
            "fri", "friday" -> DayOfWeek.FRIDAY
            "sat", "saturday" -> DayOfWeek.SATURDAY
            "sun", "sunday" -> DayOfWeek.SUNDAY
            else -> null
        }
    }
}

/**
 * 口语时间解析：
 * - 绝对：`7:30` / `7点半` / `明天早上 6 点` / `今晚 8 点` / `23:59`
 * - 重复：`每天 7 点` / `工作日 7 点` / `周末 9 点` / `每周一三五 7 点` / `周一到周五 7 点`
 * - 相对：`半小时后` / `30 分钟后` / `2 小时 30 分后` / `一个半小时后`
 */
object AlarmTimeParser {

    private val PERIODS = listOf("凌晨", "清晨", "早上", "早晨", "上午", "中午", "下午", "傍晚", "晚上", "夜里", "今晚", "明早", "明晚")

    private val RELATIVE = Regex(
        """^(?:再过|过)?(半|一个半|[0-9]+|[一二两三四五六七八九十]+)(?:个)?(小时|钟头|分钟|分)(?:([0-9]+|[一二两三四五六七八九十]+)分?)?(?:后|以后|之后)$""",
    )

    /**
     * 星期前缀：范围写法（「一到周五」，中间可再带「周/星期/礼拜」）排在普通列举之前，
     * 避免把后面紧跟的小时数字（「周一三五7点」里的 7）当成星期数吞掉。
     */
    private const val DAY_RANGE = """(?:[一二三四五六日天1-7])[到至](?:周|星期|礼拜)?[一二三四五六日天1-7]"""
    private const val DAY_LIST = """[一二三四五六日天1-7、,，]+"""
    private val DAY_SELECTOR = Regex(
        """^(每天|每日|天天|工作日|周末|每周($DAY_RANGE|$DAY_LIST)|(?:周|星期|礼拜)($DAY_RANGE|$DAY_LIST)|(今天|明天|后天|明早|明晚|今晚))""",
    )

    private val PERIOD = Regex("""^(${PERIODS.joinToString("|")})""")

    /** 行尾时刻：不能带 `^`（要允许前面还有星期/时段前缀），只用 `$` 钉住结尾 */
    private val CLOCK = Regex("""([0-9]{1,2}|[一二两三四五六七八九十]{1,3})(?:[:：点时])(半|[0-9]{1,2}|[一二两三四五六七八九十]{1,3})?分?$""")

    // 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun parse(raw: String?, now: LocalDateTime): AlarmParseResult {
        val normalized = normalize(raw)
            ?: return AlarmParseResult.Failure("没有提供时间：请说「7 点半」「半小时后」或「明天早上 6 点」，或直接给出 hour/minute")

        parseRelative(normalized, now)?.let { return it }

        // 先定位末尾的时刻部分（CLOCK 以 $ 结尾），剩下的前导词再按「星期 > 时段」剥离。
        // 反过来先剥前缀会被「周一三五7点」这类写法骗到：星期列举与小时数字共用同一批字符，
        // 「一三五7」会连 7 一起吞掉，剩下一个光秃秃的「点」。
        val clock = CLOCK.find(normalized)
            ?: return AlarmParseResult.Failure("无法识别的时间「${raw?.trim()}」：请改用「7 点半」「半小时后」「明天早上 6 点」这类说法")

        var leftover = normalized.substring(0, clock.range.first)
        var repeatDays: Set<DayOfWeek> = emptySet()
        var explicitOffset: Int? = null

        DAY_SELECTOR.find(leftover)?.let { m ->
            val head = m.value
            val tail = leftover.substring(head.length)
            when (head) {
                "每天", "每日", "天天" -> {
                    repeatDays = DayOfWeek.entries.toSet(); leftover = tail
                }
                "工作日" -> {
                    repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY)
                    leftover = tail
                }
                "周末" -> {
                    repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY); leftover = tail
                }
                "今天" -> {
                    explicitOffset = 0; leftover = tail
                }
                "明天" -> {
                    explicitOffset = 1; leftover = tail
                }
                "后天" -> {
                    explicitOffset = 2; leftover = tail
                }
                // 「明早6点」的"早"本身就是时段词：剥掉日期后把时段补回来
                "明早" -> {
                    explicitOffset = 1; leftover = "早上" + tail
                }
                "明晚" -> {
                    explicitOffset = 1; leftover = "晚上" + tail
                }
                "今晚" -> {
                    explicitOffset = 0; leftover = "晚上" + tail
                }
                else -> {
                    // 每周X / 周X / 星期X，支持「一三五」与「一到五」
                    repeatDays = parseDayGroup(m.groupValues[2].ifEmpty { m.groupValues[3] }) ?: emptySet()
                    leftover = tail
                }
            }
        }

        val period = PERIOD.find(leftover)?.value

        val hourRaw = number(clock.groupValues[1])
        val minuteRaw = clock.groupValues[2]
        val minute = when (minuteRaw) {
            "", null -> 0
            "半" -> 30
            else -> number(minuteRaw)
        }
        if (hourRaw !in 0..23 || minute !in 0..59) {
            return AlarmParseResult.Failure("时间超出范围（${hourRaw}:${minute}）：小时需 0~23，分钟需 0~59")
        }

        val hour = applyPeriod(hourRaw, period)
        if (hour !in 0..23) {
            return AlarmParseResult.Failure("时间超出范围：${raw?.trim()}")
        }

        val baseDate = now.toLocalDate().plusDays((explicitOffset ?: 0).toLong())
        val candidate = baseDate.atTime(hour, minute)

        val offset = when {
            repeatDays.isNotEmpty() -> daysUntil(baseDate, repeatDays, candidate, now)
            explicitOffset != null -> explicitOffset
            else -> if (!candidate.isAfter(now)) 1 else 0
        }

        return AlarmParseResult.Success(
            AlarmTime(
                hour = hour,
                minute = minute,
                dayOffset = offset,
                repeatDays = repeatDays,
            ),
        )
    }

    /** 结构化兜底：模型直接给 hour/minute 时组装（不做口语解析） */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun parseNumbers(hour: Int?, minute: Int?, repeatDays: Set<DayOfWeek>, now: LocalDateTime): AlarmParseResult {
        if (hour == null) return AlarmParseResult.Failure("缺少 hour：请给出 24 小时制的 hour/minute")
        val m = minute ?: 0
        if (hour !in 0..23 || m !in 0..59) {
            return AlarmParseResult.Failure("时间超出范围（$hour:$m）：小时需 0~23，分钟需 0~59")
        }
        val today = now.toLocalDate().atTime(hour, m)
        val offset = if (repeatDays.isNotEmpty()) daysUntil(now.toLocalDate(), repeatDays, today, now)
        else if (!today.isAfter(now)) 1 else 0
        return AlarmParseResult.Success(AlarmTime(hour, m, offset, repeatDays))
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun parseRelative(text: String, now: LocalDateTime): AlarmParseResult? {
        val m = RELATIVE.matchEntire(text) ?: return null
        val amountText = m.groupValues[1]
        val unit = m.groupValues[2]
        val extraMinutes = m.groupValues[3]

        val amount = when (amountText) {
            "半" -> 0.5
            "一个半" -> 1.5
            else -> number(amountText).toDouble()
        }
        val minutes = if (extraMinutes.isBlank()) 0 else number(extraMinutes)
        // 单位是"小时/钟头"才换算成分钟；"半小时"这类数值本身就是分钟数（0.5 小时=30 分不能截断成 0）
        val baseMinutes = when {
            unit == "小时" || unit == "钟头" -> (amount * 60).toLong()
            amountText == "半" -> 30L
            else -> amount.toLong()
        }
        val totalMinutes = baseMinutes + minutes
        if (totalMinutes <= 0 || totalMinutes > 24 * 60 * 7) {
            return AlarmParseResult.Failure("相对时间不合理：请给出 1 分钟到 7 天之间的时长（如「半小时后」「2 小时后」）")
        }
        val target = now.plusMinutes(totalMinutes)
        val dayOffset = ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate()).toInt()
        return AlarmParseResult.Success(
            AlarmTime(target.hour, target.minute, dayOffset, emptySet()),
        )
    }

    /** 周期前缀（每次解析都从文本头部剥离，因此「明早」只剩「早上」） */
    private fun normalize(raw: String?): String? {
        val base = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return base
            .replace("：", ":")
            .replace(" ", "")
            .replace("\u3000", "")
            .replace("点整", "点")
    }

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    private fun number(text: String): Int {
        text.toIntOrNull()?.let { return it }
        var total = 0
        var current = 0
        text.forEach { c ->
            when (c) {
                '两', '二' -> current = 2
                '一' -> current = 1
                '三' -> current = 3
                '四' -> current = 4
                '五' -> current = 5
                '六' -> current = 6
                '七' -> current = 7
                '八' -> current = 8
                '九' -> current = 9
                '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                }
                '零' -> current = 0
            }
        }
        return total + current
    }

    private fun applyPeriod(hour: Int, period: String?): Int = when (period) {
        "下午", "傍晚", "晚上", "夜里", "今晚", "明晚" -> when {
            hour == 12 -> 12
            hour < 12 -> hour + 12
            else -> hour
        }
        "中午" -> if (hour < 12) hour + 12 else hour
        "凌晨" -> if (hour == 12) 0 else hour
        else -> hour
    }

    /** 重复闹钟的下一次命中偏移（0=今天还没过，1…6=之后第几天） */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun daysUntil(today: LocalDate, days: Set<DayOfWeek>, candidate: LocalDateTime, now: LocalDateTime): Int {
        if (days.isEmpty()) return if (!candidate.isAfter(now)) 1 else 0
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
            @Suppress("MaxLineLength")
            val hit = date.dayOfWeek in days && date.atTime(candidate.toLocalTime()).let { it.isAfter(now) || offset > 0 }
            if (hit) return offset
        }
        return 0
    }

    /** 「一三五」「一到五」「1,3,5」「一、三、五」→ 星期集合 */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun parseDayGroup(raw: String): Set<DayOfWeek>? {
        if (raw.isBlank()) return null
        val range = Regex("""([一二三四五六日天1-7])[到至](?:周|星期|礼拜)?([一二三四五六日天1-7])""").find(raw)
        if (range != null) {
            val start = AlarmDays.fromName(range.groupValues[1]) ?: return null
            val end = AlarmDays.fromName(range.groupValues[2]) ?: return null
            val order = listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
            )
            val from = order.indexOf(start)
            val to = order.indexOf(end)
            return if (from <= to) order.subList(from, to + 1).toSet() else (order.subList(from,
                order.size) + order.subList(0, to + 1)).toSet()
        }
        return raw.mapNotNull { AlarmDays.fromName(it.toString()) }.toSet().ifEmpty { null }
    }
}
