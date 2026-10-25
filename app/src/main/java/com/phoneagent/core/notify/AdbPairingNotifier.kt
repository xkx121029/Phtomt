package com.phoneagent.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 无线 ADB 配对的通知栏向导（模拟 Shizuku 的交互体验）。
 *
 * 流程（shell 通道迁入 Provider 后，通知仍由主应用发——UI 在主应用）：
 * 1. 开始配对 → 推送"正在搜索无线调试服务"的实时进度通知
 * 2. 进入等待配对码阶段 → 推送"已发现"提示，用户在设置页输入 6 位配对码
 * 3. 各阶段结果（成功/失败/断开）同样走通知栏，避免用户一直停留在 App 内
 *
 * 注：通知栏内联输入配对码的接收器已随 shell 通道迁入 Provider，主应用不再注册。
 */
object AdbPairingNotifier {

    private const val CHANNEL_ID = "hpa_adb_pairing"
    private const val CHANNEL_NAME = "无线调试向导"

    // 一条固定 id 的通知，随阶段刷新文本，避免刷屏
    const val ID_PAIRING = 3101

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

    /** 已发现设备 / 等待配对码 */
    fun notifyFound(context: Context) {
        post(context, base(context, "已发现无线调试设备", "请回到应用输入「使用配对码配对设备」界面显示的 6 位数字"))
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