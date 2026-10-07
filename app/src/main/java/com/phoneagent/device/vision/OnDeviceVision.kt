package com.phoneagent.device.vision

import android.content.Context
import android.graphics.Bitmap

/**
 * 端侧视觉统一门面（进程内直跑，取代旧外挂 APK 的跨进程调用）。
 *
 * 判定链：3B 模型已下载且加载成功 → 直接推理；
 * 模型未下载 / 加载失败 / 推理异常 → 自动回退 [LocalDetector]（ML Kit OCR）。
 *
 * 原生层自带 120 秒推理看门狗（图像编码与生成阶段都会检查，超时返回 __ERR_TIMEOUT__
 * 并回退 OCR），因此这里不再包 withTimeout：中断 native 调用既不可能，也只会留下
 * 孤儿线程继续占着 CPU。
 */
object OnDeviceVision {

    /** 原生库（visionbridge + llama/mtmd .so）是否可用 */
    val nativeAvailable: Boolean get() = NativeVisionEngine.nativeAvailable

    /** 3B 模型是否已加载进内存 */
    val modelLoaded: Boolean get() = NativeVisionEngine.nativeLoaded

    /** 模型文件是否已下载到私有目录 */
    fun isModelPresent(context: Context): Boolean = NativeVisionEngine.isModelPresent(context)

    /** 模型目录（设置页展示用） */
    fun modelDir(context: Context): java.io.File = NativeVisionEngine.modelDir(context)

    /** 预加载 3B 模型（任务启动时调用，避免首次决策阻塞在模型加载） */
    suspend fun loadModel(context: Context): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            NativeVisionEngine.init(context)
        }

    /**
     * 识别控件：3B 优先，OCR 兜底。
     * [timeoutMs] 仅为兼容旧签名保留；实际超时由原生看门狗（120s）负责。
     */
    suspend fun detectControls(
        context: Context,
        bitmap: Bitmap,
        timeoutMs: Long = 0L,
    ): List<DetectedControl> = NativeVisionEngine.detect(context, bitmap)

    /** 定位目标文字：识别后按标签匹配，返回归一化中心坐标；找不到返回 null */
    suspend fun locate(
        context: Context,
        bitmap: Bitmap,
        targetText: String,
        timeoutMs: Long = 0L,
    ): Pair<Float, Float>? {
        val controls = detectControls(context, bitmap)
        return ControlFormat.locate(controls, targetText)
    }
}
