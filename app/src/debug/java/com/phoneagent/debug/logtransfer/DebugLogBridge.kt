package com.phoneagent.debug.logtransfer

import android.content.Context
import android.os.Build
import com.phoneagent.core.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * debug 版日志传输总装（仅在 debug 源集编译）。
 *
 * 组成：
 * - [AndroidLogFeed]：历史（追加流水 + 引擎缓冲 + 调试存档）与实时采集；
 * - [LogServerCore]：回环地址上的行协议服务，逐连接独立会话、序号 + 确认 + 重传；
 * - [TransferTrigger]：USB 调试连接 / 调试端接入两条信号的边沿检测；
 * - [UsbDebugWatcher]：USB 状态广播的监听壳。
 *
 * 状态一律打到 `AppLog`（tag `LogTrans`）：既进 logcat，也会作为一条普通日志
 * 被本功能自己采集并推送到电脑端，因此「调试端」能看到完整的传输进度与完成提示。
 * release 包不包含本文件（`src/debug` 源集），也不含下面那个自动启动的 Provider。
 */
object DebugLogBridge {

    const val PORT = 19_090
    const val TAG = "LogTrans"

    private val started = AtomicBoolean(false)

    @Volatile
    private var feed: AndroidLogFeed? = null

    @Volatile
    private var server: LogServerCore? = null

    @Volatile
    private var watcher: UsbDebugWatcher? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val logFeed = AndroidLogFeed(app)
        feed = logFeed
        logFeed.start()

        val trigger = TransferTrigger { reason ->
            status("$reason → 已开始一次日志传输流程，等待调试端接入")
        }

        val core = LogServerCore(
            port = PORT,
            feed = logFeed,
            status = ::status,
            deviceLabel = { deviceLabel() },
            sdk = Build.VERSION.SDK_INT,
            onClientConnected = { trigger.onClientConnected() },
            onClientDisconnected = { trigger.onClientDisconnected() },
        )
        server = core
        if (!core.start()) return

        val usb = UsbDebugWatcher(app) { connected -> trigger.onUsbDebugChanged(connected) }
        watcher = usb
        usb.start()

        status(
            "设备端日志服务已启动（127.0.0.1:$PORT），已开始自动检测 USB 连接。" +
                "电脑端执行 `adb forward tcp:$PORT tcp:$PORT` 后运行接收脚本即可",
        )
        // 历史条数不在这里统计：它要把整份追加流水读一遍，放在 Provider.onCreate 的
        // 主线程里会拖慢启动。真正的快照在每次连接建立时于连接线程内组装。
    }

    private fun status(message: String) {
        AppLog.i(TAG, message)
    }

    private fun deviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
    }
