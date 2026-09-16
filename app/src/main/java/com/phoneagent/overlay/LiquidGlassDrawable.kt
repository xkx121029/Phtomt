package com.phoneagent.overlay.LiquidGlassDrawable

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 液态玻璃背景（悬浮窗专用，原生 Drawable 实现）。
 *
 * 视觉层次（借鉴 canvas-ui Glass 组件的物理光学模型）：
 * 1. 半透明白底（透出背后屏幕内容）
 * 2. 菲涅尔边缘反射（边缘反射强、中心透，模拟真实玻璃的掠射反射）
 * 3. 顶部折射高光（模拟光线穿过玻璃上沿的折射）
 * 4. 左上对角高光 + 动态光斑（玻璃反光随视角/手势流动）
 * 5. 底部柔和阴影（浮起体积感）
 * 6. 光谱分离色散（R/G/B 三通道叠加，模拟棱镜虹彩：左暖红 / 右冷蓝 / 顶翠绿 / 底紫）
 * 7. 内侧高光描边（玻璃厚度感）
 * 8. 细边框（强化边界）
 */
class LiquidGlassDrawable(
    private val cornerRadius: Float,
    private val baseColor: Int,
    private val strokeColor: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val roundRect = RectF()

    // 动态光斑位置（相对尺寸 0..1；null 表示使用默认左上光斑）
    private var glintX: Float? = null
    private var glintY: Float? = null

    /** 设置光斑位置（0..1 相对坐标）；传 null 恢复默认左上光斑 */
    fun setGlint(x: Float?, y: Float?) {
        glintX = x
        glintY = y
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = cornerRadius

        // 1. 半透明白底（圆角裁剪）
        path.reset()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = baseColor
        canvas.drawRect(0f, 0f, w, h, paint)

        // 2. 菲涅尔边缘反射（边缘反射强、中心透，越靠边越亮）
        drawFresnel(canvas, w, h)

        // 3. 顶部折射高光
        val hlH = h * 0.12f
        paint.shader = LinearGradient(
            0f, 0f, 0f, hlH,
            0x42FFFFFF.toInt(), 0x00FFFFFF.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, hlH, paint)

        // 4. 左上对角高光（液态反射）
        val diag = min(w, h) * 0.30f
        paint.shader = LinearGradient(
            0f, 0f, diag, diag,
            0x28FFFFFF.toInt(), 0x00FFFFFF.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, diag, diag, paint)

        // 5. 动态光斑（玻璃反光随触摸点移动）
        drawGlint(canvas, w, h)

        // 6. 底部柔和阴影（浮起体积感）
        val shH = h * 0.12f
        paint.shader = LinearGradient(
            0f, h - shH, 0f, h,
            0x00FFFFFF.toInt(), 0x1A000000.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, h - shH, w, h, paint)

        // 7. 光谱分离色散（棱镜虹彩）
        drawDispersion(canvas, w, h)

        // 8. 内侧高光描边（玻璃边缘厚度）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.shader = null
        paint.color = 0x66FFFFFF.toInt()
        roundRect.set(0.8f, 0.8f, w - 0.8f, h - 0.8f)
        canvas.drawRoundRect(roundRect, r, r, paint)

        canvas.restore()

        // 9. 细边框
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = strokeColor
        roundRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(roundRect, r, r, paint)
    }

    /** 菲涅尔边缘反射：四条边缘向内渐弱的白色反射带 */
    private fun drawFresnel(canvas: Canvas, w: Float, h: Float) {
        val fw = min(w, h) * 0.06f
        if (fw <= 0f) return
        // 上缘
        paint.shader = LinearGradient(0f, 0f, 0f, fw, 0x3DFFFFFF.toInt(), 0x00FFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, fw, paint)
        // 下缘
        paint.shader = LinearGradient(0f, h - fw, 0f, h, 0x00FFFFFF.toInt(), 0x1FFFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - fw, w, fw, paint)
        // 左缘
        paint.shader = LinearGradient(0f, 0f, fw, 0f, 0x2EFFFFFF.toInt(), 0x00FFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fw, h, paint)
        // 右缘
        paint.shader = LinearGradient(w - fw, 0f, w, 0f, 0x00FFFFFF.toInt(), 0x24FFFFFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(w - fw, 0f, w, h, paint)
        paint.shader = null
    }

    /** 动态光斑：径向白色柔光，跟随手指 */
    private fun drawGlint(canvas: Canvas, w: Float, h: Float) {
        val cx = (glintX ?: 0.18f) * w
        val cy = (glintY ?: 0.16f) * h
        val radius = min(w, h) * 0.45f
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0x38FFFFFF.toInt(), 0x14FFFFFF.toInt(), 0x00FFFFFF.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** 光谱分离色散：R/G/B 三通道叠加，柔和棱镜虹彩 */
    private fun drawDispersion(canvas: Canvas, w: Float, h: Float) {
        val dw = min(w, h) * 0.012f
        if (dw <= 0f) return
        val w3 = dw * 3
        // 左缘：红→绿→蓝（从边缘向内）
        paint.shader = LinearGradient(0f, 0f, dw, 0f, 0x45FF5A6E.toInt(), 0x00FF5A6E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, dw, h, paint)
        paint.shader = LinearGradient(dw, 0f, dw * 2, 0f, 0x1E5AFF8A.toInt(), 0x005AFF8A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(dw, 0f, dw * 2, h, paint)
        paint.shader = LinearGradient(dw * 2, 0f, w3, 0f, 0x335A5AFF.toInt(), 0x005A5AFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(dw * 2, 0f, w3, h, paint)
        // 右缘：蓝→绿→红（与左缘镜像）
        paint.shader = LinearGradient(w - w3, 0f, w - dw * 2, 0f, 0x005A5AFF.toInt(), 0x335A5AFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(w - w3, 0f, w - dw * 2, h, paint)
        paint.shader = LinearGradient(w - dw * 2, 0f, w - dw, 0f, 0x005AFF8A.toInt(), 0x1E5AFF8A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(w - dw * 2, 0f, w - dw, h, paint)
        paint.shader = LinearGradient(w - dw, 0f, w, 0f, 0x00FF5A6E.toInt(), 0x45FF5A6E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(w - dw, 0f, w, h, paint)
        // 顶缘 - 翠绿
        paint.shader = LinearGradient(0f, 0f, 0f, dw, 0x225AFF8A.toInt(), 0x005AFF8A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, dw, paint)
        // 底缘 - 紫
        paint.shader = LinearGradient(0f, h - dw, 0f, h, 0x00C25AFF.toInt(), 0x22C25AFF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - dw, w, h, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
