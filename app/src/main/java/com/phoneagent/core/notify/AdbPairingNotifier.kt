package com.phoneagent.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * 无线 ADB 配对的通知栏向导（模拟 Shizuku 的交互体验）。
 *
 * 流程：
 * 1. 开始配对 → 持续推送"正在搜索无线调试服务"的实时进度通知
 * 2. 发现服务 → 推送"已发现"提示
 * 3. 请求配对码 → 弹出一条带内联输入框（RemoteInput）的通知，用户可直接在通知栏输入 6 位配对码并提交
 * 4. 提交后经 [AdbPairingReceiver] 回调，继续配对与拉起 Shizuku
 * 5. 各阶段结果（成功/失败/断开）同样走通知栏，避免用户一直停留在 App 内
 */
object AdbPairingNotifier {

    private const val CHANNEL_ID = "hpa_adb_pairing"
    private const val CHANNEL_NAME = "无线调试向导"

    // 一条固定 id 的通知，随阶段刷新文本，避免刷屏
    const val ID_PAIRING = 3101

    const val ACTION_RECEIVE_CODE = "com.phoneagent.action.RECEIVE_PAIRING_CODE"
    const val EXTRA_RESULT = "pairing_code"
    private const val RC_RECEIVE = 0x1001

    private fun nm(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm(context)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, builder: NotificationCompat.Builder) {
        if (!canNotify(context)) return
        runCatching { nm(context)?.notify(ID_PAIRING, builder.build()) }
    }

    private fun base(context: Context, title: String, text: String): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
    }

    /** 搜索中：实时进度 */
    fun notifyDiscovering(context: Context) {
        post(context, base(context, "正在搜索无线调试服务", "请保持设备「无线调试 · 使用配对码配对设备」界面"))
    }

    /** 已发现设备 */
    fun notifyFound(context: Context) {
        post(context, base(context, "已发现无线调试设备", "正在准备配对，请在通知中输入 6 位配对码"))
    }

    /**
     * 请求配对码：推一条带内联输入框的通知，用户输入后提交触发 [AdbPairingReceiver]。
     * 未授予通知权限时静默跳过（用户可回 App 手动输入）。
     */
    fun notifyRequestCode(context: Context) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val pending = PendingIntent.getBroadcast(
            context,
            RC_RECEIVE,
            Intent(ACTION_RECEIVE_CODE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remoteInput = RemoteInput.Builder(EXTRA_RESULT)
            .setLabel("输入 6 位配对码")
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "提交配对码",
            pending,
        ).addRemoteInput(remoteInput).build()
        val builder = base(context, "无线调试已就绪，请输入配对码", "输入设备「使用配对码配对设备」界面显示的 6 位数字")
            .addAction(action)
        runCatching { nm(context)?.notify(ID_PAIRING, builder.build()) }
    }

    /** 配对中 */
    fun notifyPairing(context: Context, text: String = "正在配对…") {
        post(context, base(context, "正在配对无线调试设备", text))
    }

    /** 成功 */
    fun notifySuccess(context: Context, text: String) {
        post(context, base(context, "无线调试配对成功", text))
    }

    /** 失败 / 错误提示 */
    fun notifyError(context: Context, text: String) {
        post(context, base(context, "无线调试配对失败", text))
    }

    fun cancel(context: Context) {
        runCatching { nm(context)?.cancel(ID_PAIRING) }
    }
}