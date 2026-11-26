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

    override fun send(obj: JSONObject): Boolean {
        val proc = LocalAgent.process ?: return false
        // 写失败（管道已断 / 进程刚退出）必须回报给调用方：以前直接吞掉，
        // 界面把 prompt 发进虚空后 busy 永远停在 true，只能靠用户点停止自救。
        return proc.send(obj.toString())
    }

    override fun disconnect() {
        lineHook?.let { LocalAgent.process?.detachListener(it) }
        exitHook?.let { LocalAgent.removeExitHook(it) }
        lineHook = null
        exitHook = null
    }
}
