package com.phoneagent.device.screen

import com.phoneagent.device.shell.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 状态里展示的降级时间（HH:mm）：用 Calendar 拼，避免 SimpleDateFormat 的线程安全问题 */
private fun clockOf(atMillis: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = atMillis }
    return String.format(
        java.util.Locale.getDefault(),
        "%02d:%02d",
        c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE),
    )
}

/**
 * 副屏状态：设置页与引擎日志读同一份事实，避免"开关看着开着，但没人知道卡在哪一环"。
 *
 * 四态对应设置页要展示的四种结论，[Idle] 只是"Shizuku 就绪、本次进程还没创建过"的过渡态。
 */
sealed interface VirtualDisplayStatus {
    /** 过渡态：Shizuku 就绪，任务开始时才会真正创建副屏 */
    data object Idle : VirtualDisplayStatus
    /** 未激活 Shizuku：副屏依赖 shell 通道，没有它连 settings 都写不进去 */
    data object ShizukuInactive : VirtualDisplayStatus
    /** 已启用：display=N */
    data class Active(val displayId: Int) : VirtualDisplayStatus
    /** 系统不支持：写入了设置但系统没挂出副屏（厂商关闭模拟辅助显示 / 系统版本过低） */
    data class Unsupported(val reason: String) : VirtualDisplayStatus
    /** 失败：写入被拒、shell 超时等，原因可展开看诊断 */
    data class Failed(val reason: String) : VirtualDisplayStatus
    /**
     * 运行中降级到主屏：副屏曾创建成功，但观察或启动落点校验失败。
     * 保留原因与发生时间，直到下次成功创建副屏或用户手动关开关——
     * 不能在任务收尾时被 destroy() 覆盖成"已就绪"。
     */
    data class Degraded(val reason: String, val atMillis: Long) : VirtualDisplayStatus

    /** 设置页展示口径 */
    data class Presentation(val title: String, val detail: String, val isFailure: Boolean)

    /**
     * 四态判定（纯函数）：副屏状态与 Shizuku 通道状态合成为用户能读懂的一句话。
     * 判定放在服务层而不是 UI 里，单测可以逐态覆盖。
     */
    fun present(shizukuReady: Boolean): Presentation = when {
        this is Active -> Presentation(
            "已启用 display=${displayId}",
            "任务正在副屏上运行，不占用当前屏幕",
            isFailure = false,
        )
        // 降级原因优先于"Shizuku 掉线"：那正是用户最需要看到的那条事实
        this is Degraded -> Presentation(
            "已降级到主屏",
            "原因：$reason（${clockOf(atMillis)}）· 本轮任务已在主屏继续",
            isFailure = true,
        )
        !shizukuReady -> Presentation(
            "未激活 Shizuku",
            "副屏的创建与驱动都走内置 Shizuku 的 shell 通道：请先在「权限」页启动并授权",
            isFailure = true,
        )
        this is Unsupported -> Presentation("系统不支持", reason, isFailure = true)
        this is Failed -> Presentation("创建失败", reason, isFailure = true)
        else -> Presentation(
            "已就绪",
            "Shizuku 通道在线，任务开始时会创建副屏；若创建失败会在这里给出原因",
            isFailure = false,
        )
    }
}

