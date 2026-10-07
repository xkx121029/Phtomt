package com.phoneagent.device.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本地读图识别（离线兜底）：
 * 用 ML Kit 中文 OCR 提取文字区域，布局聚类合并为"控件"，再按关键词/长度启发式推断类型与用途。
 * 3B 模型未下载 / 推理失败时由 [NativeVisionEngine] 自动回退到这里。
 */
object LocalDetector {

    private val regexList = listOf(
        listOf("确定", "确认", "好的", "同意", "允许", "保存", "发送", "提交", "登录", "注册",
            "下载", "安装", "开启", "创建", "完成", "继续", "下一步", "支付", "校验", "验证"),
        listOf("取消", "关闭", "退出", "删除", "清空", "停止"),
        listOf("返回", "菜单", "更多", "设置", "首页", "我的", "分享", "刷新"),
    )

    suspend fun detect(bitmap: Bitmap): List<DetectedControl> = withContext(Dispatchers.Default) {
        val text = runOnExecutor(bitmap)
        if (text == null) return@withContext emptyList()
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val items = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val box = l.boundingBox ?: return@mapNotNull null
            DetectedControl(
                label = l.text.trim(),
                role = "",
                purpose = "",
                bounds = floatArrayOf(
                    (box.left / w.toFloat()).coerceIn(0f, 1f),
                    (box.top / h.toFloat()).coerceIn(0f, 1f),
                    (box.right / w.toFloat()).coerceIn(0f, 1f),
                    (box.bottom / h.toFloat()).coerceIn(0f, 1f),
                ),
                cx = 0f,
                cy = 0f,
                source = "OCR",
            )
        }
        cluster(items).map { annotate(it) }
    }

    private suspend fun runOnExecutor(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            )
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    /** 垂直显著重叠、水平贴近的文字行合并为同一控件 */
    private fun cluster(items: List<DetectedControl>): List<DetectedControl> {
        if (items.isEmpty()) return items
        val used = BooleanArray(items.size)
        val out = mutableListOf<DetectedControl>()
        for (i in items.indices) {
            if (used[i]) continue
            used[i] = true
            val group = mutableListOf(items[i])
            var changed: Boolean
            do {
                changed = false
                for (j in items.indices) {
                    if (used[j]) continue
                    if (group.any { close(it, items[j]) }) {
                        group.add(items[j])
                        used[j] = true
                        changed = true
                    }
                }
            } while (changed)
            val left = group.minOf { it.bounds[0] }
            val top = group.minOf { it.bounds[1] }
            val right = group.maxOf { it.bounds[2] }
            val bottom = group.maxOf { it.bounds[3] }
            out += DetectedControl(
                label = group.sortedBy { it.bounds[1] }.joinToString("") { it.label },
                role = "",
                purpose = "",
                bounds = floatArrayOf(left, top, right, bottom),
                cx = (left + right) / 2f,
                cy = (top + bottom) / 2f,
                source = "OCR",
            )
        }
        return out
    }

    private fun close(a: DetectedControl, b: DetectedControl): Boolean {
        val ov = minOf(a.bounds[3], b.bounds[3]) - maxOf(a.bounds[1], b.bounds[1])
        if (ov <= 0f) return false
        val ah = a.bounds[3] - a.bounds[1]
        val bh = b.bounds[3] - b.bounds[1]
        if (ov / minOf(ah, bh) < 0.6f) return false
        val gap = maxOf(a.bounds[0], b.bounds[0]) - minOf(a.bounds[2], b.bounds[2])
        val width = minOf(a.bounds[2] - a.bounds[0], b.bounds[2] - b.bounds[0])
        return gap <= width * 1.5f
    }

    private fun annotate(c: DetectedControl): DetectedControl {
        val t = c.label.trim()
        var role = "文本"
        var purpose = "文本内容"
        val isInput = t.length <= 12 && (t.contains("输入") || t.contains("搜索") ||
            t.contains("手机号") || t.contains("密码") || t.contains("账号") || t.contains("验证码"))
        if (isInput) {
            role = "输入框"
            purpose = if (t.isNotBlank()) "输入框（${t.take(12)}）" else "文本输入框"
        } else if (t.startsWith("http")) {
            role = "按钮"; purpose = "跳转链接"
        } else if (t.length <= 6 || regexList[0].any { t.contains(it) } ||
            regexList[1].any { t.contains(it) } || regexList[2].any { t.contains(it) }) {
            role = "按钮"
            purpose = when {
                regexList[0].any { t.contains(it) } -> "确认/提交类操作"
                regexList[1].any { t.contains(it) } -> "取消/关闭/退出操作"
                regexList[2].any { t.contains(it) } -> "导航/菜单操作"
                else -> "可点击控件（$t）"
            }
        }
        return c.copy(role = role, purpose = purpose)
    }
}
