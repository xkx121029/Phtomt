// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.engine.execution

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.device.clock.ClockPackages
import com.phoneagent.device.clock.ClockRequest
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.domain.model.ScreenSnapshot
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * 时钟降级链（直达 → shell 写库 → 无障碍确认 → 打开页面手动设置）。
 *
 * 背景：原来的 `ClockChannel` 只做「AlarmClock 意图直达」，解析不到或 startActivity 被系统
 * 后台启动限制拦截时直接判失败，任务停在原地等人。闹钟是用户最先感知到的系统能力，
 * 不能因为某一条通道不可用就整条失败。
 *
 * 本文件把「候选 → 生效条件 → 失败判据 → 用户可见文案」写成一张可读的表
 * （[ClockFallbackPolicy]），再由 [ClockFallbackRunner] 按表执行。纯策略部分不碰 Android，
 * 可在 JVM 单测里覆盖；执行部分只做三件事：发 Intent、点一次保存按钮、必要时写一次库。
 *
 * 刻意不做的事：
 * - 不猜测时钟页的控件层级：只按「保存/确定/完成」这类文案点一次，找不到就降级，
 *   不做多点模拟（不同厂商的时钟 UI 差异太大，猜层级只会点错地方）。
 * - 不把 shell 写库当成必然可达：没有 Shizuku/ADB 时这一级标为「跳过」而不是「失败」，
 *   最终仍然能干净地落到「打开时钟页让用户自己设」。
 */
internal enum class ClockFallbackKind(val id: String, val label: String) {
    /** 系统时钟静默直达：不需要界面，也不需要无障碍 */
    DIRECT_SILENT("clock_direct", "系统时钟静默直达"),

    /** 注入通道写闹钟库并回查（仅闹钟、仅在 shell 就绪且 Provider 可读时尝试） */
    SHELL_WRITE("clock_shell", "注入通道写闹钟库"),

    /** 带界面直达 + 无障碍点一次保存/确认 */
    UI_CONFIRM("clock_a11y", "时钟页直达 + 无障碍确认"),

    /** 打开时钟页，交给用户手动确认 */
    OPEN_MANUAL("clock_manual", "打开时钟页手动设置"),
}

/** 当前设备可用的通道事实（由 [ExecutionCore] 在执行前一次性读取，避免逐级读到不同时刻的可用性） */
internal data class ClockCapabilities(
    val accessibility: Boolean,
    val shell: Boolean,
)

/** 降级链中的一级：何时尝试、失败判据是什么 */
internal data class ClockLevel(
    val kind: ClockFallbackKind,
    val condition: String,
    val failureCriterion: String,
)

/**
 * 时钟降级策略表（纯函数）。
 *
 * 顺序即尝试顺序：**越靠前越省事**（静默直达 0 次点击 → 写库 0 次点击 → 无障碍 1 次点击 →
 * 打开页面让用户动手）。每级失败都只是「本步没交付」，继续下一级，绝不把任务停在这里。
 */
internal object ClockFallbackPolicy {

    /** 闹钟与倒计时都适用的基础链 */
    fun levels(request: ClockRequest, caps: ClockCapabilities): List<ClockLevel> = buildList {
        add(
            ClockLevel(
                kind = ClockFallbackKind.DIRECT_SILENT,
                condition = "系统时钟能解析到处理该标准 action 的 Activity（先试厂商包名，再隐式解析）",
                failureCriterion = "解析不到，或 startActivity 抛异常（后台启动限制）",
            ),
        )
        // 倒计时没有通用的可写库入口，重复闹钟的星期字段各厂商不一致：这两种情况直接跳过写库
        if (caps.shell && request.action == AlarmClock.ACTION_SET_ALARM && request.repeatDays.isNullOrEmpty()) {
            add(
                ClockLevel(
                    kind = ClockFallbackKind.SHELL_WRITE,
                    condition = "Shizuku/无线 ADB 就绪，且厂商时钟 Provider 可读（一次性闹钟）",
                    failureCriterion = "Provider 不可读、插入失败或回查不到该时间",
                ),
            )
        }
        if (caps.accessibility) {
            add(
                ClockLevel(
                    kind = ClockFallbackKind.UI_CONFIRM,
                    condition = "无障碍服务在线；时钟页直达（SKIP_UI=false）后能点到保存/确认按钮",
                    failureCriterion = "时钟页没出现，或找不到任何保存/确认按钮",
                ),
            )
        }
        add(
            ClockLevel(
                kind = ClockFallbackKind.OPEN_MANUAL,
                condition = "时钟应用能被启动",
                failureCriterion = "连时钟应用都解析不到/启动失败",
            ),
        )
    }

