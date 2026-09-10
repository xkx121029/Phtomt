package com.phoneagent.shizuku.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * 接收通知栏「提交配对码」回调，把用户输入的 6 位配对码转发给正在进行的配对流程。
 * 真正执行配对/拉起 Shizuku 由 [WirelessAdbPairingFlow.active] 完成，避免本 Receiver 持有长期服务。
 */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != com.phoneagent.notify.AdbPairingNotifier.ACTION_RECEIVE_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(com.phoneagent.notify.AdbPairingNotifier.EXTRA_RESULT)
            ?.toString()
            ?.trim()
        if (code.isNullOrEmpty()) return
        WirelessAdbPairingFlow.active?.onPairingCode(code)
    }
}