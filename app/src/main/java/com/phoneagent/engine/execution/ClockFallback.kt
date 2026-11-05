// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.engine.execution

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.device.clock.ClockPackages
import com.phoneagent.device.clock.ClockRequest
import com.phoneagent.device.shell.ShizukuManager
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
                condition = "系统时钟能解析到处理该 action 的 Activity（含厂商包名/私有 action 探测）",
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

    /** 成功时给用户看的中文，必须点名「这次用的是哪种方式」 */
    fun successMessage(kind: ClockFallbackKind, request: ClockRequest, clickedLabel: String? = null): String {
        val what = describe(request)
        return when (kind) {
            ClockFallbackKind.DIRECT_SILENT ->
                "已用系统时钟静默直达设定$what（系统未回执，请在时钟应用的列表里确认）"
            ClockFallbackKind.SHELL_WRITE ->
                "已用注入通道（Shizuku/无线 ADB）写入时钟数据库并回查确认：$what"
            ClockFallbackKind.UI_CONFIRM ->
                "静默直达不可用，已改用时钟页直达 + 无障碍点击「${clickedLabel ?: "保存"}」确认：$what"
            ClockFallbackKind.OPEN_MANUAL ->
                "当前方式：打开时钟页手动设置。已打开时钟应用，请手动设定$what"
        }
    }

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

/** 厂商时钟的私有 action 候选：只在 `resolveActivity` 真的能解析时使用，解析不到就跳过 */
internal data class ClockVendorRoute(val packageName: String, val action: String)

/**
 * 厂商私有 action 探测表。
 *
 * 这些 action 没有官方契约，属于「有就更快、没有也不影响」的加速路径：
 * 每个候选都先用 `Intent(action).setPackage(包名)` 解析一次，解析不到就当不存在。
 * 标准 AlarmClock 意图始终是每台机器的保底路径，不因私有 action 猜错而跳过。
 */
internal object ClockVendorRoutes {

    val alarm: List<ClockVendorRoute> = listOf(
        ClockVendorRoute("com.oplus.alarmclock", "com.oplus.alarmclock.action.SET_ALARM"),
        ClockVendorRoute("com.coloros.alarmclock", "com.coloros.alarmclock.action.SET_ALARM"),
        ClockVendorRoute("com.miui.clock", "com.miui.clock.action.SET_ALARM"),
        ClockVendorRoute("com.huawei.clock", "com.huawei.clock.action.SET_ALARM"),
        ClockVendorRoute("com.hihonor.clock", "com.hihonor.clock.action.SET_ALARM"),
    ).distinctBy { it.packageName to it.action }

    val timer: List<ClockVendorRoute> = listOf(
        ClockVendorRoute("com.oplus.alarmclock", "com.oplus.alarmclock.action.SET_TIMER"),
        ClockVendorRoute("com.coloros.alarmclock", "com.coloros.alarmclock.action.SET_TIMER"),
        ClockVendorRoute("com.miui.clock", "com.miui.clock.action.SET_TIMER"),
        ClockVendorRoute("com.huawei.clock", "com.huawei.clock.action.SET_TIMER"),
        ClockVendorRoute("com.hihonor.clock", "com.hihonor.clock.action.SET_TIMER"),
    ).distinctBy { it.packageName to it.action }

    fun of(request: ClockRequest): List<ClockVendorRoute> =
        if (request.action == AlarmClock.ACTION_SET_ALARM) alarm else timer

    /** 纯选择：按候选顺序返回第一个可解析的路由（[resolvable] 由调用方用 PackageManager 提供） */
    fun pick(routes: List<ClockVendorRoute>, resolvable: (ClockVendorRoute) -> Boolean): ClockVendorRoute? =
        routes.firstOrNull(resolvable)
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
    @Suppress("ReturnCount")
    suspend fun run(request: ClockRequest): ClockOutcome {
        val failures = mutableListOf<String>()
        for (level in ClockFallbackPolicy.levels(request, caps)) {
            when (level.kind) {
                ClockFallbackKind.DIRECT_SILENT -> {
                    val error = dispatch(request, skipUi = true, preferVendorAction = true)
                    if (error == null) {
                        return ClockOutcome.Done(
                            ClockFallbackKind.DIRECT_SILENT,
                            ClockFallbackPolicy.successMessage(ClockFallbackKind.DIRECT_SILENT, request),
                            verified = false,
                        )
                    }
                    failures += "静默直达：$error"
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
                        return ClockOutcome.Done(
                            ClockFallbackKind.UI_CONFIRM,
                            ClockFallbackPolicy.successMessage(
                                ClockFallbackKind.UI_CONFIRM,
                                request,
                                clickedLabel = lastClickedLabel,
                            ),
                            verified = true,
                        )
                    }
                    failures += "无障碍确认：$error"
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

    // ---------------------------------------------------------------- 一级：静默直达

    /**
     * 发一次时钟 Intent。
     * @return null=已交付；非空=中文失败原因（用于下一级文案）
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun dispatch(request: ClockRequest, skipUi: Boolean, preferVendorAction: Boolean): String? {
        val probe = fillIntent(request, Intent(request.action), skipUi) ?: return "时钟类型无法识别"

        // 1) 厂商私有 action（解析不到就当没有，不影响标准意图）
        if (preferVendorAction) {
            val route = ClockVendorRoutes.pick(ClockVendorRoutes.of(request)) { candidate ->
                canResolve(Intent(candidate.action).setPackage(candidate.packageName))
            }
            if (route != null) {
                val intent = fillIntent(request, Intent(route.action).setPackage(route.packageName), skipUi)
                if (intent != null && start(intent) == null) return null
            }
        }

        // 2) 按厂商包名显式解析标准 action
        val preferred = ClockPackages.PREFERRED.firstOrNull { pkg ->
            canResolve(Intent(probe).setPackage(pkg))
        }
        if (preferred != null) {
            return start(fillIntent(request, Intent(request.action).setPackage(preferred), skipUi)!!)
        }

        // 3) 隐式解析标准 action
        return if (canResolve(probe)) start(probe) else ClockPackages.NO_CLOCK_MESSAGE
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
        val insert = shell(
            "content insert --uri '$uri' --bind hour:i:$hour --bind minutes:i:$minute " +
                "--bind enabled:i:1 --bind vibrate:i:1$bindLabel",
        )
        val insertOut = when (insert) {
            is ShizukuManager.ShellResult.Success -> insert.output
            is ShizukuManager.ShellResult.Failure -> return "插入被拒（${insert.reason}）"
        }
        if (insertOut.contains("Error", ignoreCase = true) || insertOut.contains("Exception", ignoreCase = true)) {
            return "插入失败（${insertOut.take(80)}）"
        }

        // 回查：拿不到这一行就是没写进去，不能把「命令没报错」当成成功
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val check = shell("content query --uri '$uri' --where 'hour=$hour AND minutes=$minute' --projection hour:minutes")
        val checkOut = (check as? ShizukuManager.ShellResult.Success)?.output.orEmpty()
        if (!checkOut.contains("hour=$hour") || !checkOut.contains("minutes=$minute")) {
            return "写完回查不到该闹钟"
        }
        return null
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ---------------------------------------------------------------- 三级：无障碍确认

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun confirmViaAccessibility(request: ClockRequest): String? {
        val exec = executor ?: return "无障碍服务不在线"
        val error = dispatch(request, skipUi = false, preferVendorAction = false)
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

    private fun openClockPage(request: ClockRequest): Boolean {
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
}