    /** 某一级为什么没进入候选链（进日志与最终文案，不静默） */
    fun skipReason(kind: ClockFallbackKind, request: ClockRequest, caps: ClockCapabilities): String? =
        when (kind) {
            ClockFallbackKind.SHELL_WRITE -> when {
                !caps.shell -> "未检测到 Shizuku/无线 ADB，跳过写库（不记为失败）"
                request.action != AlarmClock.ACTION_SET_ALARM -> "倒计时没有可写的数据库入口，跳过写库"
                !request.repeatDays.isNullOrEmpty() -> "重复闹钟的星期字段各厂商不一致，跳过写库"
                else -> null
            }
            ClockFallbackKind.UI_CONFIRM -> if (caps.accessibility) null else "无障碍服务未开启，跳过页面确认"
            else -> null
        }

    /**
     * 成功时给用户看的中文，必须点名「这次用的是哪种方式」。
     * [verified] 为真时才有资格说「已确认」；否则如实说明「派发了但读不到结果」。
     */
    fun successMessage(
        kind: ClockFallbackKind,
        request: ClockRequest,
        clickedLabel: String? = null,
        verified: Boolean = false,
        evidence: String? = null,
    ): String {
        val what = describe(request)
        return when (kind) {
            ClockFallbackKind.DIRECT_SILENT -> if (verified) {
                "已确认设定$what（回读：${evidence ?: "系统时钟数据"}）"
            } else {
                "已向系统时钟派发$what，但本机读不到时钟数据，无法确认是否真的生效；" +
                    "请看一眼时钟应用的列表。没有重复派发，避免出现多条一样的条目"
            }
            ClockFallbackKind.SHELL_WRITE ->
                "已用注入通道（Shizuku/无线 ADB）写入时钟数据库并回查确认：$what"
            ClockFallbackKind.UI_CONFIRM ->
                "静默直达不可用，已改用时钟页直达 + 无障碍点击「${clickedLabel ?: "保存"}」，并回读确认：$what"
            ClockFallbackKind.OPEN_MANUAL ->
                "当前方式：打开时钟页手动设置。已打开时钟应用，请手动设定$what"
        }
    }

    /** 系统里已经就是这个闹钟：不再重复派发，避免时钟列表出现两条一样的 */
    fun alreadySetMessage(request: ClockRequest): String =
        "系统里已经有过这个${describe(request)}（下一闹钟就是它），本次没有重复设置"

    /** 无障碍点了保存键，但回读不到：不能算成功，交给下一级并说明原因 */
    fun uiClickUnverifiedMessage(clickedLabel: String?, reason: String): String =
        "已在时钟页点击「${clickedLabel ?: "保存"}」，但回读不到对应条目（$reason）"

    /**
     * 最终只能停在「手动设置」时的完整说明：为什么没自动完成 + 现在用户该做什么。
     * [failures] 是前面各级的中文失败摘要与跳过说明，按尝试顺序排列。
     */
    fun manualMessage(request: ClockRequest, failures: List<String>, opened: Boolean): String {
        val what = describe(request)
        val why = failures.filter { it.isNotBlank() }.joinToString("；").ifBlank { "自动通道均未交付" }
        return if (opened) {
            "自动设定$what 未完成（$why）。当前方式：打开时钟页手动设置——已为你打开时钟应用，请在页面上设定$what。"
        } else {
            "自动设定$what 失败（$why），且打不开时钟应用。${ClockPackages.NO_CLOCK_MESSAGE}"
        }
    }

    /** 给人看的时间描述：闹钟「闹钟 07:30（每天）」；倒计时「倒计时 30 分钟」 */
    fun describe(request: ClockRequest): String =
        if (request.action == AlarmClock.ACTION_SET_ALARM) {
            "闹钟 ${"%02d".format(request.hour ?: 0)}:${"%02d".format(request.minute ?: 0)}" +
                repeatText(request.repeatDays)
        } else {
            "倒计时 ${formatSeconds(request.seconds ?: 0L)}"
        }

    private fun repeatText(days: List<Int>?): String {
        if (days.isNullOrEmpty()) return ""
        return when {
            days.size >= 7 -> "（每天）"
            days.toSet() == setOf(2, 3, 4, 5, 6) -> "（工作日）"
            days.toSet() == setOf(1, 7) -> "（周末）"
            else -> "（重复 ${days.size} 天）"
        }
    }

    private fun formatSeconds(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return buildList {
            if (h > 0) add("${h} 小时")
            if (m > 0) add("${m} 分钟")
            if (s > 0 || isEmpty()) add("${s} 秒")
        }.joinToString(" ")
    }
}

