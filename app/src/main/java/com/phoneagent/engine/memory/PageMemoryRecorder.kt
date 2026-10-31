package com.phoneagent.engine.memory

import com.phoneagent.core.security.DataSanitizer
import com.phoneagent.core.security.SensitivePageDetector
import com.phoneagent.data.store.PageMemoryStore
import com.phoneagent.data.store.PageMemoryHotspot
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.data.store.PageMemoryOps
import com.phoneagent.data.store.PageShapeNode
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.domain.rules.PageActionLabels
import com.phoneagent.domain.rules.effectiveLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 页面记忆采集器：任务执行过程中自动沉淀「遇到过的页面形态 + 页面间跳转路径」。
 *
 * 数据流（与 task_memory 同款「内存快照 + fire-and-forget 落库」模式，不阻塞决策）：
 * - 每步观察后 [onPageObserved]：指纹与上一记忆页不同才算新页面，入库页面热点与控件空间树；
 *   若上一步动作验证成功且带来了这次换页，顺带把「上页 —[控件]→ 本页」路径边入库。
 * - 决策产出后 [onIntent]：把意图转成一句动作文案（如「点击『搜索』」），并解析出发控件
 *   标识（semanticId 优先、文字兜底），暂存为待记路径。
 * - 执行验证后 [onStepResult]：动作是否真实生效，失败的路径边不记。
 *
 * 铁律：敏感页绝不入库（引擎侧在敏感判定之后才调用本类，这里再做一道双保险）。
 */
