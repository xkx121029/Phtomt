package com.phoneagent.debug

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 主动反馈通知（v2.2.1 第八章）：把系统事件用"人话"主动推给用户，不用技术术语。
 * 只发人话，不发原始数据。
 */
object ActiveNotifier {

    private const val CHANNEL_ID = "hpa_feedback"
    private const val CHANNEL_NAME = "智能体状态提醒"

    // 固定通知 id，避免刷屏（同一事件重复覆盖）
    const val ID_CHECKPOINT = 3001
    const val ID_CLOUD_TIMEOUT = 3002
    const val ID_A11Y_KILLED = 3003
    const val ID_NO_PROGRESS = 3004
    const val ID_LOCAL_LOOP = 3005
    const val ID_TEMPLATE_FAILED = 3006

    /** 发出人话通知。Android 13+ 无通知权限时静默跳过（不崩溃）。 */
    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(id, notif)
        }
    }
}