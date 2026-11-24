package com.phoneagent.coding.api

/**
 * 编码页会话里一条消息的只读视图。
 *
 * 编码页要画的都在这里：工具卡的耗时与输出摘要、审批卡的命令原文/风险档/危险原因、
 * 子 agent 的名称与人设。切片内部的消息模型比它多两个字段（工具调用 id、子 agent id），
 * 那两个是事件归约时的匹配键，页面既不渲染也不落盘，所以不跨出切片——
 * 页面拿到的就是这一份，不再需要认识 `coding.agent` 里的实现类型。
 */
// 消息本来就长这样：字段与编码事件一一对应，拆成子对象只会让事件归约和页面渲染两头都得多跳一层
@Suppress("LongParameterList")
data class CodingMessageView(
    val key: String,
    /** user | assistant | tool | approval | subagent */
    val role: String,
    val text: String = "",
    val toolName: String? = null,
    val toolOk: Boolean? = null,
    val approvalCallId: String? = null,
    /** null = 等待用户决议，true = 已允许，false = 已拒绝 */
    val approvalResolved: Boolean? = null,
    /** 审批卡要展示的操作原文：命令全文，其余工具的参数摘要 */
    val approvalCommand: String? = null,
    /** 审批卡的风险档（只读 / 写入 / 执行命令），已本地化 */
    val approvalRisk: String? = null,
    /** 审批卡的危险原因；null = 没命中危险模式 */
    val approvalDanger: String? = null,
    /** 工具 / 子 agent 执行耗时（毫秒） */
    val durationMs: Long? = null,
    /** 工具执行结果摘要：就是回填给 AI 的那段输出 */
    val outputPreview: String? = null,
    val subagentLabel: String? = null,
    /** 子 agent 人设名（实现者 / 审查者 / 测试员 …），未指定人设的老事件为 null */
    val subagentPersona: String? = null,
)

/**
 * 编码页的整页快照：连接阶段 + 消息明细 + 运行进度。
 *
 * 页面按这一份渲染，不再从桥上去取连接对象；投影在切片内部完成，
 * 所以「消息怎么组装」这件事归切片自己，宿主与工作台只读结果。
 */
data class CodingConversation(
    val connection: CodingConnection = CodingConnection.DISCONNECTED,
    val messages: List<CodingMessageView> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** 当前阶段（等待模型 / 接收输出 / 执行工具…）。空串表示无阶段。 */
    val phase: String = "",
    /** 当前第几轮工具循环（0 = 还没开始）。 */
    val iteration: Int = 0,
    /** 本轮开始时间：页面据此跑本地计时器，不依赖心跳频率。 */
    val runStartedAt: Long? = null,
)
