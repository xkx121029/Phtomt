package com.phoneagent.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.phoneagent.model.UiElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 UI 控件识别引擎。
 *
 * 与"纯 OCR"不同：它框选的是**控件**并推断其**类型 + 用途**。
 * 数据来源融合两路：
 * 1. 无障碍元素树（[UiElement]）：天然含控件框、可点/可编辑/可滚动等属性，是用途推断的最可靠来源。
 * 2. OCR（[LocalVisionEngine]）：补足元素树读不到的界面文字（WebView/自绘控件等）。
 *
 * 用途标注采用确定性启发式（离线、即时）：由控件类型 + 文字关键词推断中文用途。
 * 说明：真正"看懂截图语义"需要大视觉模型，本引擎是本地最轻量的控件级识别方案。
 */
data class DetectedControl(
    /** 控件文字或描述 */
    val label: String,
    /** 控件类型：按钮/输入框/开关/标签页/文本/可滚动… */
    val role: String,
    /** 控件用途（中文，启发式推断） */
    val purpose: String,
    /** 归一化边界 [left, top, right, bottom]（0~1） */
    val bounds: FloatArray,
    /** 中心点比例坐标 */
    val cx: Float,
    val cy: Float,
    /** 来源：元素树 / OCR */
    val source: String,
)

object LocalUiDetector {

    /** 布局聚类后的一个控件候选（由若干相邻文字区域合并） */
    private data class ClusteredRegion(
        val text: String,
        val bounds: FloatArray,
        val cx: Float,
        val cy: Float,
    )

    // ---------- 用途关键词启发式 ----------
    private val CONFIRM_KEYWORDS = listOf(
        "确定", "确认", "好的", "同意", "允许", "保存", "发送", "提交", "登录", "注册",
        "下载", "安装", "开启", "添加", "创建", "完成", "知道了", "继续", "下一步", "支付", "购买", "校验", "验证",
    )
    private val CANCEL_KEYWORDS = listOf("取消", "关闭", "退出", "删除", "清空", "停止", "忽略", "以后再说")
    private val NAV_KEYWORDS = listOf("返回", "菜单", "更多", "设置", "首页", "我的", "分享", "刷新", "转发")
    private val SEARCH_KEYWORDS = listOf("搜索", "查找", "查询", "输入", "请输入", "用户名", "密码", "手机号", "验证码", "账号")
    private val ROLE_COLORS = mapOf(
        "输入框" to 0xFF3F9BFF.toInt(),
        "按钮" to 0xFF00BFA5.toInt(),
        "开关" to 0xFFFFB300.toInt(),
        "标签页" to 0xFF9C27B0.toInt(),
        "可滚动" to 0xFF8D6E63.toInt(),
        "链接" to 0xFF1E88E5.toInt(),
        "文本" to 0xFF78909C.toInt(),
    )

    /**
     * 融合元素树 + OCR，识别截图中的控件及其用途。
     * @param screenshot 截图（可空，仅用于 OCR 补充）
     * @param elements 当前无障碍元素树
     */
    suspend fun detect(
        screenshot: Bitmap?,
        elements: List<UiElement>,
        screenW: Int,
        screenH: Int,
    ): List<DetectedControl> = withContext(Dispatchers.Default) {
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        val out = mutableListOf<DetectedControl>()
        // 1) 元素树控件（最高优先）
        for (e in elements) {
            if (!e.isVisibleToUser || !e.isEnabled) continue
            if (e.width <= 0 || e.height <= 0) continue
            val (role, purpose) = inferFromElement(e)
            if (role == "文本" && e.text.isNullOrBlank() && e.contentDescription.isNullOrBlank()) continue
            val left = (e.left.toFloat() / w).coerceIn(0f, 1f)
            val top = (e.top.toFloat() / h).coerceIn(0f, 1f)
            val right = (e.right.toFloat() / w).coerceIn(0f, 1f)
            val bottom = (e.bottom.toFloat() / h).coerceIn(0f, 1f)
            out.add(
                DetectedControl(
                    label = labelOf(e),
                    role = role,
                    purpose = purpose,
                    bounds = floatArrayOf(left, top, right, bottom),
                    cx = (left + right) / 2f,
                    cy = (top + bottom) / 2f,
                    source = "元素树",
                )
            )
        }
        // 2) 纯图像路线：OCR 文字区域 → 布局聚类 → 推断类型/用途
        //    用于元素树缺失/稀疏的 webview、小程序等场景
        if (screenshot != null) {
            for (c in detectFromImageRegions(LocalVisionEngine.analyze(screenshot), w, h)) {
                if (out.any { o -> over(c.bounds, o.bounds) > 0.5f }) continue // 与已有控件重叠则跳过
                out.add(c)
            }
        }
        out
    }

