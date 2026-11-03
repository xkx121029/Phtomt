package com.phoneagent.engine.loop

import com.phoneagent.core.security.DataSanitizer
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.domain.rules.PageTypeRules
import com.phoneagent.domain.rules.effectiveLabel
import com.phoneagent.engine.runtime.ObservationPolicy

/**
 * 「动作即观察」（对标 Eta）：动作真正交付后抓的一次轻量快照。
 *
 * 动作执行完本来就要等到页面稳定（[com.phoneagent.engine.execution.VerifyRules.stepSettleMs]），
 * 这一步恰好可以顺手读一次元素树，把「动作前 → 动作后」的变化写进动作结果。
 * 下一步观察只要拿到这份快照，就能省掉一次读屏 + 一次截图 + 一次完整标注；
 * 元素树直接复用（读屏成本已经付过，不做删减，避免把第 31 个控件裁掉后 AI 找不到目标），
 * 面向模型的差异文本才裁剪到 20~30 节点。
 *
 * 不能复用的场景（稀疏页 / 元素树为空 / 加载中 / 强制视觉 / 快照过期）由
 * [AfterObservationPolicy.isReusable] 统一判定，命中时下一步照旧走完整观察，
 * 稀疏页的「截图兜底」判断完全不受影响。
 *
 * @property snapshot 动作后整棵元素树（未截图，`hasScreenshot=false`）
 * @property actionType 触发的动作类型
 * @property step 动作发生在第几步
 * @property diffText 面向模型的「前后差异」摘要（已脱敏、已限长）
 * @property capturedAtMs 抓取时刻，用于过期判定
 */
internal data class AfterObservation(
    val snapshot: ScreenSnapshot,
    val actionType: String,
    val step: Int,
    val diffText: String,
    val capturedAtMs: Long,
)

/** 动作后快照不可复用的原因（同时用于日志文案与统计口径） */
internal enum class AfterRejectReason(val label: String) {
    EMPTY("元素树为空，读不到页面"),
    SPARSE("元素稀疏，需要截图兜底"),
    LOADING("页面仍在加载"),
    VISION_PRIMARY("强制视觉模式，每步都需要截图"),
    STALE("快照已过期"),
}

/** 「动作即观察」的判定与差异摘要（纯函数，可单测） */
internal object AfterObservationPolicy {

    /** 差异摘要里每侧最多展示的元素数：20~30 节点区间的取值 */
    const val MAX_DIFF_NODES = 24

    /** 差异摘要里每侧最多列出的标签数 */
    const val MAX_DIFF_LABELS = 8

    /** 差异摘要的总长度上限，防止长列表页把上下文撑爆 */
    const val MAX_DIFF_TEXT = 900

    /** 快照有效期：动作后到下一步观察之间的正常间隔只有几十毫秒，超过 2.5s 视为用户暂停过 */
    const val MAX_AGE_MS = 2_500L

    /** 不改变页面的动作：抓快照没有意义，白付一次读屏 */
    private val NON_SCREEN_ACTIONS = setOf(
        ActionType.WAIT,
        ActionType.SAY,
        ActionType.REMEMBER,
        ActionType.DEVICE_QUERY,
        ActionType.SHOW_AGENT,
        ActionType.MCP_CALL,
        ActionType.WRITE_DOC,
        ActionType.TASK_DONE,
        ActionType.TASK_COMPLETE,
        ActionType.ABORT,
    )

    /**
     * 是否值得在动作交付后抓快照。
     * 只认「已确认交付」的动作（未验证成功说明页面很可能没变，下一步的读屏本来就要重来）。
     */
    fun shouldCapture(actionType: String, verified: Boolean): Boolean =
        verified && actionType !in NON_SCREEN_ACTIONS

    /**
     * 下一步是否可以跳过完整观察。
     *
     * 不能复用的四类情况都是「截图/读屏是必须信息」的场景：
     * - 元素树为空：读不到页面，需要重新读
     * - 元素稀疏：现有策略会强制截图，必须走完整观察（**截图兜底不受影响**）
     * - 加载中：快照是中间态，下一步大概率已经变了
     * - 强制视觉：用户关掉了智能路由（`enableExternalVision && !smartVisionRoute`），
     *   每一步都要截图，跳过观察等于砍掉视觉链路
     *
     * 注意：`attachScreenshot`（附加截图）与智能路由默认开启时**不阻止复用**——
     * 这张图只是增强信息，动作后的元素树差异已经能说明变化；否则默认设置下永远不会复用，
     * 「动作即观察」等于没落地。需要每步必看图的用户走「强制视觉」路径，仍然每一步都截图。
     */
    fun isReusable(
        after: AfterObservation,
        settings: AppSettings.Settings,
        nowMs: Long = System.currentTimeMillis(),
    ): AfterRejectReason? {
        val snapshot = after.snapshot
        if (snapshot.elements.isEmpty()) return AfterRejectReason.EMPTY
        if (PageTypeRules.inferPageType(snapshot) == "loading") return AfterRejectReason.LOADING
        if (ObservationPolicy.isTreeSparse(snapshot)) return AfterRejectReason.SPARSE
        if (settings.enableExternalVision && !settings.smartVisionRoute) return AfterRejectReason.VISION_PRIMARY
        if (nowMs - after.capturedAtMs > MAX_AGE_MS) return AfterRejectReason.STALE
        return null
    }

