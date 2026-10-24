package com.phoneagent.coding.agent

import org.json.JSONObject

/**
 * Agent transport: either a WebSocket to a desktop host, or a pipe to the
 * on-device agent process. Same JSON-lines protocol on both sides.
 */
interface AgentTransport {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(reason: String)
        fun onFailure(message: String)
    }

    var listener: AgentTransport.Listener?

    fun connect()
    fun disconnect()
    fun send(obj: JSONObject)
}