class PageMemoryRecorder(
    private val store: PageMemoryStore,
    private val scope: CoroutineScope,
) {

    // ---- 内存快照（跨步状态）----

    /** 上一记忆页指纹：同页反复观察不重复入库 */
    private var lastFingerprint: String = ""

    /** 上一记忆页所属包名 */
    private var lastPackage: String = ""

    /** 待记路径边：出发页指纹（动作决策时所在页面） */
    private var pendingFromFp: String = ""

    /** 待记路径边：动作文案（onIntent 产出） */
    private var pendingAction: String = ""

    /** 待记路径边：出发控件标识（semanticId 优先、控件文字兜底） */
    private var pendingFromControl: String = ""

    /** 上一步动作是否验证生效：失败的边不记 */
    private var lastStepVerified: Boolean = false

    /** 最近一次观察页面的 label→semanticId 反查表：onIntent 时把 by=text 的 value 反查成 semanticId */
    private var lastSemanticIds: Map<String, String> = emptyMap()

    /** semanticId→label 正查表：动作文案里把 by=id 的 value 还原成控件文字，保证同一控件两种定位方式记同一条边 */
    private var lastIdsToLabel: Map<String, String> = emptyMap()

    /** 任务开始清空内存快照（DataStore 里的历史记忆保留） */
    fun reset() {
        lastFingerprint = ""
        lastPackage = ""
        pendingFromFp = ""
        pendingAction = ""
        pendingFromControl = ""
        lastStepVerified = false
        lastSemanticIds = emptyMap()
    }

    /**
     * 每步观察后调用。返回命中的已记忆页面（新页面返回 null），供屏幕标记与提示注入使用。
     * 仅当 [ScreenSnapshot.packageName] 与指纹都与上次不同时才视为新页面并入库。
     */
    suspend fun onPageObserved(snapshot: ScreenSnapshot, annotated: AnnotatedPage): PageMemoryEntry? {
        val pkg = snapshot.packageName.orEmpty()
        if (pkg.isBlank() || SensitivePageDetector.isSensitive(snapshot)) return null
        val fp = runCatching { PageFingerprint.computeMeaningful(snapshot) }.getOrDefault("")
        if (fp.isBlank()) return null

        // label→semanticId 反查表随观察刷新（即使同页不重记，也要保证 onIntent 能反查到）
        val pairs = annotated.elements.mapNotNull { e ->
            val label = e.effectiveLabel()?.trim().orEmpty()
            if (label.isEmpty()) null else label to e.semanticId.orEmpty()
        }
        lastSemanticIds = pairs.toMap()
        lastIdsToLabel = pairs.filter { it.second.isNotBlank() }.associate { (l, id) -> id to l }

        val known = runCatching { findKnown(pkg, fp) }.getOrNull()

        // 换页了且上一步动作真实生效 → 记「上页 —[动作]→ 本页」边；toFp 用本页规范指纹
        val pageChanged = fp != lastFingerprint && pkg == lastPackage
        // 同一页面刷新（指纹相同）不重复入库，只刷新命中状态；
        // 指纹不含包名，跨应用的同构页面（fp 相同 pkg 不同）也要各自入库
        if (fp != lastFingerprint || pkg != lastPackage) {
            lastPackage = pkg
            val shapes = extractShapes(annotated)
            val entry = runCatching {
                store.upsertPageMemory(
                    appPackage = pkg,
                    fingerprint = fp,
                    pageType = annotated.pageType,
                    title = pageTitle(annotated),
                    hotspots = extractHotspots(annotated),
                    shapes = shapes,
                    shapeSig = PageMemoryOps.shapeSignature(shapes),
                )
            }.getOrNull()
            // 同形归并命中旧条目时返回的是旧页面（规范指纹）：回写 lastFingerprint，
            // 后续路径边的 from/to 都落在规范条目上，图谱不会因别名指纹断线
            lastFingerprint = entry?.fingerprint ?: fp
            // 边在入库之后提交：toFp 取归并后的规范指纹（若目标页与本页同形归并，
            // from == to 会被 commitPendingEdge 拒掉——相同结构的页面本就是同一页）
            if (pageChanged) commitPendingEdge(pkg, lastFingerprint)
            if (known == null && entry != null) return null // 新页面首次入库不算「命中旧记忆」
        }
        return known
    }

    /** 决策产出后调用：把意图转成一句动作文案 + 出发控件标识，暂存为待记路径边 */
    fun onIntent(intent: AgentIntent) {
        pendingFromFp = lastFingerprint
        pendingAction = actionLabel(intent)
        pendingFromControl = controlTag(intent)
    }

    /** 执行验证后调用：动作真实生效才允许记路径边 */
    fun onStepResult(verified: Boolean) {
        lastStepVerified = verified
    }

    /** 按包名+指纹查已记忆页面 */
    private suspend fun findKnown(pkg: String, fp: String): PageMemoryEntry? =
        store.loadPageMemories().firstOrNull { it.appPackage == pkg && it.fingerprint == fp }

    /** 把暂存的「上页 —[动作]→ 本页」边落库（fire-and-forget，失败静默）；[toFp] 是本次观察到的目标页指纹 */
    private fun commitPendingEdge(pkg: String, toFp: String) {
        val from = pendingFromFp
        val action = pendingAction
        val control = pendingFromControl
        val verified = lastStepVerified
        pendingFromFp = ""
        pendingAction = ""
        pendingFromControl = ""
        if (from.isBlank() || action.isEmpty() || !verified || toFp.isBlank() || from == toFp) return
        scope.launch {
            runCatching { store.upsertPageEdge(pkg, from, toFp, action, control) }
        }
    }

    /** 热点提取：带文字的可点击控件（有语义 id 的优先），最多 12 个 */
    private fun extractHotspots(annotated: AnnotatedPage): List<PageMemoryHotspot> {
        val w = annotated.snapshot.screenWidth.takeIf { it > 0 } ?: return emptyList()
        val h = annotated.snapshot.screenHeight.takeIf { it > 0 } ?: 1
        return annotated.elements.asSequence()
            .filter { (it.clickable || it.longClickable) }
            .mapNotNull { e ->
                val label = e.effectiveLabel()?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
                val rx = e.ratioX ?: (e.centerX.toFloat() / w)
                val ry = e.ratioY ?: (e.centerY.toFloat() / h)
                PageMemoryHotspot(
                    label = DataSanitizer.sanitize(label).take(20),
                    semanticId = e.semanticId.orEmpty(),
                    ratioX = rx,
                    ratioY = ry,
                )
            }
            .take(12)
            .toList()
    }

    /**
     * 控件空间树提取：有文字或可交互的可见控件，整页快照供虚拟画面绘制。
     * 过滤：不可见、零面积、无文字且占屏 >90% 的根容器；重叠大容器淘汰（PageMemoryOps.dropOverlapping）。
     * 上限 [PageMemoryOps.MAX_PAGE_SHAPES]：annotated.elements 已按优先级排序，截断天然保住高价值控件。
     */
    private fun extractShapes(annotated: AnnotatedPage): List<PageShapeNode> {
        val w = annotated.snapshot.screenWidth.takeIf { it > 0 } ?: return emptyList()
        val h = annotated.snapshot.screenHeight.takeIf { it > 0 } ?: return emptyList()
        return annotated.elements.asSequence()
            .filter { it.isVisibleToUser }
            .filter { e ->
                val hasLabel = !e.effectiveLabel().isNullOrBlank()
                hasLabel || e.clickable || e.longClickable || e.editable
            }
            .map { e ->
                fun clamp(v: Int, total: Int) = (v.toFloat() / total).coerceIn(0f, 1f)
                PageShapeNode(
                    label = DataSanitizer.sanitize(e.effectiveLabel().orEmpty().trim()).take(16),
                    kind = kindOf(e),
                    l = clamp(e.left, w),
                    t = clamp(e.top, h),
                    r = clamp(e.right, w),
                    b = clamp(e.bottom, h),
                    clickable = e.clickable || e.longClickable,
                    semanticId = e.semanticId.orEmpty(),
                )
            }
            .filterNot { it.r - it.l <= 0.002f || it.b - it.t <= 0.002f }
            .filter { e ->
                // 无文字且铺满全屏的大容器（根布局）没有绘制价值
                e.label.isNotBlank() || (e.r - e.l) * (e.b - e.t) < 0.9f
            }
            .distinctBy {
                "${it.label}|${(it.l * 100).toInt()}|${(it.t * 100).toInt()}|${(it.r * 100).toInt()}|${(it.b * 100).toInt()}"
            }
            .toList()
            .let { PageMemoryOps.dropOverlapping(it) }
            .take(PageMemoryOps.MAX_PAGE_SHAPES)
    }

    /** 控件形态归一化：收敛成虚拟画面可 switch 的封闭集合（text/button/input/image/toggle/other） */
    private fun kindOf(e: com.phoneagent.domain.model.UiElement): String = when {
        e.editable -> "input"
        listOf("switch", "toggle", "checkbox", "radio").any { e.className.contains(it, true) || e.type.contains(it, true) } -> "toggle"
        listOf("imageview", "image", "imagebutton").any { e.className.contains(it, true) || e.type.contains(it, true) } -> "image"
        e.clickable || e.longClickable -> "button"
        e.effectiveLabel()?.trim().isNullOrEmpty().not() -> "text"
        else -> "other"
    }

    /** 页面标题：优先非点击的纯文字控件（多为标题/首行文案），兜底上下文提示 */
    private fun pageTitle(annotated: AnnotatedPage): String {
        val text = annotated.elements.firstOrNull { e ->
            !e.clickable && !e.longClickable && !e.effectiveLabel().isNullOrBlank()
        }?.effectiveLabel() ?: annotated.contextHint
        return DataSanitizer.sanitize(text.orEmpty().trim()).take(24)
    }

    /**
     * 意图 → 出发控件标识（≤40 字）：by=id 时 value 就是 semanticId 直接用；
     * by=text 时经最近一次观察的反查表换成 semanticId，查不到退控件文字。
     */
    private fun controlTag(intent: AgentIntent): String {
        val by = intent.target?.by.orEmpty()
        val value = intent.target?.value?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return if (by == "id") {
            value.take(40)
        } else {
            lastSemanticIds[value]?.takeIf { it.isNotBlank() }?.take(40) ?: value.take(24)
        }
    }

    /** 意图 → 一句动作文案（≤24 字）；口径收口在 [PageActionLabels]（纯函数、可单测） */
    private fun actionLabel(intent: AgentIntent): String =
        PageActionLabels.of(intent, lastIdsToLabel)
}