/**
 * 「真的设置成功」的判据（纯函数，JVM 可直接测）。
 *
 * 与旧实现的关键差别：`startActivity` 没抛异常只等于「派发成功」，不等于「时钟里真的多了条目」。
 * 这里把可回读的证据全部收拢成一组纯判断：系统下一闹钟、闹钟 Provider 行、计时器页文本。
 */
internal object ClockVerification {

    /** 闹钟是整分设置，回读时间留 90 秒容差（跨零点/时区换算不会误判） */
    const val ALARM_TOLERANCE_MS = 90_000L

    /** 标准 AlarmClock 意图无法表达「指定日期」时的统一说明（直达与页面确认都要跳过） */
    const val TARGET_DATE_UNSUPPORTED: String =
        "系统时钟的标准设置意图只能按 HH:mm 排「下一次」，无法指定具体日期；" +
            "继续走它会把这个闹钟设到错误的那一天，因此这一步不派发"

    /** 下一次 HH:mm 命中的毫秒时间戳 */
    fun nextOccurrence(hour: Int, minute: Int, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 请求的日期能否被标准 AlarmClock 意图表达。
     *
     * 只有「目标日 ≠ 下一次 HH:mm 所在的日」才是否；目标时刻已过去（如 21:00 说「今晚 8 点」）
     * 按"下一次"处理，不拦。
     */
    fun targetDateExpressible(request: ClockRequest, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (request.action != AlarmClock.ACTION_SET_ALARM) return true
        val target = request.atMillis ?: return true
        if (target <= nowMillis + ALARM_TOLERANCE_MS) return true
        val hour = request.hour ?: return false
        val minute = request.minute ?: return false
        return sameDay(nextOccurrence(hour, minute, nowMillis), target)
    }

    /** 未来 8 天内、时分一致（重复闹钟还要求星期命中）即视为「这条就是我设的」 */
    fun matchesAlarm(triggerAtMillis: Long, request: ClockRequest, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (triggerAtMillis < nowMillis - ALARM_TOLERANCE_MS) return false
        if (triggerAtMillis > nowMillis + 8L * 24 * 3600_000) return false
        // 带明确日期的请求必须连日期一起对上：只看时分会把「今天 19:00」当成「明天 19:00」
        val target = request.atMillis
        if (target != null && target > nowMillis && !sameDay(triggerAtMillis, target)) return false
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        if (cal.get(Calendar.HOUR_OF_DAY) != (request.hour ?: return false)) return false
        if (cal.get(Calendar.MINUTE) != (request.minute ?: return false)) return false
        val days = request.repeatDays
        return days.isNullOrEmpty() || cal.get(Calendar.DAY_OF_WEEK) in days
    }

    /** 闹钟列表/文本里能否看到这个时间（12/24 小时两种写法都认，带标签时也认标签） */
    fun alarmVisible(screenText: String, request: ClockRequest): Boolean {
        val hour = request.hour ?: return false
        val minute = request.minute ?: return false
        val padded = "%02d:%02d".format(hour, minute)
        val plain = "$hour:%02d".format(minute)
        if (screenText.contains(padded) || screenText.contains(plain)) return true
        val label = request.label
        return !label.isNullOrBlank() && screenText.contains(label)
    }

    /** 计时器页里能否看到这个倒计时（「5m Timer」「05:00」「5 分钟」等写法都认） */
    fun timerVisible(screenText: String, request: ClockRequest): Boolean {
        val seconds = request.seconds ?: return false
        val label = request.label
        if (!label.isNullOrBlank() && screenText.contains(label)) return true
        val minutes = seconds / 60
        val rest = seconds % 60
        val candidates = buildList {
            add("%02d:%02d".format(minutes, rest))
            add("$minutes:%02d".format(rest))
            add("${minutes}m")
            if (minutes > 0) add("$minutes 分钟")
            if (seconds >= 3600) add("${seconds / 3600} 小时")
        }
        return candidates.any { screenText.contains(it, ignoreCase = true) }
    }

    /** `dumpsys alarm` 输出里的计时器条数（TIMER_EXPIRED 一条对应一个在跑的计时器） */
    fun timerCount(dumpsysAlarm: String): Int =
        dumpsysAlarm.lineSequence().count { it.contains("TIMER_EXPIRED") }

    /** Provider 查询输出里是否含目标闹钟行（`hour=7` 与 `minutes=30` 同时出现） */
    fun providerHasAlarm(queryOutput: String, request: ClockRequest): Boolean {
        val hour = request.hour ?: return false
        val minute = request.minute ?: return false
        return queryOutput.contains("hour=$hour") && queryOutput.contains("minutes=$minute")
    }
}

/** 一次时钟降级的执行结果 */
internal sealed interface ClockOutcome {
    /** 已交付（[verified] 表示是否拿到回执/回查证据） */
    data class Done(val kind: ClockFallbackKind, val message: String, val verified: Boolean) : ClockOutcome

