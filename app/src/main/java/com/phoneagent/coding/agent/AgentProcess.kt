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
                // 环形语义：满时丢最旧的（启动阶段最早的那批通常是重试可恢复的探测行），
                // 永远给最新的行留位置——首启宽限期里能力探测回包不能被早期噪音挤掉。
                if (earlyLines.size >= EARLY_BUFFER_CAP) earlyLines.removeFirst()
                earlyLines.addLast(line)
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
        // 长会话下 host 会持续输出，只在启动时检查一次挡不住无限增长：
        // 按写入字节计数，超限就关当前 writer、把文件挪到 .old（至多保留一份）、续写新文件。
        var out: java.io.FileWriter? = null
        var written = 0L
        try {
            stderrLog.parentFile?.mkdirs()
            val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (out != null && written > STDERR_ROTATE_BYTES) {
                    runCatching { out.close() }
                    out = null
                }
                if (out == null) {
                    if (written > STDERR_ROTATE_BYTES || stderrLog.length() > STDERR_ROTATE_BYTES) {
                        val old = File(stderrLog.parentFile, stderrLog.name + ".old")
                        old.delete()
                        stderrLog.renameTo(old)
                    }
                    out = java.io.FileWriter(stderrLog, true)
                    written = 0L
                }
                out.appendLine(line)
                written += line.length + 1
                Log.d(TAG, "host: $line")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stderr pump ended: ${e.message}")
        } finally {
            runCatching { out?.flush() }
            runCatching { out?.close() }
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
        private const val TAG = "AgentProcess"
        private const val EARLY_BUFFER_CAP = 500

        /** stderr 日志轮转阈值：超过即挪到 .old（至多保留一份，上限 2 × 该值）。 */
        private const val STDERR_ROTATE_BYTES = 1L * 1024 * 1024
    }
}
