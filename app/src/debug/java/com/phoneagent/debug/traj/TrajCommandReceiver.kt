package com.phoneagent.debug.traj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * adb 驱动入口（debug-only）。一个 action、一个 `cmd` 参数分派全部操作：
 *
 * ```
 * adb shell am broadcast -a com.phoneagent.traj.CMD -n com.phoneagent/.debug.traj.TrajCommandReceiver \
 *     --es cmd start --es task "打开设置-电池"
 * adb shell am broadcast -a com.phoneagent.traj.CMD -n com.phoneagent/.debug.traj.TrajCommandReceiver \
 *     --es cmd step --es action click --ei x 540 --ei y 1180
 * adb shell am broadcast ... --es cmd finish --es outcome success --es note "已进入电池页"
 * adb shell am broadcast ... --es cmd zip
 * ```
 *
 * 命令用单线程串行执行：每一步都是「抓树 → 截图 → 派发动作 → 等界面稳定 → 落盘」的原子序列，
 * 并发执行会让两张截图串到同一状态上。
 */
class TrajCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TrajCapture"

        /** 外部驱动用的广播 action（debug 清单里注册） */
        const val ACTION = "com.phoneagent.traj.CMD"

        /** 串行队列：命令之间不重叠 */
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "traj-cmd").apply { isDaemon = true }
        }

        /** 默认界面稳定等待：派发动作后到下一帧可采集之间留的余量 */
        private const val DEFAULT_SETTLE_MS = 800L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val extras = BundleCopy(intent)
        executor.execute {
            val result = runCatching { handle(appContext, extras) }.getOrElse { error ->
                Log.w(TAG, "命令执行失败：${error.message}", error)
                JSONObject().apply {
                    put("ok", false)
                    put("error", "${error.javaClass.simpleName}: ${error.message}")
                }
            }
            // adb 的 "result data" 只在有序广播里可见，这里同时写文件 + 打日志，驱动脚本读文件判定
            Log.i(TAG, "命令结果：$result")
            runCatching {
                File(TrajRecorder(appContext).root, "last_command.json")
                    .apply { parentFile?.mkdirs() }
                    .writeText(result.toString(2), Charsets.UTF_8)
            }
            runCatching {
                pending.setResultCode(if (result.optBoolean("ok")) 0 else 1)
                pending.setResultData(result.toString())
            }
            pending.finish()
        }
    }

    /** Intent 在回调返回后仍可读，这里拷一份，避免跨线程持有 Intent 引发歧义 */
    private class BundleCopy(private val intent: Intent) {
        fun str(key: String): String? = intent.getStringExtra(key)
        fun int(key: String, fallback: Int): Int =
            if (intent.hasExtra(key)) intent.getIntExtra(key, fallback) else fallback

        fun long(key: String, fallback: Long): Long =
            if (intent.hasExtra(key)) intent.getLongExtra(key, fallback) else fallback

        fun bool(key: String, fallback: Boolean): Boolean {
            if (!intent.hasExtra(key)) return fallback
            return runCatching { intent.getBooleanExtra(key, fallback) }.getOrDefault(fallback)
        }
    }

    private fun handle(context: Context, args: BundleCopy): JSONObject {
        val cmd = text(args, "cmd")?.lowercase() ?: "status"
        val recorder = TrajRecorder(context)
        val service = TrajCaptureService.instance
        val (screenWidth, screenHeight) = service?.screenSize()
            ?: (context.resources.displayMetrics.widthPixels to context.resources.displayMetrics.heightPixels)

        return when (cmd) {
            "start" -> {
                val task = text(args, "task").orEmpty()
                if (task.isBlank()) return fail("start 需要 --es task \"任务描述\"（含空格/中文建议用 task_b64）")
                val meta = recorder.start(
                    task = task,
                    taskId = text(args, "task_id"),
                    suite = text(args, "suite").orEmpty(),
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    profile = text(args, "profile").orEmpty(),
                )
                JSONObject().apply {
                    put("ok", true)
                    put("cmd", "start")
                    put("episode_id", meta.optString("episode_id"))
                    put("dir", recorder.episodesDir.absolutePath)
                    put("task_name", meta.optString("task_name"))
                    put("profile", meta.optJSONObject("device_profile")?.optString("profile"))
                }
            }

            "step" -> {
                if (service == null) return fail("无障碍采集服务未连接：先在系统设置里开启 HAPPY Traj Capture (debug)")
                val episode = recorder.openEpisode() ?: return fail("没有进行中的集，先执行 cmd=start")
                val parsed = parseAction(args)
                if (parsed.action.isBlank()) return fail("step 需要 --es action <动作>")
                // type/enter 必须带可执行落点：显式 x/y 优先，否则取当前输入焦点中心。
                // 两者都拿不到时这一帧直接判采集失败并丢弃（不落坏样本），由驱动侧重试。
                val needsPoint = parsed.action in setOf("type", "enter")
                val spec = if (needsPoint && (parsed.x < 0 || parsed.y < 0)) {
                    val focus = service.focusedInputPoint()
                        ?: return discard("no_point", "type/enter 没有可执行落点：未给 x/y 且取不到输入焦点")
                    parsed.copy(x = focus.first, y = focus.second)
                } else {
                    parsed
                }

                // 顺序固定：动作前的状态先落内存，再派发动作
                var tree = service.captureTree()
                if (tree.truncationReasons.contains("no_root")) {
                    // 窗口切换瞬间读不到 root：等一帧再试，仍为空就丢弃该帧（no_root 一律不进库）
                    Thread.sleep(400)
                    tree = service.captureTree()
                    if (tree.truncationReasons.contains("no_root")) {
                        return discard("no_root", "读不到可访问窗口，本帧丢弃（已计数）")
                    }
                }
                val screenshot = service.takeScreenshotBlocking()
                val outcome = service.perform(spec)
                val settle = args.long("settle", DEFAULT_SETTLE_MS).coerceIn(0, 10_000)
                if (settle > 0) Thread.sleep(settle)
                // intent / intent_ok：驱动脚本声明的「这步想干什么、是否达成」，用于标失败与纠错步
                val intent = text(args, "intent").orEmpty()
                val intentOk = args.bool("intent_ok", true)
                val step = recorder.recordStep(episode, tree, screenshot, spec, outcome, settle, intent, intentOk)
                JSONObject().apply {
                    put("ok", outcome.ok)
                    put("cmd", "step")
                    put("step", step.optInt("step"))
                    put("action", spec.action)
                    put("screenshot", step.optString("screenshot"))
                    put("elements", step.optInt("elements_total"))
                    put("target_index", step.optJSONObject("action_data")?.optInt("target_index", -1) ?: -1)
                    put("detail", outcome.detail)
                    put("package", tree.packageName ?: "")
                    put("intent", intent)
                    put("intent_ok", intentOk)
                    // 截断/稀疏帧要回给驱动脚本落统计：这类帧不能当完整树用
                    put("truncated", tree.truncated)
                    put("sparse", tree.sparse)
                    put("discarded", false)
                }
            }

            "finish" -> {
                val meta = recorder.finish(
                    text(args, "outcome") ?: "unknown",
                    text(args, "note").orEmpty(),
                    text(args, "negative_kind").orEmpty(),
                )
                if (!meta.has("outcome")) return fail(meta.optString("error").ifBlank { "收尾失败" })
                JSONObject().apply {
                    put("ok", true)
                    put("cmd", "finish")
                    put("episode_id", meta.optString("episode_id"))
                    put("outcome", meta.optString("outcome"))
                    put("steps", meta.optInt("steps"))
                    put("negative_kind", meta.optString("negative_kind"))
                }
            }

            "zip" -> recorder.exportZip()

            "probe" -> {
                if (service == null) return fail("无障碍采集服务未连接")
                val tree = service.captureStableTree()
                recorder.probe(tree, service.takeScreenshotBlocking())
            }

            "clear" -> JSONObject().apply {
                put("ok", true)
                put("cmd", "clear")
                put("removed_episodes", recorder.clear())
            }

            "status" -> JSONObject().apply {
                put("ok", true)
                put("cmd", "status")
                put("state", recorder.status(screenWidth, screenHeight))
            }

            else -> fail("未知 cmd=$cmd（可用：start/step/probe/finish/zip/clear/status）")
        }
    }

    private fun parseAction(args: BundleCopy): TrajCaptureService.ActionSpec {
        val action = text(args, "action")?.lowercase()?.trim().orEmpty()
        return TrajCaptureService.ActionSpec(
            action = action,
            x = args.int("x", -1),
            y = args.int("y", -1),
            x2 = args.int("x2", -1),
            y2 = args.int("y2", -1),
            text = text(args, "text"),
            packageName = text(args, "package"),
            durationMs = args.long("duration", 0),
            waitMs = args.long("wait", 0),
        )
    }

    /**
     * 取文本 extra：优先 `<key>_b64`。
     *
     * adb shell 会把广播参数拼成一条 shell 命令，任务名/备注里的空格、引号、分号、竖线
     * 会被设备端 sh 当成语法；base64 只含 [A-Za-z0-9+/=]，怎么传都安全。
     */
    private fun text(args: BundleCopy, key: String): String? {
        args.str("${key}_b64")?.let { encoded ->
            return runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        }
        return args.str(key)
    }

    private fun fail(reason: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", reason)
    }

    /**
     * 该帧采集失败、不落库。驱动脚本按 `discarded` + `reason` 计数并重试；
     * 这类帧（缺落点 / no_root）绝不能当正常样本写进 trajectory。
     */
    private fun discard(kind: String, reason: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("discarded", true)
        put("reason", kind)
        put("error", reason)
    }
}
