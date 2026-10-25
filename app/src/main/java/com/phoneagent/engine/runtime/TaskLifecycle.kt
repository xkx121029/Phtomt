package com.phoneagent.engine.runtime

import android.content.Context
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.StepShot
import com.phoneagent.engine.PlanPhase
import com.phoneagent.engine.PendingTaskQueue
import com.phoneagent.engine.RouteDecision
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.domain.model.AgentLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 引擎任务生命周期（重构批次 5）：任务队列、队列 worker、单任务协程的启停与兜底复位接缝。
 * 从 AgentEngine 原样迁入，行为不变；`job` 与 `queueLock` 随本类走——processQueue finally 的
 * `job === self` 归属判定与它们强耦合，不可拆散。
 *
 * 执行体（run/runInner 主循环）仍留 AgentEngine（重构批次 8 随 DecisionLoop 走），
 * 经 [runTask] 钩子接入；异常复位经 [safeReset] 钩子回调引擎。
 *
 * Superagent 融合 Phase 4：任务入执行体前先经 [router] 分流（普通对话/编码委托/原链路），
 * 分流决策由 AgentEngine 组装注入，本类不感知三形态的执行细节；Coding/Chat 决策自带执行体，
 * 在队列 worker 的 job 协程内挂起执行，保持"同一时刻只允许一个任务"的语义。
 */
