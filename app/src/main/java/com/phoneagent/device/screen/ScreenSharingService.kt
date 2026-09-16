package com.phoneagent.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.ServiceCompat
import com.phoneagent.overlay.FloatingWindowService
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

    /** 最新一帧 Bitmap；写入由 [frameLock] 保护，读取方在锁内拷贝以规避回收竞态 */
    private var latestFrame: Bitmap? = null

    /** 保护 latestFrame 读写与回收的锁（兼作新帧到达的 wait/notify 信号） */
    private val frameLock: java.lang.Object = java.lang.Object()

    /** 帧序号：悬浮窗隐藏后等待其增长，确保读到的画面不含悬浮窗 */
    @Volatile
    private var frameSeq = 0L

    /** 串行化「隐藏悬浮窗→等新帧→读取→恢复」流程，避免并发交错 */
    private val captureLock = Any()

    // 屏幕旋转/分辨率变化监听：旋转后重建 VirtualDisplay/ImageReader，避免截图尺寸与屏幕错位
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        captureThread = HandlerThread("screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // targetSdk>=34 下前台服务必须声明类型，否则 Android 14+ 抛 MissingForegroundServiceTypeException
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        registerDisplayListener()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) {
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    /** 监听默认屏幕旋转/分辨率变化，尺寸变化时重建捕获显示，保持截图与屏幕一致 */
    private fun registerDisplayListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        if (displayListener != null) return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (id != android.view.Display.DEFAULT_DISPLAY) return
                val mp = mediaProjection ?: return
                val wm = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return
                val point = android.graphics.Point()
                runCatching { wm.defaultDisplay.getRealSize(point) }
                val reader = imageReader
                if (reader == null || reader.width != point.x || reader.height != point.y) {
                    recreateVirtualDisplay(mp)
                }
            }
        }
        runCatching { dm.registerDisplayListener(displayListener, captureHandler) }
    }

    /** 以当前屏幕尺寸重建 VirtualDisplay 与 ImageReader（旋转/分辨率变化时调用） */
    private fun recreateVirtualDisplay(mp: MediaProjection) {
        runCatching {
            synchronized(frameLock) {
                latestFrame?.recycle()
                latestFrame = null
            }
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            startVirtualDisplay(mp)
        }
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
                // 在锁内替换并回收旧帧，防止与读取方（captureFrame 拷贝）发生回收竞态
                synchronized(frameLock) {
                    latestFrame?.recycle()
                    latestFrame = image.toBitmap()
                    // 帧序号递增并唤醒等待者（captureFrame 中等待悬浮窗隐藏后的新一帧）
                    frameSeq++
                    frameLock.notifyAll()
                }
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

    /**
     * 抓取最新一帧屏幕内容。
     *
     * 截图前自动隐藏悬浮窗（避免其出现在 AI 读屏画面中），截取完成后再恢复。
     * 等待悬浮窗隐藏后新一帧画面的时间上限 250ms，整体控制在 0.3s 内。
     * 返回独立拷贝（锁内完成），调用方可安全读像素，不受捕获线程回收影响。
     */
    fun captureFrame(): Bitmap? = synchronized(captureLock) {
        val hasOverlay = FloatingWindowService.setVisible(false)
        try {
            if (hasOverlay) waitForCleanFrame(frameSeq, 250)
            synchronized(frameLock) {
                val f = latestFrame ?: return@synchronized null
                if (f.isRecycled) null else f.copy(Bitmap.Config.ARGB_8888, false)
            }
        } finally {
            FloatingWindowService.setVisible(true)
        }
    }

    /**
     * 供界面实时预览的最新一帧（不隐藏悬浮窗、不下发通知）。
     *
     * 与 [captureFrame] 的区别：预览是给用户观看的，悬浮窗出现在画面中也属正常，
     * 因此跳过「隐藏悬浮窗→等新帧」流程，避免频繁整屏闪烁，也避免与 AI 决策截图抢占 captureLock。
     * 返回独立拷贝（frameLock 内完成），调用方可安全读像素。
     */
    fun previewFrame(): Bitmap? = synchronized(frameLock) {
        val f = latestFrame ?: return null
        if (f.isRecycled) null else f.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** 等待悬浮窗隐藏后的新一帧画面（frameSeq 增长），超时返回，读取当前帧兜底 */
    private fun waitForCleanFrame(before: Long, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(frameLock) {
            var remaining = deadline - System.currentTimeMillis()
            while (frameSeq <= before && remaining > 0) {
                try {
                    frameLock.wait(remaining)
                } catch (_: InterruptedException) {
                    return
                }
                remaining = deadline - System.currentTimeMillis()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        displayListener?.let {
            runCatching { (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(it) }
        }
        displayListener = null
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
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
        if (rowPadding == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
    }
}