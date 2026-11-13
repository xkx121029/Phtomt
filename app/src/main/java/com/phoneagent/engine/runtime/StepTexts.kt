package com.phoneagent.engine.runtime

import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.domain.model.StepRecord
import com.phoneagent.engine.PromptLang

/**
 * 每轮决策上下文里的「文本拼装」纯函数（无 Android / 无协程依赖，可单测）。
 *
 * 这些文本直接进入提示词，改一个字都可能改变模型行为，因此从 AgentEngine 的
 * 三千行主类里下沉出来：拼装规则集中一处、可被单测钉住，主类只负责取数。
 */
internal object StepTexts {

    /**
     * 上一步执行结果文本（✅/⚠️/❌），供决策 Prompt 使用。
     * 必须跟随提示词语言：英文提示词的三态段写作 verified / sent but unverified / failed，
     * 若这里仍回中文，模型得自己做一次中英映射，等于人为制造歧义。
     */
    fun lastStepResult(history: List<StepRecord>, lang: PromptLang): String {
        val last = history.lastOrNull() ?: return ""
        val action = last.action?.run { "${type}${reason?.let { "($it)" } ?: ""}" } ?: "-"
        // detail 是执行层给出的「为什么成/不成」（如「回读 40%，期望 50%」）。
        // 只回注 type(reason) 时模型看不到失败原因，下一轮只能瞎试；这里原样带上。
        val detail = last.detail.trim()
        val fact = if (detail.isEmpty()) action else "$action；结果：$detail"
        val cn = lang == PromptLang.CN
        return when {
            last.isConfirmed -> if (cn) "✅ 已确认成功: $fact" else "✅ verified: $fact"
            last.verificationResult == "failed" -> if (cn) "❌ 未生效: $fact" else "❌ failed: $fact"
            else -> if (cn) "⚠️ 已发送但未确认: $fact" else "⚠️ sent but unverified: $fact"
        }
    }

    /** 渲染任务记忆，注入每轮决策上下文（目标与用户要求是固定部分，进度是滚动的） */
    fun taskMemory(mem: TaskMemoryEntry?, completedSteps: Int, totalPlannedSteps: Int): String {
        if (mem == null) return ""
        val sb = StringBuilder()
        sb.append("\n## 任务记忆（本次任务的既定目标与用户要求，全程不可偏离）")
        sb.append("\n目标：${mem.goal}")
        if (mem.requirements.isNotEmpty()) {
            sb.append("\n用户要求：")
            mem.requirements.forEachIndexed { i, r -> sb.append("\n${i + 1}. $r") }
        }
        if (completedSteps > 0) {
            val total = if (totalPlannedSteps > 0) "/$totalPlannedSteps" else ""
            val stage = if (totalPlannedSteps > 0) {
                val totalStages = (totalPlannedSteps + EngineLimits.STAGE_SIZE - 1) / EngineLimits.STAGE_SIZE
                val cur = ((completedSteps + EngineLimits.STAGE_SIZE - 1) / EngineLimits.STAGE_SIZE)
                    .coerceIn(1, totalStages)
                " 阶段 $cur/$totalStages。"
            } else ""
            sb.append("\n## 执行进度（概览即可，勿重复执行已完成步骤）")
            sb.append("\n已完成 ${completedSteps}${total} 步。${stage}")
            if (mem.methods.isNotEmpty()) {
                sb.append("\n已验证有效的做法：")
                mem.methods.forEachIndexed { i, m -> sb.append("\n${i + 1}. $m") }
            }
        }
        return sb.toString()
    }
}
