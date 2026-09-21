package com.phoneagent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AI 决策后需要执行的动作。
 *
 * 通过 [type] + 结构化字段描述一次屏幕操作，ActionExecutor 负责实际执行。
 * 序列化格式与 AI 的 JSON 输出保持一致，便于直接反序列化。
 */
@Serializable
data class AgentAction(
    /** 动作类型，见 [ActionType] 常量 */
    val type: String,
    /** 目标坐标 x（屏幕像素） */
    val x: Int? = null,
    /** 目标坐标 y（屏幕像素） */
    val y: Int? = null,
    /** 滑动终点 x */
    val endX: Int? = null,
    /** 滑动终点 y */
    val endY: Int? = null,
    /** 需要输入、或点击元素展示的文本 */
    val text: String? = null,
    /** 元素索引（引用元素树中的编号，便于 AI 精确定位） */
    val elementIndex: Int? = null,
    /** 滑动/按压持续时长（毫秒） */
    val durationMs: Long? = null,
    /** 等待时长（毫秒），AI 的 wait 动作输出，对应 timeout_ms */
    @SerialName("timeout_ms")
    val timeoutMs: Long? = null,
    /** 任务完成描述，当 type=TASK_DONE 时给出 */
    val summary: String? = null,
    /** 附加说明/理由 */
    val reason: String? = null,
    // ---- 以下字段对齐 HPA 提示词文档 v2.0 动作契约 ----

    /** 动作目标：{method: id|label|coordinate, value} */
    val target: ActionTarget? = null,
    /** 滑动方向：up|down|left|right */
    val direction: String? = null,
    /** 系统按键：BACK|HOME|ENTER|RECENT */
    val keycode: String? = null,
    /** 启动应用包名 */
    val packageName: String? = null,
    /** 滑动像素距离 */
    val distancePx: Int? = null,
    /** 页面指纹（一字不差回传） */
    val pageFingerprint: String? = null,
    /** 给用户看的跑马灯文字（≤20 字） */
    val reasoning: String? = null,
    /** 执行后预期 */
    val expected: String? = null,
    /** 决策置信度 0~1 */
    val confidence: Double? = null,
    /** 不可逆操作（支付/删除/发送）需用户确认 */
    val needsUserConfirmation: Boolean = false,
    /** shell 命令字符串（type=shell 时使用） */
    val command: String? = null,
    /** 深链/协议直达（type=open 时使用），如 https:// 链接或应用私有 scheme */
    val uri: String? = null,
    /** open 动作：目标软件名或包名（配合 [page] 走软件页面直达索引） */
    val app: String? = null,
    /** open 动作：目标软件内的页面索引序号（见软件页面直达索引表） */
    val page: Int? = null,
    /**
     * 内置浏览器动作的子操作（type=browse 时使用）。
     * 取值即意图名：browse_open / browse_read / browse_click / browse_input / browse_scroll / browse_back。
     * AI 不写这个字段，由端侧按 AI 输出的意图名生成，故与 AI 的 JSON 契约无关。
     */
    val op: String? = null,
)

/** 动作目标 */
@Serializable
data class ActionTarget(
    val method: String = "coordinate",
    val value: String = "",
)

/**
 * 动作类型常量。AI 在 JSON 中通过 type 字段引用。
 */
object ActionType {
    const val CLICK = "click"
    const val LONG_CLICK = "long_click"
    const val SWIPE = "swipe"
    const val SWIPE_UP = "swipe_up"
    const val SWIPE_DOWN = "swipe_down"
    const val SWIPE_LEFT = "swipe_left"
    const val SWIPE_RIGHT = "swipe_right"
    const val TYPE_TEXT = "type"
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val SCROLL = "scroll"
    const val WAIT = "wait"
    const val TASK_DONE = "task_done"
    const val REFRESH = "refresh"

    // ---- HPA 提示词文档 v2.0 动作词汇（保持向后兼容的别名） ----
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val KEY = "key"
    const val LAUNCH = "launch"
    const val SCROLL_TO = "scroll_to"
    const val ABORT = "abort"
    const val TASK_COMPLETE = "task_complete"

    /** Shizuku ADB shell 命令 */
    const val SHELL = "shell"

    /** 生成文档（text=内容，summary=文件名，可选），结果在 Agent 页预览 */
    const val WRITE_DOC = "write_doc"

    /** 记住长期信息（text=内容, summary=分类），纯本地写库、不操作设备 */
    const val REMEMBER = "remember"

    /** 查询本机信息（text=kind, summary=filter），纯本地读取、不操作设备 */
    const val DEVICE_QUERY = "device_query"

    /** 调用 MCP 技能（不操作设备，直接向 MCP 服务器发 JSON-RPC 请求） */
    const val MCP_CALL = "mcp_call"

    /** 深链/协议直达页面（uri=链接或 scheme），直接调出目标应用页面 */
    const val OPEN = "open"

    /**
     * 内置浏览器操作（op=子操作，见 [AgentAction.op]）。
     * 纯端侧执行：不依赖无障碍/Shizuku/Termux，WebView 是本 App 自己的界面，
     * 网页内容通过 DOM 脚本读取与操作，因此 AI 能"亲眼看到"网页（每步截图即为浏览器页）。
     */
    const val BROWSE = "browse"

    /** 文档动作别名 → 引擎动作的映射 */
    val ALIAS: Map<String, String> = mapOf(
        TAP to CLICK,
        LONG_PRESS to LONG_CLICK,
        KEY to KEY,
        LAUNCH to LAUNCH,
        SCROLL_TO to SCROLL,
        ABORT to TASK_DONE,
        TASK_COMPLETE to TASK_DONE,
        OPEN to OPEN,
    )
}