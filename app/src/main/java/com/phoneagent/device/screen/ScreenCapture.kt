package com.phoneagent.screen

import android.graphics.Bitmap
import com.phoneagent.a11y.AgentAccessibilityService
import com.phoneagent.floating.FloatingWindowService

/**
 * 屏幕截图统一入口。
 *
 * 优先使用无障碍服务的 takeScreenshot（API 30+，无需 MediaProjection「屏幕共享」前台服务与媒体投影授权）；
 * 仅在无障碍截图不可用（API<30 / 能力缺失）时，才回退到 MediaProjection（ScreenSharingService），保证低版本仍可用。
 *
 * 截图前隐藏悬浮窗（避免其出现在画面中），结束后恢复；MediaProjection 路径自带隐藏+等新帧+恢复。
 */
object ScreenCapture {

    /** 当前是否有任一截图源可用（用于状态展示，不做真实截图） */
    fun available(): Boolean =
        AgentAccessibilityService.instance?.canScreenshot() == true ||
            ScreenSharingService.instance != null

    /** 抓取当前屏幕 Bitmap；无可用截图源时返回 null */
    suspend fun capture(): Bitmap? {
        val a11y = AgentAccessibilityService.instance
        if (a11y?.canScreenshot() == true) {
            // 无障碍截图路径需自行隐藏悬浮窗
            val had = FloatingWindowService.setVisible(false)
            return try {
                a11y.takeScreenshotBitmap()
            } finally {
                runCatching { if (had) FloatingWindowService.setVisible(true) }
            }
        }
        // MediaProjection 路径自带头部隐藏悬浮窗 + 等新帧 + 恢复
        return ScreenSharingService.instance?.captureFrame()
    }
}