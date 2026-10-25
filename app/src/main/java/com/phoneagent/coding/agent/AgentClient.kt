package com.phoneagent.coding.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import com.phoneagent.coding.CodingAgentService
import com.phoneagent.coding.device.HostBridgeServer
import java.util.concurrent.atomic.AtomicInteger

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

data class ChatMessage(
    val key: String,
    val role: String, // user | assistant | tool | approval
    val text: String = "",
    val toolName: String? = null,
    val toolOk: Boolean? = null,
    val approvalCallId: String? = null,
    val approvalResolved: Boolean? = null, // null = pending, true = approved, false = denied
)

data class AgentUiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Agent client over a pluggable transport（HPA 只用本地模式：管道连到设备上的
 * agent 进程）。Same JSON-lines protocol both ways.
 */
class AgentClient(private val context: Context) {

    private var transport: AgentTransport? = null
    private var token: String = ""

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var lastRunId: String? = null
    private val seq = AtomicInteger(0)

    @Volatile
    private var assistantKey: String? = null

    /** 上次的连接方式（参数已捕获），用于 host 退出后自动重连。 */
    private var lastConnect: ((Boolean) -> Unit)? = null
    private var reconnectAttempts = 0

    /** 反向通道：Node 侧 agent 请求应用侧能力（工作区 / 编辑器 / 预览）。
     *  入站请求必须带当前配置的 token（见 HostBridgeServer.handle）。 */
    private val hostBridge = HostBridgeServer(context) { token }

    /** 界面主动发起的 RPC 的回调表，按 id 索引；断线时整体以错误回包落空（不能静默丢弃，否则 UI 卡在加载态）。 */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (JSONObject) -> Unit>()

