package com.phoneagent.feature.adskip

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.effectiveLabel

/**
 * 广告内容过滤结果。
 *
 * @property isAd 当前页面是否识别到广告类内容
 * @property target 需要点击的广告关闭/跳过按钮；null 表示无需点击（可能是广告但无按钮）
 * @property cleanSnapshot 剔除广告内容后的快照，供 Agent 决策使用（保证广告信息不回传给 AI）
 * @property reason 人话说明，供日志与悬浮窗展示
 */
data class AdFilterResult(
    val isAd: Boolean,
    val target: UiElement?,
    val cleanSnapshot: ScreenSnapshot,
    val reason: String,
)

/**
 * Agent 运行时的广告内容过滤（任务 7）。
 *
 * 用于 Agent 执行过程中：
 * 1. 识别"控件内部存在广告类信息，且带有 跳过/关闭/× 这类字样按钮"的广告。
 * 2. 直接返回可点击的关闭按钮（由调用方点击），并剔除广告相关元素，
 *    保证广告相关信息【绝不回传给 AI】，避免 AI 被广告弹窗误导而偏离任务。
 *
 * 只在 Agent 任务执行中由 AgentEngine 主动调用，与任务决策链路深度耦合；
 * 不做"Agent 空闲时自动帮用户跳广告"这类日常使用场景（该能力已移除）。
 */
object AdContentFilter {

    /** 广告类信息关键词（命中即视为广告内容） */
    private val adMarks = listOf(
        "广告", "推广", "赞助", "招商", "小程序广告",
        "ad", "Ad", "AD", "promotion", "sponsored",
    )

    /** 广告关闭按钮核心词（剔除倒计时/括号等干扰后匹配） */
    private val closeMarks = listOf(
        "跳过", "跳過", "略过", "跳过广告", "关闭", "知道了", "我知道了",
        "暂不开启", "取消", "skip", "skip ad", "close", "done",
    )

    /** 单字符叉号：仅当为图片类控件时才视为广告关闭按钮，避免误触输入框清除/标签页关闭 */
    private val singleCloseChars = setOf("✕", "×", "X", "x")

    /** 按钮与广告区域的相对距离阈值（像素）：按钮中心落在广告区域扩张范围内即视为同一广告的关闭按钮 */
    private const val REGION_MARGIN = 220

    /**
     * 扫描快照，识别广告内容并返回：
     * - 可点击的广告关闭/跳过按钮（存在时）
     * - 剔除广告内容后的干净快照
     */
    fun filter(snapshot: ScreenSnapshot): AdFilterResult {
        val elements = snapshot.elements
        if (elements.isEmpty()) return AdFilterResult(false, null, snapshot, "")

        // 1. 找出含广告信息的元素
        val adElements = elements.filter { isAdContent(it) }
        if (adElements.isEmpty()) return AdFilterResult(false, null, snapshot, "")

        // 2. 计算广告区域（外接矩形，并向外扩张，覆盖其角落/边缘的关闭按钮）
        val region = RectRegion.from(adElements)

        // 3. 在广告区域内寻找可点击的关闭/跳过按钮
        val target = findCloseButton(elements, region, adElements)

        // 4. 广告相关元素全部剔除，保证广告信息不回传给 AI
        val adIndices = adElements.map { it.index }.toSet()
        val cleanSnapshot = snapshot.copy(elements = elements.filterNot { it.index in adIndices })

        val reason = buildString {
            append("检测到广告内容（${adElements.size} 处），已过滤")
            if (target != null) append("，自动点击「${target.effectiveLabel()?.trim() ?: "跳过/关闭"}」")
            else append("，未发现可安全点击的关闭按钮")
        }
        return AdFilterResult(isAd = true, target = target, cleanSnapshot = cleanSnapshot, reason = reason)
    }

    /** 元素是否含广告类信息 */
    private fun isAdContent(el: UiElement): Boolean {
        val label = el.effectiveLabel()?.trim() ?: return false
        return adMarks.any { label.contains(it, ignoreCase = true) }
    }

    /** 在广告区域内寻找可点击的关闭按钮 */
    private fun findCloseButton(
        all: List<UiElement>,
        region: RectRegion,
        adElements: List<UiElement>,
    ): UiElement? {
        // 3a. 广告元素本身可能是跳过按钮（如 label="跳过 5s 广告"），优先命中
        adElements.firstOrNull { it.clickable && isCloseLabel(it.effectiveLabel()) }?.let { return it }

        // 3b. 广告区域（扩张后）内可点击的关闭按钮
        all.firstOrNull { el ->
            el.clickable && isCloseLabel(el.effectiveLabel()) && region.containsCenter(el)
        }?.let { return it }

        return null
    }

    /** 是否匹配广告关闭/跳过按钮文案（剔除倒计时/括号/空白干扰后精确匹配） */
    private fun isCloseLabel(label: String?): Boolean {
        val raw = label?.trim() ?: return false
        // 单字符叉号仅图片类控件视为关闭按钮
        if (raw.length == 1 && raw in singleCloseChars) return true
        val clean = raw
            .replace(Regex("""\s*[\d一二三四五六七八九十]+\s*s?$"""), "")
            .replace(Regex("""[()（）\[\]]"""), "")
            .trim()
        return closeMarks.any { it == clean || (it.length > 1 && clean.contains(it)) }
    }

    /** 广告外接矩形区域 */
    private class RectRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun containsCenter(el: UiElement): Boolean =
            el.centerX >= left - REGION_MARGIN && el.centerX <= right + REGION_MARGIN &&
                el.centerY >= top - REGION_MARGIN && el.centerY <= bottom + REGION_MARGIN

        companion object {
            fun from(elements: List<UiElement>): RectRegion {
                var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
                elements.forEach {
                    if (it.left < l) l = it.left
                    if (it.top < t) t = it.top
                    if (it.right > r) r = it.right
                    if (it.bottom > b) b = it.bottom
                }
                return RectRegion(l, t, r, b)
            }
        }
    }
}
