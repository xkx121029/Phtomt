package com.phoneagent.domain.model

/**
 * Agent 运行状态，用于 UI 展示与日志。
 */
data class AgentState(
    val isRunning: Boolean = false,
    val task: String = "",
    /** 当前阶段：idle / observing / thinking / acting / done / error */
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val stepCount: Int = 0,
    /** AI 最近一次决策输出的意图 */
    val lastAction: AgentIntent? = null,
    val hasAccessibility: Boolean = false,
    val hasScreenshot: Boolean = false,
    /** 本次任务开始的时间戳（0 = 尚未开始）；用于回显"已工作 N 秒" */
    val startedAtMillis: Long = 0,
    /**
     * 正在等待重试的人话说明（空串 = 当前没有重试在等）。
     *
     * 网络抖动/服务端限流时，AI 调用会自动退避重试；旧实现里这段等待对用户完全不可见，
     * 界面只会停在「正在思考」，看起来像卡死。这里把「在等什么、等多久」透出来，
     * 状态条据此显示「服务端限流，8 秒后重试」。
     */
    val retryingNote: String = "",
    /**
     * 本次失败的原因分类（[phase] = ERROR 时有效）。
     *
     * 只用于界面呈现：失败卡据此换标题与出口按钮。用枚举而不是让 UI 去猜 message 文案，
     * 是因为文案随时会改，靠字符串匹配分类型迟早失配。
     */
    val failureKind: FailureKind = FailureKind.UNKNOWN,
) {
    enum class Phase {
        IDLE, OBSERVING, THINKING, ACTING, DONE, ERROR
    }

    /**
     * 失败原因分类：只用于界面呈现，不参与任何执行逻辑。
     *
     * 步数用尽 / AI 链路异常 / 配置缺失 / 用户取消，这四种的处置完全不同
     * （重跑 / 修网络 / 去配置 / 无需处理），失败卡据此换标题与出口按钮。
     */
    enum class FailureKind {
        /** 达到最大步数上限（任务没做完，但链路是通的） */
        MAX_STEPS,

        /** AI 决策链路连续异常（网络 / 鉴权 / 服务端） */
        DECISION,

        /** 模型配置缺失（未填 Key / 未选模型） */
        CONFIG,

        /** 用户主动停止 */
        USER_STOPPED,

        /** 其他未分类失败 */
        UNKNOWN,
    }
}