    /** 只把用户带到时钟页，需要用户手动完成 */
    data class Manual(val message: String) : ClockOutcome

    /** 连时钟页都打不开 */
    data class Failed(val reason: String) : ClockOutcome
}

/**
 * 时钟意图的投递方。
 *
 * 抽成接口是因为 `android.content.Intent` 在 JVM 单测里是空壳（构造后 action/extra 都不保存），
 * 只有换成可控实现，才能把「派发 → 回读 → 判成功/降级」整条链放进单测里跑。
 */
internal interface ClockIntentDispatcher {
    /** 派发一次设置请求；返回 null 表示已交付，非空是中文失败原因 */
    fun dispatch(request: ClockRequest, skipUi: Boolean): String?

    /** 打开闹钟/计时器列表页；返回是否交付成功 */
    fun openPage(request: ClockRequest): Boolean = false
}

/**
 * 按 [ClockFallbackPolicy] 的表执行一级级降级。
 *
 * 所有 Android 依赖（Intent 解析、无障碍点击、shell）都通过构造参数注入，
 * 便于把「哪一级成功/失败、最终文案是什么」写进单测；真机行为只由注入实现决定。
 */
internal class ClockFallbackRunner(
    private val context: Context,
    private val executor: ActionExecutor?,
    private val shell: suspend (String) -> ShizukuManager.ShellResult,
    private val caps: ClockCapabilities,
    /** 时钟页文本回读：用于「无障碍能看见条目」这一档的验证；没有无障碍时返回 null */
    private val screen: suspend () -> ScreenSnapshot? = { null },
    /** 意图投递：默认走标准 AlarmClock 契约；单测里替换成假时钟 */
    private val dispatcher: ClockIntentDispatcher = IntentClockDispatcher(context),
) {

    /** 保存/确认按钮的文案候选：只点一次，点到即止；点不到就降级，绝不连环乱点 */
    private val confirmLabels = listOf("保存", "确定", "完成", "好", "OK", "Save", "Done", "开始", "Start")

    private val alarmUris = listOf(
        "content://com.android.deskclock/alarm",
        "content://com.oplus.alarmclock/alarm",
        "content://com.coloros.alarmclock/alarm",
        "content://com.miui.clock/alarm",
        "content://com.huawei.clock/alarm",
        "content://com.hihonor.clock/alarm",
        "content://com.google.android.deskclock/alarm",
    )

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    suspend fun run(request: ClockRequest): ClockOutcome {
        // 去重一：系统下一闹钟已经就是这个目标 → 不再发第二次（重复派发会多出一条闹钟）
        if (request.action == AlarmClock.ACTION_SET_ALARM && alarmAlreadyNext(request)) {
            return ClockOutcome.Done(
                ClockFallbackKind.DIRECT_SILENT,
                ClockFallbackPolicy.alreadySetMessage(request),
                verified = true,
            )
        }
        // 去重二：同一个倒计时刚刚被确认过（60 秒内）→ 不再创建第二条
        lastTimer?.let { done ->
            val same = request.action == AlarmClock.ACTION_SET_TIMER &&
                done.verified && done.seconds == (request.seconds ?: 0L) && done.label == request.label
            val ageMs = System.currentTimeMillis() - done.atMillis
            if (same && ageMs in 0..60_000L) {
                return ClockOutcome.Done(
                    ClockFallbackKind.DIRECT_SILENT,
                    "刚刚（${ageMs / 1000} 秒前）已经确认启动过同一个倒计时，本次没有重复创建",
                    verified = true,
                )
            }
        }

        val failures = mutableListOf<String>()
        // 日期表达不了时，直达与页面确认都会把闹钟设到错误的那一天：整级跳过并写明原因
        val dateExpressible = ClockVerification.targetDateExpressible(request)
        if (!dateExpressible) failures += "静默直达/页面确认：${ClockVerification.TARGET_DATE_UNSUPPORTED}"
        val beforeAlarm = nextAlarmTrigger()
        val beforeTimers = if (caps.shell) timerCount() else null
        val levels = ClockFallbackPolicy.levels(request, caps).filter { level ->
            dateExpressible || level.kind !in setOf(ClockFallbackKind.DIRECT_SILENT, ClockFallbackKind.UI_CONFIRM)
        }
        for (level in levels) {
            when (level.kind) {
                ClockFallbackKind.DIRECT_SILENT -> {
                    // 只走标准 AlarmClock 契约（与 Eta 一致）：厂商私有 action 没有回读契约，
                    // 出现了「打开了页面但没设成」时无法识别，不能作为判成功的通道。
                    val error = dispatch(request, skipUi = true)
                    if (error != null) {
                        failures += "静默直达：$error"
                        continue
                    }
                    when (val evidence = verify(request, beforeAlarm, beforeTimers)) {
                        is ClockEvidence.Confirmed -> {
                            rememberTimer(request, verified = true)
                            return ClockOutcome.Done(
                                ClockFallbackKind.DIRECT_SILENT,
                                ClockFallbackPolicy.successMessage(
                                    ClockFallbackKind.DIRECT_SILENT,
                                    request,
                                    verified = true,
                                    evidence = evidence.source,
                                ),
                                verified = true,
                            )
                        }
                        is ClockEvidence.Absent -> {
                            failures += "静默直达：${evidence.reason}"
                        }
                        ClockEvidence.Unreadable -> {
                            // 读不到不等于没设成：如实回报、停止再派发，避免重复条目
                            rememberTimer(request, verified = false)
                            return ClockOutcome.Done(
                                ClockFallbackKind.DIRECT_SILENT,
                                ClockFallbackPolicy.successMessage(
                                    ClockFallbackKind.DIRECT_SILENT,
                                    request,
                                    verified = false,
                                ),
                                verified = false,
                            )
                        }
                    }
                }

                ClockFallbackKind.SHELL_WRITE -> {
                    val error = writeAlarmViaShell(request)
                    if (error == null) {
                        return ClockOutcome.Done(
                            ClockFallbackKind.SHELL_WRITE,
                            ClockFallbackPolicy.successMessage(ClockFallbackKind.SHELL_WRITE, request),
                            verified = true,
                        )
                    }
                    failures += "写闹钟库：$error"
                }

                ClockFallbackKind.UI_CONFIRM -> {
                    val error = confirmViaAccessibility(request)
                    if (error == null) {
                        // 点完保存必须再回读一次：点到按钮不等于条目真的进了时钟
                        when (val evidence = verify(request, beforeAlarm, beforeTimers)) {
                            is ClockEvidence.Confirmed -> {
                                rememberTimer(request, verified = true)
                                return ClockOutcome.Done(
                                    ClockFallbackKind.UI_CONFIRM,
                                    ClockFallbackPolicy.successMessage(
                                        ClockFallbackKind.UI_CONFIRM,
                                        request,
                                        clickedLabel = lastClickedLabel,
                                        verified = true,
                                        evidence = evidence.source,
                                    ),
                                    verified = true,
                                )
                            }
                            is ClockEvidence.Absent -> failures +=
                                "无障碍确认：${ClockFallbackPolicy.uiClickUnverifiedMessage(lastClickedLabel, evidence.reason)}"
                            ClockEvidence.Unreadable -> failures +=
                                "无障碍确认：${ClockFallbackPolicy.uiClickUnverifiedMessage(lastClickedLabel, "本机无法回读")}"
                        }
                    } else {
                        failures += "无障碍确认：$error"
                    }
                }

                ClockFallbackKind.OPEN_MANUAL -> {
                    val opened = openClockPage(request)
                    val why = buildList {
                        addAll(failures)
                        ClockFallbackPolicy.skipReason(ClockFallbackKind.SHELL_WRITE, request, caps)?.let(::add)
                        ClockFallbackPolicy.skipReason(ClockFallbackKind.UI_CONFIRM, request, caps)?.let(::add)
                    }
                    return if (opened) {
                        ClockOutcome.Manual(ClockFallbackPolicy.manualMessage(request, why, opened = true))
                    } else {
                        ClockOutcome.Failed(ClockFallbackPolicy.manualMessage(request, why, opened = false))
                    }
                }
            }
        }
        return ClockOutcome.Failed(ClockFallbackPolicy.manualMessage(request, failures, opened = false))
    }

    private var lastClickedLabel: String? = null

    /** 最近一次已确认的倒计时：用于「同一句指令被重复执行」时防止创建多条 */
    private data class DispatchedTimer(val seconds: Long, val label: String?, val atMillis: Long, val verified: Boolean)

    private var lastTimer: DispatchedTimer? = null

    private fun rememberTimer(request: ClockRequest, verified: Boolean) {
        if (request.action != AlarmClock.ACTION_SET_TIMER) return
        lastTimer = DispatchedTimer(
            seconds = request.seconds ?: 0L,
            label = request.label,
            atMillis = System.currentTimeMillis(),
            verified = verified,
        )
    }

    // ---------------------------------------------------------------- 回读验证

    private sealed interface ClockEvidence {
        /** 已经拿到「时钟里确实有这一条」的证据 */
        data class Confirmed(val source: String) : ClockEvidence

        /** 有明确证据表明没设成 */
        data class Absent(val reason: String) : ClockEvidence

        /** 本机没有任何可回读的通道：既不能确认也不能否认 */
        data object Unreadable : ClockEvidence
    }

    private fun nextAlarmTrigger(): Long? =
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        }.getOrNull()

