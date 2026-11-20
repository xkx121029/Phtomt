package com.phoneagent.debug.traj

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍节点树 → 压缩元素列表（debug 轨迹采集专用，release 不编译本文件）。
 *
 * 输出字段与既有的 qwen-vl-distill 抽取管线对齐（见
 * `2026-11-14/qwen-vl-distill/outputs/qvdcore.py`）：`text/tag/role/clickable/editable/
 * x/y/w/h/level`，坐标一律为屏幕物理像素（与截图缩放无关）。
 *
 * 「压缩」的取舍与训练输入预算一致：只留**可见**且有信息量的节点（自带文本 / 可交互），
 * 纯布局容器不进数据；`level` 保留原始树深度，层级关系仍可还原。
 */
internal object TrajElementTree {

    /** 递归深度上限：防止畸形树把栈打穿 */
    private const val MAX_DEPTH = 40

    /** 单帧元素数量上限：训练侧文本预算 1200 token，再多也进不了提示词 */
    private const val MAX_NODES = 400

    /** 派生标签（可点容器用后代文字补名）最多拼几段 */
    private const val MAX_DERIVED_PARTS = 3

    private const val MAX_LABEL_CHARS = 80

    /** 少于这个元素数视为「异常少」，这帧不能当完整树用（可能读不到窗口或页面确实是空态） */
    private const val MIN_COMPLETE_ELEMENTS = 5

