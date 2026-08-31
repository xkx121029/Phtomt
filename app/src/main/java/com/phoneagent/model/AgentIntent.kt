package com.phoneagent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AI 决策后输出的"意图" DSL（对应 HPA动作执行逻辑优化文档 v2.1）。
 *
 * AI 只描述"做什么"与"对什么做"，不关心"怎么做"（选通道/算坐标/转命令）。
 * 具体执行方式由端侧 [com.phoneagent.execution.IntentTranslator] 依据当前授权模式
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
    /** open：深链/协议直达 uri */
    val uri: String? = null,
    /** open：软件页面直达索引序号（配合 app） */
    val page: Int? = null,
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
    const val WRITE_DOC = "write_doc"     // 写入文档到工作区（text=正文, summary=文件名）
    const val FINISH = "finish"           // 任务完成（summary）
    const val GIVE_UP = "give_up"         // 放弃（reason）

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
        FINISH to ActionType.TASK_DONE,
        GIVE_UP to ActionType.TASK_DONE,
    )
}