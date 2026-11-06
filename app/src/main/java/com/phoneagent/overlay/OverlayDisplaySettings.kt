package com.phoneagent.overlay

import android.content.Context
import android.content.SharedPreferences

/**
 * 逐步任务文字的显示位置。
 *
 * 背景：同一条任务描述原本会在跑马灯（顶部光带）和任务卡状态行同时出现，
 * 用户看到"同一步的描述被画了两遍"。这里把它收成一个用户可选的开关。
 */
enum class StepTextPlacement(val value: String) {
    /** 只在跑马灯显示：任务卡状态行只保留「第 n/m 步」，不再重复描述 */
    MARQUEE("marquee"),

    /** 只在任务卡状态行显示：跑马灯窗口整体隐藏 */
    CARD("card"),

    /** 两处都显示（历史行为，给需要同屏对照的用户） */
    BOTH("both");

    companion object {
        /** 默认只保留一处：跑马灯。见 [OverlayDisplaySettings.DEFAULT_STEP_TEXT_PLACEMENT] 的取值理由 */
        val DEFAULT = MARQUEE

        /** 容错解析：未知值/空值一律回落到默认，不让脏数据把界面变成空白 */
        fun fromValue(raw: String?): StepTextPlacement =
            values().firstOrNull { it.value == raw } ?: DEFAULT
    }
}

/**
 * 浮层显示设置（用户可调、跨进程重启保留）。
 *
 * 这是给 **UI 线** 的唯一入口：读写都走这里的访问器，不需要知道 SharedPreferences 的文件名与键名。
 *
 * 用法（设置页）：
 * ```
 * // 读
 * val placement = OverlayDisplaySettings.stepTextPlacement(context)
 * val edgeOn = OverlayDisplaySettings.edgeLightingEnabled(context)
 *
 * // 写（写完后正在运行的浮层与边缘光效会在下一次主线程消息里生效，无需重启 App）
 * OverlayDisplaySettings.setStepTextPlacement(context, StepTextPlacement.CARD)
 * OverlayDisplaySettings.setEdgeLightingEnabled(context, enabled = false)
 * ```
 *
 * 存储：独立文件 `overlay_display`，两个键分别是 [KEY_STEP_TEXT_PLACEMENT]（字符串）与
 * [KEY_EDGE_LIGHTING_ENABLED]（布尔）。用独立文件而不是塞进 `AppSettings`，
 * 是为了让浮层线的读写不依赖其他线的偏好模型（那边正在并发改动）。
 */
object OverlayDisplaySettings {

    /** SharedPreferences 文件名（内部细节，UI 线不要直接用） */
    const val PREFS_NAME = "overlay_display"

    /** 键 1：逐步任务文字的显示方式，取值见 [StepTextPlacement.value] */
    const val KEY_STEP_TEXT_PLACEMENT = "step_text_placement"

    /** 键 2：绿色主题边缘光效悬浮窗是否开启 */
    const val KEY_EDGE_LIGHTING_ENABLED = "edge_lighting_enabled"

    /**
     * 键 3：任务开始前是否先产出执行计划。
     *
     * 与上面两个键放在同一个独立 prefs 文件里：引擎线与 UI 线读同一个键，
     * 键名与取值语义（boolean，true=先规划，false=直接执行）是跨线契约，不要改。
     */
    const val KEY_PLAN_BEFORE_ACT = "plan_before_act"

    /**
     * 键 1 默认值：只跑马灯。
     *
     * 截图里顶部光带是通栏、压状态栏、对比最强的那一层，更像"主信息"；
     * 任务卡只有 300dp 宽，描述在状态行里会被省略号截断，默认不适合当唯一出口。
     */
    val DEFAULT_STEP_TEXT_PLACEMENT = StepTextPlacement.DEFAULT

    /** 键 2 默认值：开启（边缘光效是任务执行的主要环境反馈，默认不削弱） */
    const val DEFAULT_EDGE_LIGHTING_ENABLED = true

    /** 键 3 默认值：关闭（默认直接进入执行，需要先看计划时由用户手动打开） */
    const val DEFAULT_PLAN_BEFORE_ACT = false

    fun stepTextPlacement(context: Context): StepTextPlacement =
        StepTextPlacement.fromValue(prefs(context).getString(KEY_STEP_TEXT_PLACEMENT, null))

    fun setStepTextPlacement(context: Context, placement: StepTextPlacement) {
        prefs(context).edit().putString(KEY_STEP_TEXT_PLACEMENT, placement.value).apply()
    }

    fun edgeLightingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDGE_LIGHTING_ENABLED, DEFAULT_EDGE_LIGHTING_ENABLED)

    fun setEdgeLightingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDGE_LIGHTING_ENABLED, enabled).apply()
    }

    /** 任务开始前是否先出计划；引擎在任务启动时读同一个键 */
    fun planBeforeAct(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLAN_BEFORE_ACT, DEFAULT_PLAN_BEFORE_ACT)

    fun setPlanBeforeAct(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAN_BEFORE_ACT, enabled).apply()
    }

    /** 浮层服务监听设置变化用；UI 线不需要调用 */
    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    /** 浮层服务销毁时注销；UI 线不需要调用 */
    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 任务卡状态行的文案拼装（纯函数，可在 JVM 单测里钉住）。
 *
 * 拆出来的原因：显示位置切换时要"就地重绘"而不是等下一次 [FloatingWindowService.updateStatus]，
 * 同一个拼装规则被两处调用，必须只有一份实现。
 */
object StepTextContent {

    /**
     * @param step 当前步（0 表示空闲态）
     * @param total 总步数（0 表示未知）
     * @param description 本步描述（已按配置决定是否要展示）
     * @param includeDescription 是否在状态行里带上描述（跑马灯模式下为 false，只留步数）
     */
    fun cardLabel(
        step: Int,
        total: Int,
        description: String,
        includeDescription: Boolean,
    ): String {
        val counter = when {
            step <= 0 -> ""
            total > 0 -> "第 $step/$total 步"
            else -> "第 $step 步"
        }
        val desc = description.trim()
        return when {
            counter.isEmpty() -> desc
            !includeDescription || desc.isEmpty() -> counter
            else -> "$counter · $desc"
        }
    }
}
