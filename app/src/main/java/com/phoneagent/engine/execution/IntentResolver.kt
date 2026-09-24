package com.phoneagent.engine.execution

import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.domain.model.AgentAction
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

    /**
     * 解析**动作**的目标（执行前的唯一定位入口）。
     *
     * 它合并了原先散落在引擎里的两段私有逻辑（resolveTarget + resolvePoint），
     * 现在"AI 说的目标 → 屏幕上的控件/坐标"只有这一处口径，不会再出现两处规则各自演进的情况。
     *
     * 顺序：elementIndex 命中的元素优先（AI 直接引用元素树编号最准），
     * 其次 target.method = id/label 找元素；都没命中才退化为动作自带的 x/y 或 coordinate。
     */
    fun resolveAction(action: AgentAction, snapshot: ScreenSnapshot): ResolvedTarget {
        val w = if (snapshot.screenWidth > 0) snapshot.screenWidth else 1080
        val h = if (snapshot.screenHeight > 0) snapshot.screenHeight else 2400

        // 元素索引是遍历序号，只对"这一份快照"有效；这里必须用传入的 snapshot，不能拿旧索引用到新树上
        val byIndex = action.elementIndex?.let { idx -> snapshot.elements.firstOrNull { it.index == idx } }
        val t = action.target
        val byTarget = when (t?.method) {
            // 同一段文字常常同时挂在容器与内层控件上，必须挑最具体的一个，
            // 否则会点到容器中心（可能离用户看到的按钮很远）
            "label" -> pickBestByLabel(snapshot.elements, t.value)
            "id" -> pickMostSpecific(
                snapshot.elements.filter {
                    it.semanticId == t.value || (it.viewId ?: "").endsWith(t.value, ignoreCase = true)
                },
            )
            else -> null
        }
        (byIndex ?: byTarget)?.let { return elementTarget(it, w, h) }

        // 没有命中元素：动作里带坐标就用坐标（视觉定位结果也走这条路）
        action.x?.let { ax -> action.y?.let { ay -> return ResolvedTarget(x = ax, y = ay) } }
        if (t?.method == "coordinate") {
            // 统一走 parseCoordinate 的比例 / 像素口径：这里曾经一律当比例相乘，
            // AI 一给像素坐标就会被放大数倍再夹到屏幕边缘，表现出来就是"点哪儿都不对"
            parseCoordinate(t.value, w, h)?.let { return ResolvedTarget(x = it.first, y = it.second) }
        }
        return ResolvedTarget()
    }

    /**
     * 用**当前**页面重新定位目标控件，拿到它此刻的中心点；拿不到时返回 null（调用方沿用快照坐标）。
     *
     * 为什么必要：点击坐标是按"观察那一刻"的元素树算好的，而中间隔着一次 AI 请求（可能数秒）。
     * 只要这期间页面有布局变化，旧坐标就会整体失效，落到别的控件上。
     *
     * 只在同一应用内重定位：包名变了说明页面已经切走，旧控件与旧坐标一并失效，
     * 该交给原本的失败/重规划逻辑处理，不能拿新页面上的同名控件硬点。
     *
     * 不能图省事复用 [resolveAction]：它优先按 `elementIndex` 取元素，而索引是遍历序号，
     * 两次抓取之间并不稳定，拿旧索引到新树上取元素会取到完全不同的控件。
     */
    fun relocateOnLatest(source: UiElement?, snapshot: ScreenSnapshot, fresh: ScreenSnapshot): UiElement? {
        if (source == null) return null
        if (fresh.missingAccessibility || fresh.packageName != snapshot.packageName) return null
        return matchInFresh(source, fresh)
    }

    /**
     * 在另一份页面快照里找 source 的"同一个控件"：viewId 是控件自身的标识，优先按它找；
     * 找不到才退回文字；最后由"离原位置最近"定夺。
     *
     * 最后一步不可省：同一个 viewId / 同一段文字常常出现在列表的每一行上，
     * 不按距离取舍就会点到列表里的另一行。
     */
    fun matchInFresh(source: UiElement, fresh: ScreenSnapshot): UiElement? {
        val id = source.viewId?.takeIf { it.isNotBlank() }
        val label = source.effectiveLabel()?.takeIf { it.isNotBlank() }
        val pool = fresh.elements.filter { e ->
            id != null && (e.viewId ?: "").endsWith(id, ignoreCase = true)
        }.ifEmpty {
            if (label == null) emptyList()
            else fresh.elements.filter { (it.effectiveLabel() ?: "").contains(label, ignoreCase = true) }
        }
        return pool.minByOrNull {
            val dx = it.centerX - source.centerX
            val dy = it.centerY - source.centerY
            dx * dx + dy * dy
        }
    }

    /**
     * 从"动作 + 已命中元素"推导**活节点定位线索**，供在活节点树上直接 performAction 点击。
     *
     * 元素快照里只有 viewId/文字，没有节点句柄，所以线索就是这两样加上中心点；
     * 三样都拿不到（例如纯坐标点击）时返回 null，调用方只能走坐标手势。
     */
    fun nodeSelectorOf(action: AgentAction, element: UiElement?): NodeSelector? {
        val t = action.target
        val label = element?.effectiveLabel()?.takeIf { it.isNotBlank() }
            ?: t?.value?.takeIf { it.isNotBlank() && t.method == "label" }
        val selector = NodeSelector(
            viewId = element?.viewId?.takeIf { it.isNotBlank() },
            label = label,
            centerHint = element?.let { it.centerX to it.centerY },
        )
        return selector.takeUnless { it.isEmpty }
    }

    private fun elementTarget(elem: UiElement, w: Int, h: Int): ResolvedTarget {
        val x = if (elem.ratioX != null) (elem.ratioX!! * w).toInt() else elem.centerX
        val y = if (elem.ratioY != null) (elem.ratioY!! * h).toInt() else elem.centerY
        return ResolvedTarget(element = elem, x = x, y = y)
    }
}

/** 元素标签是否包含目标文字（忽略大小写） */
internal fun labelContains(elem: UiElement, value: String): Boolean {
    val label = elem.effectiveLabel() ?: return false
    return label.contains(value, ignoreCase = true)
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

/**
 * 解析 "x,y" 坐标（比例 0~1 或像素），返回像素坐标。
 *
 * 这是「比例 / 像素」两种口径的**唯一定义点**：所有需要把坐标字符串变成像素的地方都必须调这里。
 * 各自写一份的后果很实际——某一处把像素当比例再乘一次屏幕尺寸，点出来就是屏幕角落。
 */
internal fun parseCoordinate(raw: String, w: Int, h: Int): Pair<Int, Int>? {
    val parts = raw.split(",")
    if (parts.size < 2) return null
    val x = coordinateToPixel(parts[0], w) ?: return null
    val y = coordinateToPixel(parts[1], h) ?: return null
    return x to y
}

/** 单个坐标值 → 像素：比例(<1)视为 0~1 乘尺寸；否则视为像素原样使用 */
internal fun coordinateToPixel(raw: String, dim: Int): Int? {
    val f = raw.trim().toFloatOrNull() ?: return null
    return if (f < 1.0f) (f * dim).toInt().coerceIn(0, dim) else f.toInt().coerceIn(0, dim)
}