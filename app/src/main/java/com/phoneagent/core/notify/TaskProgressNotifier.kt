package com.phoneagent.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phoneagent.R
import com.phoneagent.ui.MainActivity

/**
 * 独立的任务进度通知：任务运行时在系统通知栏显示带进度条的任务进度，点击可回到 App。
 *
 * 与悬浮窗前台通知（FloatingWindowService）相互独立：不依赖悬浮窗服务也能查看进度。
 * 生命周期：任务开始 [show] → 每步更新 [show] → 完成 [complete] / 停止 [cancel]。
 */
object TaskProgressNotifier {

    const val CHANNEL_ID = "task_progress"
    private const val BASE_NOTIFY_ID = 2001

    /** 注册任务进度通知渠道（前几次调用创建，重复调用幂等） */
    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "任务进度", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示 AI 任务执行的实时进度"
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
        }
    }

    /** 每个任务一个独立通知 ID，避免多任务互相覆盖 */
    private fun notifyId(taskId: Long): Int = (BASE_NOTIFY_ID + (taskId % 1000).toInt())

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** 注册渠道并显示/更新任务进度通知。total<=0 时显示不确定进度条。 */
    fun show(context: Context, taskId: Long, taskName: String, step: Int, total: Int) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val text = when {
            total > 0 -> "第 $step / $total 步"
            step > 0 -> "已执行 $step 步"
            else -> "准备中…"
        }
        val builder = base(context, taskName)
            .setContentText(text)
            .setOngoing(true)
        if (total > 0) {
            builder.setProgress(total, step.coerceIn(0, total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        runCatching { nm.notify(notifyId(taskId), builder.build()) }
    }

    /** 任务完成：更新为完成态（保留通知供查看，点击清除） */
    fun complete(context: Context, taskId: Long, taskName: String, summary: String?) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val note = summary?.takeIf { it.isNotBlank() } ?: "任务已完成"
        val notification = base(context, taskName)
            .setContentText("已完成 · $note")
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        runCatching { nm.notify(notifyId(taskId), notification) }
    }

    /** 任务停止/取消：移除进度通知 */
    fun cancel(context: Context, taskId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(notifyId(taskId)) }
    }

    private fun base(context: Context, taskName: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Happy Agent · $taskName")
            .setContentIntent(contentIntent(context))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
}