package com.phoneagent.engine.runtime

import com.phoneagent.engine.execution.ClockFallbackKind
import com.phoneagent.engine.execution.ClockOutcome
import com.phoneagent.engine.execution.DegradationPolicy
import com.phoneagent.engine.execution.DegradationTrigger
import com.phoneagent.engine.execution.LadderResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 给用户看的「本次任务现在用的什么方式」状态出口。
 *
 * 背景：降级策略（[DegradationPolicy]）和通道阶梯（[com.phoneagent.engine.execution.ChannelLadder]）
 * 早就能生成中文说明了，但一直只落在日志里——用户看到的仍是「成功/失败」，不知道系统已经换过通道、
 * 也不知道该不该自己动手。这里把说明收敛成一条**可被任何界面线读取的稳定状态**，
 * 引擎只负责产出数据，展示（悬浮窗/任务卡/通知）由浮层线决定。
 *
 * 设计约束：
 * - 不引用 Activity/Context，纯数据 + StateFlow，可在 JVM 单测里直接验证；
 * - 只保留**最新一条**（同一时刻用户只需要知道当前方式），不堆历史队列；
 * - 同一条（id + 文案相同）重复发布不会再次发送，避免每个动作都刷屏；
 * - 引擎不负责清除时机：展示层用它自己的节奏消失（[TaskNotice.atMillis] 提供时间基准），
 *   任务收尾时也可主动 [clear]。
 */
enum class TaskNoticeKind(val id: String) {
    /** 降级发生：系统换了一种方式继续，或已经没有任何自动方式 */
    DEGRADATION("degradation"),
}

/**
 * 一条用户可见的状态说明。
 *
 * @param id 稳定键（触发原因 + 来源 + 当前方式）。相同的 id + [text] 视为同一条，不重复发送。
 * @param trigger 触发原因键，例如 `channel_unavailable`、`clock_fallback`
 * @param source 来源，例如 `clock`、`launch`、`action`、`decision`
 * @param text 中文成品，直接展示；已点名当前方式，界面不要再拼文案
 * @param method 当前实际使用的方式（中文）；null 表示没有自动方式可用
 * @param requiresAction 是否必须用户动手（手动设置/无替代通道时 true）
 */
data class TaskNotice(
    val id: String,
    val kind: TaskNoticeKind = TaskNoticeKind.DEGRADATION,
    val trigger: String,
    val source: String,
    val text: String,
    val method: String? = null,
    val requiresAction: Boolean = false,
    val atMillis: Long = System.currentTimeMillis(),
)

/**
 * 任务状态出口（单例）。浮层线读取 [notice]，展示由它决定。
 *
 * 读取示例（浮层侧）：
 * ```
 * TaskNoticeCenter.notice.collect { notice -> notice?.let { show(it.text) } }
 * ```
 */
object TaskNoticeCenter {

    private val _notice = MutableStateFlow<TaskNotice?>(null)

    /** 最新一条降级说明；null 表示当前没有需要展示的降级信息 */
    val notice: StateFlow<TaskNotice?> = _notice.asStateFlow()

    /**
     * 发布一条说明。相同 id 且文案完全相同的重复发布会保留原对象（StateFlow 不会再发送），
     * 文案变化时才替换。
     *
     * @return 实际生效的那条（重复时返回上一条）
     */
    fun publish(notice: TaskNotice): TaskNotice {
        var effective = notice
        _notice.update { prev ->
            if (prev != null && prev.id == notice.id && prev.text == notice.text) {
                effective = prev
                prev
            } else {
                notice
            }
        }
        return effective
    }

    /** 清除说明；给出 [expectedId] 时只清除匹配的那条，避免旧任务把新任务的提示清掉 */
    fun clear(expectedId: String? = null) {
        _notice.update { prev ->
            if (prev == null) null
            else if (expectedId != null && prev.id != expectedId) prev
            else null
        }
    }

    /**
     * 发布一条降级策略说明（权限缺失/通道不可用/模型超时/页面卡死）。
     * 文案统一由 [DegradationPolicy] 生成，调用方不要自己拼。
     */
    internal fun publishDegradation(
        trigger: DegradationTrigger,
        detail: String? = null,
        method: String? = null,
        source: String = "action",
    ): TaskNotice = publish(
        TaskNotice(
            id = "${TaskNoticeKind.DEGRADATION.id}:$source:${trigger.key}:${method ?: "none"}",
            trigger = trigger.key,
            source = source,
            text = DegradationPolicy.notice(trigger, detail, method),
            method = method,
            requiresAction = method == null,
        ),
    )

    /**
     * 通道阶梯发生降级（或全部不可用）时发布；没有降级则什么也不做。
     *
     * @param source 本次阶梯属于哪类动作（`launch` / `action`），用于给展示层归类
     */
    internal fun publishLadder(result: LadderResult, source: String): TaskNotice? {
        val text = result.userNotice() ?: return null
        return publish(
            TaskNotice(
                id = "${TaskNoticeKind.DEGRADATION.id}:$source:${result.channel?.id ?: "none"}",
                trigger = DegradationTrigger.CHANNEL_UNAVAILABLE.key,
                source = source,
                text = text,
                method = result.channel?.label,
                requiresAction = !result.delivered,
            ),
        )
    }

    /**
     * 时钟降级链的结果发布。
     *
     * 静默直达成功不需要提示；其余三级（写库/无障碍确认/手动）都要让用户知道现在用的是哪种方式，
     * 手动与彻底失败还需要用户动手，[TaskNotice.requiresAction] 为 true。
     */
    internal fun publishClock(outcome: ClockOutcome): TaskNotice? = when (outcome) {
        is ClockOutcome.Done -> if (outcome.kind == ClockFallbackKind.DIRECT_SILENT) {
            null
        } else {
            publish(
                TaskNotice(
                    id = "${TaskNoticeKind.DEGRADATION.id}:clock:${outcome.kind.id}",
                    trigger = "clock_fallback",
                    source = "clock",
                    text = outcome.message,
                    method = outcome.kind.label,
                    requiresAction = false,
                ),
            )
        }

        is ClockOutcome.Manual -> publish(
            TaskNotice(
                id = "${TaskNoticeKind.DEGRADATION.id}:clock:${ClockFallbackKind.OPEN_MANUAL.id}",
                trigger = "clock_fallback",
                source = "clock",
                text = outcome.message,
                method = ClockFallbackKind.OPEN_MANUAL.label,
                requiresAction = true,
            ),
        )

        is ClockOutcome.Failed -> publish(
            TaskNotice(
                id = "${TaskNoticeKind.DEGRADATION.id}:clock:failed",
                trigger = "clock_fallback",
                source = "clock",
                text = outcome.reason,
                method = null,
                requiresAction = true,
            ),
        )
    }
}
