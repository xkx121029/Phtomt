package com.phoneagent.data.prefs

/**
 * 工作台形态（[com.phoneagent.data.prefs.AppSettings.Settings.agentSurface] 的取值）。
 *
 * unified：操控与对话合成为一个 Agent 页——两者收成一个入口，
 *          提交后由云端判定意图，自动选择「直接作答 / 操控执行」；编码仍是独立 Coding 页；
 * split：  三种能力各占一个页面（操控 / 对话 / 编码 Tab 并列）。
 *
 * ## 为什么在 `:domain` 而不是 `:data`
 *
 * 它是一个 UI 形态枚举：设置页把它读成一组单选、Tab 布局按它决定收不收对话页、
 * 引擎按它决定走合并形态的判断。`:ui:design` 与下一期的 `:feature:coding` 都需要看见它，
 * 而 `:ui:design → :data` 这条边已被裁定否决（设计层不该依赖持久化层）。
 * 下沉到 `:domain` 后依赖方向是 `:ui:design → :domain` 与 `:data → :domain`，两条都合法。
 *
 * ## 为什么包名仍是 `com.phoneagent.data.prefs`
 *
 * 它是个 `object`（`const String` + `normalize`），不是类型：`typealias` 只能给类型换名，
 * 调用方 `AgentSurface.UNIFIED` 这类**成员访问**经由 typealias 解析不了，做不到「引用方零改动」。
 * 保留原包名让全仓库 6 处引用（`:app` 4 处 import + `:data` 2 处同包使用）一行都不用改，
 * 代价是 `com.phoneagent.data.prefs` 被 `:data` 与 `:domain` 各持一半（split package）——
 * 与模块化 P1 下沉那 7 个记录类型时同样的取舍。
 */
object AgentSurface {
    const val UNIFIED = "unified"
    const val SPLIT = "split"

    /** 脏数据归一：只认这两个值，其余按合并形态 */
    fun normalize(raw: String?): String = if (raw == SPLIT) SPLIT else UNIFIED
}