    /**
     * 构造动作后快照。差异摘要只覆盖「标签/值/选中态/可编辑性」这类语义变化，
     * 不含坐标——滚动导致的坐标位移不该被描述成「整页都变了」。
     */
    fun capture(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        actionType: String,
        step: Int,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): AfterObservation = AfterObservation(
        snapshot = after,
        actionType = actionType,
        step = step,
        diffText = buildDiff(before, after),
        capturedAtMs = capturedAtMs,
    )

    /** 生成「动作前 → 动作后」差异摘要（已脱敏、已限长） */
    fun buildDiff(before: ScreenSnapshot, after: ScreenSnapshot): String {
        val (added, removed) = diffElements(before.elements, after.elements)
        val pkgChanged = before.packageName != null && after.packageName != null &&
            before.packageName != after.packageName

        val text = buildString {
            if (pkgChanged) append("前台应用 ${before.packageName} → ${after.packageName}；")
            append("元素 ${before.elements.size} → ${after.elements.size}")
            if (added.isEmpty() && removed.isEmpty()) {
                append("，可见内容基本未变")
            } else {
                if (added.isNotEmpty()) {
                    append("；新增 ${added.size} 个：")
                    append(labelSummary(added))
                }
                if (removed.isNotEmpty()) {
                    append("；消失 ${removed.size} 个：")
                    append(labelSummary(removed))
                }
            }
        }
        val sanitized = DataSanitizer.sanitize(text)
        return if (sanitized.length > MAX_DIFF_TEXT) sanitized.take(MAX_DIFF_TEXT) + "…" else sanitized
    }

    /** 以「语义签名」做多重集差集，避免同名控件只看次数不看增减 */
    private fun diffElements(
        before: List<UiElement>,
        after: List<UiElement>,
    ): Pair<List<UiElement>, List<UiElement>> {
        val beforeCount = before.groupingBy(::signature).eachCount()
        val afterCount = after.groupingBy(::signature).eachCount()
        val added = buildList {
            after.groupBy(::signature).forEach { (sig, list) ->
                val delta = list.size - (beforeCount[sig] ?: 0)
                if (delta > 0) addAll(list.take(delta))
            }
        }
        val removed = buildList {
            before.groupBy(::signature).forEach { (sig, list) ->
                val delta = list.size - (afterCount[sig] ?: 0)
                if (delta > 0) addAll(list.take(delta))
            }
        }
        return added.take(MAX_DIFF_NODES) to removed.take(MAX_DIFF_NODES)
    }

    private fun signature(e: UiElement): String = buildString {
        append(e.viewId ?: "")
        append('|').append(e.className)
        append('|').append(e.effectiveLabel() ?: "")
        append('|').append(e.currentValue ?: "")
        append('|').append(e.isSelected?.toString() ?: "")
        append('|').append(if (e.editable) "e" else "")
    }

    /** 列出差异元素的有效标签：先交互控件，再带文字的，最后兜底类名 */
    private fun labelSummary(elements: List<UiElement>): String {
        val named = elements
            .sortedWith(
                compareBy<UiElement>(
                    { if (it.clickable || it.editable) 0 else 1 },
                    { if (it.effectiveLabel().isNullOrBlank()) 1 else 0 },
                ),
            )
            .mapNotNull { e ->
                val label = e.effectiveLabel()?.takeIf(String::isNotBlank)
                label ?: e.type.takeIf(String::isNotBlank)
            }
        if (named.isEmpty()) return "（无文字控件）"
        val shown = named.take(MAX_DIFF_LABELS).joinToString("、")
        return if (named.size > MAX_DIFF_LABELS) "$shown 等 ${named.size} 个" else shown
    }
}

/**
 * 「动作即观察」的命中统计与耗时对比。
 *
 * 每步都会落一条日志（复用 / 不可复用原因），并按任务累计：任务切换时把上一任务的汇总
 * 落一条日志再清零（与 [com.phoneagent.engine.perf.StageTiming] 同样的隔离思路）。
 * 这里不复用 StageTiming，是因为阶段计时按真实 taskId 分桶，而观察段拿不到 taskId；
 * 混用两种键会互相触发「任务切换清零」，把两边的汇总都搅乱。
 *
 * 耗时口径：
 * - 完整观察 = 读屏 + 标注截图
 * - 复用观察 = 仅标注（无读屏、无截图）
 * - 净节省 = (完整单步均值 - 复用单步均值) × 复用次数 - 动作后快照总耗时
 *   （不可复用的步骤既付了抓快照又走了完整观察，属于净亏，已从公式里扣掉）
 */
