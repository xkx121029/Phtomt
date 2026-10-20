package com.phoneagent.overlay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.phoneagent.R
import com.phoneagent.ui.MainActivity

/**
 * 桌面 Widget：显示当前任务状态（任务名 + 阶段/状态一句话 + 步数）。
 *
 * 数据源是 [AgentStatusStore] 单例快照；刷新时机：
 * - 引擎每步推送状态时由 [WidgetPush] 广播触发；
 * - 系统 APPWIDGET_UPDATE（首次添加到桌面 / 周期刷新兜底）。
 * 点击整块 Widget 打开 App；不引入 Glance 依赖，RemoteViews 足够这一块信息量。
 */
class AgentStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, views) }
    }

    private fun buildViews(context: Context): RemoteViews {
        val s = AgentStatusStore.snapshot.value
        val views = RemoteViews(context.packageName, R.layout.widget_agent_status)
        views.setTextViewText(R.id.widget_task, s.task ?: "Happy Agent")
        val running = !s.done && s.status != null
        val headline = when {
            s.status == null -> "待命 · 点击开始任务"
            running -> "${s.status} · 第 ${s.step} 步"
            else -> s.status
        }
        views.setTextViewText(R.id.widget_status, headline)
        // 整块点击 → 打开 App（Agent 页是入口 Tab）
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        return views
    }
}
