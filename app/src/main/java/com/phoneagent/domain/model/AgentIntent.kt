package com.phoneagent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AI 决策后输出的"意图" DSL（对应 HPA动作执行逻辑优化文档 v2.1）。
 *
 * AI 只描述"做什么"与"对什么做"，不关心"怎么做"（选通道/算坐标/转命令）。
 * 具体执行方式由端侧 [com.phoneagent.engine.execution.IntentTranslator] 依据当前授权模式
 * （SHIZUKU / ACCESSIBILITY / READONLY）转译为具体命令后执行。
 *
 * 序列化字段与 AI 的 JSON 输出保持一致，便于直接反序列化。
 * 字段命名遵循文档：intent / target{by,value} / app / text / direction / key / wait_ms / summary / reason。
 */
@Serializable
data class AgentIntent(
    /** 意图类型，见 [IntentType] 常量 */
    val intent: String,
    /** 目标控件描述（tap / input / scroll_to / long_press 需要） */
    val target: AgentIntentTarget? = null,
    /** open_app：要打开的应用名或包名（如"美团" / "com.sankuai.meituan"） */
    val app: String? = null,
    /** input：要输入的文字；write_doc：文档正文 */
    val text: String? = null,
    /** swipe：滑动方向 up|down|left|right */
    val direction: String? = null,
    /** swipe：滑动像素距离（可选） */
    val distancePx: Int? = null,
    /** press：按键 BACK|HOME|ENTER|RECENT */
    val key: String? = null,
    /** 长按时长 / 等待兜底（毫秒） */
    val durationMs: Long? = null,
    /** wait：等待时长（毫秒） */
    @SerialName("wait_ms")
    val waitMs: Long? = null,
    /** finish：完成描述；write_doc：文件名（可选）；open：软件页面直达可忽略 */
    val summary: String? = null,
    /** give_up：放弃原因 */
    val reason: String? = null,
    /** open：深链/协议直达 uri；browse_open：要在内置浏览器打开的网址 */
    val uri: String? = null,
    /** open：软件页面直达索引序号（配合 app） */
    val page: Int? = null,
    /** device_query：要查询的本机信息类别 apps|time|battery|network|storage|all */
    val kind: String? = null,
    /** device_query：应用清单的过滤关键词（可选，如「相机」） */
    val filter: String? = null,
    /** shell：AI 自写的命令原文（仅自由模式可用；友好命令或 raw 透传，通道由端侧决定） */
    val command: String? = null,
    /** a11y：要调用的无障碍端点名（仅自由模式可用，见 ActionPolicy.a11yEndpoints） */
    val endpoint: String? = null,
    /**
     * 技能调用参数：技能名 / 技能 id（或 MCP 技能 id）时，具体参数放入此对象（param 名 → 值）。
     * 用于把"调用技能"统一归一化成等价意图或 MCP 调用。普通意图场景不填。
     */
    @SerialName("args")
    val args: Map<String, String>? = null,
    // ---- 统一字段（决策与进度展示通用） ----
    /** 给用户看的跑马灯文字（≤20 字） */
    val reasoning: String? = null,
    /** 执行后预期看到什么 */
    val expected: String? = null,
    /** 决策置信度 0~1 */
    val confidence: Double? = null,
    /** 不可逆操作（支付/删除/发送）需用户确认 */
    @SerialName("needs_confirmation")
    val needsConfirmation: Boolean = false,
    /** 页面指纹（一字不差回传） */
    @SerialName("page_fingerprint")
    val pageFingerprint: String? = null,
)

/** 目标定位：AI 描述"对什么操作"，端侧负责把 [value] 变成屏幕上的控件/坐标（AI 默认不输出坐标） */
@Serializable
data class AgentIntentTarget(
    /**
     * 定位方式：
     * - id（控件 id 精确查找）
     * - text（控件文字）
     * - hint（语义描述，交给端侧视觉定位）
     * - coordinate（独占规则保留能力：仅当元素树无该控件且视觉定位拿不到时，才可给出比例/像素坐标）
     */
    val by: String = "hint",
    /** 控件 id / 控件文字 / 一句语义描述 /（by=coordinate 时）"x,y" 比例或像素坐标 */
    val value: String = "",
)

/**
 * 意图常量。AI 在 JSON 中通过 intent 字段引用。
 */
object IntentType {
    const val OPEN_APP = "open_app"       // 打开应用（app=应用名或包名）
    const val OPEN = "open"               // 深链/协议直达页面（uri 或 app+page）
    const val TAP = "tap"                 // 点击（target）
    const val LONG_PRESS = "long_press"   // 长按（target, duration_ms）
    const val INPUT = "input"             // 输入文字（target, text）
    const val SWIPE = "swipe"             // 滑动（direction）
    const val PRESS = "press"             // 按键（key）
    const val WAIT = "wait"               // 等待（wait_ms）
    const val SCROLL_TO = "scroll_to"     // 滚动查找（target）
    const val WRITE_DOC = "write_doc"     // 生成文档（text=正文, summary=文件名），结果在 Agent 页预览
    const val REMEMBER = "remember"       // 记住长期信息（text=记忆内容, summary=分类），纯本地写库、不操作设备
    const val DEVICE_QUERY = "device_query" // 查询本机信息（kind=apps/time/battery/network/storage/all，filter 可选），纯本地读取、不操作设备
    const val SAY = "say"                 // 对用户说一句话（不操作设备，直接显示在任务流；支持 Markdown）
    const val SHOW_AGENT = "show_agent"   // 把用户引导回 Agent 页看结果（不操作设备；text=要全屏展示的 Markdown，summary=标题）
    const val FETCH = "fetch"             // 获取网页/接口正文（uri），经转译层落到 Termux 命令行取数
    const val FINISH = "finish"           // 任务完成（summary）
    const val GIVE_UP = "give_up"         // 放弃（reason）

