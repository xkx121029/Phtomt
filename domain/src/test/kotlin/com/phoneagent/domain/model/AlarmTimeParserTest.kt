package com.phoneagent.domain.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闹钟口语时间解析的边界测试（纯 JVM，不依赖 Android）。
 *
 * 覆盖用户真实会说的说法：绝对时刻、口语半点、相对时长、明天/今晚、重复日，
 * 以及最容易出错的跨天判定（时刻已过 → 顺延一天；23:59 边界；重复日的下一次命中偏移）。
 */
class AlarmTimeParserTest {

    private fun parse(text: String, now: String): AlarmParseResult =
        AlarmTimeParser.parse(text, LocalDateTime.parse(now))

    private fun success(text: String, now: String): AlarmTime =
        (parse(text, now) as AlarmParseResult.Success).time

    private fun failure(text: String, now: String): String =
        (parse(text, now) as AlarmParseResult.Failure).message

    // ---- 绝对时刻 ----

    @Test
    fun 冒号格式_今天未过_不跨天() {
        val t = success("7:30", "2026-11-03T07:00:00")
        assertEquals(7, t.hour)
        assertEquals(30, t.minute)
        assertEquals(0, t.dayOffset)
        assertTrue(t.repeatDays.isEmpty())
    }

    @Test
    fun 冒号格式_今天已过_顺延一天() {
        val t = success("7:30", "2026-11-03T07:35:00")
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 半点口语_七点半() {
        val t = success("7点半", "2026-11-03T06:00:00")
        assertEquals(7, t.hour)
        assertEquals(30, t.minute)
    }

    @Test
    fun 整点口语_七点整() {
        val t = success("7点整", "2026-11-03T06:00:00")
        assertEquals(0, t.minute)
    }

    @Test
    fun 汉字数字_七点二十() {
        val t = success("七点二十", "2026-11-03T06:00:00")
        assertEquals(7, t.hour)
        assertEquals(20, t.minute)
    }

    @Test
    fun 时段前缀_晚上八点转为20点() {
        val t = success("晚上8点", "2026-11-03T06:00:00")
        assertEquals(20, t.hour)
    }

    @Test
    fun 时段前缀_中午十二点不额外加12() {
        val t = success("中午12点", "2026-11-03T06:00:00")
        assertEquals(12, t.hour)
    }

    @Test
    fun 时段前缀_凌晨十二点归零() {
        val t = success("凌晨12点", "2026-11-03T06:00:00")
        assertEquals(0, t.hour)
    }

    // ---- 跨天 ----

    @Test
    fun 明天早上六点_固定偏移一天() {
        val t = success("明天早上6点", "2026-11-03T23:00:00")
        assertEquals(6, t.hour)
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 明早六点_短写法同样偏移一天() {
        val t = success("明早6点", "2026-11-03T23:00:00")
        assertEquals(6, t.hour)
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 今晚八点_已过时刻仍按今天提交() {
        // 用户明确说"今晚"，不再自作主张顺延；AlarmClock 本身会按下一次命中排期
        val t = success("今晚8点", "2026-11-03T21:00:00")
        assertEquals(20, t.hour)
        assertEquals(0, t.dayOffset)
    }

    @Test
    fun 二十三时五十九分_当天未过不跨天() {
        val t = success("23:59", "2026-11-03T00:00:00")
        assertEquals(23, t.hour)
        assertEquals(59, t.minute)
        assertEquals(0, t.dayOffset)
    }

    @Test
    fun 二十三时五十九分_同一分钟已过则跨天() {
        // 23:59:30 时再说"23:59"，今天这一分钟已经过去，顺延到明天
        val t = success("23:59", "2026-11-03T23:59:30")
        assertEquals(1, t.dayOffset)
    }

    // ---- 相对时长 ----

    @Test
    fun 半小时后_不跨天() {
        val t = success("半小时后", "2026-11-03T07:00:00")
        assertEquals(7, t.hour)
        assertEquals(30, t.minute)
        assertEquals(0, t.dayOffset)
    }

    @Test
    fun 半小时后_跨午夜顺延() {
        val t = success("半小时后", "2026-11-03T23:40:00")
        assertEquals(0, t.hour)
        assertEquals(10, t.minute)
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 三十分钟后_与半小时等价() {
        val t = success("30分钟后", "2026-11-03T07:00:00")
        assertEquals(7, t.hour)
        assertEquals(30, t.minute)
    }

    @Test
    fun 两小时三十分后() {
        val t = success("2小时30分后", "2026-11-03T07:00:00")
        assertEquals(9, t.hour)
        assertEquals(30, t.minute)
    }

    @Test
    fun 一个半小时后() {
        val t = success("一个半小时后", "2026-11-03T07:00:00")
        assertEquals(8, t.hour)
        assertEquals(30, t.minute)
    }

    @Test
    fun 相对时长不合理_拒绝() {
        assertTrue(failure("0分钟后", "2026-11-03T07:00:00").contains("不合理"))
    }

    // ---- 重复日 ----

    @Test
    fun 每天七点_七天全选() {
        val t = success("每天7点", "2026-11-03T06:00:00")
        assertEquals(7, t.hour)
        assertEquals(DayOfWeek.entries.toSet(), t.repeatDays)
        assertEquals("每天", t.repeatText())
    }

    @Test
    fun 工作日七点_周一到周五() {
        val t = success("工作日7点", "2026-11-03T06:00:00") // 2026-11-03 是周二
        assertEquals(0, t.dayOffset)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            t.repeatDays,
        )
        assertEquals("工作日", t.repeatText())
    }

    @Test
    fun 周末九点_周六周日() {
        val t = success("周末9点", "2026-11-03T06:00:00")
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), t.repeatDays)
    }

    @Test
    fun 每周一三五七点_命中下一工作日() {
        val t = success("每周一三五7点", "2026-11-03T06:00:00") // 周二
        assertEquals(1, t.dayOffset) // 下一个命中是周三
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), t.repeatDays)
    }

    @Test
    fun 周一到周五七点_范围写法() {
        val t = success("周一到周五7点", "2026-11-03T06:00:00")
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            t.repeatDays,
        )
    }

    @Test
    fun 重复日全不命中_偏移算到下一次() {
        // 只选周日的闹钟，今天周二：下一次是周日（偏移 5）
        val t = success("每周日7点", "2026-11-03T06:00:00")
        assertEquals(setOf(DayOfWeek.SUNDAY), t.repeatDays)
        assertEquals(5, t.dayOffset)
    }

    // ---- 相对日期（明早/今晚/明天下午/三天后） ----

    @Test
    fun 明早七点_落在明天而不是今天() {
        val t = success("明早七点", "2026-11-03T18:00:00")
        assertEquals(7, t.hour)
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 明早七点_带冒号写法同样落在明天() {
        val t = success("明早7:00", "2026-11-03T18:00:00")
        assertEquals(7, t.hour)
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 今晚八点_按今天二十点换算() {
        val t = success("今晚八点", "2026-11-03T10:00:00")
        assertEquals(20, t.hour)
        assertEquals(0, t.dayOffset)
    }

    @Test
    fun 明天下午三点_时段加十二且偏移一天() {
        val t = success("明天下午三点", "2026-11-03T10:00:00")
        assertEquals(15, t.hour)
        assertEquals(1, t.dayOffset)
    }

    @Test
    fun 三天后早上七点_偏移三天() {
        val t = success("三天后早上七点", "2026-11-03T10:00:00")
        assertEquals(7, t.hour)
        assertEquals(3, t.dayOffset)
    }

    @Test
    fun 大后天七点_偏移三天() {
        val t = success("大后天7点", "2026-11-03T10:00:00")
        assertEquals(7, t.hour)
        assertEquals(3, t.dayOffset)
    }

    @Test
    fun 只说三天后没给时刻_明确拒绝而不是猜一个时间() {
        val msg = failure("三天后", "2026-11-03T10:00:00")
        assertTrue(msg.contains("还缺具体时刻"))
    }

    @Test
    fun 目标时刻_把日期换算成绝对时间戳() {
        val now = LocalDateTime.parse("2026-11-03T18:00:00")
        val t = success("明早7点", "2026-11-03T18:00:00")
        val back = java.time.Instant.ofEpochMilli(t.targetEpochMillis(now)!!)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(java.time.LocalDate.parse("2026-11-04"), back.toLocalDate())
        assertEquals(7, back.hour)
    }

    @Test
    fun 重复闹钟_没有固定日期_目标时刻为空() {
        val now = LocalDateTime.parse("2026-11-03T18:00:00")
        assertNull(success("每天7点", "2026-11-03T18:00:00").targetEpochMillis(now))
    }

    // ---- 结构化兜底 ----

    @Test
    fun 结构化_hour_minute直接组装() {
        val t = (AlarmTimeParser.parseNumbers(7, 30, emptySet(),
            LocalDateTime.parse("2026-11-03T06:00:00")) as AlarmParseResult.Success).time
        assertEquals(7, t.hour)
        assertEquals(30, t.minute)
        assertEquals(0, t.dayOffset)
    }

    @Test
    fun 结构化_小时缺省返回失败() {
        val r = AlarmTimeParser.parseNumbers(null, 30, emptySet(), LocalDateTime.parse("2026-11-03T06:00:00"))
        assertTrue(r is AlarmParseResult.Failure)
    }

    // ---- 非法输入 ----

    @Test
    fun 超出范围_二十四点拒绝() {
        assertTrue(failure("24:00", "2026-11-03T06:00:00").contains("超出范围"))
    }

    @Test
    fun 超出范围_六十分拒绝() {
        assertTrue(failure("7:60", "2026-11-03T06:00:00").contains("超出范围"))
    }

    @Test
    fun 无法识别_给出可执行说法() {
        val msg = failure("随便什么时候", "2026-11-03T06:00:00")
        assertTrue(msg.contains("无法识别"))
        assertTrue(msg.contains("7 点半") || msg.contains("半小时后"))
    }

    @Test
    fun 空字符串_缺时间() {
        val msg = failure("   ", "2026-11-03T06:00:00")
        assertTrue(msg.contains("没有提供时间"))
    }

    // ---- 星期换算 ----

    @Test
    fun 星期到Calendar常量_周日为一() {
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 1), AlarmDays.toCalendarDays(DayOfWeek.entries.toSet()))
        assertEquals(listOf(2), AlarmDays.toCalendarDays(setOf(DayOfWeek.MONDAY)))
        assertEquals(listOf(1), AlarmDays.toCalendarDays(setOf(DayOfWeek.SUNDAY)))
    }

    @Test
    fun 星期字段解析_多种写法() {
        assertEquals(setOf(DayOfWeek.MONDAY), AlarmDays.fromField(listOf("mon")))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), AlarmDays.fromField(listOf("周一", "3")))
        assertNull(AlarmDays.fromField(listOf("sun?")))
        assertNull(AlarmDays.fromField(emptyList()))
    }

    @Test
    fun 星期名解析_英文缩写() {
        assertEquals(DayOfWeek.MONDAY, AlarmDays.fromName("mon"))
        assertEquals(DayOfWeek.TUESDAY, AlarmDays.fromName("tuesday"))
        assertEquals(DayOfWeek.SUNDAY, AlarmDays.fromName("星期天"))
        assertNull(AlarmDays.fromName("xx"))
    }
}
