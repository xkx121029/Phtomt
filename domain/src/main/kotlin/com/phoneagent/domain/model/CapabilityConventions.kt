package com.phoneagent.domain.model

/**
 * 能力声明的结构性约定（纯函数）。
 *
 * 目的只有一个：**新增能力只应该在两处登记**——注册表加一条 [CapabilitySpec]，
 * 如果它产生设备动作且走屏幕/命令行通道，再在通道阶梯表加一行目标。除此之外，
 * 门控、技能目录、App 列表、提示词一致性都应自动派生。
 *
 * 这里不依赖 engine/feature，[actionHasGoal] 由调用方（单测或未来的启动自检）注入，
 * 因此既能校验真实注册表，也能校验「虚构成一条新能力」的场景。
 */
object CapabilityConventions {

    /** 时钟通道只接受这两种动作：它们有独立的 ClockFallbackPolicy，不参与通用通道阶梯 */
    val CLOCK_ACTIONS: Set<String> = setOf("set_alarm", "set_timer")

    data class Problem(val intent: String, val message: String) {
        override fun toString(): String = message
    }

    /**
     * @param specs 待校验的能力声明
     * @param actionHasGoal 动作是否有通道阶梯目标（真实场景传 `{ ChannelGoal.of(it) != null }`）
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun check(specs: List<CapabilitySpec>, actionHasGoal: (String) -> Boolean): List<Problem> {
        val problems = mutableListOf<Problem>()
        val seen = mutableSetOf<String>()

        specs.forEach { spec ->
            fun fail(message: String) {
                problems += Problem(spec.intent, "${spec.intent}: $message")
            }

            if (!seen.add(spec.intent)) fail("intent 重复声明")
            if (spec.name.isBlank()) fail("缺少中文名")
            if (spec.category.isBlank()) fail("缺少分类")
            if (spec.summary.isBlank()) fail("缺少用途说明")

            when (spec.channel) {
                CapabilityChannel.BROWSER, CapabilityChannel.VISION ->
                    if (spec.action != null) fail("该通道由浏览器/视觉通道落地，不应声明设备动作")

                CapabilityChannel.SCREEN, CapabilityChannel.SHELL -> when {
                    spec.action == null -> fail("缺少执行动作")
                    !actionHasGoal(spec.action) ->
                        fail("动作 ${spec.action} 没有通道阶梯目标（新增动作要在通道策略表加一行）")
                }

                CapabilityChannel.CLOCK ->
                    if (spec.action !in CLOCK_ACTIONS) {
                        fail("时钟通道只接受 ${CLOCK_ACTIONS}，实际为 ${spec.action}")
                    }

                else ->
                    if (spec.action == null) fail("缺少执行动作")
            }

            if (spec.risk == RiskTier.HIGH && !spec.needsConfirmation) {
                fail("高风险能力必须要求用户确认")
            }
            if (spec.channel == CapabilityChannel.A11Y || spec.channel == CapabilityChannel.SHELL) {
                if (spec.permission == CapabilityPermission.NONE) fail("该通道必须声明权限需求")
            }
            if (spec.permission == CapabilityPermission.NOTIFICATION && spec.channel != CapabilityChannel.LOCAL) {
                fail("通知使用权能力应在 LOCAL 通道登记")
            }

            val paramNames = spec.params.map { it.name }
            if (paramNames.size != paramNames.toSet().size) fail("参数名重复")
            spec.params.forEach { param ->
                if (param.name.isBlank()) fail("存在空参数名")
                if (param.type == "select") {
                    if (param.options.isEmpty()) fail("参数 ${param.name} 是 select 但没有候选项")
                    param.defaultValue?.let { default ->
                        if (default.isNotBlank() && default !in param.options) {
                            fail("参数 ${param.name} 的默认值 $default 不在候选项里")
                        }
                    }
                }
                if (param.min != null && param.max != null && param.min > param.max) {
                    fail("参数 ${param.name} 的 min > max")
                }
            }
        }

        // 通道覆盖：每个通道都必须有降级声明，且不能是没人使用的死通道
        CapabilityChannel.values().forEach { channel ->
            if (channel.fallback.isBlank()) {
                problems += Problem("channel:${channel.key}", "通道 ${channel.key} 缺少降级策略声明")
            }
            if (specs.none { it.channel == channel }) {
                problems += Problem("channel:${channel.key}", "通道 ${channel.key} 没有任何能力使用（死通道）")
            }
        }

        return problems
    }

    /** 便于单测直接断言「零问题」 */
    fun requireClean(specs: List<CapabilitySpec>, actionHasGoal: (String) -> Boolean) {
        val problems = check(specs, actionHasGoal)
        check(problems.isEmpty()) { "能力约定校验失败：\n" + problems.joinToString("\n") }
    }
}
