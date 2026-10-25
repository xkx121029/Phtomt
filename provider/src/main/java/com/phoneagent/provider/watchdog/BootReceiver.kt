package com.phoneagent.provider.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器：配置开启时把看门狗前台服务拉起。
 *
 * 重启后内置 Shizuku server 必然离线、无障碍开关通常能被系统保留但部分 ROM
 * 会丢——看门狗起来后首轮巡检就会处理这两件事（重拉 server / 自动重写启用）。
 * 注意一些 ROM 的高通/MTK 快速开机不广播 BOOT_COMPLETED，补收 QUICKBOOT_POWERON。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!WatchdogConfig.load(context).enabled) return
        runCatching {
            WatchdogConfig.startIfEnabled(context)
            Log.i("BootReceiver", "开机自启看门狗")
        }.onFailure { Log.w("BootReceiver", "开机自启失败：${it.message}") }
    }
}
