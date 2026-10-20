package com.phoneagent.overlay

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快速设置磁贴：下拉通知栏一键唤起/收起任务悬浮窗。
 *
 * 语义刻意收敛为「悬浮窗开关」而非「启停任务」：
 * - 任务启停是破坏性动作（中断执行中的任务），放在高频误触的磁贴上不合适；
 * - 悬浮窗本身不影响任务执行（见 AppSettings.floatingWindowEnabled 注释），开关无副作用。
 * 磁贴激活态跟随悬浮窗服务是否在跑（[FloatingWindowService.instance]）。
 */
class AgentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingWindowService.instance != null) {
            FloatingWindowService.stop(this)
        } else {
            FloatingWindowService.start(applicationContext)
        }
        refresh()
    }

    /** 磁贴文案与激活态：同一语义只写一处，防止文案与状态漂移 */
    private fun refresh() {
        val tile = qsTile ?: return
        val active = FloatingWindowService.instance != null
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "任务悬浮窗"
        tile.updateTile()
    }
}
