package com.phoneagent.engine.skill

import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentState
import com.phoneagent.domain.model.ActionType
import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.execution.VerifyRules
import com.phoneagent.engine.runtime.EngineLimits.MAX_MCP_OUTPUT
import com.phoneagent.engine.runtime.EngineLimits.MCP_CALL_RETRY_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineLimits.MCP_CALL_TIMEOUT_MS
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import com.phoneagent.feature.skill.SkillCallHistory
import com.phoneagent.feature.skill.SkillCompat
import com.phoneagent.feature.skill.SkillExecutionGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 引擎技能执行器（重构批次 6）：技能提示词区块、宏接力派发、MCP 技能调用（含瞬时故障重试）、
 * 缺参自动推断。从 AgentEngine 原样迁入，行为不变。
 *
 * 与主循环的接缝：步骤留档与进展记录经 [recordStep] / [recordProgress] 钩子回调引擎
 * （这两个函数与执行历史/任务记忆强耦合，留在引擎侧）。
 */
internal class SkillRunner(
    private val store: EngineStore,
    private val ui: UiBridge,
    private val skillGateway: SkillExecutionGateway?,
    private val skillCallHistory: SkillCallHistory,
    /** 引擎钩子：步骤留档（AgentEngine.recordStep） */
    private val recordStep: (step: Int, action: AgentAction, verification: String, before: String, after: String, detail: String) -> Unit,
    /** 引擎钩子：步骤进展记录（AgentEngine.recordProgress） */
    private val recordProgress: (step: Int, action: AgentAction) -> Unit,
) {
    /**
     * 生成注入系统提示的技能区块：**只列出真正可被调用的技能**。
     *
     * 与旧实现（罗列所有服务器工具）的区别：MCP 工具必须先绑定为技能才可调用，
     * 因此这里只列注册表里 source=MCP 且已启用的技能——提示词与可执行能力严格一一对应，
     * AI 不会去调一个端侧根本不认识的工具。无技能/无服务器时返回空串，不增加负担。
     */
    suspend fun skillPromptText(): String {
        val gateway = skillGateway ?: return ""
        val mcpLines = gateway.enabledMcpSkills().map { gateway.mcpSkillLine(it) }
        val macroLines = gateway.enabledMacroSkills().filter { it.steps.isNotEmpty() }.map { gateway.macroSkillLine(it) }
        val disabled = gateway.disabledSkills().map { it.name }
        if (mcpLines.isEmpty() && macroLines.isEmpty() && disabled.isEmpty() && !gateway.hasEnabledMcpServer()) return ""
        // 技能调用教训：全局最近 2 条失败留档（跨技能），帮助 AI 规避同样失败；读取失败静默跳过
        val historyHint = runCatching {
            com.phoneagent.feature.skill.SkillCallOps.summarize(
                skillCallHistory.all().let { com.phoneagent.feature.skill.SkillCallOps.recentFailures(it, 2) },
            )
        }.getOrElse { "" }
        return AgentPrompts.skillSection(
            lang = store.currentLang,
            mcpLines = mcpLines,
            disabledNames = disabled,
            hasMcpServer = gateway.hasEnabledMcpServer(),
            historyHint = historyHint,
            macroLines = macroLines,
        )
    }

    /**
     * 宏启动前的整宏预检：每一步引用的技能必须存在且启用（宏不可嵌套引用宏），
     * 意图类步骤还必须过当前动作模式门控——**任一步被拒整宏不启动**，
     * 避免"跑了一半才发现后面的步骤根本不允许执行"的半途而废。
     * @return 拒绝原因（中文，可直接回注 AI）；null = 预检通过
     */
    fun macroPrecheckReason(skill: com.phoneagent.feature.skill.Skill): String? {
        skill.steps.forEachIndexed { i, st ->
            val target = skillGateway?.findStepSkill(st)
                ?: return "宏「${skill.name}」第 ${i + 1} 步引用的技能「${st.skillId}」不存在，宏无法启动。"
            if (!target.enabled) {
                return "宏「${skill.name}」第 ${i + 1} 步引用的技能「${target.name}」已停用，宏无法启动。"
            }
            when (target.source) {
                com.phoneagent.feature.skill.SkillSource.MACRO ->
                    return "宏「${skill.name}」第 ${i + 1} 步引用了另一个宏：宏不允许嵌套调用。"
                com.phoneagent.feature.skill.SkillSource.INTENT -> {
                    val legacy = target.legacyIntent ?: target.id.removePrefix("skill_")
                    val verdict = ActionPolicy.allows(store.activeActionMode, legacy)
                    if (verdict is ActionPolicy.Verdict.Denied) {
                        return "宏「${skill.name}」第 ${i + 1} 步（${target.name}）被拒绝：${verdict.reason}。请让用户放宽动作模式后再试。"
                    }
                }
                com.phoneagent.feature.skill.SkillSource.MCP -> Unit // MCP 步骤是数据调用，不产生设备动作，无需模式门控
            }
        }
        return null
    }

    /**
     * 宏接力派发：取出下一步并展开为待执行意图（intent=目标技能 id，args=占位符展开后的参数）。
     *
     * - 派发前先收割上一步的执行结果（执行历史末条），供后续步骤 `{{stepN}}` 引用；
     * - **失败即停**：上一步明确失败（发送失败/验证失败）时终止宏，清空状态并回注说明，
     *   后续步骤不再执行，交还 AI 决策循环决定是否换路重试；
     * - 返回 null 表示宏已全部执行完（成功收尾说明已回注）或已终止（原因已回注）。
     */
    fun nextMacroIntent(messages: MutableList<ChatMessageDto>): AgentIntent? {
        // 1. 收割上一步结果：macroDispatched > 0 说明上一步已经走完一轮完整执行链路
        if (store.macroDispatched > 0) {
            val last = store.executionHistory.value.lastOrNull()
            val resultText = listOfNotNull(
                last?.detail?.takeIf { it.isNotBlank() },
                last?.shellOutput?.takeIf { it.isNotBlank() },
            ).joinToString("；").ifBlank { last?.action?.reason ?: "" }
            store.macroResults = store.macroResults + resultText.take(400)
            // 失败即停：上一步明确失败就不再派发后续步骤（unverified 不算失败——部分动作本就无验证信号）
            val failed = last != null &&
                (last.sendResult == "send_failed" || last.verificationResult == "failed")
            if (failed) {
                val reason = "宏「${store.macroName}」在第 ${store.macroDispatched}/${store.macroDispatched + store.macroQueue.size} 步失败：${resultText.take(200)}。剩余 ${store.macroQueue.size} 步已停止，请判断是否换方式继续任务。"
                ui.log(AgentLog.Level.WARN, reason)
                messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "⚠️ $reason"))))
                store.macroQueue = emptyList()
                store.macroName = ""
                return null
            }
        }
        // 2. 全部步骤执行完毕：成功收尾
        val next = store.macroQueue.firstOrNull() ?: run {
            val done = "宏「${store.macroName}」已按顺序执行完全部 ${store.macroDispatched} 步。"
            ui.log(AgentLog.Level.INFO, done)
            messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = "✅ $done 请根据当前页面判断任务是否达成；未达成时继续执行剩余步骤。"))))
            store.macroName = ""
            return null
        }
        // 3. 派发下一步：占位符展开 → intent=目标技能 id（归一化层会按 source 分流 INTENT/MCP）
        store.macroQueue = store.macroQueue.drop(1)
        store.macroDispatched++
        val expanded = com.phoneagent.feature.skill.MacroStepExpander.expand(next.args, store.macroArgs, store.macroResults)
        val stepSkill = skillGateway?.findStepSkill(next)
        val label = stepSkill?.name ?: next.skillId
        store.state.value = store.state.value.copy(phase = AgentState.Phase.ACTING, message = "宏「${store.macroName}」第 ${store.macroDispatched} 步：$label")
        ui.pushFloating("宏第 ${store.macroDispatched} 步：$label", "ACTING")
        return AgentIntent(
            intent = next.skillId,
            args = expanded,
            reasoning = "宏「${store.macroName}」第 ${store.macroDispatched} 步" + (next.description.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""),
        )
    }

    /**
     * 执行一次 MCP 技能调用：就地调用远端工具，并把返回内容作为"上一步结果"注入下一轮决策上下文。
     *
     * 与 remember 同类——端侧代办、不触碰设备，因此不截图、不走执行通道、不做生效重试；
     * 本步只留档一条 StepRecord（Agent 页可见），随后进入下一轮由 AI 依据返回内容继续决策。
     */
    suspend fun invokeMcpSkill(
        step: Int,
        mcp: SkillCompat.Normalized.Mcp,
        messages: MutableList<ChatMessageDto>,
    ) {
        val gateway = skillGateway ?: return
        val label = mcp.skill.name
        val target = "${mcp.target.server}/${mcp.target.tool}"
        ui.log(AgentLog.Level.INFO, "调用 MCP 技能：$label（$target）参数=${mcp.args}")
        store.state.value = store.state.value.copy(phase = AgentState.Phase.ACTING, message = "调用技能：$label")
        ui.pushFloating("调用技能：$label", "ACTING")
        // 远端服务不可达时不能让主循环干等：与单步决策同一思路，超时即按失败回注，让 AI 自己改路线。
        // 健壮性增强：瞬时故障（超时/连接失败/5xx）按 McpRetryPolicy 自动重试一次（首试 20s、重试 10s）；
        // 参数错/未配置这类确定性失败不重试。失败语义不变：结果照旧回注 AI，不计 skillErrorStreak。
        val callStart = System.currentTimeMillis()
        val result = invokeMcpWithRetry(mcp.target, mcp.args, target)
        val elapsedMs = System.currentTimeMillis() - callStart
        // 留档本次调用（成功/失败都记）：供缺参自动推断取历史成功参数、供提示词注入「上次教训」
        store.lastSkillOutput = result.content.trim().take(MAX_MCP_OUTPUT)
        skillCallHistory.recordAsync(
            com.phoneagent.feature.skill.SkillCallRecord(
                id = "${store.currentTaskId}-$step-${System.currentTimeMillis()}",
                skillId = mcp.skill.id,
                skillName = mcp.skill.name,
                argsJson = runCatching {
                    kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.serializer<Map<String, String>>(),
                        mcp.args,
                    )
                }.getOrElse { "{}" },
                success = !result.isError,
                elapsedMs = elapsedMs,
                failReason = if (result.isError) result.content.trim().take(160) else "",
                taskId = store.currentTaskId,
                ts = System.currentTimeMillis(),
            ),
        )
        val text = result.content.trim().take(MAX_MCP_OUTPUT).let { if (it.length == MAX_MCP_OUTPUT && it.last().isHighSurrogate()) it.dropLast(1) else it }
        val action = AgentAction(
            type = ActionType.MCP_CALL,
            reasoning = mcp.skill.description.take(60).ifBlank { label },
            reason = "MCP 技能：$label",
            confidence = 0.9,
        )
        recordStep(
            step,
            action,
            if (result.isError) VerifyRules.STATUS_UNVERIFIED else VerifyRules.STATUS_VERIFIED,
            "",
            "",
            if (result.isError) text.ifBlank { "MCP 调用失败" } else "MCP 返回：${text.ifBlank { "（空）" }}",
        )
        // 回注下一轮决策上下文：调用结果即"上一步结果"，AI 据此判断目标是否达成
        val injected = if (result.isError) {
            "技能「$label」调用失败：${text.ifBlank { "（无返回内容）" }}"
        } else {
            "技能「$label」返回内容：\n${text.ifBlank { "（空结果）" }}"
        }
        messages.add(ChatMessageDto(role = "user", content = listOf(ContentPart(type = "text", text = injected))))
        ui.addConversation("assistant", injected)
        if (result.isError) {
            ui.log(AgentLog.Level.WARN, "MCP 技能调用失败：$label → $text")
            ui.pushFloating("技能调用失败：$label", "ERROR")
        } else {
            ui.log(AgentLog.Level.INFO, "MCP 技能返回：${text.take(200)}")
            ui.pushFloating("技能已返回：$label", "THINKING")
            recordProgress(step, action)
        }
        delay(200)
    }

    /**
     * 带瞬时故障重试的 MCP 调用：按 McpRetryPolicy 判定，瞬时故障（超时/连接失败/5xx）自动重试一次。
     * 首试 20s、重试 10s，最坏总等待 ≤ 31s 不放大用户等待；
     * 确定性失败（缺参/未配置/4xx/权限）不重试，直接把原因交回主循环按既有失败链路回注 AI。
     */
    private suspend fun invokeMcpWithRetry(
        target: com.phoneagent.feature.skill.McpSkillTarget,
        args: Map<String, String>,
        label: String,
    ): com.phoneagent.feature.mcp.McpCallResult {
        val gateway = skillGateway ?: return com.phoneagent.feature.mcp.McpCallResult(
            isError = true,
            content = "MCP 未配置：技能「$label」无法调用",
        )
        var attempt = 0
        while (true) {
            val timeoutMs = if (attempt == 0) MCP_CALL_TIMEOUT_MS else MCP_CALL_RETRY_TIMEOUT_MS
            val r = withTimeoutOrNull(timeoutMs) { gateway.invokeMcp(target, args) }
            if (r == null) {
                // 端侧超时：还有重试额度就退避后重试，否则按超时失败返回
                if (attempt >= com.phoneagent.feature.mcp.McpRetryPolicy.MAX_RETRY) {
                    return com.phoneagent.feature.mcp.McpCallResult(
                        isError = true,
                        content = "MCP 调用超时（${timeoutMs / 1000}s）：$label",
                    )
                }
                ui.log(AgentLog.Level.WARN, "MCP 调用超时（${timeoutMs / 1000}s），自动重试 ${attempt + 1}/1：$label")
            } else {
                if (!r.isError) return r
                if (attempt >= com.phoneagent.feature.mcp.McpRetryPolicy.MAX_RETRY ||
                    !com.phoneagent.feature.mcp.McpRetryPolicy.shouldRetry(r.content, timedOut = false)
                ) {
                    return r
                }
                ui.log(AgentLog.Level.WARN, "MCP 瞬时故障，自动重试 ${attempt + 1}/1：$label → ${r.content.take(120)}")
            }
            attempt++
            delay(com.phoneagent.feature.mcp.McpRetryPolicy.BACKOFF_MS)
        }
    }

    /**
     * 把「可能抛异常」的调用降级为 null，但显式放行协程取消。
     * 与 AgentEngine.tryOrNull 同款（拆分的最小重复副本）：runCatching 会吞掉
     * CancellationException，导致 stop() 打不断任务，故必须用这个兜底而不是 runCatching。
     */
    private inline fun <T> tryOrNull(message: String, block: () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ui.log(AgentLog.Level.WARN, "$message：${e.message ?: e.javaClass.simpleName}")
            null
        }

    /**
     * 缺参自动推断：技能因缺必填参数被拒时，端侧先从（历史成功参数 / 上一轮技能与 shell 结果 /
     * 任务原文 / 页面文本）推断补全后重归一化；推断命中就避免一轮向 AI 的补充追问。
     * 返回重归一化结果；推断不出或补全后仍被拒返回 null（走原失败链路计数）。
     */
    suspend fun inferMissingArgs(
        intent: com.phoneagent.domain.model.AgentIntent,
        semanticSnapshot: com.phoneagent.domain.model.ScreenSnapshot?,
    ): SkillCompat.Normalized? {
        val gateway = skillGateway ?: return null
        val (skill, missing) = gateway.missingParamsOf(intent) ?: return null
        val history = runCatching { skillCallHistory.recentFor(skill.id, 3) }.getOrElse { emptyList() }
        val screenTexts = semanticSnapshot?.elements
            ?.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .orEmpty()
            .distinct()
        val filled = com.phoneagent.feature.skill.SkillParamInferencer.infer(
            com.phoneagent.feature.skill.SkillInferContext(
                skill = skill,
                missing = missing,
                taskPrompt = store.currentTaskName ?: "",
                lastResultText = listOf(store.lastSkillOutput, store.lastShellOutput).filter { it.isNotBlank() }.joinToString("\n"),
                screenTexts = screenTexts,
                history = history,
            ),
        )
        if (filled.isEmpty()) return null
        ui.log(
            AgentLog.Level.INFO,
            "缺参自动推断命中：技能「${skill.name}」补全 ${filled.keys.joinToString("、")}（来源：历史/上一步结果/任务原文/页面文本）",
        )
        ui.pushFloating("已自动补全参数：${filled.keys.joinToString("、")}", "THINKING")
        val refilled = intent.copy(args = (intent.args ?: emptyMap()) + filled)
        return tryOrNull("补参后重归一化失败") { gateway.normalize(refilled) }
    }
}