    private fun alarmAlreadyNext(request: ClockRequest): Boolean {
        val trigger = nextAlarmTrigger() ?: return false
        return ClockVerification.matchesAlarm(trigger, request)
    }

    private suspend fun timerCount(): Int? =
        (shell("dumpsys alarm") as? ShizukuManager.ShellResult.Success)
            ?.output
            ?.let(ClockVerification::timerCount)

    /** 计时器登记是异步的（实测派发后约 1.3 秒才进 AlarmManager），轮询到增加或超时 */
    private suspend fun awaitTimerIncrease(before: Int): Int? {
        var last: Int? = null
        repeat(10) {
            last = timerCount()
            val current = last
            if (current != null && current > before) return current
            delay(300)
        }
        return last
    }

    /** 闹钟 Provider 回查：true=有这一行；false=Provider 可读但没有；null=没有可读的 Provider */
    private suspend fun providerHasAlarm(request: ClockRequest): Boolean? {
        request.hour ?: return null
        request.minute ?: return null
        val dateBlocked = !ClockVerification.targetDateExpressible(request)
        var providerReadable = false
        for (uri in alarmUris) {
            val probe = shell("content query --uri '$uri' --projection _id") as? ShizukuManager.ShellResult.Success
                ?: continue
            if (probe.output.contains("Error", ignoreCase = true) ||
                probe.output.contains("Permission", ignoreCase = true)
            ) {
                continue
            }
            providerReadable = true
            val query = shell(
                "content query --uri '$uri' --projection hour:minutes " +
                    "--where '${alarmWhere(request, dateBlocked)}'",
            ) as? ShizukuManager.ShellResult.Success ?: continue
            if (ClockVerification.providerHasAlarm(query.output, request)) return true
        }
        return if (providerReadable) false else null
    }

