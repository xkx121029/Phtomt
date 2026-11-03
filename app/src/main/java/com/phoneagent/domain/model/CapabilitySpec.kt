package com.phoneagent.domain.model

/**
 * 能力的**唯一声明点**：一项能力（AI 意图）→ 它的参数、风险档、执行通道、所需权限、是否需要用户确认。
 *
 * 以前这四件事各写一份、靠人工对齐：
 * - 给模型看的意图表（提示词正文）
 * - 运行时门控（[com.phoneagent.engine.execution.ActionPolicy] 的低/中/高风险集合）
 * - 技能目录（[com.phoneagent.feature.skill.SkillCatalog]）
 * - App 内技能列表（来自 SkillCatalog 生成的内置技能）
 *
 * 现在三者（门控、技能目录、App 列表）都从本文件派生；提示词正文的静态表格由单测做一致性校验，
 * 新增能力只需在这里加一条 [CapabilitySpec]，忘了接线会由 `CapabilityRegistryTest` / `IntentTypeCoverageTest` 直接红。
 *
 * 本文件不依赖 engine/feature 任何类，避免 domain→engine 反向依赖。
 */

/** 风险档：决定动作模式（保守/均衡/自由）是否放行 */
enum class RiskTier {
    /** 只读 / 纯本地 / 低副作用（保守模式放行） */
    LOW,

    /** 会改动设备或外部状态，可见可撤销（均衡起放行） */
    MID,

    /** 真实不可逆（发送/授权结算/删除），需用户确认 */
    HIGH,

    /** 自由模式专属（保守与均衡一律拒绝） */
    EXTENDED,
}

/** 首选执行入口（通道族）。同一族的候选与降级顺序由 ChannelPolicy 按能力快照决定。 */
enum class CapabilityChannel(val key: String) {
    /** 纯端侧：不触碰屏幕/外部，无通道依赖 */
    LOCAL("local"),

    /** 屏幕操作：无障碍 / Shizuku / 无线 ADB 按 ChannelLadder 择优 */
    SCREEN("screen"),

    /** 系统时钟应用直达（AlarmClock 意图：定闹钟 / 倒计时，不经语音助手、不模拟点击） */
    CLOCK("clock"),

    /** 内置浏览器（WebView 可见页，独立通道） */
    BROWSER("browser"),

    /** 命令行（Shizuku / 无线 ADB / Termux） */
    SHELL("shell"),

    /** 无障碍端点直调 */
    A11Y("a11y"),

    /** 系统语音助手 */
    VOICE("voice"),

    /** 端侧视觉模型 */
    VISION("vision"),

    /** 系统直连（AudioManager / Settings / Clipboard / PackageManager / 副屏 / 时钟数据库等，不经 GUI） */
    DEVICE("device"),
}

/** 能力所需的本机权限（NONE = 无固定权限要求，可用性由通道探测决定） */
enum class CapabilityPermission(val key: String) {
    NONE("none"),

    /** 无障碍服务在线 */
    ACCESSIBILITY("accessibility"),

    /** shell 通道（Shizuku / 无线 ADB） */
    SHELL("shell"),

    /** 通知使用权 */
    NOTIFICATION("notification"),

    /** Termux 工具链 */
    TERMUX("termux"),
}

/** 能力参数声明（技能目录的 [com.phoneagent.feature.skill.SkillParam] 由它派生） */
data class CapabilityParam(
    /** 参数名（AI JSON 字段名） */
    val name: String,
    /** 展示标签 */
    val label: String,
    /** 类型：text / number / boolean / select */
    val type: String = "text",
    /** 是否必填 */
    val required: Boolean = false,
    /** 说明 */
    val description: String? = null,
    /** 默认值 */
    val defaultValue: String? = null,
    /** type=select 时的候选项 */
    val options: List<String> = emptyList(),
    /** type=number 时的最小值（含） */
    val min: Double? = null,
    /** type=number 时的最大值（含） */
    val max: Double? = null,
    /** type=text 时的正则约束（端侧校验，失败直接回中文原因） */
    val pattern: String? = null,
)

/** 一项能力的完整声明 */
data class CapabilitySpec(
    /** 意图名（AI JSON 的 intent 字段） */
    val intent: String,
    /** 中文名（App 技能列表展示） */
    val name: String,
    /** 分类（技能列表分组） */
    val category: String,
    /** 用途说明（喂给 AI / 展示给用户） */
    val summary: String,
    /** 参数声明 */
    val params: List<CapabilityParam> = emptyList(),
    /** 风险档 */
    val risk: RiskTier,
    /** 首选执行入口 */
    val channel: CapabilityChannel,
    /** 所需权限 */
    val permission: CapabilityPermission = CapabilityPermission.NONE,
    /** 是否需要用户确认（不可逆或高影响动作） */
    val needsConfirmation: Boolean = false,
    /**
     * 内部执行动作（[ActionType]）；null 表示不产生设备动作、由其它通道落地
     * （内置浏览器 browse_* / 看图追问 see）。
     */
    val action: String? = null,
)

/**
 * 无障碍端点白名单条目：自由模式下 AI 可通过 `a11y` 意图调用的一个底层接口。
 *
 * @param name 端点名（AI 在 `endpoint` 字段填这个）
 * @param requiredArgs 必填参数名（AI 放在 `args` 对象里）
 * @param optionalArgs 可选参数名
 * @param description 一句话说明（注入提示词）
 * @param descriptionEn 英文一句话说明（英文提示词用，避免英文提示里混中文）
 */
