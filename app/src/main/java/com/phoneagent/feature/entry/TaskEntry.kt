package com.phoneagent.feature.entry

import android.content.Intent
import android.net.Uri

/**
 * 外部入口解析器：把系统级入口（深链 / 分享 / 文本选择 / 快捷方式）带来的 Intent
 * 统一归一为「一段任务文本」或「一个页面跳转目标」。
 *
 * 所有入口共用同一套解析，落地行为只有两种：
 * - [Target.Task]：交给 AgentEngine 排队/开跑（运行中自动追加进队列，与悬浮窗排队模式同源）；
 * - [Target.Navigate]：只切页面，不起任务（如「打开记忆页」快捷方式）。
 *
 * 解析逻辑是纯函数（只依赖 Intent/Uri），便于 JVM 单测。
 */
object TaskEntry {

    /** 深链 scheme：hpa://task?text=xxx（website 任务卡片 / 厂商助手训练计划 / 更新推送共用） */
    const val DEEP_LINK_SCHEME = "hpa"
    const val DEEP_LINK_HOST_TASK = "task"
    const val DEEP_LINK_HOST_RESUME = "resume"

    /** 快捷方式 / 外部控制广播携带任务文本的 extra（对外契约，勿改名） */
    const val EXTRA_TASK_TEXT = "com.phoneagent.extra.TASK_TEXT"
    /** 快捷方式：只打开记忆页 */
    const val EXTRA_OPEN_MEMORY = "com.phoneagent.extra.OPEN_MEMORY"
    /** 快捷方式：从最近检查点续跑 */
    const val EXTRA_RESUME = "com.phoneagent.extra.RESUME"

    /** 外部控制广播（Tasker / MacroDroid / 自动化工具）action */
    const val ACTION_EXTERNAL_RUN = "com.phoneagent.action.EXTERNAL_RUN_TASK"
    const val ACTION_EXTERNAL_STOP = "com.phoneagent.action.EXTERNAL_STOP_TASK"
    const val ACTION_EXTERNAL_STATUS = "com.phoneagent.action.EXTERNAL_QUERY_STATUS"

    /** 解析结果 */
    sealed class Target {
        /** 交给 Agent 的任务文本 */
        data class Task(val text: String) : Target()
        /** 只导航：memory=打开记忆页；resume=从最近检查点续跑 */
        data class Navigate(val memory: Boolean = false, val resume: Boolean = false) : Target()
        /**
         * 分享进来的文件（content:// URI）：落进临时编码区交给编码页，
         * 不当作任务文本——文件解读走编码 agent 的读写工具，不走操控引擎。
         */
        data class SharedFiles(val uris: List<String>) : Target()
    }

    /** hpa://task?text=… / hpa://resume：非法或空文本返回 null */
    fun fromDeepLink(uri: Uri?): Target? = parseDeepLink(
        scheme = uri?.scheme,
        host = uri?.host,
        queryText = runCatching { uri?.getQueryParameter("text") }.getOrNull(),
    )

    /** 纯函数核心（JVM 可测）：深链三要素 → 目标 */
    internal fun parseDeepLink(scheme: String?, host: String?, queryText: String?): Target? = when {
        scheme != DEEP_LINK_SCHEME -> null
        host == DEEP_LINK_HOST_TASK -> queryText?.trim()?.takeIf { it.isNotEmpty() }?.let { Target.Task(it) }
        host == DEEP_LINK_HOST_RESUME -> Target.Navigate(resume = true)
        else -> null
    }

    /**
     * Google App Actions（GET_THING capability）的兜底形态：VIEW hpa://task 且
     * 语音 query 落在 `text` extra 里（shortcuts.xml 的 parameter-mapping 契约）。
     */
    fun fromGoogleAction(intent: Intent?): Target? {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        return uri?.let {
            parseDeepLink(
                scheme = it.scheme,
                host = it.host,
                queryText = runCatching { it.getQueryParameter("text") }.getOrNull(),
                fallbackText = intent.getStringExtra("text"),
            )
        }
    }

    /** 纯函数核心（JVM 可测）：深链 text 为空时回退 extra text（App Actions 契约） */
    internal fun parseDeepLink(scheme: String?, host: String?, queryText: String?, fallbackText: String?): Target? {
        val text = queryText?.trim().orEmpty().ifEmpty { fallbackText?.trim().orEmpty() }
        val matched = scheme == DEEP_LINK_SCHEME && host == DEEP_LINK_HOST_TASK
        return if (matched && text.isNotEmpty()) Target.Task(text) else null
    }

    /**
     * 分享 / 文本选择（ACTION_SEND、ACTION_SEND_MULTIPLE、ACTION_PROCESS_TEXT）→ 目标。
     *
     * 带文件时（EXTRA_STREAM / ClipData）一律走 [Target.SharedFiles]：微信、相册这类分享
     * 常会顺带塞一句配文，那句配文只是说明，把文件当任务文本发出去等于把附件丢了。
     * 纯文本/链接仍按任务文本透传：AI 收到网址自会走 browse_* 通道，端侧不再包一层指令。
     */
    fun fromShareIntent(intent: Intent?): Target? = parseShare(
        action = intent?.action,
        extraText = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
        streamUris = intent?.let { streamUris(it) }.orEmpty(),
    )

    /** 纯函数核心（JVM 可测）：action + 文本 + 文件清单 → 目标（文件优先于配文） */
    internal fun parseShare(action: String?, extraText: String?, streamUris: List<String>): Target? {
        val isShare = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        val files = streamUris.map { it.trim() }.filter { it.isNotEmpty() }
        if (isShare && files.isNotEmpty()) return Target.SharedFiles(files)
        val text = extraText?.trim().orEmpty()
        val textAction = isShare || action == Intent.ACTION_PROCESS_TEXT
        return if (textAction && text.isNotEmpty()) Target.Task(text) else null
    }

    /** 从分享 Intent 里收文件 URI：EXTRA_STREAM 与 ClipData 都看，去重保序 */
    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<String> {
        val uris = LinkedHashSet<String>()
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it.toString() }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?.forEach { uris += it.toString() }
        }
        // ClipData 是更现代、也更可靠的来源（部分 ROM 只填它不填 EXTRA_STREAM）
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it.toString() }
        }
        return uris.toList()
    }

    /** 快捷方式 / 引擎内部跳转 extra → 任务或页面目标；无有效 extra 返回 null */
    fun fromExtras(intent: Intent?): Target? = parseExtras(
        openMemory = intent?.getBooleanExtra(EXTRA_OPEN_MEMORY, false) ?: false,
        resume = intent?.getBooleanExtra(EXTRA_RESUME, false) ?: false,
        taskText = intent?.getStringExtra(EXTRA_TASK_TEXT),
    )

    /** 纯函数核心（JVM 可测）：extra 三要素 → 目标 */
    internal fun parseExtras(openMemory: Boolean, resume: Boolean, taskText: String?): Target? = when {
        openMemory -> Target.Navigate(memory = true)
        resume -> Target.Navigate(resume = true)
        else -> taskText?.trim()?.takeIf { it.isNotEmpty() }?.let { Target.Task(it) }
    }
}
