package com.phoneagent.network

import com.phoneagent.ai.AiClient
import com.phoneagent.ai.ChatMessageDto
import com.phoneagent.ai.ContentPart
import com.phoneagent.model.AgentAction
import com.phoneagent.execution.VerifyResult

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
    ): Result<AgentAction> {
        val hintMsg = ChatMessageDto(
            role = "user",
            content = listOf(ContentPart(type = "text", text = "用户提示：$userHint\n请据此重新决策下一步动作。" )),
        )
        return aiClient.chatForAction(baseUrl, apiKey, model, messages + hintMsg, null, 0.15).map { it.action }
    }
}