data class A11yEndpoint(
    val name: String,
    val requiredArgs: List<String>,
    val optionalArgs: List<String>,
    val description: String,
    val descriptionEn: String,
) {
    /** 提示词表格里的一行参数写法 */
    fun argsText(): String = (requiredArgs + optionalArgs.map { "$it?" }).joinToString(",").ifEmpty { "—" }
}

/** 无障碍端点白名单（18 项）：自由模式下 AI 可调用的底层接口全集，唯一定义点。 */
object A11yEndpoints {
    val all: List<A11yEndpoint> = listOf(
        A11yEndpoint("click", listOf("x", "y"), emptyList(), "按像素坐标点击", "tap at pixel coordinates"),
        A11yEndpoint("long_click", listOf("x", "y"), emptyList(), "按像素坐标长按", "long-press at pixel coordinates"),
        A11yEndpoint(
            "click_node", listOf("target"), listOf("long_click"),
            "直点活节点控件（免坐标，优先用它）；target 写「id:资源名」或直接写控件文字",
            "click a live node control (no coordinates; prefer this); target is \"id:resourceName\" or the control's text",
        ),
        A11yEndpoint("swipe", listOf("x1", "y1", "x2", "y2"), listOf("duration_ms"), "按坐标滑动", "swipe between coordinates"),
        A11yEndpoint("swipe_direction", listOf("direction"), listOf("distance_px"), "按方向滑动（up/down/left/right）", "swipe by direction (up/down/left/right)"),
        A11yEndpoint("scroll", listOf("direction"), listOf("target"), "滚动页面；给 target 时滚动查找该控件", "scroll the page; with target, scroll until that control appears"),
        A11yEndpoint("scroll_container", listOf("direction"), emptyList(), "滚动当前可滚动容器", "scroll the current scrollable container"),
        A11yEndpoint("global_action", listOf("action"), emptyList(), "原始全局动作：1=返回 2=主页 4=通知栏 8=最近任务", "raw global action: 1=back 2=home 4=notifications 8=recents"),
        A11yEndpoint("back", emptyList(), emptyList(), "系统返回", "system back"),
        A11yEndpoint("home", emptyList(), emptyList(), "系统主页", "system home"),
        A11yEndpoint("recents", emptyList(), emptyList(), "最近任务", "recent tasks"),
        A11yEndpoint("launch_app", listOf("package"), emptyList(), "按包名启动应用", "launch an app by package name"),
        A11yEndpoint("open_uri", listOf("uri"), listOf("package"), "打开链接/文件/深链（可指定应用包名）", "open a link/file/deep-link (optionally with a target package)"),
        A11yEndpoint("open_settings_action", listOf("action"), emptyList(), "打开系统设置页（如 accessibility_settings / wifi）", "open a system settings page (e.g. accessibility_settings / wifi)"),
        A11yEndpoint("type_text", listOf("text"), listOf("target"), "向当前焦点或指定控件输入文字", "type text into the current focus or a given control"),
        A11yEndpoint("capture_tree", emptyList(), emptyList(), "重新抓取当前页面元素树（结果回注给你）", "re-capture the current element tree (result is fed back to you)"),
        A11yEndpoint("screenshot", emptyList(), emptyList(), "截取当前屏幕位图", "capture the current screen bitmap"),
        A11yEndpoint("can_screenshot", emptyList(), emptyList(), "查询本机当前是否具备截图能力", "check whether screen capture is currently available"),
    )

    private val byName: Map<String, A11yEndpoint> = all.associateBy { it.name }

    /** 按端点名查白名单条目；未知端点名返回 null（调用方据此失败并回报可选清单） */
    fun of(name: String?): A11yEndpoint? = byName[name?.trim()?.lowercase()]
}

/**
 * 声明式能力注册表：**能力 → 需求**的单一事实来源。
 *
 * 覆盖 [IntentType.ALL] 的全部 44 项能力。下游派生关系：
 * - [com.phoneagent.engine.execution.ActionPolicy] 的三档风险集合（运行时门控）
 * - [com.phoneagent.feature.skill.SkillCatalog] 的内置技能（→ App 内技能列表、技能名解析、缺参推断）
 * - [IntentType.TO_ACTION] / [IntentType.VIA_OTHER_CHANNELS]（转译层与通道登记）
 */
object CapabilityRegistry {

    /** 高层语义能力的通用可选参数：语义控件未命中时用 AI 给的 target 兜底定位一次 */
    private fun optionalTarget(): List<CapabilityParam> = listOf(
        CapabilityParam("target", "目标（可选）", "text", description = "语义控件未命中时兜底定位，如 ctl_3 或控件文字"),
    )