    /**
     * 断线时把所有挂起的界面 RPC 以错误回包收尾。回调内部自带主线程投递，
     * 从读线程/连接线程直接调用也安全——调用方 onResult 能立刻收到失败而不是永远挂起。
     */
    private fun failPending(reason: String) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.let { cb ->
                runCatching { cb(JSONObject().put("id", id).put("type", "error").put("error", reason)) }
            }
        }
    }

    /** 回包统一投到主线程，调用方可以直接改 Compose 状态。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val listener = object : AgentTransport.Listener {
        override fun onOpen() {
            // 连上了就重置重连预算：3 次上限是「连续失败」的上限，
            // 不是「整个生命周期总共 3 次」。
            reconnectAttempts = 0
            update { it.copy(conn = ConnState.CONNECTED, error = null) }
            transport?.send(request("ping", JSONObject()))
        }
        override fun onMessage(text: String) {
            handleMessage(text)
        }

        override fun onClosed(reason: String) {
            update { it.copy(conn = ConnState.DISCONNECTED, busy = false) }
            failPending("连接已断开：$reason")
            // 连接断开要自愈（host 进程被杀很常见）。用户主动断开时
            // lastConnect 已被清空，这里自然不会重连。
            scheduleReconnect()
        }

        override fun onFailure(message: String) {
            update { it.copy(conn = ConnState.DISCONNECTED, busy = false, error = message) }
            failPending(message)
            scheduleReconnect()
        }
    }

    /**
     * 状态更新必须串行：流式 text 事件来自 transport 的读线程，UI 动作用主线程，
     * 无锁的读改写会在流式高峰期丢消息或产生撕裂状态。
     */
    private val stateLock = Any()

    private fun update(block: (AgentUiState) -> AgentUiState) {
        synchronized(stateLock) {
            _state.value = block(_state.value)
        }
    }

    private fun nextId(): String = "c${seq.incrementAndGet()}"

    private fun request(method: String, params: JSONObject): JSONObject = requestWith(nextId(), method, params)

    private fun requestWith(id: String, method: String, params: JSONObject): JSONObject =
        JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params.put("token", token))

    /**
     * 界面直接问后端一次（#31 项目模板这类动作）。与 agent 自己的运行请求共用一条
     * 管道，靠 id 区分回包——两边的 id 前缀不同，不会互相认领。
     *
     * 结果原样透传：rpc 有的返回对象、有的返回数组（比如 project_templates），
     * 在这里统一成 JSONObject 会把数组那一半吃掉。回调统一在主线程投递。
     */
    fun call(method: String, params: JSONObject, onResult: (Any?, String?) -> Unit) {
        val t = transport
        if (t == null) {
            onResult(null, "未连接 agent")
            return
        }
        val id = nextId()
        pending[id] = { reply ->
            val isError = reply.optString("type") == "error"
            val raw = reply.opt("result")?.takeIf { it !== JSONObject.NULL }
            val error = if (isError) reply.optString("error") else null
            main.post { onResult(if (isError) null else raw, error) }
        }
        t.send(requestWith(id, method, params))
    }

    // ---------- connections ----------

    fun connectLocal(config: CodingAgentConfig, keepUi: Boolean = false, auto: Boolean = false) {
        disconnect()
        this.token = config.token
        lastConnect = { keep -> connectLocal(config, keep, auto = true) }
        if (!auto) reconnectAttempts = 0
        update { it.copy(conn = ConnState.CONNECTING, error = null, messages = if (keepUi) it.messages else emptyList()) }
        LocalAgent.start(
            context = context,
            config = config,
            onReady = {
                val t = ProcessTransport()
                t.listener = listener
                transport = t
                t.connect()
            },
            onFailure = { msg ->
                update { it.copy(conn = ConnState.DISCONNECTED, error = msg) }
                scheduleReconnect()
            },
        )
    }

    /**
     * host 进程被杀 / 连接断开之后把 agent 拉回来。messages / sessionId 都保留：
     * 本地模式的会话落在 state 目录里，重连后同一个 sessionId 仍然有效。
     * 计数在 scheduleReconnect 里递增、只在用户主动 connect* 时清零，
     * 所以 3 次上限对自动重连真正生效。
     */
    private fun scheduleReconnect() {
        val again = lastConnect ?: return
        if (reconnectAttempts >= 3) return
        reconnectAttempts += 1
        val attempt = reconnectAttempts
        main.postDelayed({
            if (_state.value.conn != ConnState.CONNECTED) {
                again(true)
            }
        }, 1200L * attempt)
    }

    fun disconnect() {
        // 用户主动断开：清掉重连意图，否则一断开就被自动拉回去。
        lastConnect = null
        reconnectAttempts = 0
        transport?.disconnect()
        transport = null
        failPending("已断开连接")
        update { it.copy(conn = ConnState.DISCONNECTED, busy = false) }
    }

    fun stopLocalAgent() {
        lastConnect = null
        reconnectAttempts = 0
        context.startService(
            android.content.Intent(context, CodingAgentService::class.java).setAction(CodingAgentService.ACTION_STOP),
        )
        LocalAgent.stop()
        failPending("编码 agent 已停止")
        update { it.copy(conn = ConnState.DISCONNECTED, busy = false) }
    }

    // ---------- protocol ----------

    private fun handleMessage(raw: String) {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        // 界面自己发起的调用先认领：它的 id 前缀是 c，agent 的请求是 h，两向不串。
        pending.remove(obj.optString("id"))?.let { cb ->
            cb(obj)
            return
        }
        when (obj.optString("type")) {
            "request" -> {
                // 反向调用：agent 在向应用要设备能力，回包走同一条管道。
                hostBridge.handle(obj) { reply -> transport?.send(reply) }
            }
            "event" -> handleEvent(obj.optJSONObject("event") ?: return)
            "response" -> {
                val result = obj.optJSONObject("result") ?: return
                result.optString("sessionId").takeIf { it.isNotEmpty() }?.let { sessionId = it }
                result.optString("runId").takeIf { it.isNotEmpty() }?.let { lastRunId = it }
            }
            "error" -> update { it.copy(error = obj.optString("error")) }
            else -> Unit
        }
    }

    /**
     * 事件解析（org.json）在 AgentClient，状态映射在 [CodingEventReducer]（纯函数、可 JVM 单测）。
     * 全部字段读取与消息变更仍在 stateLock 内完成，与重构前逐分支等价。
     */
    private fun handleEvent(ev: JSONObject) {
        var reconnect = false
        update { s ->
            val r = CodingEventReducer.apply(
                type = ev.optString("type"),
                messages = s.messages,
                assistantKey = assistantKey,
                nextId = ::nextId,
                delta = ev.optString("delta"),
                name = ev.optString("name"),
                ok = ev.optBoolean("ok"),
                approved = ev.optBoolean("approved", true),
                why = ev.optString("why"),
                callId = ev.optString("callId"),
                tool = ev.optString("tool"),
                code = ev.optInt("code"),
                argsPreview = argsPreview(ev.optJSONObject("arguments")),
            )
            assistantKey = r.assistantKey
            reconnect = r.hostExited
            s.copy(
                messages = r.messages,
                busy = r.busy ?: s.busy,
                conn = r.conn ?: s.conn,
                error = r.error ?: s.error,
            )
        }
        if (reconnect) scheduleReconnect()
    }

    /** 工具参数摘要：给 UI 一行看得懂的东西（"path=src/app.js, content=…"）。 */
    private fun argsPreview(args: JSONObject?): String {
        if (args == null || args.length() == 0) return ""
        val parts = mutableListOf<String>()
        val keys = args.keys()
        while (keys.hasNext() && parts.size < 2) {
            val k = keys.next()
            val raw = args.opt(k)?.toString()?.replace("\n", " ") ?: continue
            parts += "$k=${if (raw.length > 48) raw.take(48) + "…" else raw}"
        }
        return parts.joinToString(", ")
    }

    // ---------- actions ----------
    fun sendPrompt(text: String) {
        val t = transport ?: return update { it.copy(error = "not connected") }
        update { it.copy(messages = it.messages + ChatMessage(key = "u_${nextId()}", role = "user", text = text), busy = true, error = null) }
        // 带上 sessionId 才有对话连续性；为空时 host 会新建一个并在 response 里回传。
        t.send(request("prompt", JSONObject().put("text", text).put("sessionId", sessionId ?: "")))
    }

    fun respondApproval(callId: String, approved: Boolean) {
        transport?.send(request("approve", JSONObject().put("callId", callId).put("approved", approved)))
        update { s ->
            val idx = s.messages.indexOfFirst { it.approvalCallId == callId }
            val msgs = if (idx >= 0) s.messages.toMutableList().also { it[idx] = it[idx].copy(approvalResolved = approved) } else s.messages
            s.copy(messages = msgs)
        }
    }

    fun cancel() {
        val t = transport ?: return update { it.copy(error = "not connected") }
        // host 按 sessionId 查 abortController；不带 sessionId 的 cancel 会被当成空操作，
        // 用户以为停了，模型其实还在跑、还在烧 token。
        t.send(request("cancel", JSONObject().put("sessionId", sessionId ?: "")))
    }

    fun undoLastRun() {
        val run = lastRunId ?: return update { it.copy(error = "nothing to undo yet") }
        transport?.send(request("undo", JSONObject().put("sessionId", sessionId ?: "").put("runId", run)))
        update { it.copy(error = null) }
    }

    fun newSession() {
        transport?.send(request("new_session", JSONObject()))
        assistantKey = null
        sessionId = null
        lastRunId = null
        update { it.copy(messages = emptyList(), error = null) }
    }
}
