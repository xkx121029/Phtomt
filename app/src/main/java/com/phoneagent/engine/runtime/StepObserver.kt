package com.phoneagent.engine.runtime

import android.graphics.Bitmap
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.perception.PageAnnotator
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.engine.runtime.EngineLimits.VISION_FALLBACK_THRESHOLD

/**
 * 一步观察的产物（重构阶段 B1）。
 *
 * 观察段只负责「读页面 → 标注 → 决定要不要图 → 拿图」，产出的四样东西原样交给决策段；
 * 敏感页接管、广告过滤等带 continue/return 的控制流仍留在主循环，避免改变循环语义。
 */
internal data class StepObservation(
    /** 本步快照（可能已被广告过滤清洗过） */
    val snapshot: ScreenSnapshot,
    /** 页面标注结果（页面类型 / 语义 id 表） */
    val annotated: AnnotatedPage,
    /** 回填过 semanticId 的快照，端侧决策与转译层的语义定位依赖它 */
    val semanticSnapshot: ScreenSnapshot,
    /** 本步截图；未开启截图且元素树可读时为 null */
    val screenshot: Bitmap?,
    /** 元素树是否稀疏（用于日志与视觉路由） */
    val treeSparse: Boolean,
)

/** 观察段的纯决策部分：元素稀疏判定、视觉路由、截图需求与复用判定 */
internal object ObservationPolicy {

    fun isTreeSparse(snapshot: ScreenSnapshot): Boolean =
        snapshot.elements.size <= VISION_FALLBACK_THRESHOLD

    /**
     * 端侧 3B 视觉是否需要本步截图（混合路由：简单任务才走 3B）。
     * 未开启端侧视觉时永远不需要；开启混合路由且页面复杂（元素稀疏）时跳过 3B。
     */
    fun needExternal3b(settings: AppSettings.Settings, treeSparse: Boolean): Boolean =
        settings.enableExternalVision && (!settings.smartVisionRoute || !treeSparse)

    /**
     * 是否需要截图。
     *
     * 口径：只有「图真的会被消费」时才抓帧，不再每步必截——
     * 1. 元素树稀疏/为空：视觉是唯一信息来源，必须截；
     * 2. 主模型自己能识图且开了附送截图：图会随消息发给主模型；
     * 3. 关掉混合路由（每步都走端侧 3B）：3B 需要图。
     *
     * 其余情况（文本主模型 + 混合路由）默认不截图，AI 明确要看时由 `see` 现场补抓一帧。
     */
    fun shotNeeded(
        settings: AppSettings.Settings,
        needExternal3b: Boolean,
        treeSparse: Boolean,
        mainSeesImage: Boolean,
    ): Boolean =
        treeSparse ||
            (settings.attachScreenshot && mainSeesImage) ||
            (needExternal3b && !settings.smartVisionRoute)

    /** 页面指纹未变且缓存位图仍可用时复用上一帧，省掉一次抓帧与位图分配 */
    fun canReuseShot(shotNeeded: Boolean, pageSig: String, cachedSig: String?, cachedShotUsable: Boolean): Boolean =
        shotNeeded && pageSig == cachedSig && cachedShotUsable
}

/**
 * 观察段执行器（重构阶段 B1，见 [StepObservation]）。
 *
 * 从 AgentEngine 迁出「标注 + semanticId 回填 + 截图决策 + 截图缓存」四件事。
 * 截图改为**按需抓帧**（见 [ObservationPolicy.shotNeeded]）：页面元素树可读且图没人消费时不抓，
 * 失败仍然降级为「本轮无截图」，指纹未变仍然复用上一帧位图。
 * 截图缓存随任务结束必须释放（全屏位图动辄数 MB 原生内存），由 [reset] 负责。
 */
internal class StepObserver(
    /** 实际抓帧；异常已由调用方兜底为 null，这里只负责缓存与降级判断 */
    private val capture: suspend () -> Bitmap?,
    /** 日志出口（文案与迁移前一致，由引擎侧决定落点） */
    private val log: (AgentLog.Level, String) -> Unit,
    /** 元素树统计文案（无障碍服务未连接时为 null） */
    private val treeStats: () -> String?,
    /** 主模型是否能识图（决定「附送截图」这一路是否真的有人消费） */
    private val mainSeesImage: (AppSettings.Settings) -> Boolean = { false },
) {
    /** 上一轮截图与其页面指纹：指纹未变时直接复用，省掉一次抓帧与位图分配 */
    private var cachedShot: Bitmap? = null
    private var cachedShotSig: String? = null

    /** 任务结束/停止时释放截图缓存 */
    internal fun reset() {
        cachedShot = null
        cachedShotSig = null
    }

    internal suspend fun observe(
        step: Int,
        snapshot: ScreenSnapshot,
        settingsVal: AppSettings.Settings,
    ): StepObservation {
        val annotated = PageAnnotator.annotate(snapshot)
        // 语义 id 回填：annotate() 把 semanticId 标在它新建的元素列表（annotated.elements）里，
        // 原始 snapshot 的元素里 semanticId 全为 null；不回填的话，端侧决策的 by="id" 语义定位
        // 与转译层的语义控件命中（send/confirm/close/delete 等）就永远失败
        val semanticSnapshot = snapshot.copy(
            elements = snapshot.elements.map { it.copy(semanticId = annotated.semanticIds[it.index]) },
        )
        val treeSparse = ObservationPolicy.isTreeSparse(snapshot)
        val needExternal3b = ObservationPolicy.needExternal3b(settingsVal, treeSparse)
        // 截图链路（MediaProjection / 无障碍）在权限被回收或服务断开时会直接抛异常；
        // 截图只是增强信息，失败即降级为「本轮无截图」，用无障碍元素树继续决策
        val shotNeeded = ObservationPolicy.shotNeeded(
            settingsVal, needExternal3b, treeSparse, mainSeesImage(settingsVal),
        )
        // 指纹只在本轮真要图时才算：不算图就不需要为复用做指纹计算
        val pageSig = if (shotNeeded) PageFingerprint.computeMeaningful(snapshot) else ""
        val reuseShot = ObservationPolicy.canReuseShot(
            shotNeeded, pageSig, cachedShotSig, cachedShot?.isRecycled == false,
        )
        val screenshot = when {
            !shotNeeded -> null
            reuseShot -> cachedShot
            else -> capture()?.also {
                cachedShot = it
                cachedShotSig = pageSig
            }
        }
        if (snapshot.elements.isEmpty()) {
            // 元素树为空的直接证据：区分「系统没给节点」与「被筛选条件挡掉」
            log(AgentLog.Level.WARN, "元素树为空：${treeStats() ?: "无统计"}")
        }
        log(
            AgentLog.Level.INFO,
            "第 $step 轮观察：${snapshot.elements.size} 个元素，页面类型=${annotated.pageType}" +
                if (treeSparse) "（元素稀疏，自动启用视觉模型）" else "",
        )
        return StepObservation(snapshot, annotated, semanticSnapshot, screenshot, treeSparse)
    }
}
