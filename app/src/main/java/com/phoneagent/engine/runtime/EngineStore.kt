package com.phoneagent.engine.runtime

import com.phoneagent.data.store.AiMemoryEntry
import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.EvolvedRule
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.domain.model.StepShot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.engine.AgentEngine
import com.phoneagent.engine.ClarifyAnswered
import com.phoneagent.engine.MemoryEvent
import com.phoneagent.engine.PlanPhase
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.SayEvent
import com.phoneagent.engine.TaskSession
import com.phoneagent.engine.execution.ActionMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 引擎状态仓（重构批次 1）：AgentEngine 的全部运行期状态（约 20 个 StateFlow + 任务级 var）
 * 原样迁入，字段名与初始化值保持不变。
 *
 * 设计取舍：**分仓 Flow 聚合**而不是单个 EngineState data class——异质 Flow 塞进一个
 * data class 会引发 copy 风暴并引入更新竞态。AgentEngine 通过委托属性
 * （`private var x by store::x`；@Volatile 字段用显式 get/set）以裸名访问，
 * 可见性语义与迁移前完全一致。
 *
 * 注意：`job` 与 `queueLock` 不在本仓——它们随 TaskLifecycle 走（processQueue 的
 * `job === self` 归属判定与之强耦合）；`translateCache` 随 AiTools 走。
 */
internal class EngineStore {

    // ---- 以下成员自 AgentEngine 原样迁入（含全部注释）----

    private val _state = MutableStateFlow(AgentState())
    val state: MutableStateFlow<AgentState> get() = _state

    /**
     * 本次任务内 AI 写入记忆的事件流（内存态，供 Agent 页实时插卡）。
     * 不读 DataStore：卡片要在写入瞬间出现，且撤销后立即消失。
     */
    private val _memoryEvents = MutableStateFlow<List<MemoryEvent>>(emptyList())
    val memoryEvents: MutableStateFlow<List<MemoryEvent>> get() = _memoryEvents

    /**
     * 本次任务内 AI "对用户说话"的事件流（内存态，供 Agent 页实时出气泡）。
     * 只在实时任务流里出现：归档（[TaskSession]）不存，历史回看不回放这句话。
     */
    private val _sayEvents = MutableStateFlow<List<SayEvent>>(emptyList())
    val sayEvents: MutableStateFlow<List<SayEvent>> get() = _sayEvents

    /** say 事件自增 id：不能用 text.hashCode() 当 key（重复内容会撞车） */
    val saySeq = AtomicLong(0L)

    /** 澄清往来自增 id：问题气泡与答案气泡共用同一个 id 锚在同一组上 */
    val clarifySeq = AtomicLong(0L)

    /** 本对话内的澄清往来（引擎内存态），Agent 页据此在任务流里保留 问题 + 选择 */
    private val _clarifyEvents = MutableStateFlow<List<ClarifyAnswered>>(emptyList())
    val clarifyEvents: MutableStateFlow<List<ClarifyAnswered>> get() = _clarifyEvents

    /** 连续 say 计数：达到上限即要求 AI 开始真正执行 */
    @Volatile
    var sayStreak = 0

    /**
     * 任务会话归档（Agent 页侧边栏数据源），最新一次任务排在最前。
     * 任务开始时插入一条「进行中」记录，结束时按 taskId 就地收尾为终态。
     */
    private val _taskSessions = MutableStateFlow<List<TaskSession>>(emptyList())
    val taskSessions: MutableStateFlow<List<TaskSession>> get() = _taskSessions

    /** 同一任务只提炼一次记忆，避免重试/收尾分支重复写入 */
    @Volatile
    var lastDistilledTaskId = -1L

    /**
     * 本任务已加载的记忆素材缓存：画像 + AI 记忆 + 经验规则，每任务读一次库，逐步骤复用；
     * null 表示尚未加载。**缓存的是原始素材而不是成品文本**——因为每步要用的经验规则
     * 取决于该步的前台应用，而"记忆里要剔除哪些条目"又取决于命中了哪些规则，两者都得每步现算。
     */
    @Volatile
    var memoryRawCache: MemoryRaw? = null

    /** 记忆素材三元组（画像 / 记忆 / 经验规则） */
    internal data class MemoryRaw(
        val profile: List<ProfileEntry>,
        val memories: List<AiMemoryEntry>,
        val rules: List<EvolvedRule>,
    )

    /**
     * 任务执行期间前台包名 → 出现次数。任务结束时据此推断经验规则的作用域：
     * 待得最久的那个应用，就是这次经验真正的作用对象。
     */
    val foregroundPkgCounts = mutableMapOf<String, Int>()

