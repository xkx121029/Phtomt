package com.phoneagent.perception

import com.phoneagent.model.ScreenSnapshot
import com.phoneagent.model.UiElement

/**
 * 页面标注结果：页面类型推断、语义上下文提示、控件优先级标注。
 * 对应文档“第 2 层 感知层 / 端侧页面标注器”。
 */
data class AnnotatedPage(
    val snapshot: ScreenSnapshot,
    /** 页面类型（search_page / product_detail / dialog_overlay / loading / error / generic ...） */
    val pageType: String,
    /** 语义描述提示，供 AI 理解页面 */
    val contextHint: String,
    /** 按优先级排序后的元素（high/medium/low） */
    val elements: List<UiElement>,
    /** fingerprint 字符串，供执行前后验证 */
    val fingerprint: String,
)

/**
 * 端侧页面标注器：推断页面类型、生成上下文提示、标注控件优先级。
 */
object PageAnnotator {

    /** 弹窗正向按钮关键词 */
    private val dialogPositive = listOf("允许", "同意", "确定", "知道了", "始终允许", "授权", "resume", "continue", "ok")
    /** 弹窗关闭按钮关键词 */
    private val dialogDismiss = listOf("关闭", "取消", "以后再说", "跳过", "稍后", "x", "✕", "no", "cancel")
    /** 加载中关键词 */
    private val loadingWords = listOf("加载中", "请稍候", "请稍等", "loading")
    /** 异常页关键词 */
    private val errorWords = listOf("网络异常", "加载失败", "连接失败", "点击重试", "重试", "error", "失败")
    /** 完成页关键词 */
    private val completionWords = listOf("成功", "完成", "提交成功", "支付成功", "下单成功")
    /** 倒计时广告关键词 */
    private val countdownWords = listOf("跳过", "秒", "广告")

    fun annotate(snapshot: ScreenSnapshot): AnnotatedPage {
        val pageType = inferPageType(snapshot)
        val contextHint = generateContextHint(snapshot, pageType)
        val w = snapshot.screenWidth.takeIf { it > 0 } ?: 1
        val h = snapshot.screenHeight.takeIf { it > 0 } ?: 1
        val annotated = snapshot.elements.map { e ->
            e.copy(
                priority = priorityOf(e),
                ratioX = e.centerX.toFloat() / w,
                ratioY = e.centerY.toFloat() / h,
            )
        }.sortedWith(compareByDescending<UiElement> { priorityRank(it.priority) }.thenBy { it.index })
        val fingerprint = PageFingerprint.compute(snapshot)
        return AnnotatedPage(snapshot, pageType, contextHint, annotated, fingerprint)
    }

    /** 页面类型推断 */
    fun inferPageType(snapshot: ScreenSnapshot): String {
        val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }
        val text = labels.joinToString(" ")

        // 弹窗：正向/关闭按钮共存，且元素稀疏
        val hasPositive = labels.any { dialogPositive.any { it in it } }
        val hasDismiss = labels.any { dialogDismiss.any { it in it } }
        if (hasPositive && hasDismiss && snapshot.elements.size <= 8) return "dialog_overlay"

        // 倒计时广告：跳过按钮 + 数字
        val hasSkip = labels.any { it.contains("跳过") }
        val hasCount = labels.any { it.contains(Regex("[0-9]+")) && countdownWords.any { w -> it.contains(w) } }
        if (hasSkip && hasCount) return "ad_with_countdown"

        // 加载中：加载关键词 + 元素少
        if (labels.any { loadingWords.any { w -> it.contains(w) } } && snapshot.elements.size < 3) return "loading"

        // 异常页
        if (labels.any { errorWords.any { w -> it.contains(w) } }) return "error"

        // 完成页
        if (labels.any { completionWords.any { w -> it.contains(w) } }) return "completion"

        // 输入框多 → 搜索/表单
        val editable = snapshot.elements.count { it.editable }
        if (editable > 0) {
            if (labels.any { it.contains("搜索") || it.contains("search") }) return "search_page"
            return "form"
        }
        // 可滚动 + 多个可点击卡片 → 列表
        if (snapshot.elements.any { it.scrollable } && snapshot.elements.count { it.clickable } > 3) return "content_list"
        return "generic"
    }

    /** 生成语义上下文提示 */
    private fun generateContextHint(snapshot: ScreenSnapshot, pageType: String): String {
        val sb = StringBuilder("页面类型：$pageType")
        when (pageType) {
            "dialog_overlay" -> sb.append("；检测到弹窗，需先处理（点击正向或关闭按钮）")
            "ad_with_countdown" -> sb.append("；【⚠️ 疑似倒计时广告】请尽快点击跳过按钮")
            "loading" -> sb.append("；页面加载中，请等待")
            "error" -> sb.append("；页面出现异常，可尝试重试或返回")
            "completion" -> sb.append("；任务可能已完成")
            else -> {
                val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }
                if (labels.isNotEmpty()) sb.append("；主要控件：${labels.take(6).joinToString("、")}")
            }
        }
        return sb.toString()
    }

    /** 控件优先级：high/medium/low */
    private fun priorityOf(e: UiElement): String = when {
        e.editable -> "high"
        e.clickable -> "medium"
        else -> "low"
    }

    private fun priorityRank(p: String): Int = when (p) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }
}

/** 元素的有效标签（text 优先，其次 contentDescription） */
fun UiElement.effectiveLabel(): String? =
    text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }