package com.phoneagent.ui.agent

/**
 * 发送前的能力预检（纯函数，可离线单测）。
 *
 * 只回答一件事：这一次发送是否必须让用户先做一次明确选择。
 * 其余能力（模型配置）由引擎在 `TaskLifecycle.start` 里用同一份
 * `AppSettings.Settings.configProblem` 在起跑前拦截——那里已经是唯一判据，
 * UI 再判一次只会多出一个可能与引擎不一致的第二判据。
 *
 * 为什么必须拦：无障碍没开时 AI 读不到控件、只能靠截图猜位置，
 * 一整轮（实测 40–60 秒）几乎注定失败。让用户"先等 45 秒再看到失败"，
 * 是把一次可以提前说清的选择推给了一次白等。
 */
internal object AgentPreflight {

    /**
     * @param a11yEnabled 无障碍服务当前可用
     * @param configProblem 模型配置问题（null = 配置可用）；非空时交给引擎的前置拦截，
     *   不问截图模式——那一轮根本不会起跑，问了也没有意义
     * @param screenshotModeChosen 用户本轮已经在界面上明确选了「仍然尝试（截图模式）」
     */
    fun needsScreenshotConsent(
        a11yEnabled: Boolean,
        configProblem: String?,
        screenshotModeChosen: Boolean,
    ): Boolean = configProblem == null && !a11yEnabled && !screenshotModeChosen
}
