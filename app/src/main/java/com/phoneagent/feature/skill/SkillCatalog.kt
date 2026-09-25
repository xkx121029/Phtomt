package com.phoneagent.feature.skill

import com.phoneagent.domain.model.IntentType
import com.phoneagent.engine.execution.ActionPolicy

/**
 * 内置 Skill 目录：把原硬编码命令（意图）升级为内置 Skill。
 *
 * 每个内置 Skill 通过 [Skill.legacyIntent] 指回旧命令意图名，由 [SkillCompat] 兼容层保证
 * “旧命令 → 对应内置 Skill → 原有转译逻辑”的向下兼容链路不变。AI 叫出旧意图名或技能名都可达。
 */
object SkillCatalog {

    /**
     * 高层语义技能的通用可选参数。
     *
     * 这些技能的落地方式是"点端侧标注好的语义控件"，正常无需参数；但页面未标注出对应语义控件时，
     * 转译层支持用 AI 给的 target 兜底定位一次（见 SemanticActionStrategy）。此前技能声明里没有这个
     * 参数，AI 便无从提供 —— 接口是空的，能力也就在这条路上不可达。故统一声明为可选参数。
     */
    private val optionalTarget = listOf(
        SkillParam("target", "目标（可选）", "text", description = "语义控件未命中时兜底定位，如 ctl_3 或控件文字"),
    )

