package com.phoneagent.device.vision.ExternalVisionProvider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.phoneagent.ondevice.IVisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 外部视觉 Agent（外挂 APK：包名 `com.phoneagent.ondevice`）跨进程调用器。
 *
 * 通过 AIDL 绑定外挂的 VisionService，把当前截图发给端侧 3B 视觉模型，
 * 拿回控件框选（类型 + 用途 + 归一化坐标），映射为主程序的 [DetectedControl]。
 *
 * 设计要点：
 *  - 显式 ComponentName 绑定，避免包可见性限制；外挂服务 exported=true。
 *  - 图片以 RGBA byte[] 跨进程传输（binder 传输稳定，避免 Bitmap 底层数据拷贝风险）。
 *  - 全部调用带超时，慢/失败时上层回退到云端或本地 OCR，保证主循环不被卡死。
 */
object ExternalVisionProvider {

    private const val TAG = "ExternalVisionProvider"
    private const val PKG = "com.phoneagent.ondevice"
    private const val SERVICE_CLS = "com.phoneagent.ondevice.service.VisionService"

    // ---- IPC 超时统一常量（同一语义只定义一次，杜绝散落魔法数字） ----
    private const val BIND_TIMEOUT_MS = 2_000L          // bindService 等待（主线程回抛）
    private const val AWAIT_SERVICE_BASE_MS = 10_000L   // 服务绑定后等待连接（预热场景）
    private const val DETECT_TIMEOUT_MS = 20_000L       // 控件识别（3B 推理）
    private const val LOCATE_TIMEOUT_MS = 20_000L       // 目标定位（3B 推理）
    private const val LOAD_MODEL_TIMEOUT_MS = 60_000L   // 模型预加载
    private const val CONNECT_TIMEOUT_MS = 5_000L       // 连通性检查

    @Volatile
    private var service: IVisionService? = null