    /**
     * 打开对应的时钟页并轮询无障碍文本，直到命中 [matches] 或 6 秒超时。
     *
     * 不能只等一次：实测时钟 App 冷启动后闹钟/计时器列表要 4 秒以上才渲染出来，
     * 固定等 1.2 秒会把「其实已经设好」误判成「没设成」，进而触发重复设置。
     * 全程读不到时钟页（没有无障碍/被系统挡）返回 null，表示「读不了」而不是「不存在」。
     */
    private suspend fun clockPageText(request: ClockRequest, matches: (String) -> Boolean): String? {
        if (!openClockPage(request)) return null
        var last: String? = null
        repeat(6) {
            delay(1_000)
            val snapshot = runCatching { screen() }.getOrNull() ?: return@repeat
            if (snapshot.packageName?.contains("clock", ignoreCase = true) != true) return@repeat
            last = snapshot.elements.joinToString("\n") { element ->
                listOfNotNull(element.text, element.contentDescription).joinToString(" ")
            }
            if (matches(last.orEmpty())) return last
        }
        return last
    }

    /**
     * 「真的设置成功」的判据。按证据强度依次尝试：
     * 1. 系统下一闹钟（AlarmManager，公开 API，闹钟最可靠）；
     * 2. 时钟 Provider 回查（仅闹钟，需要注入通道）；
     * 3. `dumpsys alarm` 里的计时器登记数变化（仅倒计时，需要注入通道）；
     * 4. 打开时钟页后无障碍文本里是否出现该条目。
     * 全部不可用才返回 [ClockEvidence.Unreadable]，绝不把「派发成功」当「设置成功」。
     */
    private suspend fun verify(
        request: ClockRequest,
        beforeAlarm: Long?,
        beforeTimers: Int?,
    ): ClockEvidence {
        if (request.action == AlarmClock.ACTION_SET_ALARM) {
            val trigger = nextAlarmTrigger()
            if (trigger != null && ClockVerification.matchesAlarm(trigger, request)) {
                val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(trigger))
                return ClockEvidence.Confirmed("系统下一闹钟是 $at")
            }
            providerHasAlarm(request)?.let { found ->
                return if (found) ClockEvidence.Confirmed("时钟 Provider 回查到了该闹钟")
                else ClockEvidence.Absent("时钟 Provider 回查不到该闹钟")
            }
            if (!caps.accessibility) return ClockEvidence.Unreadable
            val text = clockPageText(request) { ClockVerification.alarmVisible(it, request) }
                ?: return ClockEvidence.Unreadable
            return if (ClockVerification.alarmVisible(text, request)) {
                ClockEvidence.Confirmed("闹钟列表里能看到该时间")
            } else {
                ClockEvidence.Absent("闹钟列表里没有该时间")
            }
        }

