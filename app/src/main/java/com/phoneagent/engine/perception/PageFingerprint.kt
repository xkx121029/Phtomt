package com.phoneagent.engine.perception.PageFingerprint

import com.phoneagent.domain.model.ScreenSnapshot
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

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}