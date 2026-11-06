// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.device.clock

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 系统时钟直达通道（对齐 Eta 的 `set_alarm` / `set_timer` 工具，不经过语音助手、不模拟点击）。
 *
 * Eta 的做法（`AgentStructuredDeviceTools.dispatchClockIntent`）：
 * 1. 先 `AlarmClock.ACTION_SET_ALARM`（HOUR/MINUTES/SKIP_UI/VIBRATE/MESSAGE，重复日走
 *    `putIntegerArrayListExtra(EXTRA_DAYS, Calendar 常量)`），先按厂商时钟包名显式解析，再退回隐式解析；
 * 2. 直接 `startActivity`，成功只记 `status=dispatched, verified=false`——无 root 时读不到时钟 App 的闹钟库，
 *    所以不谎称"已验证"；
 * 3. 任何一步解析不到 → `CLOCK_UNAVAILABLE`「没有可处理该请求的时钟应用」。
 *
 * 这里保持同样的语义，但去掉它的 `ACTION_SHOW_ALARMS` UI 兜底（我们不做"打开页面让用户手点"的降级）：
 * 解析不到就如实失败并给出可执行替代路径。
 */
object ClockPackages {
    /**
     * 优先显式尝试的时钟包名（顺序=优先级）。
     * 覆盖 ColorOS/OPPO、MIUI、鸿蒙/荣耀、AOSP、Google 桌面时钟。
     */
    val PREFERRED: List<String> = listOf(
        "com.oplus.alarmclock",
        "com.coloros.alarmclock",
        "com.miui.clock",
        "com.huawei.clock",
        "com.hihonor.clock",
        "com.google.android.deskclock",
        "com.android.deskclock",
    )

    /** 失败文案统一在这里定义（执行层与单测共用，避免两处漂移） */
    const val NO_CLOCK_MESSAGE: String =
        "没有可用的时钟应用：系统里没有能处理定闹钟/倒计时的时钟 App。" +
            "请先安装或启用系统时钟应用；也可以改用 open_app 打开时钟应用手动设置"
}

/** 候选解析结果挑选（纯函数，便于"无时钟应用"场景的单测） */
object ClockTargetPicker {
    /** @param candidates (包名, 是否能解析到处理该 action 的 Activity)，按优先级排列 */
    fun pick(candidates: List<Pair<String, Boolean>>): String? =
        candidates.firstOrNull { it.second }?.first
}

/** 一次时钟请求的结构化描述（纯数据，测试不依赖 Android Intent） */
data class ClockRequest(
    val action: String,
    val hour: Int? = null,
    val minute: Int? = null,
    /** Calendar 常量（周日=1…周六=7） */
    val repeatDays: List<Int>? = null,
    val seconds: Long? = null,
    val label: String? = null,
    /**
     * 目标触发时刻（毫秒）。null = 按「HH:mm 的下一次」排期。
     *
     * 「明天下午三点」在上午说出时，"下一次 15:00"其实是今天——必须把目标日期带进来，
     * 否则闹钟会设在错误的那一天。已过去的时刻按 null 处理（下一次命中）。
     */
    val atMillis: Long? = null,
)

/** 时钟请求 → AlarmClock Intent 的字段映射（纯函数） */
object ClockRequests {
    fun alarm(
        hour: Int,
        minute: Int,
        repeatDays: List<Int>?,
        label: String?,
        atMillis: Long? = null,
    ): ClockRequest =
        ClockRequest(
            action = AlarmClock.ACTION_SET_ALARM,
            hour = hour,
            minute = minute,
            repeatDays = repeatDays?.takeIf { it.isNotEmpty() },
            label = label?.trim()?.takeIf { it.isNotBlank() },
            atMillis = atMillis,
        )

    fun timer(seconds: Long, label: String?): ClockRequest =
        ClockRequest(
            action = AlarmClock.ACTION_SET_TIMER,
            seconds = seconds,
            label = label?.trim()?.takeIf { it.isNotBlank() },
        )
}

/** 时钟通道执行结果 */
sealed interface ClockResult {
    /** 已派发；[verified] 恒为 false（系统未回执，需用户在时钟应用里确认） */
    data class Ok(val message: String, val verified: Boolean = false) : ClockResult

