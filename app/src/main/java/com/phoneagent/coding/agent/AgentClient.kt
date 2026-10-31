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
    val role: String, // user | assistant | tool | approval | subagent
    val text: String = "",
    val toolName: String? = null,
    val toolOk: Boolean? = null,
    val approvalCallId: String? = null,
    val approvalResolved: Boolean? = null, // null = pending, true = approved, false = denied
    /** 工具调用 id：并行调用同名工具时（如两个 read_file）按 id 匹配，不能再按名字猜。 */
    val toolCallId: String? = null,
    /** 工具/子 agent 执行耗时（毫秒），tool_end 回填后 UI 直接显示。 */
    val durationMs: Long? = null,
    /** 子 agent 标识与名称：并行子 agent 的进度卡按这个分组。 */
    val subagentId: String? = null,
    val subagentLabel: String? = null,
    /** 子 agent 人设名（实现者 / 审查者 / 测试员 …），未指定人设的老事件为 null。 */
    val subagentPersona: String? = null,
)

data class AgentUiState(
    val conn: ConnState = ConnState.DISCONNECTED,
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** 当前阶段（等待模型 / 接收输出 / 执行工具…）。空串表示无阶段。 */
    val phase: String = "",
    /** 当前第几轮工具循环（0 = 还没开始）。 */
    val iteration: Int = 0,
    /** 本轮开始时间：界面据此跑本地计时器，不依赖心跳频率。 */
    val runStartedAt: Long? = null,
)

/**
 * Agent client over a pluggable transport（HPA 只用本地模式：管道连到设备上的
 * agent 进程）。Same JSON-lines protocol both ways.
 *
 * 编码页与后台编码引擎现在是两条独立连接：同一台 host 进程里跑多个会话，
 * 事件会广播给所有连接，所以每条连接要用 [fixedSessionId]（引擎固定为 engine 会话）
 * 或自己持有的会话 id 过滤掉别人的流式事件，否则两个界面会互相串台。
 */
