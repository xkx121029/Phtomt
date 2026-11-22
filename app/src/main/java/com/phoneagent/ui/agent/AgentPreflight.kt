package com.phoneagent.ui.agent

/**
 * 发送前的能力预检（纯函数，可离线单测）。
 *
 * 只回答一件事：这一次发送是否必须让用户先做一次明确选择。
 *
 * 为什么必须拦：无障碍没开时 AI 读不到控件、只能靠截图猜位置，
 * 一整轮（实测 40–60 秒）几乎注定失败。让用户"先等 45 秒再看到失败"，
 * 是把一次可以提前说清的选择推给了一次白等。
 *
 * **模型配置不参与这条判断**（HAPP-59 修正）。早先的实现把
 * `configProblem == null` 也当成前提，理由看上去成立——配置不全会由引擎在
 * `TaskLifecycle.start` 里前置拦截，那一轮根本不会起跑。但 `configProblem`
 * 不只在「没填 Key」时成立：`AppSettings.Settings.configProblem` 在
 * 「上一次连接测试失败」时同样非空（见该属性的 FAILED 分支）。于是一个刚把
 * Key 填错一次、还没修好的用户，发任务时预检会被整条跳过——而这正是首次
 * 配置必经的状态。HAPP-58 在模拟器上撞到的就是它：无障碍没开、也没选过
 * 截图模式，点发送却没有任何询问，直接落到失败终态卡（那张卡的标题还写着
 * 「无障碍没开」，出口却是「去配置」，见 [AgentTerminal.headline] 的同类修正）。
 *
 * 现在的口径只有一条：**无障碍不可用、且本轮没选过截图模式，就必须问一次。**
 * 配置问题不改变「要不要问」，只决定对话框里要多说的一句（[PreflightAsk.configNote]）。
 */
internal object AgentPreflight {

    /**
     * 一次预检的结论。
     *
     * @param consent 必须拦下并让用户选一次「去开启无障碍 / 仍然尝试」
     * @param configNote 模型配置问题（null = 配置可用）。不参与 [consent]，
     *   只用来在对话框里说清「这一轮就算继续也起不来」
     */
    data class PreflightAsk(val consent: Boolean, val configNote: String?)

    /**
     * @param a11yEnabled 无障碍服务当前可用
     * @param configProblem 模型配置问题（null = 配置可用）
     * @param screenshotModeChosen 用户本轮已经在界面上明确选了「仍然尝试（截图模式）」
     */
    fun ask(
        a11yEnabled: Boolean,
        configProblem: String?,
        screenshotModeChosen: Boolean,
    ): PreflightAsk = PreflightAsk(
        // configProblem 不出现在这里：它的 FAILED 分支会出现在首次配置的正常路径上，
        // 把它当前提等于在最需要闸门的时刻把闸门关掉
        consent = !a11yEnabled && !screenshotModeChosen,
        configNote = configProblem,
    )
}
