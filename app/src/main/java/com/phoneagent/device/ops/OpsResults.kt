package com.phoneagent.device.ops

/**
 * 系统直连能力的结果类型。
 *
 * 语义保持不变（原先定义在 DeviceOpsChannel 内部）：
 * - [Ok]：成功，[Ok.text] 是给模型/用户看的中文结果；
 * - [Fail.dispatched]：区分「根本没执行」与「已下发但读不到回执」——这个区分直接进步骤历史，
 *   用户看到后该做的事完全不同（前者去修通道，后者去手机上核对）。
 *
 * 可见性为 public：`DeviceOpsChannel.run()` 是公开方法且返回本类型，拆出的实现（FileOps 等）
 * 也需要在包内互相传递它。
 */
sealed interface OpsResult {

    data class Ok(val text: String) : OpsResult

    data class Fail(val reason: String, val dispatched: Boolean = false) : OpsResult
}

/**
 * [OpsResult] 的快捷构造。
 *
 * 拆出领域实现后，每个文件都在反复写 `OpsResult.Ok(...)` / `OpsResult.Fail(...)`；
 * 统一走这里能让「已下发未确认」这种特殊失败一眼可辨（见 [unconfirmed]）。
 */
internal object OpsResults {

    fun ok(text: String): OpsResult = OpsResult.Ok(text)

    fun fail(reason: String): OpsResult = OpsResult.Fail(reason)

    /**
     * 「已下发、读不到回执」的失败：命令确实交给系统了，但不能当成成功，也不能说成没执行。
     * 单独一个入口是为了在一百多处 Fail 站点里一眼能看出哪些属于这种情况。
     */
    fun unconfirmed(reason: String): OpsResult = OpsResult.Fail(reason, dispatched = true)
}
