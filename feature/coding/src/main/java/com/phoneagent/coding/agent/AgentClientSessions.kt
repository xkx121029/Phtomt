package com.phoneagent.coding.agent

import android.content.Context
import com.phoneagent.coding.api.CodingAgentSession
import com.phoneagent.coding.api.CodingBridgeToken
import com.phoneagent.coding.api.CodingPageSession
import com.phoneagent.coding.api.CodingSessions
import com.phoneagent.coding.device.HostBridgeTokenProvider
import com.phoneagent.coding.env.CodingEnvBootstrap

/**
 * 编码切片对宿主暴露的会话工厂实现（依赖由 app 的组合根 `di.AppModule` 装配）。
 *
 * 引擎与编码页各一条连接、共享同一个宿主进程：事件按 sessionId 分流，两边的对话互不串台。
 * 引擎会话 id 带进程时间戳——每次冷启动都是一套干净上下文（与改造前一致），
 * 同时又能和编码页的会话彻底分开。
 *
 * **为什么是 public 而不是 internal**：装配它的 `di.AppModule` 住在 `:app`，而模块化 P3 起
 * 本类在 `:feature:coding`——跨模块的 `internal` 不可见。放开可见性是这次搬移唯一的
 * 接口改动，行为一行未动（与 `:ui:design` 放开三个符号同一处置）。
 */
class AgentClientSessions(
    private val context: Context,
    /** env_* 能力的来源；宿主没接编码环境时为 null */
    private val envBootstrapProvider: () -> CodingEnvBootstrap? = { null },
    private val tokenProvider: HostBridgeTokenProvider,
) : CodingSessions {

    private val engineSessionId = "engine_${System.currentTimeMillis().toString(36)}"

    override val engine: CodingAgentSession = AgentClient(
        context,
        fixedSessionId = engineSessionId,
        envBootstrapProvider = envBootstrapProvider,
    )

    override val page: CodingPageSession = AgentClient(
        context,
        envBootstrapProvider = envBootstrapProvider,
    )

    override suspend fun ensureToken(): CodingBridgeToken = tokenProvider.ensure().let {
        CodingBridgeToken(token = it.token, fresh = it.fresh)
    }
}
