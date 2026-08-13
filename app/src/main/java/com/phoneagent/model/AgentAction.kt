package com.phoneagent.model

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

    /** 文档动作别名 → 引擎动作的映射 */
    val ALIAS: Map<String, String> = mapOf(
        TAP to CLICK,
        LONG_PRESS to LONG_CLICK,
        KEY to KEY,
        LAUNCH to LAUNCH,
        SCROLL_TO to SCROLL,
        ABORT to TASK_DONE,
        TASK_COMPLETE to TASK_DONE,
    )
}