    @Volatile
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = IVisionService.Stub.asInterface(binder)
            Log.i(TAG, "外挂视觉服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.w(TAG, "外挂视觉服务已断开")
        }
    }

    /** 外挂 APK 是否已安装且服务已连上 */
    val isConnected: Boolean get() = service != null

    /** 外挂 APK 是否已安装（不要求服务已连上） */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PKG, 0)
    }.isSuccess

    /**
     * 打开外挂 APK 的启动界面（引导用户确认安装/检查服务时使用）。
     * 返回是否成功发起打开。
     */
    fun launchApp(context: Context): Boolean {
        if (!isInstalled(context)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse { false }
    }

    /**
     * 在主线程发起绑定（幂等）。bindService 必须带 Looper 的线程调用，
     * 因此任何调用线程都会切到主线程执行，并通过 CountDownLatch 等结果。
     */
    private fun bindOnMain(context: Context): Boolean {
        if (service != null || bound) return true
        val latch = CountDownLatch(1)
        val okFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            okFlag.set(runCatching {
                context.bindService(
                    Intent().setComponent(ComponentName(PKG, SERVICE_CLS)),
                    conn,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false))
            bound = okFlag.get()
            Log.i(TAG, "bindService -> ${okFlag.get()}")
            latch.countDown()
        }
        runCatching { if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "bind 超时（${BIND_TIMEOUT_MS}ms）") }
        return okFlag.get()
    }

    /** 解除绑定 */
    fun unbind(context: Context) {
        Handler(Looper.getMainLooper()).post {
            if (bound) runCatching { context.unbindService(conn) }
            bound = false
            service = null
        }
    }

    /**
     * 识别控件：返回主程序本地 [DetectedControl] 列表（外挂 3B 模型，失败为空）。
     */
    suspend fun detectControls(
        context: Context,
        bitmap: Bitmap,
        timeoutMs: Long = DETECT_TIMEOUT_MS,
    ): List<DetectedControl> = withContext(Dispatchers.Default) {
        if (!bindOnMain(context)) return@withContext emptyList()
        val svc = awaitService(timeoutMs) ?: return@withContext emptyList()
        val rgba = bitmapToRgba(bitmap)
        val jsonStr = withTimeoutOrNull(timeoutMs) {
            runCatching { svc.detectControls(rgba, bitmap.width, bitmap.height, "") }.getOrNull()
        } ?: run {
            Log.w(TAG, "外挂识别超时，返回空")
            return@withContext emptyList()
        }
        if (jsonStr.isBlank() || jsonStr == "[]") return@withContext emptyList()
        parseControls(jsonStr)
    }

    /**
     * 预加载外挂 3B 模型（幂等）。在主程序启动任务时预热调用，
     * 避免首次决策时阻塞在模型加载（可能数十秒）。未安装/不可用返回 false。
     */
    suspend fun loadModel(context: Context, timeoutMs: Long = LOAD_MODEL_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.Default) {
            if (!bindOnMain(context)) return@withContext false
            val svc = awaitService(AWAIT_SERVICE_BASE_MS) ?: return@withContext false
            withTimeoutOrNull(timeoutMs) {
                runCatching { svc.loadModel() }.getOrDefault(false)
            } ?: false
        }

    /**
     * 连接检测：发起绑定并等待服务连上，返回是否已连通（不触发实际识别）。
     * 用于首页/设置页展示"外挂视觉是否已连接"。
     */
    suspend fun checkConnection(context: Context, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.Default) {
            if (bindOnMain(context) && awaitService(timeoutMs) != null) true
            else false
        }

    /**
     * 定位目标元素：返回归一化中心坐标；找不到或失败返回 null。
     */
    suspend fun locate(
        context: Context,
        bitmap: Bitmap,
        targetText: String,
        timeoutMs: Long = LOCATE_TIMEOUT_MS,
    ): Pair<Float, Float>? = withContext(Dispatchers.Default) {
        if (!bindOnMain(context)) return@withContext null
        val svc = awaitService(timeoutMs) ?: return@withContext null
        val rgba = bitmapToRgba(bitmap)
        val s = withTimeoutOrNull(timeoutMs) {
            runCatching { svc.locate(rgba, bitmap.width, bitmap.height, targetText) }.getOrNull()
        } ?: return@withContext null
        parseCoord(s)
    }

    /** 阻塞式等待服务连上（binder 连接异步完成） */
    private suspend fun awaitService(timeoutMs: Long): IVisionService? {
        if (service != null) return service
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (service != null) return service
            delay(50)
        }
        return service
    }

    // ---------------- JSON 解析 ----------------

    private fun parseControls(jsonStr: String): List<DetectedControl> {
        return runCatching {
            // 兼容模型可能输出的 markdown 代码块或前置说明，稳健提取数组主体
            val arr = Json.parseToJsonElement(extractArray(jsonStr)).jsonArray
            arr.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    val cx = o["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val cy = o["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val w = (o["w"]?.jsonPrimitive?.floatOrNull ?: 0f).coerceIn(0f, 1f)
                    val h = (o["h"]?.jsonPrimitive?.floatOrNull ?: 0f).coerceIn(0f, 1f)
                    val l = (cx - w / 2).coerceIn(0f, 1f)
                    val t = (cy - h / 2).coerceIn(0f, 1f)
                    DetectedControl(
                        label = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        role = o["role"]?.jsonPrimitive?.contentOrNull ?: "文本",
                        purpose = o["purpose"]?.jsonPrimitive?.contentOrNull ?: "",
                        bounds = floatArrayOf(l, t, (cx + w / 2).coerceIn(0f, 1f), (cy + h / 2).coerceIn(0f, 1f)),
                        cx = cx, cy = cy,
                        source = "ondevice3b",
                    )
                }.getOrNull()
            }
        }.getOrElse { e ->
            Log.e(TAG, "解析控件 JSON 失败：${e.message}")
            emptyList()
        }
    }

    /** 提取文本中第一个"["到最后一个"]"之间的 JSON 数组体（兼容 markdown 与说明文字） */
    private fun extractArray(s0: String): String {
        var t = s0.trim()
        if (t.startsWith("```")) t = t.substringAfter('\n', t).substringBeforeLast("```").trim()
        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }

    private fun parseCoord(s: String): Pair<Float, Float>? {
        if (s.isBlank()) return null
        return runCatching {
            val o = Json.parseToJsonElement(s).jsonObject
            val x = o["x"]?.jsonPrimitive?.floatOrNull ?: return null
            val y = o["y"]?.jsonPrimitive?.floatOrNull ?: return null
            x to y
        }.getOrNull()
    }

    // ---------------- 图像转换 ----------------

    private fun bitmapToRgba(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        var i = 0
        for (p in pixels) {
            out[i++] = ((p shr 16) and 0xFF).toByte()
            out[i++] = ((p shr 8) and 0xFF).toByte()
            out[i++] = (p and 0xFF).toByte()
            out[i++] = ((p ushr 24) and 0xFF).toByte()
        }
        return out
    }
}