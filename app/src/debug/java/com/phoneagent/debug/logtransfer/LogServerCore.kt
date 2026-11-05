package com.phoneagent.debug.logtransfer

import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 设备侧日志传输服务（只监听回环地址）。
 *
 * 为什么只绑 127.0.0.1：传输端口不需要暴露给局域网。USB 与无线调试都通过
 * `adb forward tcp:19090 tcp:19090` 把电脑本地的端口映射到设备回环，因此
 * 「USB / 网络」两种接法共用同一条通路，而不会被同一网段的其他机器扫到。
 *
 * 每个连接独立一个会话：各自拿到一份完整历史快照 + 从连接时刻起的实时流。
 * 客户端断开（含异常中断）后重新连接，会重新拿到从 seq=1 开始的完整历史，
 * 因此「重连」不需要额外的断点续传状态。
 */
class LogServerCore(
    private val port: Int,
    private val feed: LogFeed,
    private val status: (String) -> Unit,
    private val deviceLabel: () -> String,
    private val sdk: Int,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    private val retransmitAfterMs: Long = LogTransferSession.DEFAULT_RETRANSMIT_AFTER_MS,
    private val pumpIntervalMs: Long = DEFAULT_PUMP_INTERVAL_MS,
) : Closeable {

    private val running = AtomicBoolean(false)
    private val clientCount = AtomicInteger(0)
    private val sessionIds = AtomicLong(0L)
    private val clients = CopyOnWriteArrayList<Socket>()
    private var server: ServerSocket? = null

    /** 实际监听端口（构造时传 0 由系统分配，单测需要读回） */
    val localPort: Int get() = server?.localPort ?: -1

    val activeClients: Int get() = clientCount.get()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val started = runCatching {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
            server = ss
            val thread = Thread({ acceptLoop(ss) }, "hpa-log-server")
            thread.isDaemon = true
            thread.start()
            status("调试日志服务已就绪：127.0.0.1:${ss.localPort}")
            true
        }.getOrElse {
            running.set(false)
            status("调试日志服务启动失败：${it.message ?: it.javaClass.simpleName}")
            false
        }
        return started
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                if (running.get()) continue else break
            } catch (_: IOException) {
                if (!running.get()) break else continue
            }
            clients += socket
            clientCount.incrementAndGet()
            onClientConnected()
            val id = sessionIds.incrementAndGet()
            val thread = Thread({ handleClient(socket, id) }, "hpa-log-client-$id")
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun handleClient(socket: Socket, id: Long) {
        val remote = runCatching { "${socket.inetAddress.hostAddress}:${socket.port}" }.getOrDefault("unknown")
        val stopped = AtomicBoolean(false)
        val writer = try {
            socket.tcpNoDelay = true
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        } catch (e: IOException) {
            status("连接建立失败（$remote）：${e.message ?: e.javaClass.simpleName}")
            runCatching { socket.close() }
            clients -= socket
            clientCount.decrementAndGet()
            onClientDisconnected()
            return
        }
        val send: (WireEvent) -> Unit = { event ->
            try {
                synchronized(writer) {
                    writer.write(LogWire.encode(event))
                    writer.write("\n")
                    writer.flush()
                }
            } catch (_: IOException) {
                stopped.set(true)
            }
        }
        val session = LogTransferSession(
            sid = "hpa-${clock()}-$id",
            device = LogTransferSession.DeviceInfo(deviceLabel(), sdk),
            send = send,
            clock = clock,
            retransmitAfterMs = retransmitAfterMs,
        )
        var subscription: Closeable? = null
        try {
            session.begin(feed.historySnapshot())
            subscription = feed.addListener { session.pushLive(it) }
            status("调试端已连接（$remote）→ 开始传输历史日志，共 ${session.totalHistory} 条")

            val reader = Thread(
                { readCommands(socket, session, stopped) },
                "hpa-log-reader-$id",
            )
            reader.isDaemon = true
            reader.start()

            var historyNotified = false
            var lastProgressAt = clock()
            while (running.get() && !stopped.get()) {
                if (!session.tick(clock())) {
                    status("重传次数超限，主动断开（$remote）")
                    break
                }
                if (!historyNotified && session.historyAcked) {
                    historyNotified = true
                    status("历史日志传输完成（${session.totalHistory} 条，重传 ${session.retransmitCount} 次）")
                }
                val now = clock()
                if (now - lastProgressAt >= PROGRESS_INTERVAL_MS && !session.historyAcked) {
                    lastProgressAt = now
                    status(
                        "传输中：历史已确认 ${session.ackedSeq.coerceAtMost(session.totalHistory)}/${session.totalHistory}，" +
                            "实时已发送 ${session.sentCount - session.totalHistory} 条",
                    )
                }
                Thread.sleep(pumpIntervalMs)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            status("连接异常中断（$remote）：${e.message ?: e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            status("会话异常（$remote）：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { subscription?.close() }
            runCatching { session.close() }
            status("传输结束：共发送 ${session.sentCount} 条（历史 ${session.totalHistory}），已确认 ${session.ackedSeq} 条，重传 ${session.retransmitCount} 次")
            stopped.set(true)
            runCatching { socket.close() }
            clients -= socket
            clientCount.decrementAndGet()
            onClientDisconnected()
        }
    }

    private fun readCommands(socket: Socket, session: LogTransferSession, stopped: AtomicBoolean) {
        try {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            while (!stopped.get()) {
                val line = reader.readLine() ?: break
                when (val command = LogWire.decodeCommand(line)) {
                    is WireCommand.Ack -> session.onAck(command.seq)
                    is WireCommand.Nak -> session.onNak(command.expect)
                    WireCommand.Bye -> break
                    null -> Unit
                }
            }
        } catch (_: IOException) {
            // 连接被关闭：主线程会在下一轮 tick 里发现并收尾
        } finally {
            stopped.set(true)
            runCatching { socket.close() }
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PUMP_INTERVAL_MS = 10L
        const val PROGRESS_INTERVAL_MS = 2_000L
    }
}
