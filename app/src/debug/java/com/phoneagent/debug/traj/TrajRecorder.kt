package com.phoneagent.debug.traj

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 轨迹落盘（debug-only）。
 *
 * 目录布局（`getExternalFilesDir(null)/traj/`，adb 可直接 pull，无需额外权限）：
 * ```
 * traj/
 *   episodes/
 *     meta.json                  # 给抽取管线用的 generation 级元数据（coord_space=px）
 *     results.jsonl              # 每集一行结局（is_success / stop_reason）
 *     <task_safe>_tN/
 *       episode.json             # 任务标识 + 起止时间 + 结局 + 设备信息
 *       meta.json                # 与 episode.json 同源的 task_id/task_name（抽取管线读这个）
 *       trajectory.json          # 每步动作序列（抽取管线读这个）
 *       step_001.jpg             # 动作发生**前**的截图，长边 896
 *       step_001_elements.json   # 同一时刻的压缩元素树 + 本步动作
 * ```
 *
 * 一集一步一条记录，写盘用「先写临时文件再 rename」，采集进程被杀也不会留下半截 JSON。
 */
internal class TrajRecorder(private val context: Context) {

    companion object {
        private const val TAG = "TrajCapture"
        private const val IMAGE_LONG_EDGE = 896
        private const val JPEG_QUALITY = 90
        private const val SCHEMA_EPISODE = "traj-episode/1"
        private const val SCHEMA_ELEMENTS = "traj-elements/1"

        /** probe 回给驱动脚本的元素条数上限（adb 输出可读性优先） */
        private const val PROBE_BRIEF_LIMIT = 80

        private fun stamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(millis))

