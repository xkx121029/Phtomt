package com.phoneagent.engine.coding

import android.content.Context
import com.phoneagent.coding.CodingRuntimeManager
import com.phoneagent.coding.CodingRuntimeState
import com.phoneagent.coding.agent.AgentClient
import com.phoneagent.coding.agent.CodingAgentConfig
import com.phoneagent.coding.agent.ConnState
import com.phoneagent.core.notify.TaskProgressNotifier
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.overlay.AgentStatusStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 编码 agent 编排桥（Superagent 融合 Phase 4）：HPA 主引擎与设备端编码 agent（agent-core 子进程）之间的委托通道。
 *
 * - 启停编排：确认内置运行时就绪（[CodingRuntimeManager]）→ 以 AppSettings 注入的配置经
 *   [AgentClient.connectLocal] 拉起 [com.phoneagent.coding.agent.LocalAgent]（node 子进程，
 *   stdio JSON-lines）→ 等 CONNECTED → [AgentClient.sendPrompt] 委托任务。
 * - 配置注入：provider 固定 "openai"（agent-core 的 OpenAI 兼容通道），baseUrl/apiKey/model 取
 *   HPA 主设置，审批档位取 codingApprovalMode（ask/risky/bypass，默认 risky），token 固定桥接标识。
 * - 进度同步：把 busy/最新 assistant 文本/等待审批同步到 [AgentStatusStore]（状态总线，桌面
 *   Widget/磁贴读这份快照）与 [TaskProgressNotifier]（任务进度通知，total<=0 为不确定进度条）。
 *   审批不做系统弹窗，只经 [client].state 暴露给 UI 渲染内嵌审批卡（项目铁律：系统弹窗与 Toast 清零）。
 *
 * UI 层（Phase 5 CodingScreen）直接消费 [client].state 渲染会话与审批卡；Bridge 只负责"启动/委托/停止"。
 */
class CodingAgentBridge(
    private val appContext: Context,
    private val settings: AppSettings,
    private val runtimeManager: CodingRuntimeManager,
) {

    val client = AgentClient(appContext)

    /**
     * 委托一个编码任务：确保运行时已装 → 连接本地 agent → sendPrompt → 挂起等待会话结束
     * （busy 翻回 false）。在调用方协程（TaskLifecycle 队列 worker 的 job 协程）内挂起，
     * 期间队列里后续任务照常排队等待（worker 单任务语义）。
     */
    suspend fun prompt(text: String) {
        sync("编码中…")
        ensureRuntimeInstalled()
        val s = settings.settings.first()
        val config = CodingAgentConfig(
            provider = "openai",
            baseUrl = s.apiBaseUrl,
            apiKey = s.apiKey,
            model = s.model,
            approval = s.codingApprovalMode,
            token = TOKEN,
        )
        client.connectLocal(config)
        awaitConnected()
        client.sendPrompt(text)
        // 任务进度通知：total<=0 → 不确定进度条（编码时长不可预估）
        TaskProgressNotifier.show(appContext, CODING_TASK_ID, "编码 Agent", 0, 0)
        while (client.state.value.busy) {
            syncCodingProgress()
            delay(POLL_MS)
        }
        TaskProgressNotifier.complete(appContext, CODING_TASK_ID, "编码 Agent", "编码完成")
        sync("编码完成")
    }

    /** 停止当前编码会话（取消子进程当前执行）并清掉进度通知 */
    fun stop() {
        client.cancel()
        TaskProgressNotifier.cancel(appContext, CODING_TASK_ID)
    }

    /** 开启全新编码会话（清空上下文与消息，透传 AgentClient.newSession） */
    fun newSession() {
        client.newSession()
    }

    /** 等待编码 agent 连接就绪；连接失败（带 error）或超时则明确抛错，不让任务无声卡死 */
    private suspend fun awaitConnected() {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (client.state.value.conn != ConnState.CONNECTED) {
            val st = client.state.value
            if (st.conn == ConnState.DISCONNECTED && st.error != null) {
                error("编码 agent 连接失败：${st.error}")
            }
            if (System.currentTimeMillis() > deadline) {
                error("编码 agent 连接超时，请到「设置 → 编码」检查运行时与模型配置")
            }
            delay(POLL_MS)
        }
    }

    /**
     * 确保编码运行时就绪。运行时随主 APK 内置（见 [CodingRuntimeManager]）：
     * 探测一遍即可，未就绪（libnode.so 缺失）直接给出中文错误，不做下载安装。
     */
    private suspend fun ensureRuntimeInstalled() {
        runtimeManager.refresh()
        when (val st = runtimeManager.state.value) {
            is CodingRuntimeState.Installed -> return
            is CodingRuntimeState.NotReady -> error("编码运行时未就绪：${st.reason}")
        }
    }

    /** 把编码会话状态同步到状态总线（busy / 最新 assistant 文本 / 等待审批 / 出错） */
    private fun syncCodingProgress() {
        val st = client.state.value
        val status = when {
            st.messages.any { it.role == "approval" && it.approvalResolved == null } -> "等待审批"
            st.error != null -> "编码出错"
            else -> st.messages.lastOrNull { it.role == "assistant" }?.text?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "编码中…"
        }
        sync(status)
    }

    private fun sync(status: String) {
        AgentStatusStore.update(AgentStatusStore.Snapshot(task = "编码 Agent", status = status))
    }

    private companion object {
        const val TOKEN = "phoneagent-bridge"
        const val POLL_MS = 250L
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** 编码任务进度通知的任务 id（与 ReAct 任务的顺序 id 区隔开，用高位段避免撞号） */
        const val CODING_TASK_ID = 9001L
    }
}