    /** 本任务已经计入过命中次数的规则 id：同一任务内命中多次只算一次，防长线任务把计数刷爆 */
    val touchedRuleIds = mutableSetOf<Long>()

    /**
     * 本任务的会话承接块（本对话内更早的往来 + 是否为追问），每任务构建一次后逐步骤复用；
     * 空串表示本对话里没有可承接的往来。规划与每步决策共用同一份，避免两处口径不一致。
     */
    @Volatile
    var sessionContextCache: String? = null

    /**
     * 当前对话的起点（毫秒时间戳）。
     *
     * 任务 id 就是任务开始时刻，因此「本对话」= taskId >= 起点的那些任务：
     * 承接块只从这里取材，实时视图也只铺开这之后的执行痕迹。
     * 「新建对话」把起点推到现在，上一段对话的聊天记录既不进上下文、也不再显示。
     */
    private val _conversationStart = MutableStateFlow(System.currentTimeMillis())
    val conversationStart: MutableStateFlow<Long> get() = _conversationStart

    /**
     * 本对话内的纯对话问答（无任务执行的那些轮次）。
     * 只用于会话承接，不落库：新建对话时清空，应用重启后同样不复存在。
     */
    val conversationChats = mutableListOf<ConversationChat>()
    val chatLock = Any()

    /** 本对话内的一次问答往来 */
    internal data class ConversationChat(val at: Long, val question: String, val answer: String)

    /** 异常经验查询的页面指纹缓存：同一页面不重复查库 */
    @Volatile
    var anomalyHintFingerprint: String = ""

    /** 当前页面命中的异常经验条目（未命中为 null），供该步结束时回写使用效果 */
    @Volatile
    var currentAnomalyEntry: AnomalyMemoryEntry? = null

    private val _logs = MutableStateFlow<List<AgentLog>>(emptyList())
    val logs: MutableStateFlow<List<AgentLog>> get() = _logs

    private val _conversation = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversation: MutableStateFlow<List<ConversationMessage>> get() = _conversation

    private val _metrics = MutableStateFlow(AgentMetrics())
    val metrics: MutableStateFlow<AgentMetrics> get() = _metrics

    private val _executionHistory = MutableStateFlow<List<StepRecord>>(emptyList())
    /** 当前任务最新一步的执行留档（截图+说明），新任务开始时清空 */
    private val _stepShot = MutableStateFlow(StepShot())
    val executionHistory: MutableStateFlow<List<StepRecord>> get() = _executionHistory
    val stepShot: MutableStateFlow<StepShot> get() = _stepShot
    /** 每步 AI 决策的详细追踪（Debug「按任务分类」页面展示） */
    private val _traces = MutableStateFlow<List<com.phoneagent.domain.model.StepTrace>>(emptyList())
    val traces: MutableStateFlow<List<com.phoneagent.domain.model.StepTrace>> get() = _traces

    // ---- 规划/澄清/批准流程 ----
    private val _planPhase = MutableStateFlow<PlanPhase>(PlanPhase.Idle)
    val planPhase: MutableStateFlow<PlanPhase> get() = _planPhase
    /** 规划阶段的流式输出文本（边思考边显示） */
    private val _planStream = MutableStateFlow("")
    val planStream: MutableStateFlow<String> get() = _planStream
    var pendingTask = ""
    var activePlan: TaskPlan? = null
    /** 若当前计划来自模板复用，记录其 id（用于健康状态回写） */
    var reusedTemplateId: String? = null
    /** 任务完成后待用户确认入库的用户模板：task/plan（仅用户主动确认才写入模板库） */
    var pendingTemplateTask: String? = null
    var pendingTemplatePlan: TaskPlan? = null
    /** 近最连续“命令不存在/参数无效”等结构性错误的次数；≥2 时强制 AI 改用无障碍动作并禁止继续 shell （防止一直编造不存在的命令） */
    var invalidCommandStreak = 0
    /** 连续「技能调用被拒」次数（未知技能 / 已停用 / 缺必填参数）；达到上限即收尾，避免 AI 反复白试 */
    var skillErrorStreak = 0
    /** 连续「浏览器意图缺参」次数（browse_open 没给 uri、browse_click 没给 target 等）；达到上限即收尾，避免反复追问 */
    var browseMissingStreak = 0

