package com.phoneagent.overlay

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 任务状态总线：引擎每步进度经 [FloatingWindowService.update] 汇入这里，
 * 桌面 Widget 与快速设置磁贴都读这一份快照，不再各自挂监听。
 *
 * 只存「最后一次推送」：Widget 刷新由推送方顺手触发（见 [WidgetPush.push]），
 * 不引入轮询，也不用为它建常驻连接。
 */
object AgentStatusStore {

    /** 一次任务状态快照（phase 为引擎 AgentState.Phase 名，done 表示收尾态） */
    data class Snapshot(
        val task: String? = null,
        val status: String? = null,
        val reasoning: String? = null,
        val step: Int = 0,
        val phase: String? = null,
        val done: Boolean = false,
        val updatedAt: Long = 0L,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    /** 引擎/悬浮窗推送状态；同时刷新桌面 Widget（有实例才刷，冷启动开销为零） */
    fun update(snap: Snapshot) {
        _snapshot.value = snap.copy(updatedAt = System.currentTimeMillis())
        WidgetPush.push()
    }

    /** 悬浮窗销毁 / 任务结束后复位为空闲（保留最后任务名供 Widget 展示一句摘要） */
    fun idle(lastTask: String?) {
        _snapshot.value = Snapshot(
            task = lastTask,
            status = "空闲",
            done = true,
            updatedAt = System.currentTimeMillis(),
        )
        WidgetPush.push()
    }
}

/** Widget 刷新入口：状态变化后广播一次 APPWIDGET_UPDATE（无 Widget 实例时开销为零） */
internal object WidgetPush {
    fun push() {
        runCatching {
            val context = com.phoneagent.PhoneAgentApp.appContext
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, AgentStatusWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, AgentStatusWidgetProvider::class.java)
                    .setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
