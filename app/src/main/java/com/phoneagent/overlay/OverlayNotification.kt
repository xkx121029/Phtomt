// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import com.phoneagent.R
import com.phoneagent.ui.MainActivity

internal fun FloatingWindowService.startForegroundCompat() {
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager?.createNotificationChannel(
            NotificationChannel(channelId, "悬浮窗进度", NotificationManager.IMPORTANCE_LOW)
        )
    }
    val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
    val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, channelId)
            .setContentTitle("Happy Agent 正在执行")
            .setContentText("实时跟踪任务进度")
            .setContentIntent(pi)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this).setContentTitle("Happy Agent 正在执行").setSmallIcon(R.drawable.ic_stat_agent).build()
    }
    ServiceCompat.startForeground(
        this,
        FloatingWindowService.NOTIFY_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )
}

/** 实时更新前台通知，显示任务状态（可选附加 AI 思考内容） */
internal fun FloatingWindowService.updateNotification(status: String, task: String, step: Int = 0, thinking: String? = null) {
    val nm = notificationManager ?: return
    val base = if (step > 0) "第 $step 步 · $status" else status
    val text = if (!thinking.isNullOrBlank()) "$base\nAI：${thinking.replace("\n", " ").take(140)}" else base
    val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, channelId)
            .setContentTitle("Happy Agent · $task")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this).setContentTitle("Happy Agent · $task").setContentText(text).setSmallIcon(R.drawable.ic_stat_agent).build()
    }
    runCatching { nm.notify(FloatingWindowService.NOTIFY_ID, notification) }
}
