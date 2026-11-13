package com.phoneagent.domain.model

/**
 * 任务终态。**由引擎显式给出**，展示层不得再从摘要文案里猜成败。
 *
 * 原来的判据是 `message.contains("失败" / "放弃" / "未完成")`，这会把
 * 「已完成登录，但支付失败」这类**成功任务**判成失败，也会把没有任何关键词的
 * 失败判成成功——两种都是对用户说谎。文案随时会改，成败只能由执行链路给出。
 */
enum class TaskStatus(val label: String) {
    /** 引擎确认目标已达成 */
    SUCCESS("已完成"),

    /** 执行链路失败（决策异常 / 无障碍中断 / 通道全不可用等） */
    FAILED("失败"),

    /** 用户或 AI 主动放弃、用户停止 */
    ABORTED("已中断"),

    /** 链路正常但没做完（达到步数上限等） */
    NOT_ACHIEVED("未达成"),
}

/**
 * 任务收尾时交给展示层的完整结果。
 *
 * 所有可空/零值字段的语义都是「引擎没有这个信息」：展示层遇到空值应省略该字段，
 * 不要补一个看起来合理的默认值——那等于替用户想象一段没有发生过的过程。
 *
 * @param elapsedMs 任务耗时（毫秒）；0 表示未记录
 * @param steps 实际执行的步数；0 表示未记录
 * @param channel 最后一次实际交付动作的执行通道中文名（如「无障碍」）；空串表示未记录
 * @param failureReason 非成功态的失败原因；成功态为空
 */
data class TaskOutcome(
    val status: TaskStatus,
    val summary: String,
    val elapsedMs: Long = 0L,
    val steps: Int = 0,
    val channel: String = "",
    val failureReason: String = "",
)
