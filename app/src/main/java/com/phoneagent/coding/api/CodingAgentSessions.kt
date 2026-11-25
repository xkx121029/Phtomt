package com.phoneagent.coding.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 一条编码 agent 连接的窄端口：只留编排桥真正会用的五个意图。
 *
 * 具体的 JSON-lines 管道、事件归约、重连预算都在切片内部（`coding.agent.AgentClient`），
 * 桥拿到的是这一份契约，因此不再 import 具体连接类。
 */
interface CodingAgentSession {

    /** 连接阶段 / 忙闲 / 出错 / 等待审批：编排桥轮询它来同步进度 */
    val status: StateFlow<CodingSessionStatus>

    /** 拉起本地 agent 并连上；[keepUi] 为 true 时保留已装回的历史消息 */
    fun connectLocal(config: CodingAgentConfig, keepUi: Boolean = false)

    /** 断开当前连接并清掉自动重连意图 */
    fun disconnect()

    /** 开一个全新会话（清空上下文与消息） */
    fun newSession()

    /** 委托一轮对话 */
    fun sendPrompt(text: String)

    /** 取消子进程里正在跑的这一轮 */
    fun cancel()
}

/**
 * 编码页那条连接：会话能力 + 页面门户。
 *
 * 页面侧只需要 [CodingSessionPortal] 的只读快照与意图方法，桥侧还要能连接与断开，
 * 两件事落在同一个对象上，所以这里把两份契约合起来。
 */
interface CodingPageSession : CodingAgentSession, CodingSessionPortal

/**
 * 宿主桥共享密钥的解析结果。
 *
 * [fresh] = 这次解析**新生成**了密钥，调用方据此处置已经在跑的宿主进程
 * （宿主手里还是旧值，它发来的每条反向请求都会被拒）。
 */
data class CodingBridgeToken(val token: String, val fresh: Boolean)

/**
 * 编码切片的会话工厂：宿主侧拿到的全部编码连接都从这里来。
 *
 * 引擎与编码页是两条独立连接、共享同一个宿主进程，事件按 sessionId 分流；
 * 具体实现（谁 new 的连接、密钥怎么落盘）由 app 的组合根装配，桥不再认识它们。
 */
interface CodingSessions {

    /** 后台编码引擎的连接（固定引擎会话，与编码页完全隔离） */
    val engine: CodingAgentSession

    /** 编码页的连接（同时是 [CodingSessionPortal]） */
    val page: CodingPageSession

    /** 每装机共享密钥：读存储 → 可用就复用，不可用就生成并落盘 */
    suspend fun ensureToken(): CodingBridgeToken
}