    /** 当前任务 ID（每次 run 开始时生成，用于分任务日志/导出） */
    @Volatile
    var currentTaskId: Long = -1
    /** 执行审核运行期覆盖：null=跟随设置；否则本次任务强制开启/关闭审核 */
    @Volatile
    var reviewOverride: Boolean? = null
    /** 当前任务描述 */
    var currentTaskName: String? = null
    /**
     * 最近一次任务的**完整**原文（[currentTaskName] 只留 60 字，用于标题展示）。
     * 「重试」按钮要把原任务原样再跑一遍，截断过的名字不够用。
     */
    @Volatile
    var lastFullTask: String = ""
    /** 连续拒绝 AI 提前"任务完成"的次数，防死循环 */
    var earlyDoneRejections = 0

    // ---- 长线任务工作记忆（文档 v2.2 5.2）----
    /**
     * 本次任务的任务记忆：目标 / 用户要求 / 已验证做法。
     * 旧的「最近 3 条进度摘要」是纯内存队列，轮转即丢，且长线任务的历史压缩会把更早的决策截掉，
     * 导致 AI 跑到后半程看不到最初目标；任务记忆每轮完整注入并落库，中断后记忆页仍可查。
     */
    @Volatile
    var currentTaskMemory: TaskMemoryEntry? = null
    /** 已完成（已验证生效）的步骤总数 */
    var completedSteps = 0
    /** 本次计划的预计总步数（无计划则为 0，表示未知） */
    var totalPlannedSteps = 0

    // ---- 主动反馈状态（v2.2.1 八）----
    /** 最近一次"步骤生效"的时间戳，用于检测长时间无进展 */
    var lastProgressAt = 0L
    /** 5 分钟无进展提醒是否已发（每个任务一次） */
    var noProgressNotified = false
    /** 连续由端侧决策的次数，用于"疑似死循环"检测 */
    var localDecisionStreak = 0
    /** 死循环提醒是否已发（每个任务一次） */
    var localLoopNotified = false

    // ---- 第 9 层：用户协作 ----
    private val _userHintRequest = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userHintRequest: MutableSharedFlow<String> get() = _userHintRequest
    private val _needsUser = MutableStateFlow(false)
    val needsUser: MutableStateFlow<Boolean> get() = _needsUser
    /**
     * 用户输入信箱：单槽缓冲（CONFLATED）。
     * 原先用 `MutableSharedFlow`（无 replay），没有订阅者时 `tryEmit` 的值会被静默丢弃 ——
     * 用户抢在 `awaitUserHint()` 订阅之前输入就丢了，主循环随后永久挂起在等待上。
     * Channel 在无接收者时会把值缓存住，接收者一到就能取走。
     */
    val userHintMailbox = Channel<String>(Channel.CONFLATED)

    // ---- 自由模式：AI 自写命令的首次确认 ----
    /**
     * 本任务内 AI 自写命令的确认状态：null = 还没问过；true = 已批准；false = 已拒绝。
     * 用户既然选了自由模式，就由用户把最后一道关——但本任务内只问一次（问完记在这里，后续同类命令直接放行/拒掉）。
     */
    @Volatile
    var shellApprovedForTask: Boolean? = null

    /** 待确认的自写命令；非空表示悬浮窗 / App 内正等着用户点「批准执行 / 拒绝」 */
    private val _pendingShellCommand = MutableStateFlow<String?>(null)
    val pendingShellCommand: MutableStateFlow<String?> get() = _pendingShellCommand

    /**
     * 用户对自写命令的答复信箱：单槽 CONFLATED。
     * 用 Channel 而非 SharedFlow 的理由与 [userHintMailbox] 完全一致——用户抢在等待方订阅之前点按，
     * 值也必须被缓存住，否则主循环会永久挂死在等待上。
     */
    val shellApprovalMailbox = Channel<Boolean>(Channel.CONFLATED)

    // ---- 不可逆动作确认门（AgentAction.needsUserConfirmation）----
    /**
     * 待确认的不可逆动作；null = 没有正在等待的确认。
     * 与 [needsUser]（用户指导等待）、[pendingShellCommand]（自写命令确认）是三套互相独立的
     * 状态与信箱：确认等待若共用 needsUser 标志，一边收尾会把另一边的等待一起掐掉（互相覆盖）。
     */
    private val _pendingActionConfirmation = MutableStateFlow<AgentEngine.PendingActionConfirmation?>(null)
    val pendingActionConfirmation: MutableStateFlow<AgentEngine.PendingActionConfirmation?> get() = _pendingActionConfirmation

    /**
     * 用户对不可逆动作的答复信箱：单槽 CONFLATED。
     * 用 Channel 而非 SharedFlow 的理由与 [userHintMailbox] 完全一致——用户抢在等待方订阅之前点按，
     * 值也必须被缓存住，否则主循环会永久挂死在等待上。
     */
    val actionConfirmationMailbox = Channel<Boolean>(Channel.CONFLATED)

