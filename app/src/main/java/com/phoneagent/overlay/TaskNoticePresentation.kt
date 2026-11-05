package com.phoneagent.overlay

/**
 * 降级提示（引擎 `TaskNoticeCenter.notice`）在浮层上的展示规则。
 *
 * 这里只放**纯函数**，不碰 View / Context：浮层怎么画由 [FloatingWindowService] 决定，
 * 文案怎么组织则由引擎给（`text` 是成品中文，界面不改写）。抽出来的原因有两点：
 * 1. 这些规则（停留时长、方式标签、需操作标记）要被 JVM 单测钉住，
 *    否则"至少停留 3 秒"这种契约只存在于注释里；
 * 2. 展示层多处复用同一份判定，不要在 View 代码里再抄一遍字符串拼接。
 */
object TaskNoticePresentation {

    /**
     * 一条提示至少停留的时长。
     *
     * 契约要求「至少停留 3 秒」：用户是在做别的事时瞥到这条细条的，低于 3 秒读不完一句话。
     * 取 4 秒留出阅读余量，到期后向上淡出；下一条提示进来会直接覆盖并重新计时，不叠加。
     */
    const val HoldMs = 4000L

    /**
     * 方式标签：`method` 非空时呈现为「当前：X」。
     *
     * 空串与纯空白都按"没有自动方式"处理（引擎约定 null 表示没有自动方式），
     * 此时返回 null，界面整行隐藏，不留下一个空的「当前：」。
     */
    fun methodLabel(method: String?): String? {
        val trimmed = method?.trim().orEmpty()
        return if (trimmed.isEmpty()) null else "当前：$trimmed"
    }

    /**
     * 需操作徽章：只有 `requiresAction = true` 才出现，普通降级提示不挂。
     *
     * 两档提示必须一眼可辨：普通提示是白底细条（信息），需操作是高亮实色条 + 这枚徽章（要动手）。
     */
    fun actionBadge(requiresAction: Boolean): String? = if (requiresAction) "需操作" else null
}