/**
 * 虚拟显示自动化代理（对齐 Operit Shower 的思路，落地为 Shizuku shell 方案）：
 *
 * 通过 Shizuku 写 `settings put global overlay_display_devices` 挂一块模拟副屏，
 * 任务在副屏上独立进行：`am start --display` 启动应用、`input -d` 注入触控、
 * 截图走 `screencap -d`（部分系统对模拟屏不支持，失败时由引擎明确改为"只用无障碍树"）。
 * 全程不抢占用户前台，用户可继续正常使用主屏。
 *
 * 能力边界（shell 方案的天然限制，与 Shower 的 native 虚拟屏不同）：
 * - 模拟副屏由系统"模拟辅助显示设备"功能提供，需 AOSP 系设置未被厂商禁用；
 * - `input -d` / `screencap -d` 需 Android 10+；`am start --display` 需 Android 8+；
 * - 副屏分辨率固定 1080p/160dpi（与主屏无关，坐标按 1080x1920 计）；
 * - `screencap -d` 在部分系统（实测 Android 16）只认物理屏，副屏截图拿不到——
 *   这条失败必须如实上报，**不允许拿主屏截图冒充副屏画面**。
 *
 * 生命周期：[ensure] 幂等创建（创建前先清残留）→ 执行阶段用 [launch]/[tap]/[screenshot] →
 * [destroy] 释放（幂等，未创建也清一遍设置项）。全部操作经 [mutex] 串行化，避免并发创建/销毁打架。
 */
class VirtualDisplayAgent(private val shizuku: ShizukuManager) {