    /**
     * 纯图像控件识别（不依赖元素树）：OCR 提取文字区域 → 布局聚类合并为控件 → 启发式推断用途。
     * 专用于无障碍无法提取控件树的场景（webview、小程序页面等）。
     */
    suspend fun detectFromImage(screenshot: Bitmap, screenW: Int, screenH: Int): List<DetectedControl> =
        withContext(Dispatchers.Default) {
            detectFromImageRegions(LocalVisionEngine.analyze(screenshot), screenW.coerceAtLeast(1), screenH.coerceAtLeast(1))
        }

    private fun detectFromImageRegions(regions: List<TextRegion>, w: Int, h: Int): List<DetectedControl> =
        clusterRegions(regions).map { c ->
            val text = c.text.trim()
            val (role, purpose) = inferFromImageText(text)
            DetectedControl(
                label = text,
                role = role,
                purpose = purpose,
                bounds = c.bounds,
                cx = c.cx,
                cy = c.cy,
                source = "OCR",
            )
        }

    /** 把垂直重叠、水平贴近的相邻文字区域聚类成一个控件候选 */
    private fun clusterRegions(regions: List<TextRegion>): List<ClusteredRegion> {
        if (regions.isEmpty()) return emptyList()
        val used = BooleanArray(regions.size)
        val out = mutableListOf<ClusteredRegion>()
        for (i in regions.indices) {
            if (used[i]) continue
            used[i] = true
            val group = mutableListOf(regions[i])
            var changed: Boolean
            do {
                changed = false
                for (j in regions.indices) {
                    if (used[j]) continue
                    if (group.any { r -> closeTo(r, regions[j]) }) {
                        group.add(regions[j])
                        used[j] = true
                        changed = true
                    }
                }
            } while (changed)
            val left = group.minOf { it.bounds[0] }
            val top = group.minOf { it.bounds[1] }
            val right = group.maxOf { it.bounds[2] }
            val bottom = group.maxOf { it.bounds[3] }
            val text = group.sortedBy { it.bounds[1] }.joinToString("") { it.text }
            out += ClusteredRegion(text, floatArrayOf(left, top, right, bottom), (left + right) / 2f, (top + bottom) / 2f)
        }
        return out
    }

    /** 判断两个文字区域是否应归为同一控件：垂直显著重叠 + 水平贴近 */
    private fun closeTo(a: TextRegion, b: TextRegion): Boolean {
        val ov = minOf(a.bounds[3], b.bounds[3]) - maxOf(a.bounds[1], b.bounds[1])
        val ah = a.bounds[3] - a.bounds[1]
        val bh = b.bounds[3] - b.bounds[1]
        if (ov <= 0f) return false
        val overlapRatio = ov / minOf(ah, bh)
        if (overlapRatio < 0.6f) return false
        // 水平贴近：横向间隔不超过较小区域宽度的 1.5 倍
        val gap = maxOf(a.bounds[0], b.bounds[0]) - minOf(a.bounds[2], b.bounds[2])
        val width = minOf(a.bounds[2] - a.bounds[0], b.bounds[2] - b.bounds[0])
        return gap <= width * 1.5f
    }

