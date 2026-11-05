// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.engine.loop

import com.phoneagent.core.security.SensitivePageDetector
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.engine.perception.AnnotatedPage
import com.phoneagent.engine.perception.PageAnnotator
import com.phoneagent.engine.runtime.LoopGuard
import com.phoneagent.engine.runtime.StepObserver
import com.phoneagent.engine.runtime.EngineLimits.MAX_SENSITIVE_TAKEOVERS
import com.phoneagent.feature.adskip.AdContentFilter
import kotlinx.coroutines.delay

/**
 * 观察阶段需要但不应耦合 Android 界面的出口（阶段 B2）。
 *
 * 全部收成回调而不是让阶段直接引用 AgentEngine：阶段只描述「观察段怎么做」，
 * 日志落点、悬浮窗文案、协作面板、状态流都由引擎实现；headless 测试里换成假实现即可。
 */
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
internal class ObservationHooks(
    val log: (AgentLog.Level, String) -> Unit,
    val pushFloating: (String, String) -> Unit,
    val showInteraction: (String, String, String) -> Unit,
    val setMessage: (String) -> Unit,
    val setNeedsUser: (Boolean) -> Unit,
    val emitHintRequest: (String) -> Unit,
    val awaitUserHint: suspend () -> String,
)

/**
 * 观察阶段（阶段 B2）：读页面 → 无障碍掉线检测 → 广告过滤 → 标注/截图 → 敏感页只读 → 页面记忆。
 *
 * 从 AgentEngine 主循环原样迁入。原先这些分支各自用 `continue` / `return` 打断循环，
 * 现在统一返回 [StepOutcome]：
 * - 广告自动关闭、敏感页用户已处理 → [StepOutcome.Continue]（本步到此为止，进入下一轮）
 * - 用户在协作面板直接离开 → [StepOutcome.Fatal]（任务记忆置失败并停止）
 *
 * 行为等价要点：
 * - 广告自动关闭连续命中的护栏仍由 [LoopGuard] 判定；被拦下时**不剔除**广告内容，
 *   把真实页面交给 AI 重新决策（与迁移前一致）
 * - 敏感页连续接管超限直接失败，不再无限弹接管
 * - 页面记忆只在敏感页判定之后采集，敏感页绝不入库
 */
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList")
internal class ObservationPhase(
    /** 实际读屏（含副屏模式与无障碍读取失败降级，由引擎侧负责） */
    private val readScreen: suspend () -> ScreenSnapshot,
    private val observer: StepObserver,
    private val loopGuard: LoopGuard,
    private val a11yOnline: () -> Boolean,
    private val clickPoint: suspend (Int, Int) -> Unit,
    private val virtualDisplayActive: () -> Boolean,
    private val onPageObserved: suspend (ScreenSnapshot, AnnotatedPage) -> PageMemoryEntry?,
    private val pageMemoryHintFor: suspend (ScreenSnapshot, PageMemoryEntry?) -> String,
    private val updatePageMarks: (PageMemoryEntry?) -> Unit,
    /** 敏感页接管计数自增并返回新值（计数存于引擎任务级状态，跨步存活） */
    private val bumpSensitiveTakeover: () -> Int,
    private val hooks: ObservationHooks,
) : StepPhase {

    // 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    override suspend fun run(session: StepSession): StepOutcome {
        // 任务切换时把上一任务的「动作即观察」汇总落一条日志再清零
        AfterObservationStats.beginTask(session.task)?.let { hooks.log(AgentLog.Level.INFO, it) }
        // 动作即观察：上一步动作交付后已经抓过一次元素树，这里优先复用。
        // 消费一次即清空，避免同一份快照被后续步骤反复复用（页面状态早就变了）。
        val pending = session.pendingAfterObservation
        session.pendingAfterObservation = null
        val reject = if (pending != null) {
            AfterObservationPolicy.isReusable(pending, session.settingsVal)
        } else {
            null
        }
        val reuse = pending != null && reject == null

        // 读屏：复用快照时这一步直接省掉；完整观察的读屏耗时单独记账，供最终对比
        var snapshot = if (reuse) {
            pending.snapshot
        } else {
            if (pending != null && reject != null) {
                AfterObservationStats.onRejected(reject)
                hooks.log(
                    AgentLog.Level.INFO,
                    "第 ${session.step} 轮观察：动作后快照不可复用（${reject.label}），走完整观察",
                )
            }
            val t0 = System.nanoTime()
            readScreen().also {
                AfterObservationStats.onFullRead((System.nanoTime() - t0) / 1_000_000)
            }
        }

        // 无障碍服务任务中途掉线检测（真机 BUG 22）：先观测到在线、随后服务对象消失，
        // 说明授权被系统清除/服务被回收。此时必须暂停并引导用户重新授权，
        // 而不是继续消耗步数盲跑——那段盲循环每一步都在浪费 Token 与用户耐心。
        if (a11yOnline()) {
            session.a11ySeenAlive = true
        } else if (session.a11ySeenAlive && !virtualDisplayActive()) {
            // 副屏模式不依赖无障碍服务（截图 + input 注入），服务掉线不构成中断条件
            hooks.log(AgentLog.Level.ERROR, "无障碍服务已断开（任务执行中途），暂停任务等待用户处理")
            hooks.pushFloating("无障碍服务已断开", "ERROR")
            val dropReason = "无障碍服务已断开，AI 读不到页面也无法操作。请到 系统设置→无障碍→已下载的服务 " +
                "重新开启「Happy Agent 无障碍服务」，开启后点「已手动处理」继续任务。"
            hooks.showInteraction("guide", "无障碍服务已断开", dropReason)
            hooks.setNeedsUser(true)
            hooks.setMessage(dropReason)
            val hint = hooks.awaitUserHint()
            if (hint.isBlank()) {
                return StepOutcome.Fatal("无障碍服务中断，用户离开", taskMemoryNote = "无障碍服务已断开")
            }
            // 用户已处理：重新观测在线状态，避免一次瞬时未重连就再次误触暂停
            session.a11ySeenAlive = a11yOnline()
        }

        // 广告过滤（任务 7）：识别到"控件含广告信息 + 跳过/关闭/× 按钮"的广告时，
        // 直接点击关闭广告并跳过本轮 AI 决策；无按钮时剔除广告信息，保证广告不回传给 AI
        val adFilter = AdContentFilter.filter(snapshot)
        if (adFilter.isAd) {
            if (adFilter.target != null) {
                // 护栏：同一关闭目标连续命中 ≥2 次，说明自动点击无效（点了广告还在）。
                // 此时停止自动点击，该轮也不再剔除广告内容，把真实页面交给 AI 重新决策
                val targetKey = "${adFilter.target.centerX / 20}:${adFilter.target.centerY / 20}"
                if (!loopGuard.shouldAutoClickAd(targetKey)) {
                    hooks.log(AgentLog.Level.WARN, "广告自动关闭连续命中，已停止自动点击")
                } else {
                    hooks.log(AgentLog.Level.INFO, adFilter.reason)
                    // 关闭广告属于「执行」动作，阶段必须写 ACTING：写成 ACTION 落到 FloatingUi.phaseColor
                    // 的兜底琥珀色上，跑马灯会闪出一个设置页阶段清单里根本没列的颜色
                    hooks.pushFloating("检测到广告，自动关闭", "ACTING")
                    clickPoint(adFilter.target.centerX, adFilter.target.centerY)
                    delay(700)
                    return StepOutcome.Continue
                }
            } else {
                hooks.log(AgentLog.Level.INFO, adFilter.reason)
                snapshot = adFilter.cleanSnapshot
            }
        }
        session.snapshot = snapshot

        // 标注 + semanticId 回填 + 截图决策/复用（阶段 B1 已迁到 StepObserver）
        val annotated: AnnotatedPage
        if (reuse) {
            // 复用路径：只做标注与 semanticId 回填（本地 CPU，无读屏、无截图）。
            // 复用快照本来就没有截图，因此把 screenshot 显式置空，避免上一帧位图串到本轮决策。
            val t0 = System.nanoTime()
            val page = PageAnnotator.annotate(snapshot)
            session.annotated = page
            session.semanticSnapshot = snapshot.copy(
                elements = snapshot.elements.map { it.copy(semanticId = page.semanticIds[it.index]) },
            )
            session.screenshot = null
            AfterObservationStats.onReuseAnnotate((System.nanoTime() - t0) / 1_000_000)
            AfterObservationStats.onReused()
            hooks.log(
                AgentLog.Level.INFO,
                "第 ${session.step} 轮观察：复用动作后快照（跳过读屏与截图），元素 ${snapshot.elements.size} 个｜" +
                    AfterObservationStats.summaryText(),
            )
            annotated = page
        } else {
            val t0 = System.nanoTime()
            val observation = observer.observe(session.step, snapshot, session.settingsVal)
            AfterObservationStats.onFullAnnotateShot((System.nanoTime() - t0) / 1_000_000)
            session.annotated = observation.annotated
            session.semanticSnapshot = observation.semanticSnapshot
            session.screenshot = observation.screenshot
            annotated = observation.annotated
        }

        // 安全：敏感页只读
        if (SensitivePageDetector.isSensitive(snapshot)) {
            val streak = bumpSensitiveTakeover()
            if (streak > MAX_SENSITIVE_TAKEOVERS) {
                // 连续多轮仍是敏感页：要么用户已无意继续，要么页面判定过于敏感。
                // 明确失败收尾，而不是无限循环弹接管（用户每次都得手动点，体验死锁）
                hooks.log(AgentLog.Level.WARN, "敏感页接管连续 $streak 次，结束任务")
                return StepOutcome.Fatal("敏感页接管连续 $streak 次", taskMemoryNote = "敏感页接管超限")
            }
            hooks.log(AgentLog.Level.WARN, "检测到敏感页面，拒绝执行动作，需用户介入")
            hooks.pushFloating("敏感页面", "ERROR")
            hooks.showInteraction("guide", "敏感页面保护", "检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后，在窗内告诉 AI 继续。")
            hooks.setNeedsUser(true)
            val sensitiveReason = "检测到敏感页面（${snapshot.packageName}），已进入只读保护。请手动操作后输入提示继续。"
            hooks.emitHintRequest(sensitiveReason)
            // 界面「需要协助」的原因取的是 agentState.message（userHintRequest 目前无订阅者），
            // 不同步写入的话用户只会看到一个没有任何说明的协作面板
            hooks.setMessage(sensitiveReason)
            val hint = hooks.awaitUserHint()
            if (hint.isBlank()) {
                return StepOutcome.Fatal("敏感页用户离开", taskMemoryNote = "敏感页只读保护中用户离开")
            }
            return StepOutcome.Continue
        }

        // 页面记忆：命中已记忆页面 → 屏幕淡色圆环标记 + 决策提示注入素材；新页面自动入库。
        // 放在敏感页判定之后：敏感页绝不写入页面记忆（Recorder 内部还有双保险）。
        // 未开启开关时什么都不做（任务收尾的 PageMarkOverlayService.hide 会兜底撤下标记）
        if (session.settingsVal.pageMemoryEnabled) {
            val hit = runCatching { onPageObserved(snapshot, annotated) }.getOrNull()
            session.pageMemoryHint = runCatching { pageMemoryHintFor(snapshot, hit) }.getOrDefault("")
            runCatching { updatePageMarks(hit) }
        }
        return StepOutcome.Continue
    }
}
