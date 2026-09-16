package com.phoneagent.ui.components.Formatters

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI 层共享格式化工具（纯 Kotlin，无 Compose 依赖，可单测）。
 *
 * 收敛自原先分散在三处的同构实现：
 * - `MainViewModel.formatTs`（诊断导出时间戳）
 * - `ui.debug.formatTime`（日志面板时钟）
 * - `ui.workspace.formatTime / formatSize`（文件列表）
 */

/** 日志/诊断时间戳："MM-dd HH:mm:ss.SSS" */
fun formatLogTimestamp(t: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(t))

/** 时钟时刻："HH:mm:ss"（当日日志流展示） */
fun formatClock(t: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))

/** 文件修改时间：24 小时内显示 "HH:mm"，更早显示 "MM-dd" */
fun formatFileTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - millis < 24 * 3600 * 1000L)
        SimpleDateFormat("HH:mm", Locale.getDefault())
    else
        SimpleDateFormat("MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}

/** 文件大小："512 B" / "1.2 KB" / "3.4 MB" */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}