internal object AfterObservationStats {
    private val lock = Any()
    private var activeTask: String? = null
    private var captured = 0
    private var reused = 0
    private var rejected = 0
    private val rejectByReason = mutableMapOf<AfterRejectReason, Int>()

    private var fullReadCount = 0
    private var fullReadTotalMs = 0L
    private var fullShotCount = 0
    private var fullShotTotalMs = 0L
    private var reuseAnnotateCount = 0
    private var reuseAnnotateTotalMs = 0L
    private var captureCount = 0
    private var captureTotalMs = 0L

    /**
     * 任务切换时调用：返回上一任务的汇总（没有上一任务或样本为空时返回 null），并重置统计。
     * 调用方负责写日志（锁内不碰 UI 状态流）。
     */
    fun beginTask(task: String): String? = synchronized(lock) {
        if (activeTask != null && activeTask != task && hasSamples()) {
            val text = summaryInternal()
            resetInternal()
            activeTask = task
            "上一任务动作即观察汇总：$text"
        } else {
            if (activeTask != task) resetInternal()
            activeTask = task
            null
        }
    }

    fun onCaptured() {
        synchronized(lock) { captured++ }
    }

    fun onReused() {
        synchronized(lock) { reused++ }
    }

    fun onRejected(reason: AfterRejectReason) {
        synchronized(lock) {
            rejected++
            rejectByReason[reason] = (rejectByReason[reason] ?: 0) + 1
        }
    }

    /** 完整观察：读屏耗时 */
    fun onFullRead(elapsedMs: Long) {
        synchronized(lock) {
            fullReadCount++
            fullReadTotalMs += elapsedMs.coerceAtLeast(0)
        }
    }

    /** 完整观察：标注 + 截图耗时 */
    fun onFullAnnotateShot(elapsedMs: Long) {
        synchronized(lock) {
            fullShotCount++
            fullShotTotalMs += elapsedMs.coerceAtLeast(0)
        }
    }

    /** 复用观察：仅标注耗时 */
    fun onReuseAnnotate(elapsedMs: Long) {
        synchronized(lock) {
            reuseAnnotateCount++
            reuseAnnotateTotalMs += elapsedMs.coerceAtLeast(0)
        }
    }

    /** 动作后快照的读屏耗时（无论最终是否被复用都要记账：这是一次实付成本） */
    fun onCapture(elapsedMs: Long) {
        synchronized(lock) {
            captureCount++
            captureTotalMs += elapsedMs.coerceAtLeast(0)
        }
    }

    fun counts(): Triple<Int, Int, Int> = synchronized(lock) { Triple(reused, rejected, captured) }

    fun summaryText(): String = synchronized(lock) { summaryInternal() }

    private fun hasSamples(): Boolean =
        captured > 0 || reused > 0 || rejected > 0 || fullReadCount > 0 || captureCount > 0

    private fun summaryInternal(): String {
        val observedSteps = reused + rejected
        // 百分比四舍五入到整数：2/3 应显示 67% 而不是截断成 66%
        val ratio = if (observedSteps > 0) (reused * 100 + observedSteps / 2) / observedSteps else 0
        val reasons = if (rejectByReason.isEmpty()) {
            ""
        } else {
            rejectByReason.entries.joinToString("，") { "${it.key.label} ${it.value} 次" }
        }
        val avgRead = avg(fullReadTotalMs, fullReadCount)
        val avgShot = avg(fullShotTotalMs, fullShotCount)
        val avgReuse = avg(reuseAnnotateTotalMs, reuseAnnotateCount)
        val avgCapture = avg(captureTotalMs, captureCount)
        val fullStepMs = avgRead + avgShot
        val savedPerStepMs = (fullStepMs - avgReuse).coerceAtLeast(0)
        val netSavedMs = savedPerStepMs * reused - captureTotalMs
        return "动作后快照复用 $reused/$observedSteps 步（跳过观察 ${ratio}%），不可复用 $rejected 步" +
            (if (reasons.isBlank()) "" else "（$reasons）") +
            "；完整观察 读屏 ${avgRead}ms + 标注截图 ${avgShot}ms = ${fullStepMs}ms，" +
            "复用观察 仅标注 ${avgReuse}ms，单步省 ${savedPerStepMs}ms；" +
            "动作后快照 ${captureCount} 次均 ${avgCapture}ms，净节省 ${netSavedMs}ms；" +
            "累计抓取 $captured 次"
    }

    private fun avg(total: Long, count: Int): Long = if (count <= 0) 0 else total / count

    fun reset() {
        synchronized(lock) {
            activeTask = null
            resetInternal()
        }
    }

    private fun resetInternal() {
        captured = 0
        reused = 0
        rejected = 0
        rejectByReason.clear()
        fullReadCount = 0
        fullReadTotalMs = 0
        fullShotCount = 0
        fullShotTotalMs = 0
        reuseAnnotateCount = 0
        reuseAnnotateTotalMs = 0
        captureCount = 0
        captureTotalMs = 0
    }
}
