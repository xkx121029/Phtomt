package com.phoneagent.debug.logtransfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * USB 调试连接检测（debug 专有）。
 *
 * 设备无法直接知道「电脑上跑着调试工具」，能观测到的只有 USB 状态本身：
 * `android.hardware.usb.action.USB_STATE`（sticky 广播）的 `connected` 表示 USB 已接入主机，
 * `adb` 表示该连接上 ADB 通道已建立（即开发者选项里的「USB 调试」生效）。
 * 两者同时为真才算「通过 USB 连接到了调试电脑」，只用 `connected` 会把
 * 仅充电的线缆也算进来，造成误触发。
 *
 * 无线调试没有对应广播，由调试端 TCP 接入的边沿补上（见 [TransferTrigger]）。
 */
class UsbDebugWatcher(
    context: Context,
    private val onChanged: (connected: Boolean) -> Unit,
) {

    private val appContext = context.applicationContext
    private var registered = false
    private var lastState = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_STATE) return
            dispatch(isAdbConnected(intent))
        }
    }

    fun start() {
        if (registered) return
        registered = runCatching {
            val filter = IntentFilter(ACTION_USB_STATE)
            // 该广播是 sticky 的，注册时能立刻拿到当前状态；拿不到就等下一次变化，
            // 反正调试端接入时还有第二个信号兜底
            val current = runCatching { appContext.registerReceiver(null, filter) }.getOrNull()
            if (current != null) dispatch(isAdbConnected(current))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun dispatch(connected: Boolean) {
        if (connected == lastState) return
        lastState = connected
        onChanged(connected)
    }

    companion object {
        /**
         * 这几个常量在 AOSP 里是 `UsbManager` 的 @hide 字段（公开 SDK 拿不到），
         * 但广播与 extra 名是稳定契约（frameworks/base 的 UsbHostManager/UsbDeviceManager 一直这么发）。
         */
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_USB_CONNECTED = "connected"
        private const val EXTRA_ADB = "adb"

        fun isAdbConnected(intent: Intent): Boolean =
            intent.getBooleanExtra(EXTRA_USB_CONNECTED, false) &&
                intent.getBooleanExtra(EXTRA_ADB, false)
    }
}