    /** 全部能力声明（顺序 = App 技能列表展示顺序） */
    val all: List<CapabilitySpec> = listOf(
        // ---- 低风险（保守模式放行） ----
        CapabilitySpec(
            intent = IntentType.SET_ALARM, name = "定闹钟", category = "时钟",
            summary = "直接在本机时钟应用创建闹钟（不经语音助手、不模拟点击）：hour/minute 为 24 小时制，或只给 when（「7点半」「半小时后」「明天早上6点」「每天7点」）由端侧解析；repeat_days 可写「每天/工作日/周一三五」；text 为标签。LOW 风险：保守档也允许，可撤销、不改动用户数据。",
            params = listOf(
                CapabilityParam("when", "时间说法", "text", description = "口语时间：「7点半」「半小时后」「明天早上6点」「每天7点」"),
                CapabilityParam("hour", "小时", "number", description = "24 小时制 0~23；与 when 二选一"),
                CapabilityParam("minute", "分钟", "number", description = "0~59，缺省 0"),
                CapabilityParam("repeat_days", "重复星期", "text", description = "「每天」「工作日」「周末」「周一三五」，也接受 mon..sun 列表"),
                CapabilityParam("text", "标签", "text", description = "闹钟标签，如「起床」"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.CLOCK, action = ActionType.SET_ALARM,
        ),
        CapabilitySpec(
            intent = IntentType.SET_TIMER, name = "设倒计时", category = "时钟",
            summary = "直接在本机时钟应用创建倒计时（不经语音助手）；duration_seconds 为秒数，text 为标签。LOW 风险：保守档也允许。",
            params = listOf(
                CapabilityParam("duration_seconds", "倒计时秒数", "number", required = true, description = "如 1800 = 30 分钟"),
                CapabilityParam("text", "标签", "text", description = "倒计时标签，可选"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.CLOCK, action = ActionType.SET_TIMER,
        ),
        CapabilitySpec(
            intent = IntentType.WAIT, name = "等待", category = "操作",
            summary = "原地等待 wait_ms 毫秒，用于等待页面加载。",
            params = listOf(CapabilityParam("wait_ms", "等待毫秒", "number", required = true, defaultValue = "1000")),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.WAIT,
        ),
        CapabilitySpec(
            intent = IntentType.WAIT_FOR, name = "等待出现", category = "操作",
            summary = "等到目标出现再继续（比盲等可靠）：text 给页面要出现的文字，app 给要进入前台的包名/应用名，wait_ms 给超时上限（默认 10000）。到点没出现会返回当前前台应用与页面摘要，不静默失败。",
            params = listOf(
                CapabilityParam("text", "等到的文字", "text", description = "页面里要出现的文字；与 app 二选一"),
                CapabilityParam("app", "等到的应用", "text", description = "要进入前台的包名或应用名；与 text 二选一"),
                CapabilityParam("wait_ms", "超时毫秒", "number", defaultValue = "10000", min = 500.0, max = 60000.0),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.WAIT_FOR,
        ),
        CapabilitySpec(
            intent = IntentType.SAY, name = "回话", category = "沟通",
            summary = "对用户说一句话（不操作屏幕、不碰设备）：解释、汇报、反问都走它，会直接显示在任务流的对话里。",
            params = listOf(CapabilityParam("text", "要说的话", "text", required = true, description = "一句话，支持 Markdown")),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.SAY,
        ),
        CapabilitySpec(
            intent = IntentType.REMEMBER, name = "记住信息", category = "记忆",
            summary = "把有长期价值的信息写入本地记忆库（不操作屏幕、不碰设备）。",
            params = listOf(
                CapabilityParam("text", "要记住的内容", "text", required = true, description = "一句话，如「用户偏好简洁界面」"),
                CapabilityParam("summary", "分类", "select", defaultValue = "general", options = listOf("preference", "fact", "habit", "tip", "general")),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.REMEMBER,
        ),
        CapabilitySpec(
            intent = IntentType.DEVICE_QUERY, name = "查询本机信息", category = "取数",
            summary = "查询本机信息（不操作屏幕、不碰设备）：装了哪些应用、当前时间、电量、网络、存储。",
            params = listOf(
                CapabilityParam("kind", "查询内容", "select", required = true, defaultValue = "all", options = listOf("apps", "time", "battery", "network", "storage", "all"), description = "apps=应用清单 time=时间 battery=电量 network=网络 storage=存储 all=全部"),
                CapabilityParam("filter", "过滤关键词", "text", description = "仅 kind=apps 时生效，如「相机」"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.DEVICE_QUERY,
        ),
        CapabilitySpec(
            intent = IntentType.DEVICE_INFO, name = "设备详情", category = "取数",
            summary = "读系统详情（不操作屏幕、不碰 GUI）：op=status 汇总 / network 网络 / storage 存储 / memory 内存 / battery 电量 / display 屏幕 / sound 声音 / apps 应用清单 / inspect_app 应用详情。inspect_app 需要 app（包名或应用名），可同时给 text=权限/版本等关注点。",
            params = listOf(
                CapabilityParam("op", "查询项", "select", required = true, defaultValue = "status",
                    options = listOf("status", "network", "storage", "memory", "battery", "display", "sound", "apps", "inspect_app")),
                CapabilityParam("app", "目标应用", "text", description = "op=inspect_app 时必填：包名或应用名"),
                CapabilityParam("text", "补充关键词", "text", description = "op=inspect_app 时可写 permissions/version/source"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.MEMORY_GET, name = "检索记忆", category = "记忆",
            summary = "按需检索本地长期记忆（不操作屏幕）：query 关键词匹配内容/分类，limit 返回条数（默认 8）。只读。",
            params = listOf(
                CapabilityParam("query", "关键词", "text", description = "留空则按最近写入排序返回"),
                CapabilityParam("limit", "条数", "number", defaultValue = "8", min = 1.0, max = 30.0),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.MEMORY_WRITE, name = "编辑记忆", category = "记忆",
            summary = "增删改本地长期记忆（不操作屏幕）：op=write 新增一条（text/category）；update 按 id 改内容（id/text，可选 revision 做版本校验）；delete 按 id 删除。id 与 revision 都取自 memory_get 的返回。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, defaultValue = "write",
                    options = listOf("write", "update", "delete")),
                CapabilityParam("text", "内容", "text", description = "write/update 时的新内容"),
                CapabilityParam("category", "分类", "select", defaultValue = "general",
                    options = listOf("preference", "fact", "habit", "tip", "general")),
                CapabilityParam("id", "条目 id", "number", min = 1.0, description = "update/delete 用，取自 memory_get"),
                CapabilityParam("revision", "版本号", "number", min = 0.0,
                    description = "可选：update 时带上 memory_get 里的 rev，不一致说明条目已被改过"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.FILE_READ, name = "读文件", category = "文件",
            summary = "只读文件系统（不操作屏幕）：op=read 读文本（截断到 4000 字符）/ list 列目录 / stat 看属性 / glob 按通配符找文件 / grep 按正则搜内容。path 必须绝对路径（/sdcard、/storage、应用私有目录），相对路径按 /sdcard 解析。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, defaultValue = "read",
                    options = listOf("read", "list", "stat", "glob", "grep")),
                CapabilityParam("path", "路径", "text", description = "绝对路径；glob/grep 作为搜索根目录（默认 /sdcard）"),
                CapabilityParam("pattern", "模式", "text", description = "glob 的通配符（如 **/*.md）或 grep 的正则"),
                CapabilityParam("limit", "条数", "number", defaultValue = "100", min = 1.0, max = 500.0),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.FILE_WRITE, name = "写文件", category = "文件",
            summary = "改文件内容（会覆盖/修改用户数据，需用户确认）：op=write 覆盖写入（不存在的文件自动创建）/ append 追加 / edit 把文件里唯一出现的一段原文替换成新内容。path 规则同 file_read。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, defaultValue = "write",
                    options = listOf("write", "append", "edit")),
                CapabilityParam("path", "路径", "text", required = true, description = "目标文件绝对路径"),
                CapabilityParam("text", "内容", "text", description = "write/append 写入的内容"),
                CapabilityParam("old", "原文", "text", description = "edit：文件里要被替换的原文（需唯一）"),
                CapabilityParam("new", "新内容", "text", description = "edit：替换成的新内容"),
            ),
            risk = RiskTier.HIGH, channel = CapabilityChannel.LOCAL, action = ActionType.DEVICE_OPS,
            needsConfirmation = true,
        ),
        CapabilitySpec(
            intent = IntentType.APP_MANAGE, name = "管控应用", category = "应用",
            summary = "对本机应用做强制操作（不点界面，需 Shizuku/无线 ADB）：op=force_stop 强制停止 / background 清理后台。app 给包名或应用名。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, defaultValue = "force_stop",
                    options = listOf("force_stop", "background")),
                CapabilityParam("app", "目标应用", "text", required = true, description = "包名或应用名"),
            ),
            risk = RiskTier.HIGH, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
            needsConfirmation = true,
        ),
        CapabilitySpec(
            intent = IntentType.TERMINAL_OP, name = "命令行", category = "命令行",
            summary = "在本机注入通道执行命令（仅自由模式；需 Shizuku/无线 ADB）：op=exec 执行 command（输出截断到 4000 字符）/ check_root 看当前通道是否 Root。AI 自己写命令属于高风险能力。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, defaultValue = "exec",
                    options = listOf("exec", "check_root")),
                CapabilityParam("command", "命令", "text", description = "op=exec 时要执行的命令原文"),
            ),
            risk = RiskTier.EXTENDED, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.PERSONAL_READ, name = "个人数据", category = "取数",
            summary = "只读个人数据（需 Shizuku/无线 ADB，系统以 shell 身份查 ContentProvider）：op=contacts 通讯录 / calls 通话记录 / sms 短信 / calendar 日历事件 / media 媒体文件 / logs 最近错误日志。query 用于按文字过滤，limit 控制条数。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, defaultValue = "contacts",
                    options = listOf("contacts", "calls", "sms", "calendar", "media", "logs")),
                CapabilityParam("query", "过滤词", "text", description = "按名称/号码/正文包含此词过滤"),
                CapabilityParam("limit", "条数", "number", defaultValue = "20", min = 1.0, max = 200.0),
                CapabilityParam("type", "媒体类型", "select", defaultValue = "images",
                    options = listOf("images", "videos", "audio"), description = "仅 op=media 用"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.SEE, name = "看图追问", category = "取数",
            summary = "就当前屏幕截图向视觉模型提一个问题（问题要写成一句明确的目的，如「给出图中轮盘指针指向的扇区」）；回答作为「上一步结果」回注下一步。元素树读不到目标、需要判断画面内容/位置时用它。",
            params = listOf(
                CapabilityParam("text", "问题", "text", required = true, description = "这次看图要回答什么，一句话（如「图里哪个是确认按钮」）"),
                CapabilityParam("target", "目标", "text", description = "可选：要定位的具体目标文字，给了就返回它的坐标"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.VISION,
        ),
        CapabilitySpec(
            intent = IntentType.WRITE_DOC, name = "写入文档", category = "创作",
            summary = "把正文 text 生成文档，文件名可选（summary），结果在 Agent 页预览。",
            params = listOf(
                CapabilityParam("text", "正文内容", "text", required = true),
                CapabilityParam("summary", "文件名", "text", description = "可选，默认自动命名"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.WRITE_DOC,
        ),
        CapabilitySpec(
            intent = IntentType.SHOW_AGENT, name = "引导用户看结果", category = "沟通",
            summary = "把用户带回 Agent 页当面看结果（不操作屏幕）：用户人已经切到别的 App、结果需要他当场过目时用它。带上 text 时会先把这段 Markdown 存成文档，随即在 Agent 页全屏展示。",
            params = listOf(
                CapabilityParam("text", "要全屏展示的正文", "text", description = "可选，Markdown；留空则只把用户带回 Agent 页"),
                CapabilityParam("summary", "标题/文件名", "text", description = "可选，留空自动命名"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.SHOW_AGENT,
        ),
        CapabilitySpec(
            intent = IntentType.FINISH, name = "完成", category = "控制",
            summary = "任务已完成，给出完成说明 summary。",
            params = listOf(CapabilityParam("summary", "完成说明", "text")),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.TASK_DONE,
        ),
        CapabilitySpec(
            intent = IntentType.GIVE_UP, name = "放弃", category = "控制",
            summary = "任务无法继续，给出原因 reason 后放弃。",
            params = listOf(CapabilityParam("reason", "放弃原因", "text")),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.TASK_DONE,
        ),
        CapabilitySpec(
            intent = IntentType.SWIPE, name = "滑动", category = "操作",
            summary = "按方向滑动屏幕。参数 direction = up|down|left|right。",
            params = listOf(CapabilityParam("direction", "方向", "select", required = true, options = listOf("up", "down", "left", "right"))),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.SWIPE,
        ),
        CapabilitySpec(
            intent = IntentType.SCROLL_TO, name = "滚动查找", category = "操作",
            summary = "向上/向下滚动查找目标控件 target，找到即停。",
            params = listOf(CapabilityParam("target", "要找的目标", "text", required = true)),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.SCROLL,
        ),
        CapabilitySpec(
            intent = IntentType.PRESS, name = "按键", category = "操作",
            summary = "触发系统按键。参数 key = BACK|HOME|ENTER|RECENT。",
            params = listOf(CapabilityParam("key", "按键", "select", required = true, options = listOf("BACK", "HOME", "ENTER", "RECENT"))),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.KEY,
        ),
        CapabilitySpec(
            intent = IntentType.BACK, name = "返回", category = "导航",
            summary = "返回上一页：优先点语义返回按钮，找不到则系统返回。",
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.HOME, name = "回桌面", category = "导航",
            summary = "回到桌面/首页（系统 HOME）。",
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.KEY,
        ),
        CapabilitySpec(
            intent = IntentType.REFRESH, name = "刷新", category = "导航",
            summary = "刷新当前页面。",
            params = optionalTarget(),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.CLOSE, name = "关闭", category = "操作",
            summary = "关闭当前弹窗/广告/标签或广告页。",
            params = optionalTarget(),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.COLLECT, name = "收藏", category = "操作",
            summary = "收藏当前内容。",
            params = optionalTarget(),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.COPY, name = "复制", category = "操作",
            summary = "复制目标内容。",
            params = optionalTarget(),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.CLEAR_INPUT, name = "清空输入", category = "操作",
            summary = "清空输入框内容。",
            params = optionalTarget(),
            risk = RiskTier.LOW, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.NOTIF_READ, name = "读取通知", category = "取数",
            summary = "读取当前通知栏的全部通知（应用名/标题/正文）。验证码、消息提醒、支付结果这类屏幕上没有的信息用它；需要本机已开启通知使用权。纯本地读取、不操作设备、无参数。",
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, permission = CapabilityPermission.NOTIFICATION,
            action = ActionType.READ_NOTIFS,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_READ, name = "抓取网页内容", category = "浏览器",
            summary = "抓取内置浏览器当前网页的正文与表单按钮；正文以 Markdown 返回（标题层级/列表/表格/代码块齐全，链接内联成 [文字](网址)），结果作为「上一步结果」回给你。无需参数。",
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_SCROLL, name = "网页滚动", category = "浏览器",
            summary = "滚动内置浏览器的网页（长文/长列表查找内容时用）。",
            params = listOf(
                CapabilityParam("direction", "方向", "select", required = true, defaultValue = "down", options = listOf("up", "down", "top", "bottom"), description = "up=上翻 down=下翻 top=回到顶部 bottom=到底部"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_BACK, name = "网页后退", category = "浏览器",
            summary = "在内置浏览器里后退到上一个网页（不是系统返回，不会退出浏览器）。",
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_INFO, name = "网页状态", category = "浏览器",
            summary = "不读正文，只看当前网页的元信息：标题、网址、文档加载状态、滚动位置、链接与输入控件数量。用来判断「页面到底加载完没有 / 现在停在哪」。无需参数。",
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_RELOAD, name = "刷新网页", category = "浏览器",
            summary = "重新加载当前网页并等加载完成（页面卡住或内容没更新时用）。无需参数。",
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_WAIT, name = "等元素出现", category = "浏览器",
            summary = "反复轮询直到目标元素出现（最多 30 秒）。target 按 id（CSS 选择器）或 text（元素文字）定位；wait_ms 不填默认 8000。",
            params = listOf(
                CapabilityParam("target", "目标元素", "text", required = true, description = "要等出现的元素：文字或 CSS 选择器"),
                CapabilityParam("wait_ms", "超时毫秒", "number", defaultValue = "8000", min = 500.0, max = 30000.0),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.WEB_SEARCH, name = "网页搜索", category = "浏览器",
            summary = "在内置浏览器里搜索（text 填搜索词）：端侧打开搜索引擎结果页并抓回正文与链接，省去先打开首页再手打关键词的几步。适合查资料、查天气、查价格这类没有原生能力的长尾信息；结果只读，不点进任何结果页。",
            params = listOf(
                CapabilityParam("text", "搜索词", "text", required = true, description = "要搜的关键词，如「明天北京天气」"),
            ),
            risk = RiskTier.LOW, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(

        // ---- 中风险（均衡模式起放行） ----
            intent = IntentType.OPEN_APP, name = "打开应用", category = "导航",
            summary = "打开指定应用。参数 app 为应用中文名或包名，如「美团」或 com.sankuai.meituan；泛指类目（浏览器/文档/相册等）同名多个时端侧优先系统自带应用。",
            params = listOf(CapabilityParam("app", "应用名/包名", "text", required = true, description = "要打开的应用中文名或包名")),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.LAUNCH,
        ),
        CapabilitySpec(
            intent = IntentType.OPEN, name = "打开链接/文件", category = "导航",
            summary = "把链接/文件交给系统应用打开（网址给系统浏览器，本地文件 /sdcard/x.ppt 给系统文档应用，端侧按扩展名补类型），或用深链直达应用页面。参数 uri 为网址、文件路径或应用私有 scheme；或 app+page 走页面直达索引。要指定用哪个应用打开就填 app。",
            params = listOf(
                CapabilityParam("uri", "链接/文件路径", "text", defaultValue = "", description = "https:// 网址、/sdcard/x.ppt 本地文件路径或 scheme://"),
                CapabilityParam("app", "应用名", "text", description = "指定用哪个应用打开（可配合 page 走页面索引）"),
                CapabilityParam("page", "页面序号", "number", description = "软件页面直达索引序号"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.OPEN,
        ),
        CapabilitySpec(
            intent = IntentType.TAP, name = "点击", category = "操作",
            summary = "点击某个控件。参数 target 的 by 用 id 引用控件编号（如 ctl_3），或 text 用外显文字。",
            params = listOf(CapabilityParam("target", "目标", "text", required = true, description = "控件 id（ctl_1）或控件文字/语义")),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.LONG_PRESS, name = "长按", category = "操作",
            summary = "长按某个控件。参数 target 见 tap。",
            params = listOf(
                CapabilityParam("target", "目标", "text", required = true),
                CapabilityParam("duration_ms", "长按时长(ms)", "number", defaultValue = "800"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.LONG_PRESS,
        ),
        CapabilitySpec(
            intent = IntentType.INPUT, name = "输入文字", category = "操作",
            summary = "向输入框 target 输入文字 text。",
            params = listOf(
                CapabilityParam("target", "目标输入框", "text", required = true),
                CapabilityParam("text", "要输入的字", "text", required = true),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TYPE_TEXT,
        ),
        CapabilitySpec(
            intent = IntentType.FETCH, name = "取网页正文", category = "取数",
            summary = "用命令行取网页/接口正文（需本机已安装并授权 Termux）；接口 JSON/纯文本原样返回，返回 HTML 时自动转成 Markdown 再回传。",
            params = listOf(CapabilityParam("uri", "网页/接口地址", "text", required = true, description = "http:// 或 https://")),
            risk = RiskTier.MID, channel = CapabilityChannel.SHELL, permission = CapabilityPermission.TERMUX,
            action = ActionType.SHELL,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_OPEN, name = "打开网页", category = "浏览器",
            summary = "在 App 内置浏览器打开网址（后台静默加载，界面不切走、截图里看不到网页）。只要打开网页就用它（不用 open 深链、不用 fetch），之后用 browse_read 看内容。",
            params = listOf(CapabilityParam("uri", "网址", "text", required = true, description = "http:// 或 https:// 开头的完整网址")),
            risk = RiskTier.MID, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_CLICK, name = "点击网页元素", category = "浏览器",
            summary = "点击当前网页里的元素。target 优先写元素文字（by=text），元素无文字时写 CSS 选择器（by=id，如 #login）。",
            params = listOf(CapabilityParam("target", "目标元素", "text", required = true, description = "元素文字（优先）或 CSS 选择器")),
            risk = RiskTier.MID, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.BROWSE_INPUT, name = "网页表单输入", category = "浏览器",
            summary = "向当前网页的输入框填字（如搜索框、登录表单）。target 定输入框，text 是要填的字。",
            params = listOf(
                CapabilityParam("target", "输入框", "text", required = true, description = "输入框提示文字/名称，或 CSS 选择器"),
                CapabilityParam("text", "要填的字", "text", required = true),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.BROWSER,
        ),
        CapabilitySpec(
            intent = IntentType.SEARCH, name = "搜索", category = "操作",
            summary = "进入搜索：聚焦搜索框或点搜索入口。",
            params = optionalTarget(),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.SHARE, name = "分享", category = "操作",
            summary = "分享当前内容。",
            params = optionalTarget(),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.DOWNLOAD, name = "下载", category = "操作",
            summary = "下载当前目标。",
            params = optionalTarget(),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.ADD, name = "新增", category = "操作",
            summary = "新增/添加一项。",
            params = optionalTarget(),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.SWITCH, name = "切换开关", category = "操作",
            summary = "切换开关状态。",
            params = optionalTarget(),
            risk = RiskTier.MID, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
        ),
        CapabilitySpec(
            intent = IntentType.ASSISTANT, name = "语音助手", category = "导航",
            summary = "唤起系统语音助手（小爱同学/小艺/小布/Google 助理等）。text 是要让助手执行的一句话（可选），拉起后端侧会尝试在助手界面自动输入并发送；传话失败只是没传上话，不算失败。要让助手办语音/系统级的事（打电话、控制硬件、问它问题）用它；单纯打开某个应用用 open_app。",
            params = listOf(CapabilityParam("text", "要传的话", "text", description = "可选：要让助手执行的一句话，如「打电话给妈妈」")),
            risk = RiskTier.MID, channel = CapabilityChannel.VOICE, permission = CapabilityPermission.ACCESSIBILITY,
            action = ActionType.LAUNCH_ASSISTANT,
        ),
        CapabilitySpec(
            intent = IntentType.NOTIF_CLEAR, name = "清理通知", category = "操作",
            summary = "清掉通知栏里的全部通知（常驻通知保留）。会清掉用户未读内容，必须标 needs_confirmation；需要本机已开启通知使用权。",
            risk = RiskTier.MID, channel = CapabilityChannel.LOCAL, permission = CapabilityPermission.NOTIFICATION,
            needsConfirmation = true, action = ActionType.CLEAR_NOTIFS,
        ),
        CapabilitySpec(
            intent = IntentType.DEVICE_CONTROL, name = "设备直控", category = "设备",
            summary = "直接改设备状态（不经过 GUI、不模拟点击）：op=flashlight_on/flashlight_off 手电筒，brightness 亮度（level 0~100），volume 音量（stream/level），media 媒体键（action=play/pause/next/prev/stop），sound_mode 响铃模式（mode=ring/vibrate/silent），dnd 勿扰（enable=true/false），screen_timeout 息屏时间（seconds），rotation 旋转（enable=true/false），wifi/bluetooth/airplane/location/nfc 系统开关（enable=true/false，需 shell）。缺权限/通道时会回中文原因与替代做法。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true,
                    options = listOf(
                        "flashlight_on", "flashlight_off", "brightness", "volume", "media",
                        "sound_mode", "dnd", "screen_timeout", "rotation",
                        "wifi", "bluetooth", "airplane", "location", "nfc",
                    )),
                CapabilityParam("enable", "开关", "boolean", description = "op=dnd/rotation/wifi/bluetooth/airplane/location/nfc 时用"),
                CapabilityParam("level", "数值", "number", description = "brightness 0~100；volume 为百分比 0~100", min = 0.0, max = 100.0),
                CapabilityParam("stream", "音量通道", "select", defaultValue = "music", options = listOf("music", "ring", "notification", "alarm", "call")),
                CapabilityParam("mode", "模式", "select", options = listOf("ring", "vibrate", "silent"), description = "op=sound_mode"),
                CapabilityParam("action", "媒体动作", "select", options = listOf("play", "pause", "next", "prev", "stop"), description = "op=media"),
                CapabilityParam("seconds", "秒数", "number", min = 5.0, max = 1800.0, description = "op=screen_timeout"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.SYSTEM_SETTINGS, name = "系统设置读写", category = "设备",
            summary = "读写系统设置项：op=get 读（namespace/key），op=set 写（namespace/key/value）。namespace=system/secure/global。写 secure/global 通常需要 shell（Shizuku/无线 ADB）；写 system 需「修改系统设置」授权。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, options = listOf("get", "set")),
                CapabilityParam("namespace", "命名空间", "select", required = true, defaultValue = "system", options = listOf("system", "secure", "global")),
                CapabilityParam("key", "设置项", "text", required = true, description = "如 screen_brightness / font_scale", pattern = "[A-Za-z0-9_.]+"),
                CapabilityParam("value", "值", "text", description = "op=set 时必填"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.CLIPBOARD, name = "剪贴板", category = "设备",
            summary = "读写系统剪贴板（不经过 GUI）：op=get 读取当前内容，op=set 写入 text，op=clear 清空。Android 10+ 限制后台读剪贴板；读不到时会如实说明并建议先让本应用在前台。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, options = listOf("get", "set", "clear")),
                CapabilityParam("text", "内容", "text", required = false, description = "op=set 时必填"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.DEVICE, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.CALENDAR_OP, name = "日历读写", category = "设备",
            summary = "读写系统日历事件（不经过日历 App 的 GUI）：op=list 列出（query 过滤、days 时间窗、limit 条数），op=create 新建（title/start，可选 end 或 duration_min、all_day、calendar_id），op=update 改（event_id + 要改的字段），op=delete 删（event_id）。start/end 可写「2026-11-05 09:00」或毫秒时间戳。查询/写入以 shell 身份进行，需要 Shizuku 或无线 ADB；没有时回中文原因并建议改走 GUI。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, options = listOf("list", "create", "update", "delete")),
                CapabilityParam("title", "标题", "text", description = "op=create/update：事件标题"),
                CapabilityParam("start", "开始时间", "text", description = "「YYYY-MM-DD HH:mm」或毫秒时间戳；op=create 必填"),
                CapabilityParam("end", "结束时间", "text", description = "可选；不给时按 duration_min 推算"),
                CapabilityParam("duration_min", "时长（分钟）", "number", min = 5.0, max = 1440.0, defaultValue = "60"),
                CapabilityParam("event_id", "事件 id", "text", pattern = "[0-9]+", description = "op=update/delete：从 op=list 结果里取"),
                CapabilityParam("calendar_id", "日历 id", "number", min = 0.0, description = "可选，默认 1（本机主日历）"),
                CapabilityParam("all_day", "全天", "boolean", description = "op=create/update"),
                CapabilityParam("query", "过滤词", "text", description = "op=list：按标题过滤"),
                CapabilityParam("days", "时间窗（天）", "number", min = 1.0, max = 365.0, defaultValue = "30", description = "op=list：只看未来多少天"),
                CapabilityParam("limit", "条数", "number", min = 1.0, max = 100.0, defaultValue = "20"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.DEVICE, permission = CapabilityPermission.SHELL,
            action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.ALARM_MANAGE, name = "管理闹钟", category = "时钟",
            summary = "管理时钟应用里已有的闹钟：op=list 列出，op=enable 开关（hour/minute/enable），op=delete 删除（hour/minute），op=update 改时间（hour/minute/new_hour/new_minute）。读取/修改他人时钟数据需要 Root 或 Shizuku；没有时会回中文原因，并建议改走 GUI 或重新新增。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true, options = listOf("list", "enable", "delete", "update")),
                CapabilityParam("hour", "小时", "number", min = 0.0, max = 23.0, description = "定位已有闹钟"),
                CapabilityParam("minute", "分钟", "number", min = 0.0, max = 59.0),
                CapabilityParam("enable", "启用", "boolean", description = "op=enable"),
                CapabilityParam("new_hour", "新小时", "number", min = 0.0, max = 23.0, description = "op=update"),
                CapabilityParam("new_minute", "新分钟", "number", min = 0.0, max = 59.0, description = "op=update"),
            ),
            risk = RiskTier.MID, channel = CapabilityChannel.DEVICE, permission = CapabilityPermission.SHELL,
            action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(

        // ---- 高风险（不可逆，需用户确认） ----
            intent = IntentType.SEND, name = "发送", category = "操作",
            summary = "发送消息/提交。",
            params = optionalTarget(),
            risk = RiskTier.HIGH, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
            needsConfirmation = true,
        ),
        CapabilitySpec(
            intent = IntentType.CONFIRM, name = "确认", category = "操作",
            summary = "确认当前操作（授权/确定/结算）。",
            params = optionalTarget(),
            risk = RiskTier.HIGH, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
            needsConfirmation = true,
        ),
        CapabilitySpec(
            intent = IntentType.DELETE, name = "删除", category = "操作",
            summary = "删除目标（不可逆，自动触发用户确认）。",
            params = optionalTarget(),
            risk = RiskTier.HIGH, channel = CapabilityChannel.SCREEN, action = ActionType.TAP,
            needsConfirmation = true,
        ),
        CapabilitySpec(

        // ---- 自由模式专属（保守/均衡拒绝） ----
            intent = IntentType.SHELL, name = "执行命令", category = "自由模式",
            summary = "执行 AI 自写的命令（仅自由模式；本任务首次执行前用户确认一次）。command 填命令原文，shizuku / 无线 ADB / Termux 通道由端侧按可用性自动选，终端输出会作为「上一步结果」回给你。",
            params = listOf(CapabilityParam("command", "命令原文", "text", required = true, description = "如 pm list packages | grep 相机；也可写友好命令")),
            risk = RiskTier.EXTENDED, channel = CapabilityChannel.SHELL, permission = CapabilityPermission.SHELL,
            needsConfirmation = true, action = ActionType.SHELL,
        ),
        CapabilitySpec(
            intent = IntentType.VIRTUAL_DISPLAY, name = "副屏操作", category = "自由模式",
            summary = "操作端侧虚拟副屏（仅自由模式；需要 Shizuku/无线 ADB）：op=status 查状态，create 创建，destroy 销毁，launch 启动应用（app），tap 点击（x/y），swipe 滑动（x1/y1/x2/y2），type 输入（text），key 按键（key），screenshot 截图。副屏上的动作不占用用户当前屏幕。",
            params = listOf(
                CapabilityParam("op", "操作", "select", required = true,
                    options = listOf("status", "create", "destroy", "launch", "tap", "swipe", "type", "key", "screenshot")),
                CapabilityParam("app", "应用", "text", description = "op=launch：包名或应用名"),
                CapabilityParam("x", "X 坐标", "number", min = 0.0),
                CapabilityParam("y", "Y 坐标", "number", min = 0.0),
                CapabilityParam("x1", "起点 X", "number", min = 0.0),
                CapabilityParam("y1", "起点 Y", "number", min = 0.0),
                CapabilityParam("x2", "终点 X", "number", min = 0.0),
                CapabilityParam("y2", "终点 Y", "number", min = 0.0),
                CapabilityParam("text", "文字", "text", description = "op=type"),
                CapabilityParam("key", "按键", "select", options = listOf("BACK", "HOME", "ENTER", "RECENT")),
                CapabilityParam("direction", "滑动方向", "select", options = listOf("up", "down", "left", "right")),
            ),
            risk = RiskTier.EXTENDED, channel = CapabilityChannel.DEVICE,
            permission = CapabilityPermission.SHELL, action = ActionType.DEVICE_OPS,
        ),
        CapabilitySpec(
            intent = IntentType.A11Y, name = "调用无障碍端点", category = "自由模式",
            summary = "直接调用一个无障碍底层端点（仅自由模式）。endpoint 选端点名，端点所需参数直接写在 args 对象里（键名见各端点说明，如 click 需 x/y）。它是转译层够不着时的兜底手段，能用既有意图解决就不要用它。",
            params = listOf(
                CapabilityParam(
                    "endpoint", "端点", "select", required = true,
                    description = A11yEndpoints.all.joinToString("；") { "${it.name}(${it.argsText()}) ${it.description}" },
                    options = A11yEndpoints.all.map { it.name },
                ),
            ),
            risk = RiskTier.EXTENDED, channel = CapabilityChannel.A11Y, permission = CapabilityPermission.ACCESSIBILITY,
            action = ActionType.A11Y_CALL,
        ),
    )

    /** 意图名 → 声明 */
    val byIntent: Map<String, CapabilitySpec> = all.associateBy { it.intent }

    /** 按风险档筛选出的意图名集合 */
    fun ofRisk(tier: RiskTier): Set<String> = all.filter { it.risk == tier }.map { it.intent }.toSet()

    /** 按通道筛选出的意图名集合（无设备动作的能力也在这里登记，见 [CapabilitySpec.action]） */
    fun ofChannel(channel: CapabilityChannel): Set<String> =
        all.filter { it.channel == channel }.map { it.intent }.toSet()

    /** 取一项能力的声明；未登记返回 null（新增意图忘了登记时由单测兜住） */
    fun specOf(intent: String?): CapabilitySpec? = byIntent[intent?.trim().orEmpty()]
}
