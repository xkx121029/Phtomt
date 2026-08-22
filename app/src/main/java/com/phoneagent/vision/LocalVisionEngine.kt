package com.phoneagent.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 一处 OCR 识别出的文字区域（含归一化坐标） */
data class TextRegion(
    val text: String,
    /** 中心点比例坐标 x（0~1） */
    val cx: Float,
    /** 中心点比例坐标 y（0~1） */
    val cy: Float,
    /** 归一化边界 [left, top, right, bottom]（0~1） */
    val bounds: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
)

/**
 * 轻量本地读图模型：基于 ML Kit 中文 OCR 在设备端离线识别截图文字与位置。
 *
 * 作为云端视觉模型（glm-4.6v-flash）的轻量替代，走 `visionMode = LOCAL / AUTO`。
 * - [analyze] 单次 OCR 得到全部文字区域（供 [toDescription] 描述 + [locate] 定位复用，避免二次识别）
 * - [toDescription] 生成决策上下文用的"文字 + 比例坐标"描述
 * - [locate] 在已识别区域中匹配目标文字，返回中心比例坐标
 *
 * 离线、免费、即时，但只认文字；纯图形按钮无法识别。
 */
object LocalVisionEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 对截图执行一次 OCR，返回全部文字区域（比例坐标，0~1）；失败返回空列表 */
    suspend fun analyze(screenshot: Bitmap): List<TextRegion> = withContext(Dispatchers.Default) {
        runCatching {
            val input = InputImage.fromBitmap(screenshot, 0)
            val result = awaitTask(recognizer.process(input))
            if (result == null) return@runCatching emptyList<TextRegion>()
            val w = screenshot.width.coerceAtLeast(1)
            val h = screenshot.height.coerceAtLeast(1)
            buildList {
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        if (text.isEmpty()) continue
                        val b = line.boundingBox ?: continue
                        val left = (b.left.toFloat() / w).coerceIn(0f, 1f)
                        val top = (b.top.toFloat() / h).coerceIn(0f, 1f)
                        val right = (b.right.toFloat() / w).coerceIn(0f, 1f)
                        val bottom = (b.bottom.toFloat() / h).coerceIn(0f, 1f)
                        add(TextRegion(text, (left + right) / 2f, (top + bottom) / 2f, floatArrayOf(left, top, right, bottom)))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    /** 在已识别的文字区域中匹配目标文字，返回中心比例坐标；未找到返回 null */
    fun locate(regions: List<TextRegion>, targetText: String): Pair<Float, Float>? {
        if (targetText.isBlank()) return null
        // 优先完整匹配，其次包含匹配（忽略大小写）
        regions.firstOrNull { it.text == targetText }?.let { return it.cx to it.cy }
        return regions.firstOrNull { it.text.contains(targetText, ignoreCase = true) }?.let { it.cx to it.cy }
    }

    /** 生成决策上下文用的"文字 + 坐标"描述文本 */
    fun toDescription(regions: List<TextRegion>): String {
        if (regions.isEmpty()) return "（本地OCR未识别到文字，页面可能为纯图形界面）"
        return buildString {
            append("本地OCR识别到以下文字及位置（比例坐标，x为横向 0~1，y为纵向 0~1）：\n")
            for (r in regions) {
                append("文字“${r.text}”位于（${fmt(r.cx)}, ${fmt(r.cy)}）\n")
            }
        }
    }

    /**
     * 在原截图上给每个识别区域绘制蓝色框选 + 顶部文字标签，生成"识别截图"。
     * 供本地调试板块展示，辅助可视化 UI 元素框选（独立于视觉模式，始终可用）。
     */
    suspend fun annotate(screenshot: Bitmap, regions: List<TextRegion>): Bitmap =
        withContext(Dispatchers.Default) {
            val out = screenshot.copy(Bitmap.Config.ARGB_8888, true)
            if (regions.isEmpty()) return@withContext out
            val w = out.width
            val h = out.height
            val stroke = (w / 300).coerceIn(2, 6)
            val accent = 0xFF3F9BFF.toInt()
            val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = stroke.toFloat()
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.FILL
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (w / 34f).coerceIn(12f, 26f)
            }
            val canvas = Canvas(out)
            for (r in regions) {
                val rect = RectF(
                    r.bounds[0] * w,
                    r.bounds[1] * h,
                    r.bounds[2] * w,
                    r.bounds[3] * h,
                )
                canvas.drawRect(rect, rectPaint)
                // 顶部小标签：色块背景 + 白色文字（截断）
                val label = r.text.take(12)
                val tw = textPaint.measureText(label)
                val lh = textPaint.textSize + stroke * 2f
                val labelTop = (rect.top - lh).coerceAtLeast(0f)
                canvas.drawRect(rect.left, labelTop, rect.left + tw + stroke * 4f, labelTop + lh, labelPaint)
                canvas.drawText(label, rect.left + stroke * 2f, labelTop + textPaint.textSize + stroke, textPaint)
            }
            out
        }

    /** 坐标格式化为 2 位小数 */
    private fun fmt(v: Float): String {
        val n = (v * 100).toInt() / 100f
        return if (n % 1f == 0f) n.toInt().toString() else n.toString()
    }

    /** 将 ML Kit Task 挂起为可取消协程；失败返回 null */
    private suspend fun <T> awaitTask(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }
}