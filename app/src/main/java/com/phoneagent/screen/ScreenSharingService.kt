package com.phoneagent.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phoneagent.ui.MainActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * MediaProjection 前台服务：持有屏幕捕获权限，供截图与视觉理解使用。
 * 通过 [captureFrame] 抓取当前屏幕 Bitmap。
 */
class ScreenSharingService : Service() {

    companion object {
        private const val TAG = "ScreenSharing"
        private const val CHANNEL_ID = "screen_capture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIF_ID = 1001

        @Volatile
        var instance: ScreenSharingService? = null
            private set

        fun createIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenSharingService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var latestFrame: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        captureThread = HandlerThread("screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) {
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)?.also { mp ->
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, captureHandler)
            startVirtualDisplay(mp)
        }
    }

    private fun startVirtualDisplay(mp: MediaProjection) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val point = android.graphics.Point()
        wm.defaultDisplay.getRealSize(point)
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(point.x, point.y, PixelFormat.RGBA_8888, 2)
        val reader = imageReader!!
        reader.setOnImageAvailableListener({ available ->
            val image = available.acquireLatestImage()
            if (image != null) {
                latestFrame = image.toBitmap()
                image.close()
            }
        }, captureHandler)

        virtualDisplay = mp.createVirtualDisplay(
            "PhoneAgentCapture",
            point.x,
            point.y,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        )
    }

    /** 抓取最新一帧屏幕内容 */
    fun captureFrame(): Bitmap? = latestFrame

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机智能体")
            .setContentText("屏幕捕获运行中，用于 AI 视觉理解")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }
}