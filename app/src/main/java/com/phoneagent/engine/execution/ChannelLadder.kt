package com.phoneagent.engine.execution

/**
 * 单次通道尝试的结果。
 *
 * 三态而不是两态：**已交付但未能确认**（例如拉起应用成功、但焦点校验超时）必须与"未交付"分开。
 * 混在一起会犯两类错误：把成功当失败继续升级（用户看到应用被拉起两次），
 * 或者把未交付当成功（任务卡在原地还宣称完成）。
 */
internal sealed interface ChannelStepResult {
    /** 已交付；[confirmed] 表示是否拿到页面级证据（false = 已交付但未确认） */
    data class Delivered(val confirmed: Boolean, val detail: String = "") : ChannelStepResult

    /** 未交付（可以继续尝试下一条通道） */
    data class NotDelivered(val detail: String) : ChannelStepResult

    /** 通道不可用/不适用（不算失败，不参与健康度） */
    data class Skipped(val detail: String) : ChannelStepResult
}

internal enum class ChannelOutcome { CONFIRMED, DELIVERED_UNCONFIRMED, NOT_DELIVERED, SKIPPED }

/** 一条通道的一次尝试记录（可进日志、可进结构化结果，不进提示词） */
internal data class ChannelAttempt(
    val kind: ChannelKind,
    val outcome: ChannelOutcome,
    val detail: String,
    val elapsedMs: Long,
)

internal data class LadderResult(
    val delivered: Boolean,
    val confirmed: Boolean,
    val channel: ChannelKind?,
    val attempts: List<ChannelAttempt>,
    val detail: String,
) {
    /** 给日志用的一行中文轨迹（例如："无障碍 已交付；内置 Shizuku 未交付（超时）"） */
    fun trace(): String = attempts.joinToString("；") { attempt ->
        val word = when (attempt.outcome) {
            ChannelOutcome.CONFIRMED -> "已交付"
            ChannelOutcome.DELIVERED_UNCONFIRMED -> "已交付未确认"
            ChannelOutcome.NOT_DELIVERED -> "未交付"
            ChannelOutcome.SKIPPED -> "跳过"
        }
        val extra = attempt.detail.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        "${attempt.kind.label} $word$extra"
    }

    /** 实际发生降级时返回被放弃的通道；未发生返回 null */
    fun degradedFrom(): ChannelKind? =
        attempts.firstOrNull { it.outcome == ChannelOutcome.NOT_DELIVERED }?.kind
            ?.takeIf { it != channel }

    /**
     * 给用户看的中文说明（点名现在用的是哪种方式）。
     * 未发生降级返回 null，调用方保持原有成功文案不动。
     */
    fun userNotice(): String? {
        val from = degradedFrom() ?: attempts.firstOrNull { it.outcome == ChannelOutcome.NOT_DELIVERED }?.kind
            ?: return null
        val now = channel?.label
        val detail = attempts.firstOrNull { it.kind == from }?.detail?.takeIf { it.isNotBlank() }
        return DegradationPolicy.notice(
            DegradationTrigger.CHANNEL_UNAVAILABLE,
            detail ?: from.label,
            now,
        )
    }
}

/**
 * 可复用的**通道阶梯执行器**（同一个目标的自动择优与降级）。
 *
 * 设计要点：
 * 1. 只在"未交付"时升级到下一条通道——已交付（含未确认）立即停，避免重复操作；
 * 2. 每次尝试都写入 [ChannelHealth]，下次同类目标会优先走更健康的通道；
 * 3. 全程记录 [ChannelAttempt]，供结构化结果与日志排查使用。
 *
 * 该抽象从 `ExecutionCore` 里两套手写阶梯（应用启动、动作通道回退）沉淀出来，
 * 新增目标只需在 [ChannelPolicy] 补一行候选，不必再写一遍循环与失败文案。
 */
internal class ChannelLadder(private val health: ChannelHealth) {

    suspend fun run(
        goal: ChannelGoal,
        candidates: List<ChannelCandidate>,
        onStep: suspend (ChannelCandidate) -> ChannelStepResult,
    ): LadderResult {
        if (candidates.isEmpty()) {
            return LadderResult(
                delivered = false,
                confirmed = false,
                channel = null,
                attempts = emptyList(),
                detail = "无可用${goal.label}通道",
            )
        }
        val ordered = health.rank(goal, candidates)
        val attempts = ArrayList<ChannelAttempt>(ordered.size)
        var lastDetail = ""
        for (candidate in ordered) {
            val t0 = System.nanoTime()
            val step = try {
                onStep(candidate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消是控制流，不是通道失败：必须原样抛出，否则任务取消会被记成"通道不可用"
                throw e
            } catch (t: Throwable) {
                ChannelStepResult.NotDelivered(t.message?.take(80) ?: "通道执行异常")
            }
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            when (step) {
                is ChannelStepResult.Delivered -> {
                    health.recordSuccess(goal, candidate.kind)
                    attempts += ChannelAttempt(
                        candidate.kind,
                        if (step.confirmed) ChannelOutcome.CONFIRMED else ChannelOutcome.DELIVERED_UNCONFIRMED,
                        step.detail,
                        elapsed,
                    )
                    return LadderResult(
                        delivered = true,
                        confirmed = step.confirmed,
                        channel = candidate.kind,
                        attempts = attempts,
                        detail = step.detail,
                    )
                }

                is ChannelStepResult.NotDelivered -> {
                    health.recordFailure(goal, candidate.kind)
                    attempts += ChannelAttempt(candidate.kind, ChannelOutcome.NOT_DELIVERED, step.detail, elapsed)
                    lastDetail = step.detail
                }

                is ChannelStepResult.Skipped -> {
                    attempts += ChannelAttempt(candidate.kind, ChannelOutcome.SKIPPED, step.detail, elapsed)
                    lastDetail = step.detail
                }
            }
        }
        return LadderResult(
            delivered = false,
            confirmed = false,
            channel = null,
            attempts = attempts,
            detail = lastDetail.ifBlank { "所有${goal.label}通道均未交付" },
        )
    }
}