    data class Failure(val reason: String) : ClockResult
}

/**
 * Android 侧落地：解析 → 一次 startActivity → 如实回报（不重试、不换目标乱猜）。
 */
class ClockChannel(private val context: Context) {

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun setAlarm(hour: Int?, minute: Int?, repeatDays: List<Int>?, label: String?): ClockResult {
        if (hour == null || minute == null) {
            return ClockResult.Failure("缺少闹钟时间：请给出 hour/minute，或一句可识别的时间（如「7点半」）")
        }
        if (hour !in 0..23 || minute !in 0..59) {
            return ClockResult.Failure("闹钟时间超出范围（$hour:$minute）：小时需 0~23，分钟需 0~59")
        }
        val req = ClockRequests.alarm(hour, minute, repeatDays, label)
        val clock = "%02d:%02d".format(hour, minute)
        val repeat = repeatText(repeatDays)
        val message = "已向系统时钟提交闹钟 $clock$repeat（系统未回执，请在时钟应用的闹钟列表确认）"
        return dispatch(req, message)
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    fun setTimer(seconds: Long?, label: String?): ClockResult {
        if (seconds == null || seconds <= 0L) {
            return ClockResult.Failure("倒计时时长无效：duration_seconds 需大于 0（如 1800 = 30 分钟）")
        }
        if (seconds > 24 * 3600L) {
            return ClockResult.Failure("倒计时时长过长（$seconds 秒）：请控制在 24 小时以内")
        }
        val req = ClockRequests.timer(seconds, label)
        val message = "已向系统时钟提交倒计时 ${formatDuration(seconds)}（系统未回执，请在时钟应用的计时器列表确认）"
        return dispatch(req, message)
    }

    private fun dispatch(req: ClockRequest, okMessage: String): ClockResult {
        val pm = context.packageManager
        val base = buildIntent(req)

        // 先按厂商时钟包名显式解析（Eta 先试 ColorOS，再退回隐式），命中即用，不枚举第三方
        val resolvedPackage = ClockTargetPicker.pick(
            ClockPackages.PREFERRED.map { pkg ->
                pkg to runCatching {
                    pm.resolveActivity(Intent(base).setPackage(pkg), 0) != null
                }.getOrDefault(false)
            },
        )
        val direct = when {
            resolvedPackage != null -> Intent(base).setPackage(resolvedPackage)
            runCatching { pm.resolveActivity(base, 0) != null }.getOrDefault(false) -> base
            else -> return ClockResult.Failure(ClockPackages.NO_CLOCK_MESSAGE)
        }

        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(direct)
            ClockResult.Ok(okMessage)
        }.getOrElse { e ->
            ClockResult.Failure(
                "打开时钟应用失败（${direct.`package` ?: "系统默认"}）：${e.message ?: e.javaClass.simpleName}。" +
                    "可能被系统的后台启动限制拦截，请重试或手动在时钟应用里设置",
            )
        }
    }

    private fun buildIntent(req: ClockRequest): Intent {
        val intent = Intent(req.action)
        when (req.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                intent.putExtra(AlarmClock.EXTRA_HOUR, req.hour ?: 0)
                intent.putExtra(AlarmClock.EXTRA_MINUTES, req.minute ?: 0)
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                intent.putExtra(AlarmClock.EXTRA_VIBRATE, true)
                req.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                req.repeatDays?.let { days ->
                    // AlarmClock 契约要求 ArrayList<Integer>（Calendar 常量），传 IntArray 部分时钟会忽略
                    intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            else -> {
                intent.putExtra(AlarmClock.EXTRA_LENGTH, (req.seconds ?: 0L).toInt())
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                req.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            }
        }
        return intent
    }

    private fun repeatText(calendarDays: List<Int>?): String {
        if (calendarDays.isNullOrEmpty()) return ""
        val label = "%d".format(calendarDays.size)
        return when {
            calendarDays.size == 7 -> "（每天）"
            calendarDays.toSet() == setOf(2, 3, 4, 5, 6) -> "（工作日）"
            else -> "（重复 $label 天）"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append("${h}小时")
            if (m > 0) append("${m}分钟")
            if (s > 0 || isEmpty()) append("${s}秒")
        }
    }
}
