package com.phoneagent.coding

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phoneagent.R
import com.phoneagent.coding.agent.LocalAgent

/**
 * 前台服务：持有设备上的编码 agent 宿主进程，聊天界面退到后台时 agent 仍能继续跑。
 */
class CodingAgentService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        // 配置由主工程（AppSettings）在 LocalAgent.start 时注入并记录；服务被系统
        // 重启（START_STICKY）时拿最后一次配置把宿主拉回来。还没有任何配置
        // （用户从未发起编码会话）就只保持前台通知，不拿空配置瞎起进程。
        val config = LocalAgent.lastConfig
        if (LocalAgent.process?.isAlive != true && config != null) {
            LocalAgent.start(
                context = this,
                config = config,
                onReady = {},
                onFailure = { msg -> Log.e(TAG, "本地编码 agent 启动失败: $msg") },
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LocalAgent.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "编码 Agent", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "保持编码 Agent 在后台运行"
                },
            )
        }
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("编码 Agent")
            .setContentText("编码 Agent 正在后台运行")
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        private const val TAG = "CodingAgentService"
        const val ACTION_STOP = "com.phoneagent.action.STOP_CODING_AGENT"
        private const val CHANNEL_ID = "coding_agent"
    }
}
