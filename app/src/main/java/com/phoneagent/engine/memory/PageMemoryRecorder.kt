package com.phoneagent.engine.memory

import com.phoneagent.core.security.SensitivePageDetector
import com.phoneagent.data.store.PageMemoryStore
import com.phoneagent.data.store.PageMemoryHotspot
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.IntentType
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.engine.perception.effectiveLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 页面记忆采集器：任务执行过程中自动沉淀「遇到过的页面 + 页面间跳转路径」。
 *
 * 数据流（与 task_memory 同款「内存快照 + fire-and-forget 落库」模式，不阻塞决策）：
 * - 每步观察后 [onPageObserved]：指纹与上一记忆页不同才算新页面，入库页面热点；
 *   若上一步动作验证成功且带来了这次换页，顺带把「上页 —[动作]→ 本页」路径边入库。
 * - 决策产出后 [onIntent]：把意图转成一句动作文案（如「点击『搜索』」），暂存为待记路径。
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

    /** 上一步动作是否验证生效：失败的边不记 */
    private var lastStepVerified: Boolean = false

    /** 任务开始清空内存快照（DataStore 里的历史记忆保留） */
    fun reset() {
        lastFingerprint = ""
        lastPackage = ""
        pendingFromFp = ""
        pendingAction = ""
        lastStepVerified = false
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

        val known = runCatching { findKnown(pkg, fp) }.getOrNull()

        // 换页了：先把「上页 —[动作]→ 本页」的边记掉（仅当上一步真实生效、且跳转发生在同一应用内）
        if (fp != lastFingerprint && pkg == lastPackage) {
            commitPendingEdge(pkg, fp)
        }
        // 同一页面刷新（指纹相同）不重复入库，只刷新命中状态；
        // 指纹不含包名，跨应用的同构页面（fp 相同 pkg 不同）也要各自入库
        if (fp != lastFingerprint || pkg != lastPackage) {
            lastFingerprint = fp
            lastPackage = pkg
            val entry = runCatching {
                store.upsertPageMemory(
                    appPackage = pkg,
                    fingerprint = fp,
                    pageType = annotated.pageType,
                    title = pageTitle(annotated),
                    hotspots = extractHotspots(annotated),
                )
            }.getOrNull()
            if (known == null && entry != null) return null // 新页面首次入库不算「命中旧记忆」
        }
        return known
    }

    /** 决策产出后调用：把意图转成一句动作文案，暂存为待记路径边 */
    fun onIntent(intent: AgentIntent) {
        pendingFromFp = lastFingerprint
        pendingAction = actionLabel(intent)
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
        val verified = lastStepVerified
        pendingFromFp = ""
        pendingAction = ""
        if (from.isBlank() || action.isEmpty() || !verified || toFp.isBlank() || from == toFp) return
        scope.launch {
            runCatching { store.upsertPageEdge(pkg, from, toFp, action) }
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
                    label = label.take(20),
                    semanticId = e.semanticId.orEmpty(),
                    ratioX = rx,
                    ratioY = ry,
                )
            }
            .take(12)
            .toList()
    }

    /** 页面标题：优先非点击的纯文字控件（多为标题/首行文案），兜底上下文提示 */
    private fun pageTitle(annotated: AnnotatedPage): String {
        val text = annotated.elements.firstOrNull { e ->
            !e.clickable && !e.longClickable && !e.effectiveLabel().isNullOrBlank()
        }?.effectiveLabel() ?: annotated.contextHint
        return text.orEmpty().trim().take(24)
    }

    /** 意图 → 一句动作文案（≤24 字，记忆页与提示注入共用） */
    private fun actionLabel(intent: AgentIntent): String {
        val target = intent.target?.value?.take(12).orEmpty()
        val quoted = if (target.isBlank()) "" else "『$target』"
        return when (intent.intent) {
            IntentType.TAP, IntentType.LONG_PRESS,
            IntentType.SEARCH, IntentType.SEND, IntentType.CONFIRM, IntentType.CLOSE,
            IntentType.SHARE, IntentType.COLLECT, IntentType.COPY, IntentType.DELETE,
            IntentType.DOWNLOAD, IntentType.ADD, IntentType.SWITCH, IntentType.CLEAR_INPUT,
            IntentType.REFRESH, IntentType.SCROLL_TO,
            -> "点击$quoted"
            IntentType.INPUT -> "输入$quoted"
            IntentType.OPEN_APP -> "打开${intent.app.orEmpty().take(12)}"
            IntentType.OPEN -> "直达页面"
            IntentType.SWIPE -> "滑动${intent.direction.orEmpty()}"
            IntentType.PRESS -> "按键${intent.key.orEmpty()}"
            IntentType.BACK -> "返回"
            IntentType.HOME -> "回桌面"
            IntentType.BROWSE_OPEN -> "打开网页"
            IntentType.BROWSE_CLICK -> "点击网页元素$quoted"
            else -> ""
        }
    }
}
