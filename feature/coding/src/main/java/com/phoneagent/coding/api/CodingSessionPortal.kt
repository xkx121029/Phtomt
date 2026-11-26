package com.phoneagent.coding.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 编码页与它那条 agent 连接之间的门户：读一份快照，往下下意图。
 *
 * 页面与工作台只认这一个接口。[com.phoneagent.coding.agent.AgentClient]（JSON-lines 管道、
 * 事件归约、重连预算、审批回执、会话 id 过滤）留在切片内部，宿主不再接触它的类型，
 * 也不再需要知道消息明细是怎么组装出来的。
 */
interface CodingSessionPortal {

    /** 整页快照：连接阶段、消息明细、运行进度 */
    val conversation: StateFlow<CodingConversation>

    /**
     * 当前挂在连接上的会话 id：页面用它判断「要落盘的是不是还在跑的那个会话」，
     * 切会话时连接已经换成新的，按 id 比对才不会写串文件。
     */
    val activeSessionId: String?

    /** 把磁盘上装回的消息装进当前连接（切会话 / 切工作区时调用） */
    fun activateSession(id: String, messages: List<CodingMessageView>)

    /** 落盘时取一份消息快照 */
    fun snapshotMessages(): List<CodingMessageView>

    /** 发一轮普通对话 */
    fun sendPrompt(text: String)

    /** `/goal` 无人值守循环：跑完一轮由判官决定是否继续；失败经 [onResult] 回中文原因 */
    fun startGoal(
        objective: String,
        allowExec: Boolean = false,
        onResult: ((Boolean, String?) -> Unit)? = null,
    )

    /** 停止当前执行（取消子进程里正在跑的这一轮） */
    fun cancel()

    /** 审批卡的决议回执：允许 / 拒绝某一条待审批的工具调用 */
    fun respondApproval(callId: String, approved: Boolean)

    /** 换审批档位：宿主侧热更新，立即生效 */
    fun setApprovalMode(mode: String, onResult: ((Boolean, String?) -> Unit)? = null)
}
