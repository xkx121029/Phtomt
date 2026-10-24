package com.phoneagent.coding.agent

import org.json.JSONObject

/** Transport that talks to the on-device agent process owned by [LocalAgent]. */
class ProcessTransport : AgentTransport {

    override var listener: AgentTransport.Listener? = null

    private var lineHook: ((String) -> Unit)? = null
    private var exitHook: ((Int) -> Unit)? = null

    override fun connect() {
        val proc = LocalAgent.process
        if (proc == null) {
            listener?.onFailure("local agent is not running")
            return
        }
        lineHook = { line -> listener?.onMessage(line) }
        exitHook = { listener?.onClosed("agent process exited") }
        // attachListener 会先补发 host 启动阶段积压的行（能力探测回包就在里面）。
        proc.attachListener(lineHook!!)
        LocalAgent.addExitHook(exitHook!!)
        listener?.onOpen()
    }

    override fun send(obj: JSONObject) {
        LocalAgent.process?.send(obj.toString())
    }

    override fun disconnect() {
        lineHook?.let { LocalAgent.process?.detachListener(it) }
        exitHook?.let { LocalAgent.removeExitHook(it) }
        lineHook = null
        exitHook = null
    }
}
