package com.phoneagent.engine.prompt

/**
 * 提示词组：**按引擎入口点划分**，而不是按提示词的"阶段语义"划分。
 *
 * 这样每个组都能被独立装配、独立单测；若按阶段分组，同一组里会混进不同入口点的正文
 * （例如 planning 与 replan），装配时就得靠标志位去模拟分组，反而更脆。
 */
enum class PromptGroup {
    /** 系统提示主体（[com.phoneagent.engine.AgentPrompts.system] 的内置部分） */
    SYSTEM,

    /** 动作模式（授权范围）段落，三档互斥 */
    ACTION_MODE,

    /** 功能可用性声明（有/无视觉），两版互斥 */
    CAPABILITIES,

    /** 技能区块（内置技能等价说明 + MCP 技能表 + 停用清单） */
    SKILLS,

    /** 任务规划 */
    PLANNING,

    /** 每步决策 */
    DECISION,

    /** 审核者系统提示 */
    REVIEW,

    /** 任务结束后的记忆提炼 */
    DISTILL,
}

/**
 * 装配条件标志位：**全部命中才注入**该区块（[PromptBlock.requires]），
 * 或**全部不命中才注入**（[PromptBlock.excludes]）。
 *
 * 用具名枚举而非表达式：装配结果可枚举、可穷举单测，也便于审计"这句话为什么在/不在"。
 */
enum class PromptFlag {
    /** 本轮真的带了屏幕截图（注意：不是用户勾的开关，见 AgentEngine 的 effectiveHasVision） */
    HAS_VISION,

    ACTION_CONSERVATIVE,
    ACTION_BALANCED,
    ACTION_FREE,

    /** 任务与网页内容相关（或任务文本未知）。任务文本为空时一律置位，宁可不裁 */
    WEB_TASK,

    /** 任务与"打开链接/文件/直达页面"相关（或任务文本未知） */
    OPEN_TASK,

    /** 有可注入的记忆简报 */
    HAS_MEMORY,

    /** 有命中的经验规则 */
    HAS_EVOLVED_RULES,

    /** 有可调用的 MCP 技能 */
    MCP_TOOLS,

    /** 配了 MCP 服务器但一个工具都没绑定成技能 */
    MCP_SERVER_ONLY,

    /** 有被停用的技能 */
    DISABLED_SKILLS,
}

/**
 * 提示词区块：一段可独立启停的提示词正文 + 它的装配条件。
 *
 * [prefix] / [sep] 是区块两端的空白，搬迁时由原文字面量精确算出（见 PromptBodies 的生成说明）：
 * 装配用 `joinToString("")` 直接首尾相接，不在块间插入任何额外换行，
 * 因此"全开时拼回原文"是逐字符相等的（由 PromptSnapshotTest 的金样本锁定）。
 *
 * @param id 唯一标识，如 `sys.web_browse`
 * @param bodyCN 中文正文，可含 `{占位符}`（由 [PromptAssembler] 用 [PromptVars] 渲染）
 * @param safe 安全块：裁剪逻辑永不裁它（铁律 / 授权范围 / 禁止输出 / 能力声明等）
 * @param exclusiveGroup 互斥族名：同族区块"只出一块"（如动作模式三档、功能声明两版）。
 *        空串 = 不属于任何互斥族。裁剪自检据此区分"被错误裁掉"与"正常的二选一"。
 * @param order 组内拼接顺序，必须与原文顺序一致
 * @param origin 溯源，说明这段正文原来写在哪
 */
data class PromptBlock(
    val id: String,
    val group: PromptGroup,
    val bodyCN: String,
    val bodyEN: String,
    val requires: Set<PromptFlag> = emptySet(),
    val excludes: Set<PromptFlag> = emptySet(),
    val safe: Boolean = false,
    val exclusiveGroup: String = "",
    val order: Int,
    val prefix: String = "",
    val sep: String = "",
    val origin: String = "",
)