    /** 一次抓取的产物：元素 JSON 数组 + 前台包名 + 屏宽高 + 用于回填命中下标的几何表 */
    data class Snapshot(
        val elements: JSONArray,
        val packageName: String?,
        val screenWidth: Int,
        val screenHeight: Int,
        private val boxes: List<Box>,
        /** 是否被压缩规则截断（节点数上限/深度上限）：为 true 时这帧不能当"完整树"用 */
        val truncated: Boolean = false,
        val truncationReasons: List<String> = emptyList(),
        /** 元素数异常少（可能读不到窗口 / 页面确实是空态）：训练时不能当完整树 */
        val sparse: Boolean = false,
    ) {
        /**
         * 找出坐标落在哪个可点元素上：优先面积最小的**可点击**节点（最具体的点击目标），
         * 没有可点节点时退回面积最小的命中节点；都没命中返回 -1。
         */
        fun hitIndex(x: Int, y: Int): Int {
            val hits = boxes.filter { it.left <= x && x <= it.right && it.top <= y && y <= it.bottom }
            if (hits.isEmpty()) return -1
            val clickable = hits.filter { it.clickable }
            val pool = if (clickable.isNotEmpty()) clickable else hits
            return pool.minByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }?.index ?: -1
        }
    }

    internal data class Box(
        val index: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
    )

    /** 抓取并序列化整棵树；root 为 null（读不到窗口）时返回空列表 */
    fun capture(root: AccessibilityNodeInfo?, screenWidth: Int, screenHeight: Int): Snapshot {
        val elements = JSONArray()
        val boxes = ArrayList<Box>(64)
        val seen = HashSet<String>(128)
        val reasons = HashSet<String>(2)
        var counter = 0
        if (root != null) {
            counter = walk(root, 0, counter, elements, boxes, seen, reasons, null, isRoot = true)
        } else {
            reasons += "no_root"
        }
        return Snapshot(
            elements = elements,
            packageName = root?.packageName?.toString(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            boxes = boxes,
            truncated = reasons.isNotEmpty(),
            truncationReasons = reasons.toList().sorted(),
            sparse = elements.length() < MIN_COMPLETE_ELEMENTS,
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        counterIn: Int,
        out: JSONArray,
        boxes: MutableList<Box>,
        seen: MutableSet<String>,
        reasons: MutableSet<String>,
        parentBounds: Rect?,
        isRoot: Boolean,
    ): Int {
        var counter = counterIn
        if (depth > MAX_DEPTH) {
            reasons += "depth_limit"
            return counter
        }
        if (counter >= MAX_NODES) {
            reasons += "node_limit"
            return counter
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString() ?: "android.view.View"
        val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable
        // 密码框正文不落盘：轨迹数据会离开设备，密码没有理由进数据集
        val ownText = if (node.isPassword) null else node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val ownDesc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        // RecyclerView 复用视图时，个别子节点会保留滚动前的旧 bounds（常见于 Settings 卡片文字）。
        // 子节点与父节点区域不相交 = 这层的坐标不可信，直接丢弃；容器自身的派生标签仍保留同样信息。
        val insideParent = parentBounds == null || parentBounds.isEmpty ||
            Rect.intersects(bounds, parentBounds)
        val visible = node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 && insideParent

        if (!isRoot && visible && (ownText != null || ownDesc != null || interactive)) {
            val text = ownText ?: ownDesc ?: derivedLabel(node)
            val key = "${text.orEmpty()}|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|$depth"
            // 同一位置的同名节点（TextView 套 span）只留一条：训练文本预算经不起三倍重复
            if (text != null && !seen.add(key)) {
                // 重复文本节点仍然继续遍历子节点（子节点可能有别的标签）
            } else {
                val index = counter
                // 设置类页面常把 Switch/CheckBox 藏在可点行里：把语义与勾选状态提升到行上，
                // 否则训练数据里只剩一堆没有状态的 button
                val toggle = if (node.isClickable) toggleDescendant(node) else null
                out.put(
                    JSONObject().apply {
                        put("i", index)
                        put("text", text ?: "")
                        put("tag", shortClass(className))
                        put("role", roleOf(className, node, toggle?.first))
                        put("clickable", node.isClickable)
                        put("editable", node.isEditable)
                        put("x", bounds.centerX())
                        put("y", bounds.centerY())
                        put("w", bounds.width())
                        put("h", bounds.height())
                        put("level", depth)
                        put("visible", true)
                        put("scrollable", node.isScrollable)
                        put("checked", toggle?.second ?: node.isChecked)
                        put("disabled", !node.isEnabled)
                        node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                            ?.let { put("view_id", it) }
                        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && !node.isPassword }
                            ?.let { put("value", it.take(MAX_LABEL_CHARS)) }
                    },
                )
                boxes += Box(index, bounds.left, bounds.top, bounds.right, bounds.bottom, node.isClickable)
                counter++
            }
        }

        for (i in 0 until node.childCount) {
            if (counter >= MAX_NODES) {
                reasons += "node_limit"
                break
            }
            val child = node.getChild(i) ?: continue
            counter = walk(child, depth + 1, counter, out, boxes, seen, reasons, bounds, isRoot = false)
            // API 33 起框架自动回收；更低版本必须手动回收，否则长时间采集会撑爆节点池
            if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
        }
        return counter
    }

    /** 可点/可编辑容器自身没文字时，用后代文字拼一个标签（与 Provider 侧 DerivedLabel 同规则） */
    private fun derivedLabel(node: AccessibilityNodeInfo): String? {
        if (!node.isClickable && !node.isLongClickable && !node.isEditable) return null
        val parts = ArrayList<String>(MAX_DERIVED_PARTS)
        collectText(node, 0, parts)
        if (parts.isEmpty()) return null
        val joined = parts.joinToString(" / ")
        return if (joined.length > MAX_LABEL_CHARS) joined.take(MAX_LABEL_CHARS) else joined
    }

    private fun collectText(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>) {
        if (depth >= 3 || out.size >= MAX_DERIVED_PARTS) return
        for (i in 0 until node.childCount) {
            if (out.size >= MAX_DERIVED_PARTS) return
            val child = node.getChild(i) ?: continue
            val text = if (child.isPassword) null else {
                child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            text?.let { out += it }
            collectText(child, depth + 1, out)
            if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
        }
    }

    /** 类名取末段：`android.widget.Button` → `Button`，让提示词里的 tag 更短 */
    private fun shortClass(className: String): String =
        className.substringAfterLast('.').ifBlank { className }

    /** 可点容器里的开关/复选子节点：返回 (类名, 是否勾选)，用于把状态提升到容器上 */
    private fun toggleDescendant(node: AccessibilityNodeInfo, depth: Int = 0): Pair<String, Boolean>? {
        if (depth >= 3) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cls = child.className?.toString().orEmpty()
            val checkable = child.isCheckable || cls.contains("Switch") || cls.contains("CheckBox") ||
                cls.contains("ToggleButton") || cls.contains("RadioButton")
            if (checkable) {
                val state = Pair(cls, child.isChecked)
                if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
                return state
            }
            val nested = toggleDescendant(child, depth + 1)
            if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
            if (nested != null) return nested
        }
        return null
    }

    /**
     * 语义角色：抽取管线按 role ∈ {button,input,link,checkbox,switch,tab} 判「可交互」，
     * 因此这里映射的是**语义**而不是控件类名。
     */
    private fun roleOf(
        className: String,
        node: AccessibilityNodeInfo,
        toggleClass: String? = null,
    ): String = when {
        node.isEditable -> "input"
        toggleClass != null && (toggleClass.contains("Switch") || toggleClass.contains("ToggleButton")) -> "switch"
        toggleClass != null && toggleClass.contains("RadioButton") -> "radio"
        toggleClass != null -> "checkbox"
        className.contains("CheckBox") || className.contains("CheckedTextView") -> "checkbox"
        className.contains("Switch") || className.contains("ToggleButton") -> "switch"
        className.contains("RadioButton") -> "radio"
        className.contains("Tab") -> "tab"
        className.contains("SeekBar") || className.contains("Slider") -> "slider"
        className.contains("Button") -> "button"
        className.contains("EditText") -> "input"
        className.contains("RecyclerView") || className.contains("ListView") ||
            className.contains("ScrollView") -> "list"
        className.contains("ImageView") || className.contains("Image") -> "image"
        className.contains("TextView") -> "text"
        node.isClickable -> "button"
        else -> "view"
    }
}
