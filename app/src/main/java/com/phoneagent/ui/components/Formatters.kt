package com.phoneagent.ui.components

import com.phoneagent.core.text.TimeFormats

/**
 * UI 层共享格式化工具（纯 Kotlin，无 Compose 依赖，可单测）。
 *
 * 收敛自原先分散在 UI 层的同构实现：
 * - `ui.debug.formatTime`（日志面板时钟）
 *
 * 注：日志导出用的 "MM-dd HH:mm:ss.SSS" 时间戳随导出逻辑一并迁到 `data.export.LogExporter`，
 * 此处不再保留（数据层不反向依赖 UI 层）。
 */

/** 时钟时刻："HH:mm:ss"（当日日志流展示） */
fun formatClock(t: Long): String = TimeFormats.format("HH:mm:ss", t)

/** 文件修改时间：24 小时内显示 "HH:mm"，更早显示 "MM-dd" */
fun formatFileTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val pattern = if (now - millis < 24 * 3600 * 1000L) "HH:mm" else "MM-dd"
    return TimeFormats.format(pattern, millis)
}

/** 文件大小："512 B" / "1.2 KB" / "3.4 MB" */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}