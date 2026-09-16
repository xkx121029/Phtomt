package com.phoneagent.engine.network.CloudAgent

import com.phoneagent.core.ai.AiClient
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.core.ai.ContentPart
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.engine.execution.VerifyResult

/**
 * 云端 Agent 接口补充：执行验证 / 用户指导。
 * 对应文档“第 6 层 网络层 / CloudAgent 接口”中可被决策 schema 直接支撑的方法。
 */
class CloudAgent(private val aiClient: AiClient) {

    /** 执行验证：对比执行前后指纹，判断动作是否生效 */
    suspend fun verify(before: String, after: String): VerifyResult {
        val success = before != after && after.isNotBlank()
        return VerifyResult(success, if (success) "页面已变化" else "页面未变化", before, after)
    }

    /** 用户指导：结合用户提示重新决策 */
    suspend fun decideWithUserHint(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        userHint: String,
        onDelta: (String) -> Unit = {},
    ): Result<AgentIntent> {
        val hintMsg = ChatMessageDto(
            role = "user",
            content = listOf(ContentPart(type = "text", text = "用户提示：$userHint\n请据此重新决策下一步动作。" )),
        )
        return aiClient.chatForAction(baseUrl, apiKey, model, messages + hintMsg, null, 0.15, onDelta = onDelta).map { it.action }
    }
}