    /** 纯图像推断类型/用途（无元素树时）：按文字关键词 + 长度布局启发式 */
    private fun inferFromImageText(text: String): Pair<String, String> {
        val t = text.trim()
        if (SEARCH_KEYWORDS.any { t.contains(it) } && t.length <= 12) return "输入框" to purposeOfInput(t)
        return when {
            CONFIRM_KEYWORDS.any { t.contains(it) } || CANCEL_KEYWORDS.any { t.contains(it) } ||
                NAV_KEYWORDS.any { t.contains(it) } || t.startsWith("http") -> "按钮" to purposeOfButton(t)
            t.length <= 6 -> "按钮" to "可点击控件（$t）" // 短文本更可能是按钮/标签页/入口
            else -> "文本" to "文本内容"
        }
    }

    /** 在原截图上框选控件并标注用途，返回"识别截图" */
    suspend fun annotate(screenshot: Bitmap, controls: List<DetectedControl>): Bitmap =
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
                // 用途标签：色底 + 白字
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

    /** 生成给主模型看的"控件+用途"决策描述 */
    fun describe(controls: List<DetectedControl>): String {
        if (controls.isEmpty()) return "（本地未识别到控件）"
        return buildString {
            append("本地控件识别结果（比例坐标，x为横向，y为纵向）：\n")
            for (c in controls) {
                append("· ${c.role}「${c.label}」用途=${c.purpose}，位于(${fmt(c.cx)}, ${fmt(c.cy)})\n")
            }
        }
    }

    /** 在识别控件中匹配目标文字，返回中心比例坐标 */
    fun locate(controls: List<DetectedControl>, targetText: String): Pair<Float, Float>? {
        if (targetText.isBlank()) return null
        controls.firstOrNull { it.label == targetText }?.let { return it.cx to it.cy }
        return controls.firstOrNull { it.label.contains(targetText, ignoreCase = true) }?.let { it.cx to it.cy }
    }

    // ---------- 推断 ----------
    private fun inferFromElement(e: UiElement): Pair<String, String> {
        val label = labelOf(e).trim()
        val cls = e.className.orEmpty().lowercase()
        return when {
            e.editable -> "输入框" to purposeOfInput(label)
            cls.contains("switch") || cls.contains("checkbox") || cls.contains("radiobutton") -> "开关" to "切换/勾选"
            cls.contains("tab") -> "标签页" to "切换标签"
            e.scrollable -> "可滚动" to "可滚动区域"
            cls.contains("button") || e.clickable -> moveRollOfButton(label)
            else -> "文本" to "文本内容"
        }
    }

    private fun moveRollOfButton(label: String): Pair<String, String> = "按钮" to purposeOfButton(label)

    private fun purposeOfButton(label: String): String = when {
        CONFIRM_KEYWORDS.any { label.contains(it) } -> "确认/提交类操作"
        CANCEL_KEYWORDS.any { label.contains(it) } -> "取消/关闭/退出操作"
        NAV_KEYWORDS.any { label.contains(it) } -> "导航/菜单操作"
        SEARCH_KEYWORDS.any { label.contains(it) } -> "搜索"
        label.contains("删除", ignoreCase = true) -> "删除操作"
        label.isNotBlank() -> "点击「$label」"
        else -> "可点击控件"
    }

    private fun purposeOfInput(label: String): String =
        if (label.isNotBlank()) "输入框（${label.trim().take(12)}）" else "文本输入框"

    private fun labelOf(e: UiElement): String =
        e.text?.takeIf { it.isNotBlank() }
            ?: e.contentDescription?.takeIf { it.isNotBlank() }
            ?: e.viewId?.takeIf { it.isNotBlank() }
            ?: ""

    /** 返回 [b] 被 [a] 覆盖的比例 */
    private fun over(a: FloatArray, b: FloatArray): Float {
        val l = maxOf(a[0], b[0])
        val t = maxOf(a[1], b[1])
        val r = minOf(a[2], b[2])
        val bt = minOf(a[3], b[3])
        if (r <= l || bt <= t) return 0f
        val inter = (r - l) * (bt - t)
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return if (areaB <= 0f) 0f else inter / areaB
    }

    private fun fmt(v: Float): String {
        val n = (v * 100).toInt() / 100f
        return if (n % 1f == 0f) n.toInt().toString() else n.toString()
    }
}