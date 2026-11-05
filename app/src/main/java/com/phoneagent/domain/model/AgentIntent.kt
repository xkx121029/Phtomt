package com.phoneagent.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

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
    /** swipe：滑动像素距离（可选）；JSON 字段与提示词一致（snake_case） */
    @SerialName("distance_px")
    val distancePx: Int? = null,
    /** press：按键 BACK|HOME|ENTER|RECENT */
    val key: String? = null,
    /** 长按时长 / 等待兜底（毫秒）；JSON 字段与提示词一致（snake_case） */
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    /** wait：等待时长（毫秒） */
    @SerialName("wait_ms")
    val waitMs: Long? = null,
    /** set_alarm：小时（0~23，24 小时制） */
    @Serializable(with = FlexibleIntSerializer::class)
    val hour: Int? = null,
    /** set_alarm：分钟（0~59） */
    @Serializable(with = FlexibleIntSerializer::class)
    val minute: Int? = null,
    /** set_alarm：口语时间（「7点半」/「半小时后」/「明天早上6点」），hour/minute 缺失时端侧解析 */
    @SerialName("when")
    val timeText: String? = null,
    /** set_alarm：重复星期（["mon".."sun"] 或 ["周一"..]，空=一次性） */
    @SerialName("repeat_days")
    val repeatDays: List<String>? = null,
    /** set_timer：倒计时秒数 */
    @SerialName("duration_seconds")
    val durationSeconds: Long? = null,
    /** finish：完成描述；write_doc：文件名（可选）；open：软件页面直达可忽略 */
    val summary: String? = null,
    /** give_up：放弃原因 */
    val reason: String? = null,
    /** open：深链/协议直达 uri；browse_open：要在内置浏览器打开的网址 */
    val uri: String? = null,
    /**
     * open：软件页面直达索引序号（配合 app）。
     * 提示词教 AI 以字符串传出（如 "1"），故用 [FlexibleIntSerializer] 兼容 "1" 与 1 两种形态。
     */
    @Serializable(with = FlexibleIntSerializer::class)
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
    @Serializable(with = FlexibleArgsSerializer::class)
    val args: Map<String, String>? = null,
    /**
     * 系统直连类意图的"子操作"（如 device_control 的 flashlight_on / volume / brightness）。
     * 端侧按 意图 + op 分派到具体实现，AI 不写命令、不碰底层 API。
     */
    val op: String? = null,
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
 * 宽松整数序列化器：JSON 字符串 "1" → 1，数字 1 → 1；其余形态原样抛异常
 * （宁可解析失败走 give_up 兜底，也不静默吞值）。
 * 属性类型是 Int?（AgentIntent.page），编译器插件会自动把本序列化器包上 nullable 处理 null/缺省，
 * 故这里只需处理非空形态。
 */
object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int = when (decoder) {
        is JsonDecoder -> when (val el = decoder.decodeJsonElement()) {
            // JsonNull（JsonPrimitive 子类）等非整数形态统一走抛异常
            is JsonPrimitive -> el.content.toIntOrNull()
                ?: throw SerializationException("期望整数或整数字符串，实际：${el.content}")
            else -> throw SerializationException("期望整数或整数字符串，实际：$el")
        }
        // 非 JSON 格式（当前链路不存在）：按标准整数读取
        else -> decoder.decodeInt()
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

/**
 * args 的宽松序列化器：模型把参数值写成数字/布尔（`{"level":30}`、`{"all_day":true}`）是常态，
 * 而 [AgentIntent.args] 的类型是 `Map<String, String>` —— 严格解码会因为"类型不匹配"把**整条意图**
 * 判为解析失败，连 intent 名都读不进来，表现就是"设备直连怎么都不工作"。
 *
 * 因此这里只做一件事：把对象里每个原始值按字面量读成字符串（30 → "30"、true → "true"）；
 * `null` 直接丢掉（等价于没给这个参数）；嵌套对象/数组原样交给原解码器（那种形态不属于参数语义，
 * 应当尽早失败而不是被悄悄收成一个无意义的字符串）。
 */
object FlexibleArgsSerializer : JsonTransformingSerializer<Map<String, String>>(
    MapSerializer(String.serializer(), String.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        return JsonObject(
            element.mapNotNull { (key, value) ->
                when (value) {
                    is JsonNull -> null
                    is JsonPrimitive -> key to JsonPrimitive(value.content)
                    else -> key to value
                }
            }.toMap(),
        )
    }
}

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
    const val SEE = "see"                 // 看图追问（text=这次看图要回答什么，target 可选=要定位的目标），纯端侧视觉调用、不操作设备
    const val FETCH = "fetch"             // 获取网页/接口正文（uri），经转译层落到 Termux 命令行取数
    const val FINISH = "finish"           // 任务完成（summary）
    const val GIVE_UP = "give_up"         // 放弃（reason）
    const val ASSISTANT = "assistant"     // 唤起系统语音助手（小爱/小艺/小布等），text=要传的话（可选）
    // ---- 时钟直达（AlarmClock 意图，不经过语音助手；端侧直接创建并跳到时钟应用） ----
    const val SET_ALARM = "set_alarm"     // 定闹钟（hour/minute 或 when；repeat_days；label=text）
    const val SET_TIMER = "set_timer"     // 设倒计时（duration_seconds；label=text）
    // ---- 系统直连（不经 GUI、不模拟点击；对齐 Eta 的 device 工具族） ----
    const val WAIT_FOR = "wait_for"               // 等待某段文字出现或某个应用到前台（text / app / wait_ms）
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    const val DEVICE_INFO = "device_info"         // 读设备信息（op=status/network/storage/memory/battery/display/sound/inspect_app/apps）
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    const val DEVICE_CONTROL = "device_control"   // 改设备状态（op=flashlight_on/flashlight_off/brightness/volume/media/sound_mode/dnd/screen_timeout/rotation/wifi/bluetooth/airplane/location/nfc）
    const val SYSTEM_SETTINGS = "settings_op"     // 读写系统设置（op=get/set；namespace/key/value）
    const val CLIPBOARD = "clipboard"             // 剪贴板（op=get/set/clear）
    const val ALARM_MANAGE = "alarm_manage"       // 管理已有闹钟（op=list/enable/delete/update；需 Root/Shizuku）
    const val CALENDAR_OP = "calendar_op"         // 日历读写（op=list/create/update/delete；title/start/end/event_id；走注入通道）
    const val VIRTUAL_DISPLAY = "virtual_display" // 副屏（op=status/create/destroy/launch/tap/swipe/type/key/screenshot）
    const val MEMORY_GET = "memory_get"           // 按需检索长期记忆（query/limit）
    const val MEMORY_WRITE = "memory_write"       // 增删改长期记忆（op=write/update/delete；id/revision 取自 memory_get）
    // ---- 文件与应用管控（对齐 Eta 的 file / app 工具族；不经 GUI） ----
    const val FILE_READ = "file_read"             // 只读文件（op=read/list/stat/glob/grep；path/pattern/limit）
    const val FILE_WRITE = "file_write"           // 改文件（op=write/append/edit；path/text/old/new）
    const val APP_MANAGE = "app_manage"           // 管控应用（op=force_stop/background；app）
    const val TERMINAL_OP = "terminal_op"         // 注入通道命令行（op=exec/check_root；command；仅自由模式）
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    const val PERSONAL_READ = "personal_read"     // 个人数据只读（op=contacts/calls/sms/calendar/media/logs；query/limit/type；走注入通道）
    const val NOTIF_READ = "notif_read"   // 读取当前通知（需通知监听权限），纯本地读取、不操作设备
    const val NOTIF_CLEAR = "notif_clear" // 清理当前通知（ongoing 不动；会清掉用户未读内容，AI 需标注 needs_confirmation）

