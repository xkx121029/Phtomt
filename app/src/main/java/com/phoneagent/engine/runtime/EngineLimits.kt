package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.rules.EngineRules

/**
 * 引擎常量仓（重构批次 1）：从 AgentEngine.companion 整体迁入，值与注释原样保留。
 * 调用方按成员导入（`import com.phoneagent.engine.runtime.EngineLimits.MAX_LOGS`），
 * 裸名引用不受影响。
 */
internal object EngineLimits {
    /** 参数缺失追问的最大补全轮数：tap 缺 target / open_app 缺 app 时最多向 AI 追问几轮 */
    internal const val MAX_PARAM_REFILL = 3

    /** 无障碍元素树精简阈值：小于等于该值时视为"无障碍读不到控件"，自动转视觉模型截图补足 */
    internal const val VISION_FALLBACK_THRESHOLD = 3

    /** 端侧视觉单次识别的超时（ms）：3B 在 CPU 上较慢，超时回退云端/本地，避免阻塞决策循环 */
    internal const val EXTERNAL_VISION_TIMEOUT = 20_000L

    /** 决策链路最多保留的近期对话轮次（每轮 user+assistant 各算一条）。超过则截断早期历史，
     *  让长线任务上下文长度保持恒定，避免历史无限累积拖慢/带偏 AI */
    internal const val MAX_DIALOG_TURNS = 6

    /** 单步云端决策看门狗（文档 v2.2 5.5）：超过则视为云端卡住，本步改为等待、下一轮重试，避免长线任务卡死 */
    internal const val WATCHDOG_DECIDE_MS = 45_000L

    /** 单步视觉分析看门狗：云端视觉（OkHttp 读超时 120s）与端侧 3B（单次 20s）都可能长时间阻塞，
     *  没有它界面会一直停在“观察屏幕”（视觉分析发生在决策状态切换之前），看起来像卡死 */
    internal const val WATCHDOG_VISION_MS = 25_000L

    /** 日志环形缓冲上限（v2.2.1 LogCollector），防长线任务内存膨胀 */
    internal const val MAX_LOGS = 800

    /** 敏感页接管连续次数上限：超了仍敏感就收尾，避免无限弹接管死循环 */
    internal const val MAX_SENSITIVE_TAKEOVERS = 5

    /** 端侧决策来源标注（写入 StepTrace.visionSource，便于区分端侧/云端决策） */
    internal const val LOCAL_DECISION_SOURCE = "端侧决策"

    /**
     * 执行前需要"重新定位目标"的动作类型。
     *
     * 这些动作都依赖元素坐标（输入框中心、滚动容器中心、滑动起点），而坐标是按**观察那一刻**的
     * 元素树算好的，中间隔着一次 AI 往返（可能数秒）：键盘顶起页面、列表滚动、弹窗落下之后，
     * 坐标就指到别处了——典型表现是"文字填进了别的输入框""滚动的不是那个列表"。
     * 点击类不在此列：[com.phoneagent.engine.execution.ClickRunner] 内部已经做了同一件事，且做得更细（活节点直点 → 手势 → 滚动查找）。
     * 方向滑动（SWIPE_UP/DOWN/LEFT/RIGHT）也不在此列：它们按设计以屏幕中心为起点，不带元素目标。
     */
    internal val RELOCATE_BEFORE_TYPES = setOf(
        ActionType.TYPE_TEXT, ActionType.SCROLL, ActionType.SCROLL_TO, ActionType.SWIPE,
    )

    /** 调试轨迹（每步决策）最多保留条数：配图缩略化，双保险防内存溢出闪退 */
    internal const val MAX_TRACES = 300
    /** 调试执行历史最多保留条数 */
    internal const val MAX_EXECUTION_HISTORY = 400
    /** 对话历史（决策 prompt）最多保留条数：长线任务每步决策都 append，需上限防内存膨胀 */
    internal const val MAX_CONVERSATION = 200

    /** 阶段切分粒度：每 [STAGE_SIZE] 步为一个阶段（文档 v2.2 5.1） */
    internal const val STAGE_SIZE = 6

    /** 歧义检测 + 规划 */
    internal const val PLANNING_TEMPERATURE = 0.3
    /** 每步决策（正常）温度：引用 EngineRules，单一事实来源 */
    internal const val DECISION_TEMPERATURE = EngineRules.DECISION_TEMPERATURE
    /** 失败 3 次后重规划温度：引用 EngineRules，单一事实来源 */
    internal const val REPLAN_TEMPERATURE = EngineRules.REPLAN_TEMPERATURE

