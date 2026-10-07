package com.phoneagent.device.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 端侧 3B 视觉模型引擎（llama.cpp / GGUF + mtmd 视觉投影），进程内直跑。
 *
 * 通过 JNI 垫片 libvisionbridge 调用 libllama + libmtmd：
 *  - 主权重 Qwen2.5-VL-3B-Instruct-*.gguf
 *  - 视觉投影 mmproj-*.gguf
 *
 * 模型文件由设置页下载到应用内部私有目录（filesDir/vision_models/）。
 * 未下载 / 加载失败 / 推理异常时，自动回退到 [LocalDetector]（ML Kit OCR）。
 * 原生层自带 120s 推理看门狗（图像编码与生成阶段都会检查），超时返回 __ERR_TIMEOUT__。
 */
object NativeVisionEngine {

    private const val TAG = "NativeVisionEngine"

    /** 模型在应用私有目录下的存放目录 */
    const val MODELS_DIR = "vision_models"

    /** JNI 垫片原生库 */
    private const val BRIDGE_LIB = "visionbridge"

    var msg: (String) -> Unit = {}

    /** 当前已加载的运行时句柄；0 表示未加载 */
    @Volatile
    private var handle: Long = 0

    /** 串行化推理：llama_context 非线程安全，防并发访问导致卡死/崩溃 */
    private val detectLock = Mutex()

    /** 串行化模型加载：防并发 init 竞态（预热 + 手动加载同时触发） */
    private val initLock = Any()

    /** 最近的模型原始输出（调试用） */
    var lastRuntimeText: String = ""
        private set

    val nativeAvailable: Boolean
        get() = runCatching { System.loadLibrary(BRIDGE_LIB) }.isSuccess

    val nativeLoaded: Boolean get() = handle != 0L

    /** 默认模型文件命名（按当前部署约定） */
    const val TEXT_MODEL_FILE = "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
    const val MMPROJ_FILE = "mmproj-model-f16.gguf"

    // llama.cpp 加载需要绝对路径，且当前目录必须是可写的私有目录
    fun modelDir(context: Context): java.io.File =
        java.io.File(context.filesDir, MODELS_DIR)

    fun textModelPath(context: Context): String =
        java.io.File(modelDir(context), TEXT_MODEL_FILE).absolutePath

    fun mmprojPath(context: Context): String =
        java.io.File(modelDir(context), MMPROJ_FILE).absolutePath

    fun isModelPresent(context: Context): Boolean =
        java.io.File(textModelPath(context)).exists() &&
            java.io.File(mmprojPath(context)).exists()

    /**
     * 初始化 JNI 原生引擎。返回是否成功；失败原因写入 error payload。
     * 用 [initLock] 串行化模型加载，防止并发 init（预热 + 手动加载同时触发）共享 context 竞态卡死。
     */
    fun init(context: Context): Boolean {
        if (handle != 0L) return true
        if (!nativeAvailable) { msg("无法加载原生垫片库"); return false }
        if (!isModelPresent(context)) {
            msg("模型未下载，请先在设置中下载 Qwen2.5-VL-3B")
            return false
        }
        synchronized(initLock) {
            if (handle != 0L) return true
            return runCatching {
                handle = nativeInit(textModelPath(context), mmprojPath(context), nThreads(context))
                if (handle != 0L) {
                    Log.i(TAG, "3B 视觉模型加载成功")
                    true
                } else {
                    Log.e(TAG, "nativeInit 返回 0: $lastRuntimeError")
                    false
                }
            }.getOrElse { e ->
                Log.e(TAG, "init failed", e)
                false
            }
        }
    }

