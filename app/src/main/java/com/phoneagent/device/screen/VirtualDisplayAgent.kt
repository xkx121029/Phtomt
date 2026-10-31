package com.phoneagent.device.screen

import com.phoneagent.device.shell.ShizukuManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 虚拟显示自动化代理（对齐 Operit Shower 的思路，落地为 Shizuku shell 方案）：
 *
 * 通过 Shizuku 写 `settings put global overlay_display_devices` 挂一块模拟副屏，
 * 任务在副屏上独立进行：`am start --display` 启动应用、`input -d` 注入触控、
 * `screencap -d` 截图。全程不抢占用户前台，用户可继续正常使用主屏。
 *
 * 能力边界（shell 方案的天然限制，与 Shower 的 native 虚拟屏不同）：
 * - 模拟副屏由系统"模拟辅助显示设备"功能提供，需 AOSP 系设置未被厂商禁用；
 * - `input -d` / `screencap -d` 需 Android 10+；`am start --display` 需 Android 8+；
 * - 副屏分辨率固定 1080p/160dpi（与主屏无关，坐标按 1080x1920 计）。
 *
 * 生命周期：[ensure] 幂等创建 → 执行阶段用 [launch]/[tap]/[screenshot] → [destroy] 释放。
 * 全部操作经 [mutex] 串行化，避免并发创建/销毁打架。
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
        private const val POLL_INTERVAL_MS = 300L
        /** 截图暂存路径（app 外部缓存，shell 可读） */
        private const val SHOT_PATH = "/data/local/tmp/phoneagent_vd_shot.png"

        /** base64 输出清洗与 displayId 解析正则（预编译，避免每次截图/轮询都构造） */
        private val WHITESPACE_RE = Regex("\\s")
        private val DISPLAY_ID_RE = Regex("""mDisplayId=(\d+)""")
        private val DISPLAY_ID_ALT_RE = Regex("""Display id=(\d+)""")
    }

    /** 已创建的副屏 displayId；null 表示未创建 */
    @Volatile
    var displayId: Int? = null
        private set

    private val mutex = Mutex()

    /** 一次操作的执行结果 */
    sealed class Outcome {
        data class Ok(val output: String = "") : Outcome()
        data class Fail(val reason: String) : Outcome()
    }

    /** 当前副屏是否就绪 */
    val isReady: Boolean get() = displayId != null

    /**
     * 确保副屏存在（幂等）：已就绪直接返回；否则记录现有 displayId 集合，
     * 写入 overlay 规格后轮询 diff 出新 id。
     */
    suspend fun ensure(): Outcome = mutex.withLock {
        displayId?.let { return Outcome.Ok("display=$it") }
        if (!shizuku.isAvailable()) return Outcome.Fail("Shizuku 不可用，虚拟显示需要 Shizuku 通道")
        val before = listDisplayIds()
        // 先清掉历史残留再写入，避免旧条目与新规格拼接产生多块屏
        shizuku.executeShell("settings put global $SETTINGS_KEY $OVERLAY_SPEC").let {
            if (it is ShizukuManager.ShellResult.Failure) return Outcome.Fail("写入模拟显示设置失败：${it.reason}")
        }
        val deadline = System.currentTimeMillis() + CREATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val fresh = listDisplayIds() - before
            // 跳过 0（主屏恒在 before 里，diff 不会含 0，此处防御 overlay id 异常）
            val candidate = fresh.firstOrNull { it != 0 }
            if (candidate != null) {
                displayId = candidate
                return Outcome.Ok("display=$candidate")
            }
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
        }
        Outcome.Fail("模拟副屏创建超时（设备可能不支持模拟辅助显示）")
    }

    /** 释放副屏：清空 overlay 设置并复位状态（幂等，未创建时调用无害） */
    suspend fun destroy() = mutex.withLock {
        if (displayId == null) return
        shizuku.executeShell("settings delete global $SETTINGS_KEY")
        displayId = null
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
        if (target.contains('/')) return run("am start --display $id -n $target")
        val resolved = when (val r = shizuku.executeShell("cmd package resolve-activity --brief $target")) {
            is ShizukuManager.ShellResult.Success ->
                r.output.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') }
            is ShizukuManager.ShellResult.Failure -> null
        }
        return if (resolved != null) {
            run("am start --display $id -n $resolved")
        } else {
            // 解析不到入口（少数 ROM 的 resolve-activity 行为差异）：monkey 同样支持指定 display
            run("monkey -p $target --display $id -c android.intent.category.LAUNCHER 1")
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
     * 失败（如系统 screencap 不支持 -d）返回 null，调用方可回退主屏截图。
     */
    suspend fun screenshot(): ByteArray? {
        val id = displayId ?: return null
        val r = run("screencap -d $id -p $SHOT_PATH")
        if (r !is Outcome.Ok) return null
        val b64 = shizuku.executeShell("base64 $SHOT_PATH && rm -f $SHOT_PATH")
        val text = when (b64) {
            is ShizukuManager.ShellResult.Success -> b64.output
            is ShizukuManager.ShellResult.Failure -> return null
        }
        return runCatching {
            android.util.Base64.decode(text.replace(WHITESPACE_RE, ""), android.util.Base64.DEFAULT)
        }.getOrNull()
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
