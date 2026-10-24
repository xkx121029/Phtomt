package com.phoneagent.coding.agent

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the on-device agent host process (node + extracted agent-host/index.js)
 * and its stdio JSON-lines pipes. Protocol-identical to the WebSocket server.
 */
class AgentProcess(
    private val nodePath: String,
    private val scriptPath: String,
    private val workDir: File,
    private val env: Map<String, String>,
    private val stderrLog: File,
    private val onExit: (Int) -> Unit = {},
) {
    private var process: java.lang.Process? = null
    private var writer: BufferedWriter? = null
    private var running = false

    /** stdout lines (one JSON object each). Thread-safe. */
    val lineListeners = CopyOnWriteArrayList<(String) -> Unit>()

    // 应用要等启动宽限期（~800ms）才接上监听，而 host 一启动就会发
    // host_capabilities 探测请求——这段时间差的回包必须缓存再补发，
    // 否则第一次能力探测永远超时，shell 通道会被误判成不可用。
    private val bufferLock = Any()
    private val earlyLines = ArrayDeque<String>()

    /** 接上 stdout 监听：先补发积压的行，再开始实时转发。 */
    fun attachListener(listener: (String) -> Unit) {
        synchronized(bufferLock) {
            val backlog = earlyLines.toList()
            earlyLines.clear()
            lineListeners.add(listener)
            backlog.forEach { line -> runCatching { listener(line) } }
        }
    }

    fun detachListener(listener: (String) -> Unit) {
        lineListeners.remove(listener)
    }

    private fun deliver(line: String) {
        val targets: List<(String) -> Unit>
        synchronized(bufferLock) {
            if (lineListeners.isEmpty()) {
                if (earlyLines.size < EARLY_BUFFER_CAP) earlyLines.addLast(line)
                return
            }
            targets = lineListeners.toList()
        }
        targets.forEach { runCatching { it(line) } }
    }

    val isAlive: Boolean
        get() = running && process?.isAlive == true

    fun start() {
        if (isAlive) return
        val pb = ProcessBuilder(listOf(nodePath, scriptPath))
        pb.directory(workDir)
        pb.redirectErrorStream(false)
        pb.environment().putAll(env)
        val proc = pb.start()
        process = proc
        running = true
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

        Thread({ pumpStdout(proc) }, "coding-stdout").start()
        Thread({ pumpStderr(proc) }, "coding-stderr").start()
        Thread(
            {
                val code = proc.waitFor()
                running = false
                Log.w(TAG, "agent host exited with code $code")
                val bye = """{"type":"event","event":{"type":"host_exited","code":$code}}"""
                lineListeners.forEach { it(bye) }
                onExit(code)
            },
            "coding-waiter",
        ).start()
    }

    private fun pumpStdout(proc: java.lang.Process) {
        try {
            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                deliver(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stdout pump ended: ${e.message}")
        }
    }

    private fun pumpStderr(proc: java.lang.Process) {
        try {
            stderrLog.parentFile?.mkdirs()
            val out = java.io.FileWriter(stderrLog, true)
            val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                out.appendLine(line)
                Log.d(TAG, "host: $line")
            }
            out.flush()
            out.close()
        } catch (e: Exception) {
            Log.w(TAG, "stderr pump ended: ${e.message}")
        }
    }

    fun send(line: String) {
        try {
            writer?.write(line)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
    }

    companion object {
        private const val TAG = "phoneagent-coding"
        private const val EARLY_BUFFER_CAP = 500
    }
}