class AgentClient(
    private val context: Context,
    private val fixedSessionId: String? = null,
) {

    private var transport: AgentTransport? = null
    private var token: String = ""

    @Volatile
    private var sessionId: String? = fixedSessionId

    /** 当前会话 id（编码页用它把消息落盘到对应会话） */
    val currentSessionId: String? get() = sessionId

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
                // 界面上可能有两条连接（引擎 + 编码页），同一个请求只处理一次，
                // 否则两边都会执行并各回一份包。
                if (claimReverseRequest(obj.optString("id"))) {
                    hostBridge.handle(obj) { reply -> transport?.send(reply) }
                }
            }
            "event" -> {
                val ev = obj.optJSONObject("event") ?: return
                // 多会话广播：只认自己这条连接的会话，避免引擎与编码页互相串台
                val evSession = ev.optString("sessionId")
                if (evSession.isNotEmpty() && sessionId != null && evSession != sessionId) return
                handleEvent(ev)
            }
            "response" -> {
                val result = obj.optJSONObject("result") ?: return
                // 固定会话（引擎）不接受 host 回传的其它 sessionId
                result.optString("sessionId").takeIf { it.isNotEmpty() && fixedSessionId == null }
                    ?.let { sessionId = it }
                result.optString("runId").takeIf { it.isNotEmpty() }?.let { lastRunId = it }
            }
            "error" -> update { it.copy(error = obj.optString("error")) }
            else -> Unit
        }
    }

    companion object {
        /** 已处理过的反向请求 id：两条连接共享，保证一次请求只执行一次。 */
        private val handledReverse = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
        )

        private fun claimReverseRequest(id: String): Boolean {
            if (id.isEmpty()) return true
            if (handledReverse.size > 512) handledReverse.clear()
            return handledReverse.add(id)
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
                durationMs = ev.optLong("durationMs").takeIf { it > 0 } ?: ev.optLong("elapsedMs"),
                subagentId = ev.optString("subagentId"),
                subagentLabel = ev.optString("label"),
                subagentPersona = ev.optString("personaName"),
                promptPreview = ev.optString("promptPreview"),
                iteration = ev.optInt("index"),
                errorDetail = ev.optString("error"),
            )
            assistantKey = r.assistantKey
            reconnect = r.hostExited
            s.copy(
                messages = r.messages,
                busy = r.busy ?: s.busy,
                conn = r.conn ?: s.conn,
                error = r.error ?: s.error,
                // 空串是 reducer 的「清空阶段」哨兵；null 才是「本事件不改」
                phase = r.phase ?: s.phase,
                iteration = r.iteration ?: s.iteration,
                runStartedAt = if (r.busy == false) null else s.runStartedAt,
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
        // 新一轮必须从新的 assistant 气泡开始：上一轮若是报错/断流收场，
        // assistantKey 还停在旧气泡上，继续拼会把新回答接在旧回答后面。
        assistantKey = null
        val userKey = "u_${nextId()}"
        update {
            it.copy(
                messages = it.messages + ChatMessage(key = userKey, role = "user", text = text),
                busy = true,
                error = null,
                // 新一轮从零开始计时：界面上的计时器由此刻起跑，不等 host 的心跳。
                phase = "启动中",
                iteration = 0,
                runStartedAt = System.currentTimeMillis(),
            )
        }
        // prompt 的回包是这一轮的终态：成功（response）或失败（error）都代表 run 已经结束。
        // provider 抛网络错时 host 不会再补 done 事件（agent.prompt 直接抛出，rpc 只回错误包），
        // 以前没认领这个回包 → busy 永远停在 true：界面卡在「编码中…」、停止键发了 cancel
        // 也只会拿到 no active run，新消息也发不出去。这里必须自己把 busy 放下。
        val id = nextId()
        pending[id] = { reply ->
            val isError = reply.optString("type") == "error"
            val err = if (isError) reply.optString("error").ifEmpty { "编码请求失败" } else null
            // 回包里的 sessionId 仍要认领：pending 先于 type 分支消费回包，
            // 不在这里处理，未指定会话的连接就再也拿不到 host 分配的 id。
            reply.optJSONObject("result")?.let { result ->
                result.optString("sessionId").takeIf { it.isNotEmpty() && fixedSessionId == null }
                    ?.let { sessionId = it }
                result.optString("runId").takeIf { it.isNotEmpty() }?.let { lastRunId = it }
            }
            val answer = if (isError) null else reply.optJSONObject("result")?.optString("text").orEmpty()
            main.post {
                update { s ->
                    // 兜底：本轮没有任何 text 事件落地（网关不回 SSE / 事件被丢），
                    // 就等收尾时把回包里的完整正文补成一条 assistant 消息——
                    // 宁可少一次流式动画，也不能让用户看不到回答。
                    val at = s.messages.indexOfFirst { it.key == userKey }
                    val answered = at >= 0 && s.messages.drop(at + 1).any { it.role == "assistant" && it.text.isNotBlank() }
                    val msgs = if (!answered && answer != null && answer.isNotEmpty()) {
                        s.messages + ChatMessage(key = "a_${nextId()}", role = "assistant", text = answer)
                    } else {
                        s.messages
                    }
                    s.copy(
                        messages = msgs,
                        busy = false,
                        error = err ?: s.error,
                        // 回包是终态：阶段与计时一并收束，避免状态行残留一个停住的计时器。
                        phase = "",
                        runStartedAt = null,
                    )
                }
            }
        }
        // 带上 sessionId 才有对话连续性；为空时 host 会新建一个并在 response 里回传。
        t.send(requestWith(id, "prompt", JSONObject().put("text", text).put("sessionId", sessionId ?: "")))
    }

    fun respondApproval(callId: String, approved: Boolean) {
        transport?.send(request("approve", JSONObject().put("callId", callId).put("approved", approved)))
        update { s ->
            val idx = s.messages.indexOfFirst { it.approvalCallId == callId }
            val msgs = if (idx >= 0) s.messages.toMutableList().also { it[idx] = it[idx].copy(approvalResolved = approved) } else s.messages
            s.copy(messages = msgs)
        }
    }

    /**
     * 热更新审批档位（ask / risky / bypass）：改的是宿主进程里正在生效的
     * ApprovalPolicy，不重启进程——重启会掐断当前这一轮和连接。
     * [onResult] 收到失败（未连接 / 非法档位）时由界面提示；设置本身仍会落盘，
     * 下次宿主启动按新值生效。
     */
    fun setApprovalMode(mode: String, onResult: ((Boolean, String?) -> Unit)? = null) {
        call("set_approval_mode", JSONObject().put("mode", mode)) { result, error ->
            onResult?.invoke(error == null && result != null, error)
        }
    }

    fun cancel() {
        val t = transport ?: return update { it.copy(error = "not connected") }
        // host 按 sessionId 查 abortController；不带 sessionId 的 cancel 会被当成空操作，
        // 用户以为停了，模型其实还在跑、还在烧 token。
        val id = nextId()
        pending[id] = { reply ->
            // cancelled=false 说明 host 侧这一轮已经结束（比如请求早就失败了），
            // 没有任何活动 run 可停。此时界面必须自己退出「编码中」，否则停止键
            // 点了没反应、输入框也一直锁着；cancelled=true 则等 host 的 cancelled
            // 事件把 busy 放下（run 真正断流后才会走到那里）。
            val cancelled = reply.optJSONObject("result")?.optBoolean("cancelled") ?: false
            if (!cancelled) main.post { update { s -> s.copy(busy = false) } }
        }
        t.send(requestWith(id, "cancel", JSONObject().put("sessionId", sessionId ?: "")))
    }

    fun undoLastRun() {
        val run = lastRunId ?: return update { it.copy(error = "nothing to undo yet") }
        transport?.send(request("undo", JSONObject().put("sessionId", sessionId ?: "").put("runId", run)))
        update { it.copy(error = null) }
    }

    fun newSession() {
        transport?.send(request("new_session", JSONObject()))
        assistantKey = null
        // 固定会话（引擎）不能置空——置空后事件就没有归属可匹配了；
        // 改为换一个全新 id，语义仍是"清掉上下文重新开始"
        sessionId = fixedSessionId?.let { "${it}_r${System.currentTimeMillis().toString(36)}" }
        lastRunId = null
        update { it.copy(messages = emptyList(), error = null) }
    }

    /**
     * 切到某个已存在的会话：把会话 id 与本地的历史消息一起装回来。
     * 会话 id 由 host 侧 `new_session` 产生并持久化，重启后依然有效。
     */
    fun activateSession(id: String, messages: List<ChatMessage>) {
        assistantKey = null
        lastRunId = null
        sessionId = id
        // busy 保持原样：用户中途离开页面再回来时，正在跑的这一轮要继续显示为「编码中」
        update { it.copy(messages = messages, error = null) }
    }

    /** 消息有变化时给编码页一个落盘时机（切会话/退页面时保存） */
    fun snapshotMessages(): List<ChatMessage> = state.value.messages
}
