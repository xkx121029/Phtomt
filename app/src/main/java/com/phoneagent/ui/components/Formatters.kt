// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.ui.components

import com.phoneagent.core.text.TimeFormats
import java.util.Locale

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

/** 执行耗时（中文单位）：毫秒级、秒级、分钟级各用不同精度，别显示 "0.0 秒" */
fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms} 毫秒"
    ms < 60_000 -> String.format(Locale.ROOT, "%.1f 秒", ms / 1000.0)
    else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
}

/** 文件大小："512 B" / "1.2 KB" / "3.4 MB" */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}