    companion object {
        /** 模拟副屏逻辑宽（与 OVERLAY_SPEC 一致，坐标按此换算） */
        const val WIDTH = 1080
        /** 模拟副屏逻辑高 */
        const val HEIGHT = 1920
        /** 模拟副屏规格：1080x1920 / 160dpi（写进 overlay_display_devices 的标准格式） */
        private const val OVERLAY_SPEC = "1080x1920/160"
        private const val SETTINGS_KEY = "overlay_display_devices"
        /** 等待系统真正挂上副屏的轮询上限 */
        private const val CREATE_TIMEOUT_MS = 8_000L
        /** 写入后的回读窗口：settings put 的命令回执不可信，以回读到的设置值为准 */
        private const val WRITE_VERIFY_TIMEOUT_MS = 5_000L
        /** 起应用后回读任务栈落点的窗口：`am start --display` 成功不代表应用真的在副屏 */
        private const val LAUNCH_VERIFY_TIMEOUT_MS = 3_000L
        /** 删除设置项后等待系统回收旧副屏的上限：不等它回收，新屏可能复用同一个 id，diff 就永远等不到 */
        private const val TEARDOWN_TIMEOUT_MS = 3_000L
        private const val POLL_INTERVAL_MS = 300L
        /** 截图暂存路径（app 外部缓存，shell 可读） */
        private const val SHOT_PATH = "/data/local/tmp/phoneagent_vd_shot.png"

        /** base64 输出清洗与 displayId 解析正则（预编译，避免每次截图/轮询都构造） */
        private val WHITESPACE_RE = Regex("\\s")
        private val DISPLAY_ID_RE = Regex("""mDisplayId=(\d+)""")
        private val DISPLAY_ID_ALT_RE = Regex("""Display id=(\d+)""")

        private val _status = MutableStateFlow<VirtualDisplayStatus>(VirtualDisplayStatus.Idle)
        /** 供设置页收集的副屏状态（四态 + 过渡态） */
        val status: StateFlow<VirtualDisplayStatus> = _status.asStateFlow()

        /** 最近一次失败的完整诊断（设置页展开项展示；成功时清空） */
        @Volatile
        var lastDiagnostic: String = ""
            private set

        /**
         * 销毁后的状态迁移（纯函数，便于单测）。
         *
         * - 运行时降级（[degradedReason] 非空）：保留 [VirtualDisplayStatus.Degraded]，不回落到 Idle；
         * - 正常收尾 / 用户主动关闭：原来有活动副屏才回 Idle；
         * - 其余（创建失败等）：原样保留，避免把该给用户看的失败原因擦掉。
         */
        internal fun statusAfterDestroy(
            previous: VirtualDisplayStatus,
            degradedReason: String?,
            atMillis: Long,
        ): VirtualDisplayStatus = when {
            degradedReason != null -> VirtualDisplayStatus.Degraded(degradedReason, atMillis)
            previous is VirtualDisplayStatus.Active -> VirtualDisplayStatus.Idle
            else -> previous
        }

        /** 起应用落点判定（纯函数）：见到副屏即成功；只在别的屏上出现或始终读不到都算失败 */
        internal fun landingResult(expectedDisplayId: Int, seen: Set<Int>, waitedMs: Long): Outcome = when {
            seen.contains(expectedDisplayId) -> Outcome.Ok("display=$expectedDisplayId")
            seen.isNotEmpty() -> Outcome.Fail(
                "应用未落在副屏（任务实际在 display=${seen.sorted().joinToString("/")}，期望 $expectedDisplayId）",
                landingMismatch = true,
            )
            else -> Outcome.Fail(
                "应用未落在副屏（等待 ${waitedMs}ms 后仍未读到该应用的任务栈）",
                landingMismatch = true,
            )
        }

        /**
         * 解析 `am stack list`：按 RootTask 分块，块内出现该包名的 taskId 即记下块的 displayId。
         * 同时兼容旧版 `Stack id=` 输出。
         */
        internal fun displayIdsInStackList(output: String, pkg: String): Set<Int> {
            val ids = mutableSetOf<Int>()
            var current: Int? = null
            val displayRe = Regex("""displayId=(\d+)""")
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("RootTask") || line.startsWith("Stack id=")) {
                    current = displayRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                } else if (line.startsWith("taskId=") && line.contains("$pkg/")) {
                    current?.let { ids.add(it) }
                }
            }
            return ids
        }

        /** 解析 `dumpsys activity activities`：按 `displayId=N` 分段，段内 Task 的 I=包名/ 归属该屏 */
        internal fun displayIdsInActivityDump(output: String, pkg: String): Set<Int> {
            val ids = mutableSetOf<Int>()
            var current: Int? = null
            val displayRe = Regex("""displayId=(\d+)""")
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("displayId=")) {
                    current = displayRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    continue
                }
                if (line.contains("Task{") && line.contains("I=$pkg/")) {
                    current?.let { ids.add(it) }
                }
            }
            return ids
        }

        private fun publish(next: VirtualDisplayStatus, diagnostic: String = "") {
            lastDiagnostic = diagnostic
            _status.value = next
        }

        /**
         * App 启动时的幽灵屏清理：上个进程被强杀后 `overlay_display_devices` 会留在系统设置里，
         * 系统因此一直挂着一块没人用的副屏（用户会在显示设置里看到"第二块屏"）。
         *
         * 新进程启动时不可能有本进程创建的活动副屏，所以这里可以无条件清掉。
         */
        suspend fun cleanupGhost(shizuku: ShizukuManager) {
            if (!shizuku.isAvailable()) return
            runCatching { shizuku.executeShell("settings delete global $SETTINGS_KEY") }
            runCatching { shizuku.executeShell("rm -f $SHOT_PATH") }
        }

        /** 用户手动关闭开关：清掉上一轮的降级原因/诊断，回到就绪态 */
        fun clearStatus() = publish(VirtualDisplayStatus.Idle)
    }

    /** 已创建的副屏 displayId；null 表示未创建 */
    @Volatile
    var displayId: Int? = null
        private set

    /** 最近一次副屏截图失败的原因（screencap 的 stderr），供日志与诊断引用 */
    @Volatile
    var lastScreenshotError: String? = null
        private set

    private val mutex = Mutex()

    /** 一次操作的执行结果 */
    sealed class Outcome {
        data class Ok(val output: String = "") : Outcome()

        /**
         * [landingMismatch] 为 true 表示命令执行成功、但回读发现应用没落在副屏上。
         * 调用方据此把副屏整体降级主屏，而不是继续在错误的屏上跑后续动作。
         */
        data class Fail(val reason: String, val landingMismatch: Boolean = false) : Outcome()
    }

    /** 当前副屏是否就绪 */
    val isReady: Boolean get() = displayId != null

    /**
     * 确保副屏存在（幂等）：已就绪直接返回；否则**先清残留**，再写入 overlay 规格并轮询新 id。
     *
     * 清残留是必要的：崩溃/强杀留下的旧 overlay 会让系统复用同一个 displayId，
     * 只做 diff 的话"新 id"永远不出现，表现为创建超时。
     */
    suspend fun ensure(): Outcome = mutex.withLock {
        displayId?.let { return Outcome.Ok("display=$it") }
        if (!shizuku.isAvailable()) {
            publish(
                VirtualDisplayStatus.ShizukuInactive,
                "Shizuku 通道不可用：副屏的创建/驱动依赖 shell（settings、am、input 均需 root 或 shell uid）",
            )
            return Outcome.Fail("Shizuku 不可用，虚拟显示需要 Shizuku 通道")
        }
        clearOverlaySetting()
        waitOverlayGone()
        val before = listDisplayIds()
        // 先清掉历史残留再写入，避免旧条目与新规格拼接产生多块屏。
        // 判据是「回读」而不是「命令退出」：Shizuku 跑 settings put 时子进程会继承 stdout，
        // 命令回执可能变成 "process hasn't exited"，但设置往往已经生效——拿回执判失败是误判。
        val put = shizuku.executeShell("settings put global $SETTINGS_KEY $OVERLAY_SPEC")
        val verified = awaitOverlayValue(OVERLAY_SPEC, WRITE_VERIFY_TIMEOUT_MS)
        if (verified !is OverlayRead.Value || verified.raw != OVERLAY_SPEC) {
            val reason = describeWriteFailure(put, verified)
            publish(
                VirtualDisplayStatus.Failed(reason),
                buildString {
                    appendLine(reason)
                    appendLine("命令：settings put global $SETTINGS_KEY $OVERLAY_SPEC")
                    append("命令回执：${describeResult(put)}")
                },
            )
            return Outcome.Fail(reason)
        }
        // 回读已生效，但命令回执是失败：如实记一条「未返回但已生效」，不冒充命令成功
        val commandNotReturned = put is ShizukuManager.ShellResult.Failure
        val deadline = System.currentTimeMillis() + CREATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val fresh = listDisplayIds() - before
            // 跳过 0（主屏恒在 before 里，diff 不会含 0，此处防御 overlay id 异常）
            val candidate = fresh.firstOrNull { it != 0 }
            if (candidate != null) {
                displayId = candidate
                publish(
                    VirtualDisplayStatus.Active(candidate),
                    if (commandNotReturned) {
                        "写入命令未返回但已生效：回读 $SETTINGS_KEY=$OVERLAY_SPEC，display=$candidate"
                    } else {
                        ""
                    },
                )
                return Outcome.Ok("display=$candidate")
            }
            delay(POLL_INTERVAL_MS)
        }
        val reason = "模拟副屏创建超时（设备可能不支持模拟辅助显示）"
        publish(
            VirtualDisplayStatus.Unsupported(reason),
            buildString {
                appendLine(reason)
                appendLine("已写入：$SETTINGS_KEY=$OVERLAY_SPEC")
                appendLine("等待 ${CREATE_TIMEOUT_MS}ms 后当前 display：${listDisplayIds().sorted()}")
                append("常见原因：厂商关闭「模拟辅助显示设备」、系统策略禁止 overlay_display_devices、或系统版本过低")
            },
        )
        Outcome.Fail(reason)
    }

    /**
     * 释放副屏：清空 overlay 设置并复位状态（幂等，未创建时也会清一遍设置项）。
     *
     * [degradedReason] 区分两条路径：
     * - `null`：任务正常收尾 / 用户手动关闭 → 原来有活动副屏才回 Idle；
     * - 非空：运行时降级（观察全失败、启动落点不符）→ 发布 [VirtualDisplayStatus.Degraded]，
     *   把原因与发生时间留在设置页，直到下次成功创建副屏或用户关闭开关。
     */
    suspend fun destroy(degradedReason: String? = null) = mutex.withLock {
        val previous = _status.value
        val wasActive = previous is VirtualDisplayStatus.Active
        val atMillis = System.currentTimeMillis()
        displayId = null
        if (shizuku.isAvailable()) {
            // 删除与写入同口径：settings delete 也可能不返回，以回读是否清空为准
            val del = runCatching { shizuku.executeShell("settings delete global $SETTINGS_KEY") }.getOrNull()
            val cleared = awaitOverlayCleared(TEARDOWN_TIMEOUT_MS + WRITE_VERIFY_TIMEOUT_MS)
            if (!cleared && wasActive) {
                val current = when (val read = readOverlaySetting()) {
                    is OverlayRead.Value -> read.raw ?: ""
                    OverlayRead.Unreadable -> "（回读失败）"
                }
                val reason = "关闭副屏失败：设置项未清空（回读「$current」）"
                if (degradedReason == null) {
                    publish(
                        VirtualDisplayStatus.Failed(reason),
                        buildString {
                            appendLine(reason)
                            appendLine("命令：settings delete global $SETTINGS_KEY")
                            append("命令回执：${describeResult(del)}")
                        },
                    )
                    return@withLock
                }
                // 降级路径：主结论仍是降级原因，关闭异常只补进诊断，不覆盖原因
                publish(
                    VirtualDisplayStatus.Degraded(degradedReason, atMillis),
                    buildString {
                        appendLine("降级原因：$degradedReason")
                        appendLine(reason)
                        appendLine("命令：settings delete global $SETTINGS_KEY")
                        append("命令回执：${describeResult(del)}")
                    },
                )
                return@withLock
            }
            runCatching { shizuku.executeShell("rm -f $SHOT_PATH") }
        }
        // 正常收尾才回 Idle；运行时降级必须保留原因（见 statusAfterDestroy）
        publish(statusAfterDestroy(previous, degradedReason, atMillis))
    }

    /** settings 回读结果：Value(raw=null) 表示未设置；Unreadable 表示 shell 读不出来 */
    private sealed interface OverlayRead {
        data class Value(val raw: String?) : OverlayRead
        data object Unreadable : OverlayRead
    }

    /** 回读 overlay_display_devices：`settings get` 未设置时输出 "null" */
    private suspend fun readOverlaySetting(): OverlayRead =
        when (val r = shizuku.executeShell("settings get global $SETTINGS_KEY")) {
            is ShizukuManager.ShellResult.Success ->
                OverlayRead.Value(r.output.trim().takeUnless { it.isBlank() || it == "null" })
            is ShizukuManager.ShellResult.Failure -> OverlayRead.Unreadable
        }

    /** 轮询等回读到期望值；超时返回最后一次读数（用于失败文案） */
    private suspend fun awaitOverlayValue(expected: String, timeoutMs: Long): OverlayRead {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val read = readOverlaySetting()
            if (read is OverlayRead.Value && read.raw == expected) return read
            if (System.currentTimeMillis() >= deadline) return read
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 轮询等设置项被清空（读为 null）；读失败按未清空处理 */
    private suspend fun awaitOverlayCleared(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val read = readOverlaySetting()
            if (read is OverlayRead.Value && read.raw == null) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun describeResult(result: ShizukuManager.ShellResult?): String = when (result) {
        null -> "调用超时或通道不可用"
        is ShizukuManager.ShellResult.Success -> "退出码 0"
        is ShizukuManager.ShellResult.Failure -> "退出码 ${result.exitCode}：${result.reason}"
    }

    /**
     * 写入失败的两种口径必须分开：
     * - 命令没回来（超时 / Shizuku 的 "process hasn't exited"）：可能还在跑，但回读没看到目标值；
     * - 命令正常返回但设置没变：是真的没写进去。
     */
    private fun describeWriteFailure(result: ShizukuManager.ShellResult, read: OverlayRead): String {
        val current = when (read) {
            is OverlayRead.Value -> read.raw ?: "（未设置）"
            OverlayRead.Unreadable -> "（回读失败）"
        }
        val reason = (result as? ShizukuManager.ShellResult.Failure)?.reason.orEmpty()
        val nonReturning = reason.contains("超时") || reason.contains("hasn't exited")
        return if (nonReturning) {
            "写入模拟显示设置失败：命令超时未返回，且回读未看到目标值（回读「$current」）"
        } else {
            "写入模拟显示设置失败：命令已返回但设置未生效（回读「$current」）"
        }
    }

    /** 删除 overlay 设置项（失败不打断调用方：ensure 后面还有真正的判定） */
    private suspend fun clearOverlaySetting() {
        runCatching { shizuku.executeShell("settings delete global $SETTINGS_KEY") }
    }

    /** 等系统把旧副屏回收掉（只剩主屏 0），超时也继续——创建阶段还有自己的超时兜底 */
    private suspend fun waitOverlayGone() {
        val deadline = System.currentTimeMillis() + TEARDOWN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (listDisplayIds().count { it != 0 } == 0) return
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * 在副屏上启动应用：先 `cmd package resolve-activity` 解析入口 Activity，再
     * `am start --display <id> -n pkg/Act` 指定副屏启动。
     * （`am start` 没有 `-p` 选项，直接传包名会解析失败；解析不到入口时退回 monkey）
     * [pkg] 支持包名，或已是 `包名/Activity` 全名。
     */
    suspend fun launch(pkg: String): Outcome {
        val id = displayId ?: return Outcome.Fail("虚拟副屏未创建")
        val target = pkg.trim().trim('"')
        if (target.isBlank()) return Outcome.Fail("应用包名为空")
        val started = if (target.contains('/')) {
            run("am start --display $id -n $target")
        } else {
            val resolved = when (val r = shizuku.executeShell("cmd package resolve-activity --brief $target")) {
                is ShizukuManager.ShellResult.Success ->
                    r.output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }
                is ShizukuManager.ShellResult.Failure -> null
            }
            if (resolved != null) {
                run("am start --display $id -n $resolved")
            } else {
                // 解析不到入口（少数 ROM 的 resolve-activity 行为差异）：monkey 同样支持指定 display
                run("monkey -p $target --display $id -c android.intent.category.LAUNCHER 1")
            }
        }
        if (started is Outcome.Fail) return started
        // 命令成功 ≠ 落在副屏：应用已有主屏任务时，部分系统会把那个任务直接带到前台，
        // `am start --display` 仍是退出码 0。必须回读任务栈归属，否则后续动作会打错屏。
        return awaitLanding(target.substringBefore('/'), id)
    }

    /**
     * 起应用后回读任务栈所在 display（`am stack list` 为主，旧版本退回 `dumpsys activity activities`）。
     *
     * 判定交给纯函数 [landingResult]：见到副屏即成功；只在别的屏上出现、或窗口内始终读不到，
     * 都返回 [Outcome.Fail] 且 `landingMismatch = true`，由引擎降级主屏。
     */
    private suspend fun awaitLanding(pkg: String, expectedDisplayId: Int): Outcome {
        val deadline = System.currentTimeMillis() + LAUNCH_VERIFY_TIMEOUT_MS
        while (true) {
            val seen = packageDisplayIds(pkg)
            if (seen.contains(expectedDisplayId) || seen.isNotEmpty()) {
                return landingResult(expectedDisplayId, seen, LAUNCH_VERIFY_TIMEOUT_MS)
            }
            if (System.currentTimeMillis() >= deadline) {
                return landingResult(expectedDisplayId, emptySet(), LAUNCH_VERIFY_TIMEOUT_MS)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 读该包名当前任务栈所在的 display 集合；两种 dump 都读不到时返回空集（按失败处理） */
    private suspend fun packageDisplayIds(pkg: String): Set<Int> {
        when (val r = shizuku.executeShell("am stack list")) {
            is ShizukuManager.ShellResult.Success -> {
                val ids = displayIdsInStackList(r.output, pkg)
                // 输出里没有 RootTask/Stack 块说明命令可用但格式不认识，退回 activity dump
                if (ids.isNotEmpty() || r.output.contains("RootTask") || r.output.contains("Stack id=")) {
                    return ids
                }
            }
            is ShizukuManager.ShellResult.Failure -> Unit
        }
        val dump = shizuku.executeShell("dumpsys activity activities")
        return if (dump is ShizukuManager.ShellResult.Success) {
            displayIdsInActivityDump(dump.output, pkg)
        } else {
            emptySet()
        }
    }

    /** 副屏触控：tap / swipe（坐标按 1080x1920） */
    suspend fun tap(x: Int, y: Int): Outcome {
        val id = displayId ?: return Outcome.Fail("虚拟副屏未创建")
        return run("input -d $id tap $x $y")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Outcome {
        val id = displayId ?: return Outcome.Fail("虚拟副屏未创建")
        return run("input -d $id swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /** 副屏文本输入（注入到副屏当前焦点控件） */
    suspend fun typeText(text: String): Outcome {
        val id = displayId ?: return Outcome.Fail("虚拟副屏未创建")
        // 引号转义，避免文本里的空格/特殊字符打断 shell 词法
        val safe = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        return run("input -d $id text \"$safe\"")
    }

    /**
     * 副屏截图：`screencap -d <id> -p` 生成 PNG 后经 base64 回传字节。
     * （shell 通道的 stdout 按文本读，二进制直传会损坏；base64 是无损通路）
     *
     * 失败（实测 Android 16 的 screencap 只认物理屏）返回 null **且记录原因**：
     * 调用方必须据此明确降级为"只读无障碍树"，绝不能拿主屏截图冒充副屏画面。
     */
    suspend fun screenshot(): ByteArray? {
        val id = displayId ?: run {
            lastScreenshotError = "副屏未创建（displayId 为空）"
            return null
        }
        when (val r = run("screencap -d $id -p $SHOT_PATH")) {
            is Outcome.Ok -> Unit
            is Outcome.Fail -> {
                lastScreenshotError = "screencap -d $id 失败：${r.reason}"
                return null
            }
        }
        val b64 = shizuku.executeShell("base64 $SHOT_PATH && rm -f $SHOT_PATH")
        val text = when (b64) {
            is ShizukuManager.ShellResult.Success -> b64.output
            is ShizukuManager.ShellResult.Failure -> {
                lastScreenshotError = "读取副屏截图失败：${b64.reason}"
                return null
            }
        }
        val bytes = runCatching {
            android.util.Base64.decode(text.replace(WHITESPACE_RE, ""), android.util.Base64.DEFAULT)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) lastScreenshotError = "副屏截图解码失败（${text.length} 字符）"
        return bytes
    }

    /** 副屏按键：keyevent（如 4=返回 3=主页） */
    suspend fun keyevent(code: Int): Outcome {
        val id = displayId ?: return Outcome.Fail("虚拟副屏未创建")
        return run("input -d $id keyevent $code")
    }

    /** 列出当前全部 displayId（dumpsys display 解析；失败返回空集） */
    private suspend fun listDisplayIds(): Set<Int> = runCatching {
        val out = when (val r = shizuku.executeShell("dumpsys display displays")) {
            is ShizukuManager.ShellResult.Success -> r.output
            is ShizukuManager.ShellResult.Failure -> return emptySet()
        }
        DISPLAY_ID_RE.findAll(out).map { it.groupValues[1].toInt() }.toSet()
            .ifEmpty { DISPLAY_ID_ALT_RE.findAll(out).map { it.groupValues[1].toInt() }.toSet() }
    }.getOrDefault(emptySet())

    /** shell 执行包装：Shizuku 结果 → Outcome */
    private suspend fun run(command: String): Outcome = when (val r = shizuku.executeShell(command)) {
        is ShizukuManager.ShellResult.Success -> Outcome.Ok(r.output)
        is ShizukuManager.ShellResult.Failure -> Outcome.Fail(r.reason)
    }
}
