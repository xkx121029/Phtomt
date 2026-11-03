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
        CapabilitySpec(

        // ---- 低风险（保守模式放行） ----
            intent = IntentType.WAIT, name = "等待", category = "操作",
            summary = "原地等待 wait_ms 毫秒，用于等待页面加载。",
            params = listOf(CapabilityParam("wait_ms", "等待毫秒", "number", required = true, defaultValue = "1000")),
            risk = RiskTier.LOW, channel = CapabilityChannel.LOCAL, action = ActionType.WAIT,
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
