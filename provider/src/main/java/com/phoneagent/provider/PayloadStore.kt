package com.phoneagent.provider

/**
 * AIDL 大块数据的进程内暂存区。
 *
 * 背景：Binder 单次事务上限约 1MB，一张全屏 PNG 有 2~6MB，直接作为回包必然抛
 * TransactionTooLargeException；主应用侧还会把它误判成「连接已断」并清空代理，
 * 表现就是「显示已连接，但后续命令一条都发不出去」。
 *
 * 改为 Provider 暂存、主应用按 128KB 分片拉取：每片远低于事务上限，
 * 且任何单片失败都不会影响连接本身。
 *
 * 只保留最近两份（正常调用是一份截图 + 一份元素树），旧 token 自动淘汰，
 * 避免主应用异常退出后缓存无限堆积。
 */
object PayloadStore {

    /** 单次分片上限：256KB，给 Binder 事务留足余量 */
    const val CHUNK_LIMIT = 256 * 1024

    /** 同时最多保留的份数 */
    private const val MAX_ENTRIES = 2

    private class Entry(val bytes: ByteArray, val at: Long)

    private val entries = LinkedHashMap<Int, Entry>()
    private var seq = 0

    /** 暂存字节并返回 token（从 1 开始，0 与 -1 留给失败） */
    @Synchronized
    fun put(bytes: ByteArray): Int {
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.at } ?: break
            entries.remove(oldest.key)
        }
        val token = ++seq
        entries[token] = Entry(bytes, System.currentTimeMillis())
        return token
    }

    @Synchronized
    fun length(token: Int): Int = entries[token]?.bytes?.size ?: -1

    /** 读取一片；越界/失效返回 null，由调用方按失败处理 */
    @Synchronized
    fun read(token: Int, offset: Int, maxBytes: Int): ByteArray? {
        val bytes = entries[token]?.bytes ?: return null
        if (offset < 0 || offset >= bytes.size) return null
        val step = maxBytes.coerceIn(1, CHUNK_LIMIT)
        val end = minOf(bytes.size, offset + step)
        return bytes.copyOfRange(offset, end)
    }

    @Synchronized
    fun remove(token: Int) {
        entries.remove(token)
    }
}
