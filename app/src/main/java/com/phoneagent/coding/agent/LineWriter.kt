package com.phoneagent.coding.agent

import java.io.Writer

/**
 * JSON-lines 写入器：保证「一行 = 一条消息」在并发下不被拆散。
 *
 * 为什么需要它：`BufferedWriter` 只保证单次 `write` 原子，`write / newLine / flush`
 * 是三次独立的同步调用。发送方来自不同线程（主线程的用户批准、stdout 读线程的 host 回包），
 * 不加外层互斥时两行会拼成一行，host 侧 JSON.parse 直接失败。
 *
 * 单独成类而不是内联 `synchronized`：可以用普通 [Writer] 做并发回归测试
 * （[AgentProcess] 必须起真实子进程，无法在 JVM 单测里注入）。
 */
internal class LineWriter(private val writer: Writer) {

    @Synchronized
    fun writeLine(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }
}
