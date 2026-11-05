package com.phoneagent.debug.logtransfer

/**
 * 单个连接的传输会话（纯逻辑，无 Android / 无 IO，由 [LogServerCore] 驱动）。
 *
 * 协议要点：
 * - **序号**：历史日志按时间戳正序编号 1..N，随后实时日志从 N+1 续号，整条流单调递增；
 *   电脑端据此校验"不丢包、不乱序"。
 * - **窗口 + 确认**：仅在未确认数量小于 [window] 时继续发送，防止弱网下把整段历史灌进内存；
 *   收到 `ack n` 即认为 ≤ n 全部送达。
 * - **重传**：`nak expect` 立即从 expect 起重发；超时未确认的包在 [tick] 里重发；
 *   单个包重试超过 [maxRetries] 次即判定连接不可用（避免无限重试把会话挂死）。
 * - **顺序**：历史未发完不会发实时，实时只按入队顺序追加，因此电脑端永远拿到连续序号。
 */
class LogTransferSession(
    private val sid: String,
    private val device: DeviceInfo,
    private val send: (WireEvent) -> Unit,
    private val clock: () -> Long,
    private val retransmitAfterMs: Long = DEFAULT_RETRANSMIT_AFTER_MS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val window: Int = DEFAULT_WINDOW,
) {

    data class DeviceInfo(val label: String, val sdk: Int)

    private class Packet(val event: WireEvent, val seq: Long) {
        var sentAt: Long = 0L
        var attempts: Int = 0
    }

    private val history = ArrayList<LogRecord>()
    private val live = ArrayDeque<LogRecord>()
    private val pending = ArrayDeque<Packet>()
    private var historyCursor = 0
    private var historyEndSent = false
    private var nextSeq = 1L
    private var closed = false
    private val startedAt = clock()

    var totalHistory: Long = 0L
        private set

    /** 电脑端已累计确认到哪一条（0 表示一条都没确认） */
    var ackedSeq: Long = 0L
        private set

    var sentCount: Long = 0L
        private set

    var retransmitCount: Long = 0L
        private set

    /** 历史是否已全部发出（不要求已确认） */
    val historyDispatched: Boolean get() = historyEndSent

    /** 历史是否已全部被确认 */
    val historyAcked: Boolean get() = historyEndSent && ackedSeq >= totalHistory

    @Synchronized
    fun begin(history: List<LogRecord>) {
        if (history.isNotEmpty()) this.history.addAll(history)
        totalHistory = history.size.toLong()
        // 历史从 1 开始编号；发完 N 条后 nextSeq 自然等于 N+1，实时日志继续递增
        nextSeq = 1L
        send(WireEvent.Hello(sid, totalHistory, device.label, device.sdk, startedAt))
    }

    /** 实时日志入队；实际发送由 [tick] 推进（超出窗口时留在队列里等待确认） */
    @Synchronized
    fun pushLive(record: LogRecord) {
        live.addLast(record)
    }

    @Synchronized
    fun onAck(seq: Long) {
        if (seq > ackedSeq) ackedSeq = seq
        while (pending.isNotEmpty() && pending.first().seq <= ackedSeq) pending.removeFirst()
    }

    @Synchronized
    fun onNak(expect: Long) {
        pending.forEach { if (it.seq >= expect) resend(it) }
    }

    /**
     * 推进一轮：先检查超时重传，再补发新包。
     * @return false 表示重传次数用尽，调用方应断开连接
     */
    @Synchronized
    fun tick(now: Long): Boolean {
        if (closed) return false
        val stale = pending.firstOrNull { now - it.sentAt >= retransmitAfterMs }
        if (stale != null) {
            if (stale.attempts >= maxRetries) return false
            resend(stale)
        }
        pump()
        return true
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        send(WireEvent.Bye(sentCount, ackedSeq))
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun liveQueueSize(): Int = live.size

    private fun pump() {
        while (pending.size < window) {
            when {
                historyCursor < history.size -> dispatch(history[historyCursor++], PHASE_HISTORY)
                !historyEndSent -> {
                    // 历史尾部标记：seq 取最后一条历史的序号，全部确认即代表历史完整送达
                    send(WireEvent.HistoryEnd(totalHistory, totalHistory))
                    historyEndSent = true
                }
                live.isNotEmpty() -> dispatch(live.removeFirst(), PHASE_LIVE)
                else -> return
            }
        }
    }

    private fun dispatch(record: LogRecord, phase: String) {
        val seq = nextSeq++
        val event = WireEvent.LogEntry(seq, phase, record)
        send(event)
        pending.addLast(Packet(event, seq).apply { sentAt = clock() })
        sentCount++
    }

    private fun resend(packet: Packet) {
        send(packet.event)
        packet.sentAt = clock()
        packet.attempts++
        retransmitCount++
    }

    companion object {
        const val PHASE_HISTORY = "history"
        const val PHASE_LIVE = "live"

        const val DEFAULT_RETRANSMIT_AFTER_MS = 600L
        const val DEFAULT_MAX_RETRIES = 4
        const val DEFAULT_WINDOW = 128
    }
}
