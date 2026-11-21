// 本文件里的数字是时间换算阈值（毫秒 / 分钟 / 小时）与摘要取字节数，语义紧邻标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.core.ai

import java.security.MessageDigest

/**
 * 主模型配置的可信度：用户「到底配好了没有」。
 *
 * 填了 Key 不等于能用——Key 可能失效、地址可能写错、模型名可能不存在。
 * 四态把「填了」和「验过」分开：
 *  - [State.UNCONFIGURED] 地址 / Key / 模型名有空缺，请求根本发不出去；
 *  - [State.UNVERIFIED]   填齐了，但没有一次针对**当前这份配置**的验证记录
 *                         （含「改了 Key / 地址 / 模型，旧结论作废」）；
 *  - [State.VERIFIED]     当前配置（地址 + 模型 + Key）最近一次连接测试通过；
 *  - [State.FAILED]       当前配置最近一次连接测试失败，带上原因摘要。
 *
 * 判定必须是纯函数：首页、状态条、设置页、单测都从这一处取结论。各写一遍
 * 「Key 非空就算配好」正是 UX-01 的来源——模型页测出红色错误，首页照样报「已接入」。
 */
object ModelTrust {
    enum class State { UNCONFIGURED, UNVERIFIED, VERIFIED, FAILED }

    /**
     * 一次验证记录的快照。
     *
     * 时间与原因只给人看（「刚才验过」比「已验证」可信），判定只看 [state]。
     * 每个字段只在自己那个状态下有意义，其余状态一律留默认值，
     * 调用方就不会出现「未验证却带着上次失败原因」这种拼凑。
     */
    data class Snapshot(
        val state: State,
        /** 通过验证的时间（毫秒）；仅 [State.VERIFIED] 有意义 */
        val verifiedAt: Long = 0L,
        /** 失败原因摘要；仅 [State.FAILED] 有意义 */
        val error: String = "",
        /** 失败时间（毫秒）；仅 [State.FAILED] 有意义 */
        val failedAt: Long = 0L,
    ) {
        /** 能不能按「配置好了」来展示：只有验过的才算 */
        val ready: Boolean get() = state == State.VERIFIED

        /** 有没有可用的配置（无论验没验过） */
        val configured: Boolean get() = state != State.UNCONFIGURED
    }

    /**
     * 配置指纹：地址 + 模型名 + Key 摘要。
     *
     * 验证记录绑在指纹上，而不是绑在「设置里有个时间戳」上：改了任何一项，
     * 上一次的「已验证」立刻作废回到未验证，不会出现「换了 Key 还显示已接入」。
     * Key 只留 SHA-256 前 8 字节，落盘不暴露密钥本身。
     */
    fun fingerprint(baseUrl: String, model: String, apiKey: String): String =
        baseUrl.trim().trimEnd('/').lowercase() + "\n" + model.trim() + "\n" + digest(apiKey.trim())

    /**
     * 由当前配置 + 最近一次验证记录推出四态。
     *
     * @param verifiedTarget 最近一次验证时的指纹（空 = 从未验证过）
     * @param verifiedAt     最近一次验证的时间，0 = 从未验证
     * @param verifiedOk     最近一次验证是否通过
     * @param verifyError    最近一次验证失败的原因摘要
     */
    fun resolve(
        baseUrl: String,
        apiKey: String,
        model: String,
        verifiedTarget: String,
        verifiedAt: Long,
        verifiedOk: Boolean,
        verifyError: String,
    ): Snapshot {
        if (apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) return Snapshot(State.UNCONFIGURED)
        // 没有记录、或记录的是另一份配置 → 未验证。后一种正是「改了配置必须重新验」的实现点
        if (verifiedTarget.isBlank() || verifiedAt <= 0L) return Snapshot(State.UNVERIFIED)
        if (verifiedTarget != fingerprint(baseUrl, model, apiKey)) return Snapshot(State.UNVERIFIED)
        return if (verifiedOk) {
            Snapshot(State.VERIFIED, verifiedAt = verifiedAt)
        } else {
            Snapshot(State.FAILED, error = verifyError.ifBlank { "连接测试未通过" }, failedAt = verifiedAt)
        }
    }

    /** 时间差的中文说法；验证时间只用来给「刚才验过」一个可信的刻度 */
    fun describeAge(now: Long, at: Long): String {
        if (at <= 0L) return ""
        val minutes = (now - at).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> "${minutes} 分钟前"
            minutes < 24L * 60L -> "${minutes / 60L} 小时前"
            else -> "${minutes / (24L * 60L)} 天前"
        }
    }

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
