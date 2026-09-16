package com.phoneagent.device.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视觉识别返回的一个控件（主程序侧的数据模型）。
 *
 * 说明：实际的"读图/OCR/框选"已统一放在外挂视觉 Agent（`com.phoneagent.ondevice`）中，
 * 主程序不再内置 OCR，仅通过 [ExternalVisionProvider] 跨进程拿到此模型，做展示与定位。
 */
data class DetectedControl(
    /** 控件文字或描述 */
    val label: String,
    /** 控件类型：按钮/输入框/开关/标签页/文本/可滚动… */
    val role: String,
    /** 控件用途（中文，启发式推断，由外挂产出） */
    val purpose: String,
    /** 归一化边界 [left, top, right, bottom]（0~1） */
    val bounds: FloatArray,
    /** 中心点比例坐标 */
    val cx: Float,
    val cy: Float,
    /** 来源 */
    val source: String,
)

/** 主程序侧的控件列表格式化/定位/画框辅助（纯展示，不含 OCR） */
object ControlFormat {

    private val ROLE_COLORS = mapOf(
        "输入框" to 0xFF3F9BFF.toInt(),
        "按钮" to 0xFF00BFA5.toInt(),
        "开关" to 0xFFFFB300.toInt(),
        "标签页" to 0xFF9C27B0.toInt(),
        "可滚动" to 0xFF8D6E63.toInt(),
        "链接" to 0xFF1E88E5.toInt(),
        "文本" to 0xFF78909C.toInt(),
    )

    /** 生成给主模型看的"控件+用途"描述 */
    fun describe(controls: List<DetectedControl>): String {
        if (controls.isEmpty()) return "（未识别到控件）"
        return buildString {
            append("控件识别结果（比例坐标，x为横向，y为纵向）：\n")
            for (c in controls) {
                append("· ${c.role}「${c.label}」用途=${c.purpose}，位于(${fmt(c.cx)}, ${fmt(c.cy)})\n")
            }
        }
    }

    /** 在控件中匹配目标文字，返回中心比例坐标 */
    fun locate(controls: List<DetectedControl>, targetText: String): Pair<Float, Float>? {
        if (targetText.isBlank()) return null
        controls.firstOrNull { it.label == targetText }?.let { return it.cx to it.cy }
        return controls.firstOrNull { it.label.contains(targetText, ignoreCase = true) }?.let { it.cx to it.cy }
    }

    /** 在原截图上框选控件并标注用途，返回"识别截图"（供调试展示） */
    suspend fun drawBoxes(screenshot: Bitmap, controls: List<DetectedControl>): Bitmap =
        withContext(Dispatchers.Default) {
            val out = screenshot.copy(Bitmap.Config.ARGB_8888, true)
            if (controls.isEmpty()) return@withContext out
            val w = out.width
            val h = out.height
            val stroke = (w / 320).coerceIn(2, 5)
            val canvas = Canvas(out)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (w / 36f).coerceIn(11f, 24f)
            }
            for (c in controls) {
                val color = ROLE_COLORS[c.role] ?: 0xFF607D8B.toInt()
                val rect = RectF(c.bounds[0] * w, c.bounds[1] * h, c.bounds[2] * w, c.bounds[3] * h)
                canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = stroke.toFloat()
                })
                val tag = "${c.role}:${c.purpose}"
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
                val tw = textPaint.measureText(tag)
                val lh = textPaint.textSize + stroke * 2f
                val labelTop = (rect.top - lh).coerceAtLeast(0f)
                canvas.drawRect(rect.left, labelTop, rect.left + tw + stroke * 4f, labelTop + lh, labelPaint)
                canvas.drawText(tag, rect.left + stroke * 2f, labelTop + textPaint.textSize + stroke, textPaint)
            }
            out
        }

    private fun fmt(v: Float): String {
        val n = (v * 100).toInt() / 100f
        return if (n % 1f == 0f) n.toInt().toString() else n.toString()
    }
}