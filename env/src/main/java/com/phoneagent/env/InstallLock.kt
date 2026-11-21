package com.phoneagent.env

/**
 * 装配锁的持有者记录与「锁是残留还是真有人在装配」的判定。
 *
 * ## 为什么锁必须能自愈
 * 首次装配要下 200 MB 级归档，几十分钟里随时可能被系统杀掉（后台限制、清理内存、用户强停）。
 * `finally` 在被 SIGKILL 时不执行，锁目录就永久留在磁盘上——此后每次安装都等满 180 秒再失败，
 * 而 App 里没有任何卸载/重置入口，用户只能清应用数据。锁必须是**可判定的**而不是「存在即占用」。
 *
 * ## 判据（按顺序，先强后弱）
 * 1. 记录读不出来 → 残留。唯一例外见调用方的 `owner settle` 窗口：目录刚建、记录还没落盘的那几毫秒。
 * 2. 单调时钟倒退（当前 `elapsedRealtime` 小于记录里的读数）→ 设备在装配期间重启过 → 残留。
 *    这是不需要读 `/proc/sys/kernel/random/boot_id` 的重启判据，且不受用户改系统时间影响。
 * 3. pid 与本进程相同但 token 不同 → 同一个进程的上一轮装配没走 `finally` → 残留。
 *    token 每进程生成一次，用来区分「pid 被复用」与「我自己正在装」。
 * 4. pid 不同 → 查 `/proc/<pid>`：不存在 → 残留；cmdline 不是本进程 → pid 被回收给了别的进程 → 残留。
 * 5. `/proc` 读不到（跨 uid 时 SELinux 会拒）→ 退回心跳：超过 [STALE_HEARTBEAT_MS] 没更新 → 残留。
 *
 * 全部是纯函数：时钟、pid、进程查询都由调用方注入，所以每一档都能在 JVM 单测里钉死。
 */
internal object InstallLock {

    /** 持有者心跳超过这个时长没更新，就认为它已经不在了 */
    const val STALE_HEARTBEAT_MS = 120_000L

    private const val VERSION = "1"

    /** 一把锁的持有者身份 */
    data class Holder(
        val pid: Int,
        /** 每进程生成一次的随机串：区分「同一 pid 的新进程」与「真正的自己」 */
        val token: String,
        /** 取得锁时的 `elapsedRealtime`，同时用于重启判定 */
        val acquiredElapsedMs: Long,
        /** 最近一次心跳的 `elapsedRealtime` */
        val heartbeatElapsedMs: Long,
        /** 取得锁时的墙上时间，只用于给人看的时间戳 */
        val acquiredWallMs: Long,
    ) {
        /** 心跳距今多久（时钟倒退时按 0 处理，避免负数被当成「刚刚」） */
        fun heartbeatAgeMs(nowElapsedMs: Long): Long =
            (nowElapsedMs - heartbeatElapsedMs).coerceAtLeast(0L)
    }

    /** `/proc/<pid>` 探针的结论 */
    enum class Peer { ALIVE, GONE, UNKNOWN }

    sealed class Verdict {
        /** 上一次装配留下的残留，可以接管；[reason] 直接写进用户可见的日志 */
        data class Stale(val reason: String) : Verdict()

        /** 确实有装配在跑，只能等 */
        data class Live(val pid: Int, val heartbeatAgeMs: Long) : Verdict()
    }

    fun encode(holder: Holder): String = buildString {
        appendLine("v=$VERSION")
        appendLine("pid=${holder.pid}")
        appendLine("token=${holder.token}")
        appendLine("boot=${holder.acquiredElapsedMs}")
        appendLine("heartbeat=${holder.heartbeatElapsedMs}")
        appendLine("acquired=${holder.acquiredWallMs}")
    }

    /** 解析失败的锁记录一律返回 null（调用方据此判残留），不抛异常 */
    fun decode(raw: String?): Holder? {
        val text = raw ?: return null
        val values = text.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        if (values["v"] != VERSION) return null
        val pid = values["pid"]?.toIntOrNull() ?: return null
        val token = values["token"]?.takeIf { it.isNotEmpty() } ?: return null
        val acquired = values["boot"]?.toLongOrNull() ?: return null
        val heartbeat = values["heartbeat"]?.toLongOrNull() ?: acquired
        val wall = values["acquired"]?.toLongOrNull() ?: 0L
        return Holder(pid, token, acquired, heartbeat, wall)
    }

    /**
     * 把 `/proc/<pid>` 的两份原始信息翻译成 [Peer]。
     *
     * [cmdline] 为空不等于进程不存在：跨 uid 读取会被拒，所以是 UNKNOWN 而不是 GONE。
     * 唯一的一条硬结论是「cmdline 读得到、但不是本进程」——那是 pid 被回收的实锤。
     */
    fun classifyPeer(exists: Boolean, cmdline: String?, selfProcessName: String): Peer = when {
        !exists -> Peer.GONE
        cmdline.isNullOrBlank() -> Peer.UNKNOWN
        cmdline.trim('\u0000', ' ', '\n').substringBefore('\u0000') == selfProcessName -> Peer.ALIVE
        else -> Peer.GONE
    }

    fun judge(
        holder: Holder?,
        nowElapsedMs: Long,
        selfPid: Int,
        selfToken: String,
        peer: (Int) -> Peer,
    ): Verdict {
        if (holder == null) return Verdict.Stale("锁记录缺失或已损坏")

        if (nowElapsedMs < holder.acquiredElapsedMs) {
            return Verdict.Stale("设备在装配期间重启过（单调时钟倒退了 ${holder.acquiredElapsedMs - nowElapsedMs} ms）")
        }

        if (holder.pid == selfPid) {
            return if (holder.token == selfToken) {
                Verdict.Live(holder.pid, holder.heartbeatAgeMs(nowElapsedMs))
            } else {
                Verdict.Stale("本进程上一轮装配没有收尾（同一 pid，不同会话）")
            }
        }

        when (peer(holder.pid)) {
            Peer.GONE -> return Verdict.Stale("持有者进程 ${holder.pid} 已不存在，或该 pid 已被别的进程占用")
            Peer.ALIVE, Peer.UNKNOWN -> Unit
        }

        val age = holder.heartbeatAgeMs(nowElapsedMs)
        return if (age > STALE_HEARTBEAT_MS) {
            Verdict.Stale("持有者 ${holder.pid} 已 ${age / 1000} 秒没有心跳")
        } else {
            Verdict.Live(holder.pid, age)
        }
    }
}