    // ---- 内置浏览器（端侧 WebView 可见页，AI 能亲眼看到网页；不依赖 Termux、不操控用户设备） ----
    const val BROWSE_OPEN = "browse_open"     // 在内置浏览器打开网址（uri）
    const val BROWSE_READ = "browse_read"     // 抓取当前网页内容（标题/正文/链接/表单），结果回注决策
    const val BROWSE_CLICK = "browse_click"   // 点击网页元素（target：文字优先，或 CSS 选择器）
    const val BROWSE_INPUT = "browse_input"   // 网页表单输入（target, text）
    const val BROWSE_SCROLL = "browse_scroll" // 网页滚动（direction：up/down/top/bottom）
    const val BROWSE_BACK = "browse_back"     // 网页内后退（不是系统返回，不会退出浏览器）

    // ---- 高层语义接口（Φ 端侧已识别的语义控件类，转译层本地映射为命令，AI 不写命令/坐标） ----
    const val BACK = "back"               // 返回上一页（优先语义按钮，找不到走系统返回）
    const val HOME = "home"               // 回到桌面/首页（系统 HOME）
    const val REFRESH = "refresh"         // 刷新当前页
    const val SEARCH = "search"           // 进入搜索（聚焦搜索框/点搜索入口）
    const val SEND = "send"               // 发送（发消息/提交）
    const val CONFIRM = "confirm"         // 确认当前（授权/确定/结算）
    const val CLOSE = "close"             // 关闭弹窗/广告/标签
    const val SHARE = "share"             // 分享
    const val COLLECT = "collect"         // 收藏/加入收藏
    const val COPY = "copy"               // 复制
    const val DELETE = "delete"           // 删除（不可逆，自动 needs_confirmation）
    const val DOWNLOAD = "download"       // 下载
    const val ADD = "add"                 // 新增/添加
    const val SWITCH = "switch"           // 切换开关状态
    const val CLEAR_INPUT = "clear_input" // 清空输入框

    // ---- 自由模式专属（保守/均衡一律拒绝，见 ActionPolicy.freeOnly） ----
    const val SHELL = "shell"             // AI 自写命令（command=命令原文；shizuku/adb/termux 通道由端侧决定）
    const val A11Y = "a11y"               // 调用无障碍端点（endpoint=端点名，args=参数）

    /**
     * 全部合法意图（转译层能识别的意图全集）。
     * 供判分/白名单校验使用：AI 输出的 intent 不在此集合内即为非法意图。
     */
    val ALL: Set<String> = setOf(
        OPEN_APP, OPEN, TAP, LONG_PRESS, INPUT, SWIPE, PRESS, WAIT, SCROLL_TO, WRITE_DOC, REMEMBER, DEVICE_QUERY,
        SAY, SHOW_AGENT, FETCH, FINISH, GIVE_UP,
        BROWSE_OPEN, BROWSE_READ, BROWSE_CLICK, BROWSE_INPUT, BROWSE_SCROLL, BROWSE_BACK,
        BACK, HOME, REFRESH, SEARCH, SEND, CONFIRM, CLOSE, SHARE, COLLECT, COPY, DELETE, DOWNLOAD, ADD,
        SWITCH, CLEAR_INPUT,
        SHELL, A11Y,
    )

    /** 意图 → 内部执行动作（AgentAction.type）的映射。供转译层把意图变成命令。 */
    val TO_ACTION: Map<String, String> = mapOf(
        OPEN_APP to ActionType.LAUNCH,
        OPEN to ActionType.OPEN,
        TAP to ActionType.TAP,
        LONG_PRESS to ActionType.LONG_PRESS,
        INPUT to ActionType.TYPE_TEXT,
        SWIPE to ActionType.SWIPE,
        PRESS to ActionType.KEY,
        WAIT to ActionType.WAIT,
        SCROLL_TO to ActionType.SCROLL,
        WRITE_DOC to ActionType.WRITE_DOC,
        DEVICE_QUERY to ActionType.DEVICE_QUERY,
        SAY to ActionType.SAY,
        SHOW_AGENT to ActionType.SHOW_AGENT,
        FETCH to ActionType.SHELL,
        FINISH to ActionType.TASK_DONE,
        GIVE_UP to ActionType.TASK_DONE,
        // 自由模式专属：AI 自写命令落到 shell，无障碍端点落到 a11y_call
        SHELL to ActionType.SHELL,
        A11Y to ActionType.A11Y_CALL,
        // 6 个浏览意图（browse_*）不在此表：它们走内置浏览器通道（BrowserChannel），
        // 由 DOM 脚本直接落地，不经过转译层，也不产生 ActionType。
    )
}