        private fun slug(raw: String, fallback: String): String {
            val cleaned = raw.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
            return if (cleaned.isBlank()) fallback else cleaned.take(48)
        }
    }

    /** 单集内存态；steps 惰性从磁盘补齐，进程重启后仍能续采 */
    class Episode internal constructor(
        val dir: File,
        val taskId: String,
        val taskName: String,
        val suite: String,
        val startedMs: Long,
        val steps: MutableList<JSONObject>,
    )

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "traj")

    val episodesDir: File = File(root, "episodes")

    // ==================== 集的生命周期 ====================

    /** 开始新一集；上一集未收尾时先按 aborted 收尾，避免两集混在同一目录 */
    fun start(
        task: String,
        taskId: String?,
        suite: String,
        screenWidth: Int,
        screenHeight: Int,
        profile: String = "",
    ): JSONObject {
        openEpisode()?.let { finish("aborted", "被新一集打断") }
        episodesDir.mkdirs()
        val taskName = task.ifBlank { "未命名任务" }
        // 目录名保持 ASCII：中文目录在 Windows 上用 adb pull 会因编码错位失败
        val taskIdSafe = slug(taskId.orEmpty().ifBlank { taskName }, "task")
        var dir = File(episodesDir, "${taskIdSafe}_t0")
        var trial = 0
        while (dir.exists()) {
            trial++
            dir = File(episodesDir, "${taskIdSafe}_t$trial")
        }
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val meta = JSONObject().apply {
            put("schema", SCHEMA_EPISODE)
            put("episode_id", "${taskIdSafe}_t$trial")
            put("task_id", taskIdSafe)
            put("task_name", taskName)
            put("trial_id", trial)
            put("suite", suite)
            put("outcome", "running")
            put("note", "")
            put("started_at", stamp(now))
            put("started_ms", now)
            put("ended_at", JSONObject.NULL)
            put("ended_ms", JSONObject.NULL)
            put("steps", 0)
            put("image_long_edge", IMAGE_LONG_EDGE)
            put("coord_space", "px")
            put("device", deviceJson(screenWidth, screenHeight))
            // 设备档案：同一批任务在不同分辨率/主题/字体下采集，结果要能按 profile 单独筛出来
            put("device_profile", deviceProfile(profile, screenWidth, screenHeight))
        }
        writeJson(File(dir, "episode.json"), meta)
        writeJson(File(dir, "meta.json"), pipelineMeta(meta))
        writeJson(File(dir, "trajectory.json"), JSONArray())
        ensureGenerationMeta(screenWidth, screenHeight)
        Log.i(TAG, "开始采集：${dir.name}（$taskName）")
        return meta
    }

    /** 当前未收尾的一集（没有则 null）；进程重启后从 episode.json 恢复 */
    fun openEpisode(): Episode? {
        if (!episodesDir.isDirectory) return null
        val candidates = episodesDir.listFiles()
            ?.filter { it.isDirectory && File(it, "episode.json").exists() }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        for (dir in candidates) {
            val meta = readJson(File(dir, "episode.json")) ?: continue
            if (meta.optString("outcome") != "running") continue
            val steps = readJsonArray(File(dir, "trajectory.json"))?.let { arr ->
                MutableList(arr.length()) { arr.optJSONObject(it) ?: JSONObject() }
            } ?: mutableListOf()
            return Episode(
                dir = dir,
                taskId = meta.optString("task_id"),
                taskName = meta.optString("task_name"),
                suite = meta.optString("suite"),
                startedMs = meta.optLong("started_ms"),
                steps = steps,
            )
        }
        return null
    }

    /**
     * 落一步：截图与元素树都是**动作发生前**的状态（训练输入契约：状态 → 动作）。
     *
     * @return 该步的摘要（含下标、文件名、命中元素），失败时抛出并带中文原因
     */
    fun recordStep(
        episode: Episode,
        tree: TrajElementTree.Snapshot,
        screenshot: Bitmap?,
        spec: TrajCaptureService.ActionSpec,
        outcome: TrajCaptureService.ActionOutcome,
        settleMs: Long,
        intent: String,
        intentOk: Boolean,
    ): JSONObject {
        val stepNo = episode.steps.size + 1
        val name = "step_%03d".format(stepNo)
        val imageName = "$name.jpg"
        val elementsName = "${name}_elements.json"
        val targetIndex = if (spec.x >= 0 && spec.y >= 0) tree.hitIndex(spec.x, spec.y) else -1
        val imageSize = screenshot?.let { writeJpeg(File(episode.dir, imageName), it) }

        val now = System.currentTimeMillis()
        val actionData = JSONObject().apply {
            when (spec.action) {
                "click", "long_press", "double_tap", "type" -> {
                    if (spec.x >= 0 && spec.y >= 0) put("point", JSONArray().apply { put(spec.x); put(spec.y) })
                }

                "swipe", "drag" -> {
                    put("point1", JSONArray().apply { put(spec.x); put(spec.y) })
                    put("point2", JSONArray().apply { put(spec.x2); put(spec.y2) })
                    put("duration_ms", spec.durationMs)
                }
            }
            spec.text?.let { put("value", it) }
            spec.packageName?.let { put("package", it) }
            if (targetIndex >= 0) put("target_index", targetIndex)
            if (spec.durationMs > 0) put("duration_ms", spec.durationMs)
        }

        val elementsDoc = JSONObject().apply {
            put("schema", SCHEMA_ELEMENTS)
            put("step", stepNo)
            put("created_at", stamp(now))
            put("package", tree.packageName ?: "")
            put("screen", JSONArray().apply { put(tree.screenWidth); put(tree.screenHeight) })
            put("image", JSONObject.NULL)
            if (imageSize != null) {
                put("image", imageName)
                put("image_size", JSONArray().apply { put(imageSize.first); put(imageSize.second) })
            }
            put("action", JSONObject().apply { put("action", spec.action) })
            // 截断/稀疏标记：为 true 的帧不能当完整树用，训练侧要么丢掉要么降权
            put("truncated", tree.truncated)
            put("truncation_reasons", JSONArray(tree.truncationReasons))
            put("sparse", tree.sparse)
            put("elements", tree.elements)
        }
        writeJson(File(episode.dir, elementsName), elementsDoc)

        val stepJson = JSONObject().apply {
            put("step", stepNo)
            put("ts", stamp(now))
            put("ts_ms", now)
            put("action_type", trainerActionType(spec.action))
            put("action_data", actionData)
            put("action", JSONObject().apply { put("action", spec.action) })
            put("screenshot", if (imageSize != null) imageName else JSONObject.NULL)
            put("elements_path", elementsName)
            put("route", JSONObject().apply {
                put("app", tree.packageName ?: "")
                put("path", "")
            })
            put("elements_total", tree.elements.length())
            put("elements_truncated", tree.truncated)
            put("elements_sparse", tree.sparse)
            // intent/intent_ok 是驱动脚本声明的"这步想干什么、是否达成"，用来标失败与纠错步；
            // 与 outcome_source 一样属于脚本标注，不是模型判分
            put("intent", intent)
            put("intent_ok", intentOk)
            put("action_result", JSONObject().apply {
                put("ok", outcome.ok)
                put("detail", outcome.detail)
                put("intent_ok", intentOk)
            })
            put("settle_ms", settleMs)
        }
        episode.steps += stepJson
        writeJson(File(episode.dir, "trajectory.json"), JSONArray(episode.steps))
        touchEpisode(episode)
        Log.i(TAG, "第 $stepNo 步：${spec.action} ok=${outcome.ok}（${outcome.detail}）")
        return stepJson
    }

    /** 收尾：写结局、同步 pipeline 侧 results.jsonl */
    fun finish(outcome: String, note: String, negativeKind: String = ""): JSONObject {
        val episode = openEpisode()
            ?: return JSONObject().apply {
                put("ok", false)
                put("error", "没有正在采集的集")
            }
        val now = System.currentTimeMillis()
        val meta = readJson(File(episode.dir, "episode.json")) ?: JSONObject()
        meta.put("outcome", if (outcome.isBlank()) "unknown" else outcome)
        meta.put("note", note)
        meta.put("ended_at", stamp(now))
        meta.put("ended_ms", now)
        meta.put("steps", episode.steps.size)
        // 负例来源必须分开：probe=设计好的探针（点错/找不到控件/重复点击/返回重来/意图不符），
        // natural=采集过程中自然发生的失败。两者不能混成一个 failed，训练侧用法不同。
        meta.put("negative_kind", normalizeNegativeKind(negativeKind))
        val durationMs = (now - episode.startedMs).coerceAtLeast(0)
        meta.put("duration_ms", durationMs)
        writeJson(File(episode.dir, "episode.json"), meta)
        appendResult(episode, meta)
        Log.i(TAG, "采集结束：${episode.dir.name} outcome=$outcome steps=${episode.steps.size}")
        return meta
    }

    /**
     * 只读探测：抓一帧树+截图但不写入 trajectory（驱动脚本用它先看清页面再决定点哪）。
     *
     * 文件落在 `traj/probes/`，不进集目录、不进导出 zip；
     * 它不会被抽取管线当成训练样本（trajectory.json 里没有对应步）。
     */
    fun probe(tree: TrajElementTree.Snapshot, screenshot: Bitmap?): JSONObject {
        // 固定落在 traj/probes/：探测是调试辅助，不能混进集目录（导出时会把集目录整体打包）
        val dir = File(root, "probes").apply { mkdirs() }
        val seq = dir.listFiles()?.count { it.name.startsWith("probe_") }?.div(2)?.plus(1) ?: 1
        val name = "probe_%03d".format(seq)
        val imageSize = screenshot?.let { writeJpeg(File(dir, "$name.jpg"), it) }
        writeJson(File(dir, "${name}_elements.json"), JSONObject().apply {
            put("schema", SCHEMA_ELEMENTS)
            put("created_at", stamp(System.currentTimeMillis()))
            put("package", tree.packageName ?: "")
            put("screen", JSONArray().apply { put(tree.screenWidth); put(tree.screenHeight) })
            put("image", if (imageSize != null) "$name.jpg" else JSONObject.NULL)
            put("elements", tree.elements)
        })
        // 只把「有文字 / 可点」的条目回给驱动脚本：几百个节点没法在 adb 输出里用
        val brief = JSONArray()
        for (i in 0 until tree.elements.length()) {
            val el = tree.elements.optJSONObject(i) ?: continue
            val text = el.optString("text")
            if (text.isBlank() && !el.optBoolean("clickable")) continue
            if (brief.length() >= PROBE_BRIEF_LIMIT) break
            brief.put(JSONObject().apply {
                put("i", el.optInt("i"))
                put("text", text.take(40))
                put("tag", el.optString("tag"))
                put("role", el.optString("role"))
                put("clickable", el.optBoolean("clickable"))
                put("editable", el.optBoolean("editable"))
                put("x", el.optInt("x"))
                put("y", el.optInt("y"))
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("cmd", "probe")
            put("dir", dir.absolutePath)
            put("file", "$name.jpg")
            put("package", tree.packageName ?: "")
            put("elements", tree.elements.length())
            put("screen", JSONArray().apply {
                put(tree.screenWidth)
                put(tree.screenHeight)
            })
            put("truncated", tree.truncated)
            put("truncation_reasons", JSONArray(tree.truncationReasons))
            put("sparse", tree.sparse)
            put("items", brief)
        }
    }

    // ==================== 导出 ====================
    /** 把整个 episodes 目录打成 zip（含 meta.json / results.jsonl），返回路径与统计 */
    fun exportZip(): JSONObject {
        val target = File(root, "traj_export_${System.currentTimeMillis()}.zip")
        var files = 0
        var bytes = 0L
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            episodesDir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.forEach { file ->
                val entryName = "episodes/" + file.relativeTo(episodesDir).path.replace('\\', '/')
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> bytes += input.copyTo(zip).toLong() }
                zip.closeEntry()
                files++
            }
        }
        val result = JSONObject().apply {
            put("ok", files > 0)
            put("path", target.absolutePath)
            put("files", files)
            put("bytes", bytes)
            put("episodes", episodesDir.listFiles()?.count { it.isDirectory && File(it, "episode.json").exists() } ?: 0)
        }
        if (files == 0) {
            result.put("error", "还没有任何采集数据，先 start 再 step")
        }
        Log.i(TAG, "导出 zip：${target.absolutePath}（$files 个文件）")
        return result
    }

    /** 清空采集数据（重跑验证前用；不会碰应用其他目录） */
    fun clear(): Int {
        val episodes = episodesDir.listFiles()?.count { it.isDirectory } ?: 0
        episodesDir.deleteRecursively()
        File(root, "probes").deleteRecursively()
        root.listFiles()?.filter { it.name.startsWith("traj_export_") }?.forEach { it.delete() }
        Log.i(TAG, "已清空采集数据：$episodes 集")
        return episodes
    }

    fun status(screenWidth: Int, screenHeight: Int): JSONObject {
        val episode = openEpisode()
        return JSONObject().apply {
            put("service_connected", TrajCaptureService.isConnected())
            put("root", root.absolutePath)
            put("screen", JSONArray().apply { put(screenWidth); put(screenHeight) })
            put("episodes_dir", episodesDir.absolutePath)
            put(
                "episodes",
                episodesDir.listFiles()?.count { it.isDirectory && File(it, "episode.json").exists() } ?: 0,
            )
            put("current", episode?.let { ep ->
                JSONObject().apply {
                    put("episode_id", ep.dir.name)
                    put("task_name", ep.taskName)
                    put("steps", ep.steps.size)
                }
            } ?: JSONObject.NULL)
        }
    }

    // ==================== 内部 ====================

    private fun touchEpisode(episode: Episode) {
        val meta = readJson(File(episode.dir, "episode.json")) ?: return
        meta.put("steps", episode.steps.size)
        meta.put("last_step_at", stamp(System.currentTimeMillis()))
        writeJson(File(episode.dir, "episode.json"), meta)
    }

    private fun appendResult(episode: Episode, meta: JSONObject) {
        val outcome = meta.optString("outcome")
        val success = outcome == "success"
        val line = JSONObject().apply {
            put("id", episode.taskId)
            put("task_id", episode.taskId)
            put("task_name", episode.taskName)
            put("trial_id", meta.optInt("trial_id", 0))
            put("is_success", success)
            put("execution", JSONObject().apply {
                // 结局由驱动脚本声明（非模型判分）：这里如实记录，不当成模型能力
                put("stop_reason", outcome)
                put("error", if (outcome == "env_error") "capture failed" else JSONObject.NULL)
            })
            put("judge", JSONObject().apply { put("judge_error", false) })
            put("episode_id", episode.dir.name)
            put("steps", episode.steps.size)
            put("outcome_source", "scripted_driver")
            put("negative_kind", meta.optString("negative_kind"))
            put("note", meta.optString("note"))
        }
        episodesDir.mkdirs()
        File(episodesDir, "results.jsonl").appendText(line.toString() + "\n")
    }

    /** generation 级元数据：抽取管线靠 coord_space 判断坐标是否要归一化换算 */
    private fun ensureGenerationMeta(screenWidth: Int, screenHeight: Int) {
        val file = File(episodesDir, "meta.json")
        if (file.exists()) return
        writeJson(file, JSONObject().apply {
            put("schema", "traj-generation/1")
            put("generation", "android-sim")
            put("platform", "android-sim-emulator")
            put("bench", "traj-capture")
            put("coord_space", "px")
            put("image_long_edge", IMAGE_LONG_EDGE)
            put("screen", JSONArray().apply { put(screenWidth); put(screenHeight) })
            put("device", deviceJson(screenWidth, screenHeight))
            put("created_at", stamp(System.currentTimeMillis()))
        })
    }

    private fun pipelineMeta(meta: JSONObject): JSONObject = JSONObject().apply {
        put("task_id", meta.optString("task_id"))
        put("task_name", meta.optString("task_name"))
        put("trial_id", meta.optInt("trial_id", 0))
        put("suite", meta.optString("suite"))
        put("platform", "android-sim-emulator")
        // 必须是字符串：抽取管线用 "device"/"real" 子串判定真机，嵌套 device 对象会被误判成 real
        put("device", Build.MODEL)
        put("episode_id", meta.optString("episode_id"))
    }

    private fun deviceJson(screenWidth: Int, screenHeight: Int): JSONObject {
        val metrics = context.resources.displayMetrics
        return JSONObject().apply {
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("screen", JSONArray().apply { put(screenWidth); put(screenHeight) })
            put("density_dpi", metrics.densityDpi)
            put("font_scale", fontScale().toDouble())
            put("night", isNightMode())
            put("note", "android emulator：延迟/内存/帧率不代表真机")
        }
    }

    /**
     * 设备档案（鲁棒性验证用）：分辨率 + 密度 + 字体缩放 + 深浅色，全部当场从设备读取，
     * 驱动脚本只传一个档案名，避免「脚本说切了、设备其实没生效」这类静默错配。
     */
    private fun deviceProfile(profile: String, screenWidth: Int, screenHeight: Int): JSONObject {
        return JSONObject().apply {
            put("profile", profile.ifBlank { "default" })
            put("screen", JSONArray().apply { put(screenWidth); put(screenHeight) })
            put("density_dpi", context.resources.displayMetrics.densityDpi)
            put("font_scale", fontScale().toDouble())
            put("night", isNightMode())
        }
    }

    /** 系统字体缩放（1.0 默认，1.3 是常见"大字体"档） */
    private fun fontScale(): Float = runCatching {
        android.provider.Settings.System.getFloat(
            context.contentResolver,
            android.provider.Settings.System.FONT_SCALE,
            1.0f,
        )
    }.getOrDefault(1.0f)

    private fun isNightMode(): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun normalizeNegativeKind(raw: String): String = when (raw.trim().lowercase(Locale.US)) {
        "probe" -> "probe"
        "natural" -> "natural"
        else -> ""
    }

    /** 训练器动作词 → 抽取管线认识的 ActionType（见 qvdcore.ACTION_MAP） */
    private fun trainerActionType(action: String): String = when (action) {
        "click" -> "CLICK"
        "long_press" -> "LONG_PRESS"
        "double_tap" -> "DOUBLE_TAP"
        "type" -> "TYPE"
        "swipe" -> "SWIPE"
        "drag" -> "DRAG"
        "back" -> "BACK"
        "home" -> "HOME"
        "recent" -> "RECENT"
        "enter" -> "ENTER"
        "wait" -> "WAIT"
        "open_app" -> "AWAKE"
        "answer" -> "ANSWER"
        "complete" -> "COMPLETE"
        "abort" -> "ABORT"
        "info" -> "INFO"
        else -> "NOOP"
    }

    /** 截图缩到长边 896 后落 JPEG；返回**实际写入**的宽高，失败返回 null */
    private fun writeJpeg(file: File, bitmap: Bitmap): Pair<Int, Int>? = runCatching {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > IMAGE_LONG_EDGE) {
            val ratio = IMAGE_LONG_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        file.outputStream().buffered().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        val size = scaled.width to scaled.height
        if (scaled !== bitmap) scaled.recycle()
        size
    }.getOrElse {
        Log.w(TAG, "截图落盘失败：${it.message}")
        null
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        writeAtomic(file, json.toString(2))
    }

    private fun writeJson(file: File, array: JSONArray) {
        file.parentFile?.mkdirs()
        writeAtomic(file, array.toString(2))
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 部分文件系统不支持覆盖 rename：退回直接写，宁可牺牲原子性也不要丢数据
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
    }.getOrNull()

    private fun readJsonArray(file: File): JSONArray? = runCatching {
        if (file.exists()) JSONArray(file.readText(Charsets.UTF_8)) else null
    }.getOrNull()
}