    /** 连续「被动作模式拒绝」的次数；放行一次即清零 */
    var modeDenyStreak = 0

    /**
     * 任务是否被用户「搁置」（悬浮窗上的隐藏按钮）。
     *
     * 搁置不等于停止：主循环在每轮开始前挂在 awaitResume 上，用户点悬浮球唤出后从当前步继续。
     * 这一层是必须的——只把面板藏起来的话，AI 依旧在屏幕上点来点去，那不叫"搁置"。
     */
    @Volatile
    var taskPaused = false

    /**
     * 搁置状态的对外可观察版本（供状态条切换「暂停 / 继续」）。
     *
     * 运行期仍以 [taskPaused] 为准（主循环每轮直接读字段，不做流收集）；
     * 这里只是把同一事实暴露给 UI，两处由 pauseTask/resumeTask 一起维护。
     */
    private val _pausedState = MutableStateFlow(false)
    val pausedState: MutableStateFlow<Boolean> get() = _pausedState

    /** 同步搁置标志与对外状态，避免"字段改了、界面没变" */
    fun setPaused(paused: Boolean) {
        taskPaused = paused
        _pausedState.value = paused
    }

    var lastSnapshot: ScreenSnapshot = ScreenSnapshot()
    var currentLang = PromptLang.CN
    var consecutiveFailures = 0

    /** 敏感页接管的连续次数：页面持续敏感时反复弹接管会变成死循环，超上限直接收尾 */
    var sensitiveTakeoverStreak = 0
    /** 连续「决策链路异常」次数，达到上限即收尾 */
    var decisionFailureStreak = 0
    /** 上一次自动点击的广告关闭目标 key（centerX/20:centerY/20），供连续命中护栏使用 */
    @Volatile
    var lastAdClickTargetKey: String? = null
    /** 同一广告关闭目标被自动点击的连续次数 */
    @Volatile
    var adAutoClickStreak = 0
    /** 最近一次 shell 命令输出（查询类命令回传给 AI 上下文） */
    var lastShellOutput: String = ""

    /** 本步 shell 输出的暂存：由 finishShellResult 写入、由 recordStep 消费进 StepRecord，供 Agent 页回显 */
    var pendingShellOutput: String = ""

    /** 当前决策的流式输出（AI 正在生成的内容），用于在 Agent 页实时回显"AI 此刻在说什么" */
    private val _decisionStream = MutableStateFlow("")
    val decisionStream: MutableStateFlow<String> get() = _decisionStream

    // ---- 技能 / 宏 / 页面记忆 / 动作模式（自 AgentEngine 实例字段迁入）----

    /** 上一轮技能/MCP/查询返回文本（缺参自动推断的来源之一；随任务复位，见 run() 开头清空段） */
    var lastSkillOutput: String = ""
    // ---- 宏技能接力执行状态（Normalized.Macro 启动时赋值，随任务复位）----
    /** 待执行的宏步骤队列（剩余步骤，首元素 = 下一步） */
    var macroQueue: List<com.phoneagent.feature.skill.MacroStep> = emptyList()
    /** 宏入参（AI 调宏时给的 args，供 {{参数名}} 占位符展开） */
    var macroArgs: Map<String, String> = emptyMap()
    /** 已执行步骤的结果文本（下标 0 = 第 1 步，供 {{stepN}} 引用） */
    var macroResults: List<String> = emptyList()
    /** 正在执行的宏名（日志与回注说明用） */
    var macroName = ""
    /** 已派发的宏步骤数（0 = 尚未派发任何步骤；用于区分「收割上一步结果」与「宏刚启动」） */
    var macroDispatched = 0
    /** 页面记忆提示缓存：按页面指纹缓存，换页才重查（与 anomalyHint 同款） */
    var pageMemoryHintFingerprint: String = ""
    var pageMemoryHintText: String = ""

    /**
     * 本次任务生效的动作模式（保守 / 均衡 / 自由）。
     *
     * 每步决策都读它，转译层与门控共用同一个值：一旦两处各取一次设置，
     * 就会出现"提示词按 A 档说能用、端侧按 B 档拒掉"的错位。
     */
    @Volatile
    var activeActionMode: ActionMode = ActionMode.DEFAULT

    /** decision 阶段对 hint 目标视觉定位得到的像素坐标，供转译层本次使用 */
    @Volatile
    var lastVisualCoordinate: Pair<Int, Int>? = null
}