internal class TaskLifecycle(
    private val appContext: Context,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val store: EngineStore,
    private val ui: UiBridge,
    /** 执行体：引擎的 run(task, plan)（内含 runInner 主循环与 finally 兜底） */
    private val runTask: suspend (task: String, plan: TaskPlan?) -> Unit,
    /** 异常/收尾复位：引擎的 safeResetRuntime(taskId, message) */
    private val safeReset: (taskId: Long?, message: String?) -> Unit,
    /**
     * 任务路由（Superagent 融合 Phase 4）：由 AgentEngine 组装注入。
     * 返回 [RouteDecision.Agent] 时走原 runTask 链路，行为与迁移前完全一致。
     *
     * 第二个参数是「对话页提交」标记：为 true 时路由必须只作答、不进任何执行体
     * （对话模式不开启任务执行链路，见 [start]）。
     */
    private val router: suspend (task: String, chatOnly: Boolean) -> RouteDecision =
        { _, _ -> RouteDecision.Agent },
) {
    /** 队列 worker；由主线程入队与 Default 线程的任务收尾共同读写，故需 @Volatile */
    @Volatile
    private var job: Job? = null
    private val pendingTasks = PendingTaskQueue()
    /**
     * worker 生命周期互斥锁：队列协程的启停与队列内容必须一起原子判定，
     * 否则「worker 判空退出」与「任务入队」并发时，两边都会以为对方会处理，任务就漏跑了。
     */
    private val queueLock = Any()

    val taskQueue: StateFlow<List<String>> get() = pendingTasks.items

    /**
     * 提交任务到队列并开始处理。
     *
     * @param chatOnly 对话页提交：只作答、不执行。此时连「执行前预热」与执行留档复位一律跳过——
     *   对话不该预热端侧视觉模型，也不该冲掉上一轮任务的截图留档；
     *   路由侧见到该标记必须直接给 [RouteDecision.Chat]（对话模式不开启任务执行模式：
     *   不设 isRunning、不开悬浮窗/跑马灯、不亮边缘光效、不碰无障碍操控）。
     */
    fun start(task: String, chatOnly: Boolean = false) {
        if (task.isBlank()) return
        if (!chatOnly) {
            // 预热：若启用端侧视觉，异步预加载 3B 模型，避免首次决策阻塞在模型加载
            scope.launch {
                val ext = runCatching { settings.settings.first().enableExternalVision }.getOrDefault(true)
                if (ext) com.phoneagent.device.vision.OnDeviceVision.loadModel(appContext)
            }
            // 新任务开始时清空上一步的执行留档（截图+说明），由本次任务重新覆盖
            store.stepShot.value = StepShot()
        }
        // 队列入口（不经规划）也要作废上一轮的会话承接块，否则会拿上一轮的输入当上下文
        store.sessionContextCache = null
        pendingTasks.enqueue(task, chatOnly)
        // 入队与 worker 启动必须一起判定：否则「worker 刚好判空退出」与本次入队并发时，
        // 两边都以为对方会处理这个任务，它就永远不会被执行（漏跑）
        ensureQueueWorker()
        ui.log(AgentLog.Level.INFO, if (chatOnly) "对话已加入队列：$task" else "任务已加入队列：$task")
    }

    /** 确保队列有 worker 在消费；入队与收尾补启动都走这里，幂等 */
    fun ensureQueueWorker() {
        synchronized(queueLock) {
            if (job?.isActive != true) job = scope.launch { processQueue() }
        }
    }

    /**
     * 启动一个直接执行单次任务的协程（如用户批准计划后立即执行）。
     * 与队列 worker 共用同一个 job 槽位：同一时刻只允许一个任务在跑。
     * 已有任务在跑时不再静默丢弃：把任务入队交给队列 worker 排队执行
     * （队列只带任务文本，计划不随队带入，排队任务由 AI 现场决策）。
     */
    fun launchSingleTask(task: String, plan: TaskPlan? = null) {
        synchronized(queueLock) {
            if (job?.isActive == true) {
                // 入队 + 补启动 worker：若当前任务正好在收尾，没有这次补启动任务就漏跑了
                pendingTasks.enqueue(task, chatOnly = false)
                ensureQueueWorker()
                ui.log(AgentLog.Level.INFO, "已有任务在执行，已加入任务队列：$task")
                // 任务改为排队执行，规划面板不能停留在「已批准」
                store.planPhase.value = PlanPhase.Idle
                return
            }
            job = scope.launch {
                val self = coroutineContext[Job]
                try {
                    dispatch(task, plan)
                } finally {
                    // 兜底：单任务协程结束时补启动队列 worker，执行期间入队的任务不能没人消费。
                    // 被取消（用户停止）时不续跑：stop() 的语义是残留队列留给下次提交一并处理
                    if (coroutineContext.isActive) {
                        synchronized(queueLock) { if (job === self) job = null }
                        ensureQueueWorker()
                    }
                }
            }
        }
    }

    /**
     * 按路由决策分发执行体（Superagent 融合 Phase 4）：
     * Coding/Chat 决策的执行体在本协程（队列 worker 的 job 协程）内挂起执行——
     * 不占用 runTask 的 ReAct 主循环，但同样维持 worker 单任务语义（后续任务排队等待）；
     * Agent 决策走原 runTask 链路，行为不变。
     */
    private suspend fun dispatch(task: String, plan: TaskPlan?, chatOnly: Boolean = false) {
        when (val decision = router(task, chatOnly)) {
            is RouteDecision.Coding -> decision.run()
            is RouteDecision.Chat -> decision.run()
            RouteDecision.Agent -> runTask(task, plan)
        }
    }

    private suspend fun processQueue() {
        val self = coroutineContext[Job]
        // 本 worker 最后执行的任务 id：作为兜底复位的归属凭据（见 safeReset）
        var lastTaskId: Long? = null
        var abnormal: String? = null
        var cancelled = false
        try {
            while (coroutineContext.isActive) {
                val item = pendingTasks.poll() ?: break
                dispatch(item.text, null, item.chatOnly)
                lastTaskId = store.currentTaskId
                // 调试记录（日志/轨迹/历史/对话）与任务会话归档的持久化已由 run() 的收尾统一完成：
                // 放这里会与收尾再写一遍同样的整包数据（含全部截图缩略图与 API 原文），白写一倍磁盘
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw e
        } catch (e: Throwable) {
            val reason = "任务队列异常退出：${e.message ?: e.javaClass.simpleName}"
            abnormal = reason
            ui.log(AgentLog.Level.ERROR, reason)
        } finally {
            // 释放 worker 引用与「是否还有待执行任务」必须在同一把锁内判定：
            // 否则入队方会在这个窗口里既看不到活着的 worker、也看不到非空队列，任务就此漏跑。
            // 只清自己的引用（job === self），避免把入队方刚启动的新 worker 误清掉 → 双 worker 重复执行同一任务
            val pending = synchronized(queueLock) {
                if (job === self) job = null
                pendingTasks.isNotEmpty()
            }
            // 用户主动停止（协程被取消）时既不复位也不续跑：stop() 已完成复位，
            // 残留队列留待用户下次提交任务时一并带走，保持原有语义
            if (!cancelled) {
                if (pending) ensureQueueWorker() else safeReset(lastTaskId, abnormal)
            }
        }
    }

    /** 用户主动停止：取消当前 worker（stop() 的其余复位仍在引擎侧完成） */
    fun cancelWorker() {
        synchronized(queueLock) {
            job?.cancel()
            job = null
        }
    }
}