        // 倒计时
        if (beforeTimers != null) {
            val after = awaitTimerIncrease(beforeTimers)
            if (after != null && after > beforeTimers) {
                return ClockEvidence.Confirmed("系统计时器登记从 $beforeTimers 条增加到 $after 条")
            }
            // 没增加先别急着否定：部分 ROM 的 dumpsys 不按预期登记，继续看页面文本
        } else if (!caps.accessibility) {
            // 既没有注入通道也没有无障碍：读不到任何证据
            return ClockEvidence.Unreadable
        }
        if (!caps.accessibility) return ClockEvidence.Unreadable
        val text = clockPageText(request) { ClockVerification.timerVisible(it, request) }
            ?: return ClockEvidence.Unreadable
        return if (ClockVerification.timerVisible(text, request)) {
            ClockEvidence.Confirmed("计时器页里能看到该倒计时正在运行")
        } else {
            ClockEvidence.Absent("计时器页里没有该倒计时")
        }
    }

    // ---------------------------------------------------------------- 一级：静默直达

    private fun dispatch(request: ClockRequest, skipUi: Boolean): String? =
        dispatcher.dispatch(request, skipUi)

    // ---------------------------------------------------------------- 二级：shell 写库

    /**
     * 用注入通道把一次性闹钟写进厂商时钟 Provider，并**回查**确认。
     * 任何一步不成立都返回中文原因，交给下一级；不吞异常、不假装成功。
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun writeAlarmViaShell(request: ClockRequest): String? {
        if (request.action != AlarmClock.ACTION_SET_ALARM) return "倒计时不支持写库"
        if (!request.repeatDays.isNullOrEmpty()) return "重复闹钟的星期字段各厂商不一致"
        val hour = request.hour ?: return "缺少小时"
        val minute = request.minute ?: return "缺少分钟"

        val uri = alarmUris.firstOrNull { candidate ->
            val out = shell("content query --uri '$candidate' --projection _id")
            out is ShizukuManager.ShellResult.Success &&
                !out.output.contains("Error", ignoreCase = true) &&
                !out.output.contains("Permission", ignoreCase = true)
        } ?: return "读不到时钟 Provider（未被 Root/Shizuku 开放）"

        val label = request.label?.takeIf { it.isNotBlank() }
        val bindLabel = label?.let { " --bind label:s:${quote(it)}" } ?: ""
        // 标准意图表达不了目标日期时，写库必须把绝对触发时刻一起写进去，否则同样会落到"下一次"那天
        val dateBlocked = !ClockVerification.targetDateExpressible(request)
        val targetMillis = request.atMillis
        val bindDate = if (dateBlocked && targetMillis != null) " --bind alarm_time:l:$targetMillis" else ""
        val insert = shell(
            "content insert --uri '$uri' --bind hour:i:$hour --bind minutes:i:$minute " +
                "--bind enabled:i:1 --bind vibrate:i:1$bindLabel$bindDate",
        )
        val insertOut = when (insert) {
            is ShizukuManager.ShellResult.Success -> insert.output
            is ShizukuManager.ShellResult.Failure -> return "插入被拒（${insert.reason}）"
        }
        if (insertOut.contains("Error", ignoreCase = true) || insertOut.contains("Exception", ignoreCase = true)) {
            return if (bindDate.isNotEmpty()) {
                "该机型的时钟数据库没有可写的 alarm_time 列，无法指定日期（${insertOut.take(80)}）"
            } else {
                "插入失败（${insertOut.take(80)}）"
            }
        }

        // 回查：拿不到这一行就是没写进去，不能把「命令没报错」当成成功
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val check = shell(
            "content query --uri '$uri' --where '${alarmWhere(request, dateBlocked)}' --projection hour:minutes",
        )
        val checkOut = (check as? ShizukuManager.ShellResult.Success)?.output.orEmpty()
        if (!checkOut.contains("hour=$hour") || !checkOut.contains("minutes=$minute")) {
            return "写完回查不到该闹钟"
        }
        return null
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 闹钟行的查询条件；要求指定日期时把 alarm_time 也带上，避免命中别的那一天 */
    private fun alarmWhere(request: ClockRequest, dateBlocked: Boolean): String {
        val base = "hour=${request.hour ?: 0} AND minutes=${request.minute ?: 0}"
        return if (dateBlocked && request.atMillis != null) "$base AND alarm_time=${request.atMillis}" else base
    }

    // ---------------------------------------------------------------- 三级：无障碍确认

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun confirmViaAccessibility(request: ClockRequest): String? {
        val exec = executor ?: return "无障碍服务不在线"
        val error = dispatch(request, skipUi = false)
        if (error != null) return "时钟页拉不起来：$error"
        // 页面已拉起：等它完成转场后再找按钮
        delay(1200)
        for (label in confirmLabels) {
            if (exec.clickNode(NodeSelector(label = label)) is ActionExecutor.Result.Success) {
                lastClickedLabel = label
                return null
            }
        }
        return "时钟页已打开，但没找到保存/确认按钮"
    }

    // ---------------------------------------------------------------- 四级：打开页面

    private fun openClockPage(request: ClockRequest): Boolean = dispatcher.openPage(request)
}