    private fun nThreads(context: Context): Int {
        // 端侧 CPU 推理不宜跑满所有核：给系统/前后台其他进程留余量，避免线程抢占导致"卡死"
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores * 3 / 4).coerceIn(2, 6)
    }

    /**
     * 识别图片中的 UI 控件。
     * 3B 模型可用时优先，否则回退 [LocalDetector]（OCR）。
     * 用互斥锁串行化调用：llama_context 非线程安全，并发推理会造成数据竞争/卡死。
     */
    suspend fun detect(context: Context, bitmap: Bitmap): List<DetectedControl> =
        detectLock.withLock {
            withContext(Dispatchers.Default) {
                if (!init(context)) return@withContext LocalDetector.detect(bitmap)

                val text = runCatching {
                    nativeDetectSignal(bitmap)
                }.getOrElse { e ->
                    Log.e(TAG, "3B 推理失败，回退 OCR: $e")
                    null
                }

                if (text.isNullOrBlank() || text.startsWith("__ERR")) {
                    return@withContext LocalDetector.detect(bitmap)
                }

                parseControls(text)
            }
        }

    /**
     * 一次推理调用：缩放输入 → RGB → native。
     * 缩放输入：旗舰机在画面质量与推理 token 之间取 960，显著降低视觉 token 数（提速关键）。
     * 输出上限放宽到 1024，避免控件数组被截断导致解析失败（"识别出 0 个"的根因之一）。
     */
    private suspend fun nativeDetectSignal(bitmap: Bitmap): String =
        withContext(Dispatchers.Default) {
            val scaled = scaleToMax(bitmap, 960)
            val rgb = toRgb(scaled)
            val result = nativeDetect(handle, rgb, scaled.width, scaled.height, "", 1024, 0.2f)
            lastRuntimeText = result
            result
        }

    /** 解析 3B 模型输出的控件列表 JSON（主程序侧 DetectedControl 形状：bounds 数组 + source） */
    private fun parseControls(text: String): List<DetectedControl> {
        val arr = extractJsonArray(text) ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val x = o["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                val y = o["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                val w = o["w"]?.jsonPrimitive?.floatOrNull ?: 0f
                val h = o["h"]?.jsonPrimitive?.floatOrNull ?: 0f
                DetectedControl(
                    label = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    role = o["role"]?.jsonPrimitive?.contentOrNull ?: "文本",
                    purpose = o["purpose"]?.jsonPrimitive?.contentOrNull ?: "",
                    bounds = floatArrayOf(
                        (x - w / 2).coerceIn(0f, 1f),
                        (y - h / 2).coerceIn(0f, 1f),
                        (x + w / 2).coerceIn(0f, 1f),
                        (y + h / 2).coerceIn(0f, 1f),
                    ),
                    cx = x,
                    cy = y,
                    source = "3B",
                )
            }.getOrNull()
        }
    }

    private fun extractJsonArray(text: String): JsonArray? {
        val s = text.trim()
        // 从反向找 ']' 与最后 '[' 之间的内容；优先提取 [] 包裹的完整数组
        for (startIdx in s.lastIndexOf('[') downTo 0) {
            if (s[startIdx] == '[') {
                val end = s.lastIndexOf(']')
                if (end > startIdx) {
                    return runCatching {
                        Json.parseToJsonElement(s.substring(startIdx, end + 1)) as? JsonArray
                    }.getOrNull()
                }
            }
        }
        return null
    }

    private fun scaleToMax(src: Bitmap, max: Int): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= max) return src
        val scale = max.toFloat() / maxDim
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun toRgb(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val rgb = ByteArray(w * h * 3)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var idx = 0
        for (p in pixels) {
            rgb[idx++] = ((p shr 16) and 0xFF).toByte()
            rgb[idx++] = ((p shr 8) and 0xFF).toByte()
            rgb[idx++] = (p and 0xFF).toByte()
        }
        return rgb
    }

    private external fun nativeLoadLibs(): String?
    private external fun nativeInit(modelPath: String, mmprojPath: String, threads: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeError(handle: Long): String
    private external fun nativeVersion(): String
    private external fun nativeDetect(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
    ): String

    private val lastRuntimeError: String
        get() = if (handle != 0L) nativeError(handle) else "未初始化"
}