    // ---- 内置浏览器（端侧 WebView 可见页，AI 能亲眼看到网页；不依赖 Termux、不操控用户设备） ----
    const val BROWSE_OPEN = "browse_open"     // 在内置浏览器打开网址（uri）
    const val BROWSE_READ = "browse_read"     // 抓取当前网页内容（标题/正文/链接/表单），结果回注决策
    const val BROWSE_CLICK = "browse_click"   // 点击网页元素（target：文字优先，或 CSS 选择器）
    const val BROWSE_INPUT = "browse_input"   // 网页表单输入（target, text）
    const val BROWSE_SCROLL = "browse_scroll" // 网页滚动（direction：up/down/top/bottom）
    const val BROWSE_BACK = "browse_back"     // 网页内后退（不是系统返回，不会退出浏览器）
    const val BROWSE_INFO = "browse_info"     // 看当前网页状态（标题/网址/加载状态/滚动位置/可交互数量）
    const val BROWSE_RELOAD = "browse_reload" // 刷新当前网页并等加载完成
    const val BROWSE_WAIT = "browse_wait"     // 等某元素出现（target 选择器/文字 + wait_ms，上限 30s）
    const val WEB_SEARCH = "web_search"           // 内置浏览器搜索（query；打开搜索引擎并抓结果正文）

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
        SAY, SHOW_AGENT, SEE, FETCH, FINISH, GIVE_UP, ASSISTANT, SET_ALARM, SET_TIMER,
        WAIT_FOR, DEVICE_INFO, DEVICE_CONTROL, SYSTEM_SETTINGS, CLIPBOARD, ALARM_MANAGE, CALENDAR_OP,
        VIRTUAL_DISPLAY, MEMORY_GET, MEMORY_WRITE, FILE_READ, FILE_WRITE, APP_MANAGE, TERMINAL_OP, PERSONAL_READ,
        BROWSE_INFO, BROWSE_RELOAD, BROWSE_WAIT, WEB_SEARCH,
        NOTIF_READ, NOTIF_CLEAR,
        BROWSE_OPEN, BROWSE_READ, BROWSE_CLICK, BROWSE_INPUT, BROWSE_SCROLL, BROWSE_BACK,
        BACK, HOME, REFRESH, SEARCH, SEND, CONFIRM, CLOSE, SHARE, COLLECT, COPY, DELETE, DOWNLOAD, ADD,
        SWITCH, CLEAR_INPUT,
        SHELL, A11Y,
    )

    /**
     * 不经过转译层的意图：内置浏览器（browse_*）由 BrowserChannel 的 DOM 脚本直接落地，
     * see（看图追问）由引擎就地调用视觉模型——它们都不产生 [ActionType]，因此不会出现在转译层分派表里。
     *
     * 与 [ALL] 一样是「唯一登记点」：新增意图若既没接转译表、也没登记在这里，
     * IntentTypeCoverageTest 会直接红（否则 AI 输出该意图后只会得到一句"未知意图"）。
     */
    val VIA_OTHER_CHANNELS: Set<String> =
        CapabilityRegistry.all.filter { it.action == null }.map { it.intent }.toSet()

    /** 意图 → 内部执行动作（AgentAction.type）的映射。供转译层把意图变成命令。 */
    val TO_ACTION: Map<String, String> =
        CapabilityRegistry.all.mapNotNull { spec -> spec.action?.let { spec.intent to it } }.toMap()
}
