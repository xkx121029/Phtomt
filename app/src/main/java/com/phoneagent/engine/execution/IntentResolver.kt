package com.phoneagent.engine.execution

import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.effectiveLabel

/**
 * 目标定位器（对应 HPA动作执行逻辑优化文档 v2.1 六、IntentResolver 三级定位）。
 *
 * 把 AI 的"对什么操作"（text/hint 描述）变成屏幕上的具体控件或像素坐标：
 * - 第一级 by=id：从元素树按 id 精确查找
 * - 第二级 by=text：从元素树按文字匹配（精确 → 包含）
 * - 第三级 by=hint / 前两级失败：视觉定位（截图 + 视觉模型），由调用方在 decision 阶段
 *   已算出的视觉坐标通过 [visualCoordinate] 传入，这里只负责组装
 *
 * 引发注意：AI 永不输出坐标。坐标由这里命中元素后算中心点，或由视觉定位给出。
 */
class IntentResolver {

    /** 定位结果：命中的元素（无障碍节点定位用）+ 最终像素坐标（Shizuku/手势 用） */
    data class ResolvedTarget(
        val element: UiElement? = null,
        val x: Int? = null,
        val y: Int? = null,
    ) {
        val locatedByElement: Boolean get() = element != null
    }

    /**
     * 解析目标。
     * @param target AI 的目标描述（by=id/text/hint）
     * @param snapshot 当前页面元素树（含屏幕尺寸）
     * @param visualCoordinate 视觉定位结果（对 hint 或元素树找不到时使用；由调用方在 decision 阶段算出）
     * @param screenW/screenH 屏幕尺寸（元素中心换算像素坐标用）
     */
    fun resolve(
        target: AgentIntentTarget?,
        snapshot: ScreenSnapshot,
        visualCoordinate: Pair<Int, Int>? = null,
        screenW: Int = snapshot.screenWidth,
        screenH: Int = snapshot.screenHeight,
    ): ResolvedTarget {
        if (target == null) return ResolvedTarget()
        val w = if (screenW > 0) screenW else 1080
        val h = if (screenH > 0) screenH else 2400

        // 独占规则保留能力：by=coordinate 直接给出比例/像素坐标（值形如"0.5,0.2"或"500,800"）
        if (target.by == "coordinate") {
            val c = parseCoordinate(target.value, w, h)
            if (c != null) return ResolvedTarget(x = c.first, y = c.second)
        }

        // 第三级前提：hint 用视觉坐标
        val useVisual = target.by == "hint"

        // 第一级：id（by=id 时精确查找；找不到再降级文字）
        if (target.by == "id") {
            val byId = pickMostSpecific(
                snapshot.elements.filter {
                    it.semanticId == target.value || (it.viewId ?: "").endsWith(target.value, ignoreCase = true)
                },
            )
            if (byId != null) return elementTarget(byId, w, h)
        }

        // 第二级：text（by=text，或 by=id 失败后降级）
        if (target.by == "text" || target.by == "id") {
            val byText = pickBestByLabel(snapshot.elements, target.value)
            if (byText != null) return elementTarget(byText, w, h)
        }

        // 第三级：视觉坐标（hint / 前两级失败）
        if (useVisual || visualCoordinate != null) {
            if (visualCoordinate != null) return ResolvedTarget(x = visualCoordinate.first, y = visualCoordinate.second)
        }
        return ResolvedTarget()
    }

    /** 解析 "x,y" 坐标（比例 0~1 或像素），返回像素坐标 */
    private fun parseCoordinate(raw: String, w: Int, h: Int): Pair<Int, Int>? {
        val parts = raw.split(",")
        if (parts.size < 2) return null
        val x = toPixel(parts[0], w) ?: return null
        val y = toPixel(parts[1], h) ?: return null
        return x to y
    }

    /** 单个坐标值 → 像素：比例(<1)视为 0~1 乘尺寸；否则视为像素原样使用 */
    private fun toPixel(raw: String, dim: Int): Int? {
        val f = raw.trim().toFloatOrNull() ?: return null
        return if (f < 1.0f) (f * dim).toInt().coerceIn(0, dim) else f.toInt().coerceIn(0, dim)
    }

    private fun elementTarget(elem: UiElement, w: Int, h: Int): ResolvedTarget {
        val x = if (elem.ratioX != null) (elem.ratioX!! * w).toInt() else elem.centerX
        val y = if (elem.ratioY != null) (elem.ratioY!! * h).toInt() else elem.centerY
        return ResolvedTarget(element = elem, x = x, y = y)
    }

    private fun labelContains(elem: UiElement, value: String): Boolean {
        val label = elem.effectiveLabel() ?: return false
        return label.contains(value, ignoreCase = true)
    }
}

/**
 * 多个候选里挑最像"用户看到的那个控件"的一个。
 *
 * 为什么不能直接取第一个命中：同一段文字常常同时挂在**外层容器**和**内层控件**上
 * （容器没有自己的文字时，标签由后代文字拼出来），而容器在遍历顺序里排在前面。
 * 直接取第一个，点击就会落到容器中心——对整屏/整卡片级的容器来说，那可能离按钮很远。
 *
 * 取舍顺序：可点击/可编辑优先（那才是能按的），其次面积小的优先（越具体越贴近视觉位置）。
 */
internal fun pickMostSpecific(elements: List<UiElement>): UiElement? =
    elements.minWithOrNull(
        compareBy(
            { if (it.clickable || it.editable || it.longClickable) 0 else 1 },
            { it.width * it.height },
        ),
    )

/** 按文字挑目标：标签与目标文字完全相等的最优先，其余交给 [pickMostSpecific] 取舍 */
internal fun pickBestByLabel(elements: List<UiElement>, value: String): UiElement? {
    val hits = elements.filter { labelContains(it, value) }
    if (hits.isEmpty()) return null
    val wanted = value.trim()
    val exact = hits.filter { it.effectiveLabel()?.trim().equals(wanted, ignoreCase = true) }
    return pickMostSpecific(exact.ifEmpty { hits })
}