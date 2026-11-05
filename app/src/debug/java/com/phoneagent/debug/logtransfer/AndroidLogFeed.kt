package com.phoneagent.debug.logtransfer

import android.content.Context
import android.util.Log
import com.phoneagent.core.log.AppLog
import com.phoneagent.data.store.DebugRecordsStore
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.engine.AgentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 设备侧日志源（debug 专有）。
 *
 * 采集面：
 * 1. **追加日志**（[LogJournal]）：本功能启用后的完整流水，跨重启保留，不裁剪；
 * 2. **引擎内存缓冲**：`AgentEngine.logs`（最近 800 条，含引擎自身与 AppLog 镜像）；
 * 3. **调试存档**：`DebugRecordsStore`（任务结束落盘的快照，功能启用前的历史只能靠它）。
 *
 * 实时面：
 * - 引擎存在时订阅 `engine.logs` 增量（覆盖引擎日志 + 经 TelemetryRecorder 镜像的 AppLog 日志）；
 * - 引擎尚未构造时（进程刚起、界面还没打开）先挂 AppLog 的 sink 链，保证早期日志不丢；
 *   引擎一旦构造会接管 sink，此时改由引擎流采集，避免同一批日志两份。
 */
class AndroidLogFeed(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LogFeed {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journal = LogJournal(File(appContext.filesDir, "$JOURNAL_DIR/$JOURNAL_FILE"))
    private val listeners = CopyOnWriteArrayList<(LogRecord) -> Unit>()
    private val started = AtomicBoolean(false)
    private val deduper = CrossSourceDeduper(clock)
    private val engineKey = AtomicReference<String?>(null)
    private val feedStartAt = AtomicLong(0L)
    private val lastEmitAt = AtomicLong(0L)

    /** AppLog 挂接：先调原 sink（可能是 TelemetryRecorder），再交给本功能采集 */
    private lateinit var tap: (Int, String, String, Throwable?) -> Unit

    @Volatile
    private var previousSink: ((Int, String, String, Throwable?) -> Unit)? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        feedStartAt.set(clock())
        installTap()
        scope.launch { watchEngine() }
    }

    override fun historySnapshot(): List<LogRecord> {
        val journalRecords = journal.readAll()
        val memoryRecords = runCatching {
            findEngine()?.logs?.value?.map { toRecord(it) }
        }.getOrNull().orEmpty()
        val persistedRecords = runCatching {
            DebugRecordsStore.load(appContext)?.logs?.map { toRecord(it) }
        }.getOrNull().orEmpty()
        return LogHistoryAssembler.assemble(journalRecords, memoryRecords, persistedRecords)
    }

    override fun addListener(listener: (LogRecord) -> Unit): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    fun journalSizeBytes(): Long = journal.sizeBytes()

    private fun installTap() {
        tap = { level: Int, tag: String, msg: String, tr: Throwable? ->
            previousSink?.invoke(level, tag, msg, tr)
            val text = buildString {
                append('[').append(tag).append("] ").append(msg)
                if (tr != null) append('\n').append(Log.getStackTraceString(tr))
            }
            emit(LogRecord(clock(), levelName(level), text), SOURCE_APPLOG)
        }
        previousSink = AppLog.sink
        AppLog.sink = tap
    }

    private fun uninstallTapIfOwned() {
        if (AppLog.sink === tap) AppLog.sink = previousSink
    }

    private suspend fun watchEngine() {
        var engine = findEngine()
        while (engine == null) {
            delay(ENGINE_POLL_MS)
            engine = findEngine()
        }
        // 引擎已接管 AppLog（TelemetryRecorder 构造时覆盖了 sink），此时 AppLog 日志会以镜像形式
        // 进入 engine.logs，无需自己再挂一份
        uninstallTapIfOwned()
        // 补录交接窗口：sink 被接管到我们发现引擎之间的日志只存在于 engine.logs 里，
        // 从「本功能启动时刻」与「最后一次经过 tap 的日志时刻」中较晚者起重放，避免漏条。
        // 重放会写入追加流水，历史快照按 (时间戳|级别|正文) 精确去重，因此不会与环形缓冲重复。
        val replayFrom = maxOf(feedStartAt.get(), lastEmitAt.get())
        engine.logs.value.filter { it.timestamp >= replayFrom }.forEach {
            emit(toRecord(it), SOURCE_ENGINE)
        }
        engineKey.set(engine.logs.value.lastOrNull()?.let(::agentKey))
        engine.logs.collect { list ->
            val previousKey = engineKey.get()
            val fresh = EngineLogDelta.after(list, previousKey, ::agentKey)
            fresh.forEach { emit(toRecord(it), SOURCE_ENGINE) }
            list.lastOrNull()?.let { engineKey.set(agentKey(it)) }
        }
    }

    private fun findEngine(): AgentEngine? = runCatching {
        GlobalContext.get().getOrNull<AgentEngine>(AgentEngine::class, null, null)
    }.getOrNull()

    private fun emit(record: LogRecord, source: String) {
        // 跨来源去重不比较时间戳：同一条 AppLog 被 TelemetryRecorder 镜像进引擎缓冲时，
        // 正文一致但时刻会差几毫秒，带时间戳的 key 会让镜像漏过去
        if (!deduper.accept("${record.level}|${record.message}", source)) return
        lastEmitAt.set(record.timestamp)
        journal.append(record)
        listeners.forEach { listener ->
            runCatching { listener(record) }
        }
    }

    private fun toRecord(log: AgentLog): LogRecord = LogRecord(
        timestamp = log.timestamp,
        level = log.level.name,
        message = log.message,
        detail = log.detail,
        taskId = log.taskId,
        taskName = log.taskName,
    )

    private fun agentKey(log: AgentLog): String = "${log.timestamp}|${log.level.name}|${log.message}"

    private fun levelName(level: Int): String = when (level) {
        Log.DEBUG -> "DEBUG"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        else -> "INFO"
    }

    companion object {
        private const val JOURNAL_DIR = "debug_logtransfer"
        private const val JOURNAL_FILE = "journal.jsonl"
        private const val ENGINE_POLL_MS = 200L
        private const val SOURCE_APPLOG = "applog"
        private const val SOURCE_ENGINE = "engine"
    }
}
