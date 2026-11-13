package com.phoneagent.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 任务计划：由云端规划生成的多步分解 */
@Serializable
data class TaskPlan(
    val steps: List<TaskStep> = emptyList(),
    val estimatedTimeSeconds: Int = 0,
    val confidence: Double = 0.0,
)

/** 计划中的一步 */
@Serializable
data class TaskStep(
    val description: String,
    val intent: String = "",
)

/**
 * 从 JSON 数组解析步骤列表，兼容多种 AI 输出格式：
 * - 字符串：["点击搜索框", "输入关键词"]
 * - 对象：{"description":"点击搜索框","intent":"打开搜索"}
 * - 对象：{"step":1,"action":"点击搜索框"}  （step 为序号，action 为描述）
 * - 对象：{"text":"点击搜索框"}
 */
fun parseTaskSteps(json: kotlinx.serialization.json.JsonArray): List<TaskStep> {
    return json.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> TaskStep(description = el.contentOrNull ?: "")
            is JsonObject -> {
                // 优先 description，其次 action，step 仅当为字符串时才用（数字 = 序号）
                val desc = el["description"]?.jsonPrimitive?.contentOrNull
                    ?: el["action"]?.jsonPrimitive?.contentOrNull
                    ?: el["text"]?.jsonPrimitive?.contentOrNull
                    ?: el["step"]?.let { s ->
                        // step 为字符串时作为描述，数字时跳过
                        val sv = s.jsonPrimitive.contentOrNull
                        if (sv != null && sv.toIntOrNull() == null) sv else null
                    }
                    ?: return@mapNotNull null
                val intent = el["intent"]?.jsonPrimitive?.contentOrNull
                    ?: el["expected"]?.jsonPrimitive?.contentOrNull
                    ?: el["expected_result"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                TaskStep(description = desc, intent = intent)
            }
            else -> null
        }
    }
}

/** 步骤执行记录（执行历史） */
data class StepRecord(
    val step: Int,
    /**
     * 所属任务 ID（-1 表示无归属）。
     * 调试页「执行流」按一次次执行组织数据，必须靠 taskId 把执行层记录（本类）
     * 与决策层记录（StepTrace）归到同一次执行里；旧数据没有该字段时回退为 -1。
     */
    val taskId: Long = -1,
    /** 所属任务描述 */
    val taskName: String? = null,
    val action: AgentAction? = null,
    /** 发送结果：sent / send_failed */
    val sendResult: String = "sent",
    /** 验证结果：verified_success / unverified / failed */
    val verificationResult: String = "unverified",
    val beforeFingerprint: String = "",
    val afterFingerprint: String = "",
    val durationMs: Long = 0,
    val isConfirmed: Boolean = false,
    /** 该步 shell 命令的执行输出（成功为 stdout/stderr，失败为可读原因）；供 Agent 页向用户回显 */
    val shellOutput: String = "",
    /**
     * 执行/转译的结果说明（成功为执行层说明，失败为可读原因）。
     * 与 [shellOutput] 的区别：这里是"这一步为什么成/不成"，覆盖所有动作类型，
     * 让「未生效」不再是一句没有解释的结论。
     */
    val detail: String = "",
)

/** 执行结果 */
data class ExecutionResult(
    val success: Boolean,
    val detail: String = "",
    val durationMs: Long = 0,
)

/** 接管恢复结果 */
data class RecoveryResult(
    val stepIndex: Int = -1,
    val confidence: Double = 0.0,
    val reason: String = "",
)

/** 澄清选项 */
@Serializable
data class ClarificationOption(
    val id: String = "",
    val label: String = "",
    val description: String = "",
    val isDefault: Boolean = false,
)

/** 歧义澄清 */
@Serializable
data class Clarification(
    val question: String = "",
    val options: List<ClarificationOption> = emptyList(),
)

/** AI 规划响应：无歧义返回计划，有歧义返回澄清 */
@Serializable
data class PlanResponse(
    val needs_clarification: Boolean = false,
    val plan: TaskPlan? = null,
    val clarification: Clarification? = null,
)