/**
 * 生产实现：标准 AlarmClock 契约 + 显式组件投递。
 *
 * 与 Eta 一致，先按厂商时钟包名尝试标准 action，再退回隐式解析；不使用厂商私有 action
 * （私有 action 没有回读契约，出现「页面开了但没设成」时无法识别）。
 */
internal class IntentClockDispatcher(private val context: Context) : ClockIntentDispatcher {

    override fun dispatch(request: ClockRequest, skipUi: Boolean): String? {
        val base = fillIntent(request, Intent(request.action), skipUi) ?: return "时钟类型无法识别"

        // 1) 按厂商时钟包名显式解析标准 action
        val explicit = ClockPackages.PREFERRED.firstNotNullOfOrNull { pkg ->
            resolveClockIntent(Intent(base).setPackage(pkg))
        }
        // 2) 隐式解析标准 action
        val target = explicit ?: resolveClockIntent(base) ?: return ClockPackages.NO_CLOCK_MESSAGE
        return start(target)
    }

    override fun openPage(request: ClockRequest): Boolean {
        val pageAction = if (request.action == AlarmClock.ACTION_SET_ALARM) AlarmClock.ACTION_SHOW_ALARMS
        else AlarmClock.ACTION_SHOW_TIMERS
        val page = Intent(pageAction)
        if (canResolve(page)) return start(page) == null
        // 部分 ROM 没有 SHOW_* 入口：退回启动时钟应用本身
        val launch = ClockPackages.PREFERRED.firstNotNullOfOrNull { pkg ->
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        }
        return launch != null && start(launch) == null
    }

    /**
     * 解析到具体 Activity 并显式指定组件。
     *
     * 只 `setPackage` 时，系统可能把意图投递到该应用当前最上层的任意 Activity（实测时钟停在
     * 计时页时会「已投递但没处理」），表现为「启动了但没设成」。显式组件保证落到真正处理
     * SET_ALARM / SET_TIMER 的那个入口 Activity（与 `am start` 打印的 cmp= 行为一致）。
     */
    private fun resolveClockIntent(intent: Intent): Intent? {
        val resolved = runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull()
            ?: return null
        val info = resolved.activityInfo ?: return null
        return Intent(intent).setComponent(ComponentName(info.packageName, info.name))
    }

    /** 往 Intent 里填参数；时钟类型不认识时返回 null */
    private fun fillIntent(request: ClockRequest, intent: Intent, skipUi: Boolean): Intent? {
        when (request.action) {
            AlarmClock.ACTION_SET_ALARM -> {
                intent.putExtra(AlarmClock.EXTRA_HOUR, request.hour ?: 0)
                intent.putExtra(AlarmClock.EXTRA_MINUTES, request.minute ?: 0)
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                intent.putExtra(AlarmClock.EXTRA_VIBRATE, true)
                request.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                request.repeatDays?.takeIf { it.isNotEmpty() }?.let { days ->
                    intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            AlarmClock.ACTION_SET_TIMER -> {
                intent.putExtra(AlarmClock.EXTRA_LENGTH, (request.seconds ?: 0L).toInt())
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                request.label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            }
            else -> return null
        }
        return intent
    }

    private fun canResolve(intent: Intent): Boolean =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private fun start(intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            null
        }.getOrElse { e ->
            "启动时钟页失败（${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}）"
        }
    }
}
