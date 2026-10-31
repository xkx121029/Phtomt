package com.phoneagent.engine.perception

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.rules.effectiveLabel
import java.security.MessageDigest

/**
 * 页面指纹：基于可交互控件的 id + type + label + 边界生成哈希。
 * 页面不变 → 指纹不变；页面变化 → 指纹必变。
 * 用于执行前后验证（确认动作是否真的改变了页面）。
 * 对应文档“第 3 层 页面指纹”。
 */
object PageFingerprint {

    /** 计算页面指纹（轻量版，只取关键控件特征） */
    fun compute(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append(snapshot.packageName ?: "").append('|')
        // 按坐标排序，保证稳定
        val sorted = snapshot.elements.sortedWith(compareBy<com.phoneagent.domain.model.UiElement> { it.left }.thenBy { it.top })
        for (e in sorted) {
            sb.append(e.viewId ?: "").append(':')
            sb.append(e.type).append(':')
            sb.append(e.effectiveLabel() ?: "").append(':')
            sb.append(e.left).append(',').append(e.top).append(',').append(e.right).append(',').append(e.bottom)
            sb.append(';')
        }
        return sha256(sb.toString()).take(16)
    }

    /** 计算有意义的指纹：忽略状态栏时间等动态变化（仅标签与坐标，不含 viewId 中可能变化的序号） */
    fun computeMeaningful(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        val sorted = snapshot.elements.sortedWith(compareBy<com.phoneagent.domain.model.UiElement> { it.left }.thenBy { it.top })
        for (e in sorted) {
            sb.append(e.type).append(':')
            sb.append(e.effectiveLabel() ?: "").append(':')
            sb.append(e.centerX / 10).append(',').append(e.centerY / 10)
            sb.append(';')
        }
        return sha256(sb.toString()).take(16)
    }

    /** 十六进制字符表：替代 `"%02x".format(it)`（每次指纹计算 32 次 String.format 代价过高） */
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** 一个字节拆成两个十六进制字符：高 4 位右移、低 4 位掩码 */
    private const val HEX_SHIFT = 4
    private const val HEX_MASK = 0x0F

    /**
     * MessageDigest 非线程安全，按线程缓存复用实例（引擎步进与页面记忆采集在不同线程）。
     * 每次使用前 reset，避免上一次未取尽的摘要状态串扰。
     */
    private val DIGEST = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    private fun sha256(input: String): String {
        val md = DIGEST.get()!!
        md.reset()
        val bytes = md.digest(input.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr HEX_SHIFT]).append(HEX_CHARS[v and HEX_MASK])
        }
        return sb.toString()
    }
}