package com.phoneagent.debug.logtransfer

/**
 * 连接检测触发器（纯逻辑）：把「USB 调试连接」「调试端接入」两类信号归一成
 * 「需要开始一次日志传输流程」的边沿事件。
 *
 * 只在 0→1 的上升沿触发：USB 反复广播状态、多客户端并发接入都不会重复触发。
 * 断开后再次连上会重新触发一次（重连场景）。
 */
class TransferTrigger(private val onTrigger: (reason: String) -> Unit) {

    private var usbDebug = false
    private var clients = 0

    /** USB 调试连接状态变化；返回是否产生了新的触发 */
    @Synchronized
    fun onUsbDebugChanged(connected: Boolean): Boolean {
        val was = usbDebug
        usbDebug = connected
        if (!connected || was) return false
        onTrigger(REASON_USB)
        return true
    }

    /** 调试端建立连接；返回是否产生了新的触发（首个连接才算） */
    @Synchronized
    fun onClientConnected(): Boolean {
        clients++
        if (clients != 1) return false
        onTrigger(REASON_CLIENT)
        return true
    }

    @Synchronized
    fun onClientDisconnected() {
        if (clients > 0) clients--
    }

    @Synchronized
    fun activeClients(): Int = clients

    companion object {
        const val REASON_USB = "USB 调试连接已建立"
        const val REASON_CLIENT = "调试端已接入（USB/网络）"
    }
}