    /** 意图名 → 技能定义（name/description/category/params）。 */
    private val catalog: Map<String, Skill> = buildMap {
        put(
            IntentType.OPEN_APP, Skill(
                id = "skill_open_app", name = "打开应用", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.OPEN_APP,
                description = "打开指定应用。参数 app 为应用中文名或包名，如「美团」或 com.sankuai.meituan；泛指类目（浏览器/文档/相册等）同名多个时端侧优先系统自带应用。",
                params = listOf(
                    SkillParam("app", "应用名/包名", "text", required = true, description = "要打开的应用中文名或包名"),
                ),
            ),
        )
        put(
            IntentType.OPEN, Skill(
                id = "skill_open_deeplink", name = "打开链接/文件", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.OPEN,
                description = "把链接/文件交给系统应用打开（网址给系统浏览器，本地文件 /sdcard/x.ppt 给系统文档应用，端侧按扩展名补类型），或用深链直达应用页面。参数 uri 为网址、文件路径或应用私有 scheme；或 app+page 走页面直达索引。要指定用哪个应用打开就填 app。",
                params = listOf(
                    SkillParam("uri", "链接/文件路径", "text", defaultValue = "", description = "https:// 网址、/sdcard/x.ppt 本地文件路径或 scheme://"),
                    SkillParam("app", "应用名", "text", description = "指定用哪个应用打开（可配合 page 走页面索引）"),
                    SkillParam("page", "页面序号", "number", description = "软件页面直达索引序号"),
                ),
            ),
        )
        put(
            IntentType.TAP, Skill(
                id = "skill_tap", name = "点击", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.TAP,
                description = "点击某个控件。参数 target 的 by 用 id 引用控件编号（如 ctl_3），或 text 用外显文字。",
                params = listOf(
                    SkillParam("target", "目标", "text", required = true, description = "控件 id（ctl_1）或控件文字/语义"),
                ),
            ),
        )
        put(
            IntentType.LONG_PRESS, Skill(
                id = "skill_long_press", name = "长按", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.LONG_PRESS,
                description = "长按某个控件。参数 target 见 tap。",
                params = listOf(
                    SkillParam("target", "目标", "text", required = true),
                    SkillParam("duration_ms", "长按时长(ms)", "number", defaultValue = "800"),
                ),
            ),
        )
        put(
            IntentType.INPUT, Skill(
                id = "skill_input", name = "输入文字", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.INPUT,
                description = "向输入框 target 输入文字 text。",
                params = listOf(
                    SkillParam("target", "目标输入框", "text", required = true),
                    SkillParam("text", "要输入的字", "text", required = true),
                ),
            ),
        )
        put(
            IntentType.SWIPE, Skill(
                id = "skill_swipe", name = "滑动", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SWIPE,
                description = "按方向滑动屏幕。参数 direction = up|down|left|right。",
                params = listOf(
                    SkillParam("direction", "方向", "select", required = true, options = listOf("up", "down", "left", "right")),
                ),
            ),
        )
        put(
            IntentType.PRESS, Skill(
                id = "skill_press", name = "按键", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.PRESS,
                description = "触发系统按键。参数 key = BACK|HOME|ENTER|RECENT。",
                params = listOf(
                    SkillParam("key", "按键", "select", required = true, options = listOf("BACK", "HOME", "ENTER", "RECENT")),
                ),
            ),
        )
        put(
            IntentType.WAIT, Skill(
                id = "skill_wait", name = "等待", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.WAIT,
                description = "原地等待 wait_ms 毫秒，用于等待页面加载。",
                params = listOf(
                    SkillParam("wait_ms", "等待毫秒", "number", required = true, defaultValue = "1000"),
                ),
            ),
        )
        put(
            IntentType.SCROLL_TO, Skill(
                id = "skill_scroll_to", name = "滚动查找", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SCROLL_TO,
                description = "向上/向下滚动查找目标控件 target，找到即停。",
                params = listOf(
                    SkillParam("target", "要找的目标", "text", required = true),
                ),
            ),
        )
        put(
            IntentType.WRITE_DOC, Skill(
                id = "skill_write_doc", name = "写入文档", source = SkillSource.INTENT, isBuiltIn = true,
                category = "创作", legacyIntent = IntentType.WRITE_DOC,
                description = "把正文 text 生成文档，文件名可选（summary），结果在 Agent 页预览。",
                params = listOf(
                    SkillParam("text", "正文内容", "text", required = true),
                    SkillParam("summary", "文件名", "text", description = "可选，默认自动命名"),
                ),
            ),
        )
        put(
            IntentType.REMEMBER, Skill(
                id = "skill_remember", name = "记住信息", source = SkillSource.INTENT, isBuiltIn = true,
                category = "记忆", legacyIntent = IntentType.REMEMBER,
                description = "把有长期价值的信息写入本地记忆库（不操作屏幕、不碰设备）。",
                params = listOf(
                    SkillParam("text", "要记住的内容", "text", required = true, description = "一句话，如「用户偏好简洁界面」"),
                    SkillParam(
                        "summary", "分类", "select", defaultValue = "general",
                        options = listOf("preference", "fact", "habit", "tip", "general"),
                    ),
                ),
            ),
        )
        put(
            IntentType.DEVICE_QUERY, Skill(
                id = "skill_device_query", name = "查询本机信息", source = SkillSource.INTENT, isBuiltIn = true,
                category = "取数", legacyIntent = IntentType.DEVICE_QUERY,
                description = "查询本机信息（不操作屏幕、不碰设备）：装了哪些应用、当前时间、电量、网络、存储。",
                params = listOf(
                    SkillParam(
                        "kind", "查询内容", "select", required = true, defaultValue = "all",
                        options = listOf("apps", "time", "battery", "network", "storage", "all"),
                        description = "apps=应用清单 time=时间 battery=电量 network=网络 storage=存储 all=全部",
                    ),
                    SkillParam("filter", "过滤关键词", "text", description = "仅 kind=apps 时生效，如「相机」"),
                ),
            ),
        )
        put(
            IntentType.SAY, Skill(
                id = "skill_say", name = "回话", source = SkillSource.INTENT, isBuiltIn = true,
                category = "沟通", legacyIntent = IntentType.SAY,
                description = "对用户说一句话（不操作屏幕、不碰设备）：解释、汇报、反问都走它，会直接显示在任务流的对话里。",
                params = listOf(
                    SkillParam("text", "要说的话", "text", required = true, description = "一句话，支持 Markdown"),
                ),
            ),
        )
        put(
            IntentType.SHOW_AGENT, Skill(
                id = "skill_show_agent", name = "引导用户看结果", source = SkillSource.INTENT, isBuiltIn = true,
                category = "沟通", legacyIntent = IntentType.SHOW_AGENT,
                description = "把用户带回 Agent 页当面看结果（不操作屏幕）：用户人已经切到别的 App、结果需要他当场过目时用它。" +
                    "带上 text 时会先把这段 Markdown 存成文档，随即在 Agent 页全屏展示。",
                params = listOf(
                    SkillParam("text", "要全屏展示的正文", "text", description = "可选，Markdown；留空则只把用户带回 Agent 页"),
                    SkillParam("summary", "标题/文件名", "text", description = "可选，留空自动命名"),
                ),
            ),
        )
        put(
            IntentType.FETCH, Skill(
                id = "skill_fetch", name = "取网页正文", source = SkillSource.INTENT, isBuiltIn = true,
                category = "取数", legacyIntent = IntentType.FETCH,
                description = "用命令行取网页/接口正文（需本机已安装并授权 Termux）；接口 JSON/纯文本原样返回，返回 HTML 时自动转成 Markdown 再回传。",
                params = listOf(
                    SkillParam("uri", "网页/接口地址", "text", required = true, description = "http:// 或 https://"),
                ),
            ),
        )
        // ---- 内置浏览器（WebView 后台静默加载，内容用 browse_read 读）----
        put(
            IntentType.BROWSE_OPEN, Skill(
                id = "skill_browse_open", name = "打开网页", source = SkillSource.INTENT, isBuiltIn = true,
                category = "浏览器", legacyIntent = IntentType.BROWSE_OPEN,
                description = "在 App 内置浏览器打开网址（后台静默加载，界面不切走、截图里看不到网页）。只要打开网页就用它（不用 open 深链、不用 fetch），之后用 browse_read 看内容。",
                params = listOf(
                    SkillParam("uri", "网址", "text", required = true, description = "http:// 或 https:// 开头的完整网址"),
                ),
            ),
        )
        put(
            IntentType.BROWSE_READ, Skill(
                id = "skill_browse_read", name = "抓取网页内容", source = SkillSource.INTENT, isBuiltIn = true,
                category = "浏览器", legacyIntent = IntentType.BROWSE_READ,
                description = "抓取内置浏览器当前网页的正文与表单按钮；正文以 Markdown 返回（标题层级/列表/表格/代码块齐全，链接内联成 [文字](网址)），结果作为「上一步结果」回给你。无需参数。",
            ),
        )
        put(
            IntentType.BROWSE_CLICK, Skill(
                id = "skill_browse_click", name = "点击网页元素", source = SkillSource.INTENT, isBuiltIn = true,
                category = "浏览器", legacyIntent = IntentType.BROWSE_CLICK,
                description = "点击当前网页里的元素。target 优先写元素文字（by=text），元素无文字时写 CSS 选择器（by=id，如 #login）。",
                params = listOf(
                    SkillParam("target", "目标元素", "text", required = true, description = "元素文字（优先）或 CSS 选择器"),
                ),
            ),
        )
        put(
            IntentType.BROWSE_INPUT, Skill(
                id = "skill_browse_input", name = "网页表单输入", source = SkillSource.INTENT, isBuiltIn = true,
                category = "浏览器", legacyIntent = IntentType.BROWSE_INPUT,
                description = "向当前网页的输入框填字（如搜索框、登录表单）。target 定输入框，text 是要填的字。",
                params = listOf(
                    SkillParam("target", "输入框", "text", required = true, description = "输入框提示文字/名称，或 CSS 选择器"),
                    SkillParam("text", "要填的字", "text", required = true),
                ),
            ),
        )
        put(
            IntentType.BROWSE_SCROLL, Skill(
                id = "skill_browse_scroll", name = "网页滚动", source = SkillSource.INTENT, isBuiltIn = true,
                category = "浏览器", legacyIntent = IntentType.BROWSE_SCROLL,
                description = "滚动内置浏览器的网页（长文/长列表查找内容时用）。",
                params = listOf(
                    SkillParam(
                        "direction", "方向", "select", required = true, defaultValue = "down",
                        options = listOf("up", "down", "top", "bottom"),
                        description = "up=上翻 down=下翻 top=回到顶部 bottom=到底部",
                    ),
                ),
            ),
        )
        put(
            IntentType.BROWSE_BACK, Skill(
                id = "skill_browse_back", name = "网页后退", source = SkillSource.INTENT, isBuiltIn = true,
                category = "浏览器", legacyIntent = IntentType.BROWSE_BACK,
                description = "在内置浏览器里后退到上一个网页（不是系统返回，不会退出浏览器）。",
            ),
        )
        put(
            IntentType.FINISH, Skill(
                id = "skill_finish", name = "完成", source = SkillSource.INTENT, isBuiltIn = true,
                category = "控制", legacyIntent = IntentType.FINISH,
                description = "任务已完成，给出完成说明 summary。",
                params = listOf(SkillParam("summary", "完成说明", "text")),
            ),
        )
        put(
            IntentType.GIVE_UP, Skill(
                id = "skill_give_up", name = "放弃", source = SkillSource.INTENT, isBuiltIn = true,
                category = "控制", legacyIntent = IntentType.GIVE_UP,
                description = "任务无法继续，给出原因 reason 后放弃。",
                params = listOf(SkillParam("reason", "放弃原因", "text")),
            ),
        )
        // ---- 高层语义接口升级为内置技能 ----
        put(
            IntentType.BACK, Skill(
                id = "skill_back", name = "返回", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.BACK, description = "返回上一页：优先点语义返回按钮，找不到则系统返回。",
            ),
        )
        put(
            IntentType.HOME, Skill(
                id = "skill_home", name = "回桌面", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.HOME, description = "回到桌面/首页（系统 HOME）。",
            ),
        )
        put(
            IntentType.REFRESH, Skill(
                id = "skill_refresh", name = "刷新", source = SkillSource.INTENT, isBuiltIn = true,
                category = "导航", legacyIntent = IntentType.REFRESH, description = "刷新当前页面。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SEARCH, Skill(
                id = "skill_search", name = "搜索", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SEARCH, description = "进入搜索：聚焦搜索框或点搜索入口。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SEND, Skill(
                id = "skill_send", name = "发送", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SEND, description = "发送消息/提交。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.CONFIRM, Skill(
                id = "skill_confirm", name = "确认", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.CONFIRM, description = "确认当前操作（授权/确定/结算）。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.CLOSE, Skill(
                id = "skill_close", name = "关闭", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.CLOSE,
                description = "关闭当前弹窗/广告/标签或广告页。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SHARE, Skill(
                id = "skill_share", name = "分享", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SHARE, description = "分享当前内容。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.COLLECT, Skill(
                id = "skill_collect", name = "收藏", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.COLLECT, description = "收藏当前内容。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.COPY, Skill(
                id = "skill_copy", name = "复制", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.COPY, description = "复制目标内容。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.DELETE, Skill(
                id = "skill_delete", name = "删除", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.DELETE,
                description = "删除目标（不可逆，自动触发用户确认）。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.DOWNLOAD, Skill(
                id = "skill_download", name = "下载", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.DOWNLOAD, description = "下载当前目标。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.ADD, Skill(
                id = "skill_add", name = "新增", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.ADD, description = "新增/添加一项。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.SWITCH, Skill(
                id = "skill_switch", name = "切换开关", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.SWITCH, description = "切换开关状态。",
                params = optionalTarget,
            ),
        )
        put(
            IntentType.CLEAR_INPUT, Skill(
                id = "skill_clear_input", name = "清空输入", source = SkillSource.INTENT, isBuiltIn = true,
                category = "操作", legacyIntent = IntentType.CLEAR_INPUT, description = "清空输入框内容。",
                params = optionalTarget,
            ),
        )
        // ---- 自由模式专属（保守/均衡一律拒绝，见 ActionPolicy.freeOnly）----
        put(
            IntentType.SHELL, Skill(
                id = "skill_shell", name = "执行命令", source = SkillSource.INTENT, isBuiltIn = true,
                category = "自由模式", legacyIntent = IntentType.SHELL,
                description = "执行 AI 自写的命令（仅自由模式；本任务首次执行前用户确认一次）。" +
                    "command 填命令原文，shizuku / 无线 ADB / Termux 通道由端侧按可用性自动选，" +
                    "终端输出会作为「上一步结果」回给你。",
                params = listOf(
                    SkillParam(
                        "command", "命令原文", "text", required = true,
                        description = "如 pm list packages | grep 相机；也可写友好命令",
                    ),
                ),
            ),
        )
        put(
            IntentType.A11Y, Skill(
                id = "skill_a11y", name = "调用无障碍端点", source = SkillSource.INTENT, isBuiltIn = true,
                category = "自由模式", legacyIntent = IntentType.A11Y,
                description = "直接调用一个无障碍底层端点（仅自由模式）。endpoint 选端点名，" +
                    "端点所需参数直接写在 args 对象里（键名见各端点说明，如 click 需 x/y）。" +
                    "它是转译层够不着时的兜底手段，能用既有意图解决就不要用它。",
                params = listOf(
                    SkillParam(
                        "endpoint", "端点", "select", required = true,
                        options = ActionPolicy.a11yEndpoints.map { it.name },
                        description = ActionPolicy.a11yEndpoints.joinToString("；") { "${it.name}(${it.argsText()}) ${it.description}" },
                    ),
                ),
            ),
        )
    }

    fun builtins(): List<Skill> = catalog.values.toList()

    fun byLegacyIntent(intentName: String): Skill? = catalog[intentName]

    fun byId(id: String): Skill? = catalog[id]
}