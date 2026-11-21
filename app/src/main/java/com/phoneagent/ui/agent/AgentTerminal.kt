package com.phoneagent.ui.agent

import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.TaskStatus

/**
 * 终态卡的文案与出口（纯函数，无 Compose 依赖，可离线单测）。
 *
 * 三态口径与执行层同一套：**已确认生效 / 已交付未确认 / 未生效**。
 * 「已交付未确认」不是「失败」——用户把「AI 点了但读不到回执」读成「AI 没做到」，
 * 就会去做一遍已经做过的事（重复下单、重复发消息）。所以卡片只说清三态各几步，
 * 把「未确认」单独摆出来，而不是把它们折进红色的失败数里。
 *
 * 原因优先说「哪里需要你动手」：引擎已经按结构化分支给出原因（连续超时、决策链路异常、
 * 配置缺失），这里不再把它压平成一句「AI 出了点问题」。
 */
internal object AgentTerminal {

    /** 卡片标题：四种结束方式处置完全不同，标题必须分开 */
    fun headline(
        status: TaskStatus,
        kind: AgentState.FailureKind,
        a11yEnabled: Boolean,
    ): String = when (status) {
        TaskStatus.SUCCESS -> "任务完成"
        TaskStatus.ABORTED -> "已停止"
        TaskStatus.NOT_ACHIEVED -> "步数用完了"
        TaskStatus.FAILED -> when {
            // 无障碍没开时，失败的真实前置原因就是「看不见屏幕」，
            // 比「AI 链路中断」有用得多：后者会让用户去查网络，而问题就在权限里
            !a11yEnabled -> "无障碍没开，AI 看不见屏幕"
            kind == AgentState.FailureKind.CONFIG -> "还没配置模型"
            kind == AgentState.FailureKind.DECISION -> "AI 链路中断"
            else -> "任务未完成"
        }
    }

    /**
     * 可行动的原因。引擎原始消息非空时优先用它——它来自结构化分支
     * （「网络似乎不通：AI 连续 3 次没能连上云端」比任何兜底文案都具体），
     * 只有在没有任何结构化信号时才由这里补一句能指导下一步的话。
     */
    fun cause(
        status: TaskStatus,
        kind: AgentState.FailureKind,
        a11yEnabled: Boolean,
        engineReason: String,
    ): String {
        val raw = engineReason.trim()
        return when {
            status == TaskStatus.SUCCESS -> raw
            status == TaskStatus.FAILED && !a11yEnabled ->
                "无障碍服务没开：AI 读不到屏幕上的控件，只能靠截图猜位置，这一轮注定失败。" +
                    "开启后再试一次，它才能看见并点中控件。"
            status == TaskStatus.ABORTED -> raw.ifBlank { "你按了停止，任务在这里中断。" }
            status == TaskStatus.NOT_ACHIEVED ->
                raw.ifBlank { "AI 执行了允许的最大步数，仍未拿到「目标已达成」的证据。" }
            kind == AgentState.FailureKind.CONFIG ->
                raw.ifBlank { "模型还没配好：填入可用的 API Key 并选一个模型，再回来重试。" }
            else -> raw.ifBlank { "执行过程中出错，可以重试；若反复失败，去检查模型配置与网络。" }
        }
    }

    /** 卡片底部的出口按钮：只有真的能解决问题的才摆出来 */
    fun actions(
        status: TaskStatus,
        kind: AgentState.FailureKind,
        a11yEnabled: Boolean,
    ): List<TerminalAction> = when {
        status == TaskStatus.SUCCESS -> emptyList()
        // 配置问题重试还是同一个结果——先去配置，回来再重试
        kind == AgentState.FailureKind.CONFIG && status == TaskStatus.FAILED ->
            listOf(TerminalAction.OPEN_SETTINGS)
        // 无障碍没开导致的失败：主出口是去授权，重试只是给「已经授权过的人」留的后路
        status == TaskStatus.FAILED && !a11yEnabled ->
            listOf(TerminalAction.OPEN_PERMISSIONS, TerminalAction.RETRY)
        else -> listOf(TerminalAction.RETRY)
    }

    /**
     * 三态步数行（零值省略、全零返回空串）。
     * 顺序固定为「生效 → 未确认 → 未生效」，读起来就是一次任务的实际进展。
     */
    fun stepLine(okSteps: Int, unconfirmedSteps: Int, failedSteps: Int): String {
        val parts = ArrayList<String>(3)
        if (okSteps > 0) parts += "已确认生效 $okSteps 步"
        if (unconfirmedSteps > 0) parts += "已交付未确认 $unconfirmedSteps 步"
        if (failedSteps > 0) parts += "未生效 $failedSteps 步"
        return parts.joinToString(" · ")
    }

    /**
     * 「未确认」的核对提示：这三态里唯一需要用户去手机上确认的一档。
     * 没有未确认的步就不出这一行，避免没事找事。
     */
    fun unconfirmedNote(unconfirmedSteps: Int): String =
        if (unconfirmedSteps > 0) "「已交付未确认」表示动作已经发出，但没有读到执行回执——请到手机上核对一下。" else ""
}