    /** 用户点「已手动处理」时发出的语义信号 */
    // 文案铁律：不得出现"完成/已完成"等收尾暗示——这类词会诱导 AI 判定整项任务已达成、
    // 直接下发 task_done，用户点一下"已手动处理"任务就退（2026-08-18 BUG-001 复盘）。
    // 必须突出"仅处理了当前环节、继续剩余步骤"，并把 task_done 的门槛抬高到"亲眼看到目标达成"
    internal const val SELF_DISMISS_HINT = "[[自处理]]用户已手动处理当前环节，请重新观察页面继续执行剩余步骤；只有亲眼看到任务目标已达成才允许输出 task_done"

    /** 任务内记忆事件流的保留上限（只用于界面提示，超出丢弃最早的） */
    internal const val MAX_MEMORY_EVENTS = 20

    /** say 事件流的保留上限（只用于界面实时气泡，超出丢弃最早的） */
    internal const val MAX_SAY_EVENTS = 20

    /** 澄清往来（问题 + 选择）流的保留上限，只用于界面展示，超出丢弃最早的 */
    internal const val MAX_CLARIFY_EVENTS = 20

    /** 本对话内纯对话往来（问答）的保留上限，只喂给会话承接块 */
    internal const val MAX_CONVERSATION_CHATS = 6

    /** 连续 say 次数上限：超过即要求 AI 开始真正执行，避免"只聊天不干活"死循环 */
    internal const val MAX_SAY_STREAK = 3

    /** 记忆提炼的超时：独立于主流程，超时直接放弃，不阻塞任务收尾 */
    internal const val DISTILL_TIMEOUT_MS = 12_000L

    /** MCP 技能单次调用的超时：远端服务不可达时不能让主循环干等 */
    internal const val MCP_CALL_TIMEOUT_MS = 20_000L

    /** MCP 技能重试那一遍的超时：缩到 10s，最坏总等待 ≈ 20+0.5+10 ≤ 31s，不放大用户等待 */
    internal const val MCP_CALL_RETRY_TIMEOUT_MS = 10_000L

    /** MCP 技能返回值注入 AI 上下文的最大字符数（与 shell 输出一致，防长文撑爆上下文） */
    internal const val MAX_MCP_OUTPUT = 1200

    /** 连续「技能调用被拒」（未知/停用/缺参）次数上限：达到即收尾，避免 AI 反复白试 */
    internal const val MAX_SKILL_ERROR_STREAK = 3

    /**
     * 连续「被动作模式拒绝」次数上限：达到即收尾。
     * 保守模式下 AI 若反复尝试点击/输入这类被禁动作，只会把步数耗在无意义的往返上；
     * 明确停下并告诉用户去换档，比让它空转到 maxSteps 更诚实。
     */
    internal const val MAX_MODE_DENY_STREAK = 3

    /** 连续「浏览器意图缺参」次数上限：达到即收尾，避免 AI 一直输出缺参的 browse_* */
    internal const val MAX_BROWSE_MISSING_STREAK = 3

    /** 侧边栏保留的历史任务会话条数上限（只留任务级元数据，逐步明细另有环形上限） */
    internal const val MAX_TASK_SESSIONS = 30

    /** 会话承接：最多回看几轮更早的任务（越靠前越近） */
    internal const val MAX_PREVIOUS_TASKS = 3

    /** device_query 返回内容注入 AI 上下文的最大字符数（按需查询，可以给足；应用清单本身另有条数上限） */
    internal const val MAX_DEVICE_QUERY_OUTPUT = 9000

    /**
     * 连续「决策链路异常」次数上限。
     * 主循环把决策抛出的异常降级为 `wait` 意图后，若链路持续不可用就会一直空转到最大步数；
     * 这个计数器是异常隔离的护栏：连续异常到上限即收尾，避免任务在坏链路上无限打转。
     */
    internal const val MAX_DECISION_FAILURE_STREAK = 5

    /** 不可逆动作（needsUserConfirmation）确认等待的超时：超时视为用户取消，不让任务无限挂起 */
    internal const val ACTION_CONFIRM_TIMEOUT_MS = 120_000L

    /** 长时间无进展判定阈值 */
    internal const val NO_PROGRESS_MS = 5L * 60L * 1000L
}
