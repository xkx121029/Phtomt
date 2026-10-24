package com.phoneagent.feature.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 通知监听服务：把本机活动通知汇入 [NotificationFeed]，为 AI 决策提供通知维度上下文，
 * 并支撑「读通知 / 清理通知」技能。
 *
 * 权限由用户在系统设置中授予（安装后引导一次）；未授权时本服务不会被拉起，
 * NotificationFeed.connected 保持 false，相关技能按"未开启"失败，决策上下文不注入通知块。
 */
class AgentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationFeed.setConnected(true)
        NotificationFeed.canceller = { key ->
            // cancelNotification 需要监听身份（system 调用），失败返回 false 让上层计数准确
            runCatching { cancelNotification(key) }.isSuccess
        }
        // 连接成功即回灌当前活动通知（系统在服务重连后不会重放 posted 事件）
        activeNotifications?.forEach { upsert(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        upsert(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationFeed.remove(sbn.key)
    }

    override fun onDestroy() {
        NotificationFeed.setConnected(false)
        NotificationFeed.canceller = null
        super.onDestroy()
    }

    private fun upsert(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        // 自己的通知（任务进度/更新提示）与纯进度条/无文字通知对决策没有价值，不入缓存
        val meaningful = sbn.packageName != packageName && (title.isNotBlank() || text.isNotBlank())
        if (meaningful) {
            NotificationFeed.upsert(
                NotificationFeed.Item(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appLabel(sbn.packageName),
                    title = title,
                    text = text,
                    postedAt = sbn.postTime,
                    ongoing = sbn.isOngoing,
                ),
            )
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)
}

/** 通知监听授权检测：设置页与技能入口共用 */
fun notificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)

/**
 * 引导开启通知使用权：部分 ROM 没有「通知使用权」独立页（startActivity 抛 ActivityNotFoundException），
 * 回退到系统设置首页；统一加 NEW_TASK 标记兜底非 Activity 上下文调用，避免 AndroidRuntimeException 崩溃。
 * 返回是否成功拉起了任意一页。
 */
fun openNotificationAccessSettings(context: Context): Boolean =
    listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    ).any { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.isSuccess
    }
