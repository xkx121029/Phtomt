package com.phoneagent.security

import com.phoneagent.domain.model.ScreenSnapshot

/**
 * 敏感页面检测：银行 / 支付密码 / 金融确认页 → 只读模式，拒绝执行任何动作。
 * 对应文档“第 12 层 安全与边界 / 敏感页面检测”。
 */
object SensitivePageDetector {

    /** 敏感应用包名特征 */
    private val sensitivePackages = listOf(
        "com.unionpay", "com.alipay", "com.tencent.mm", "com.icbc", "com.ccb",
        "com.cmbchina", "com.bankcomm", "com.android.bank", "cn.com.spdb",
    )

    /** 敏感页面关键词 */
    private val sensitiveLabels = listOf(
        "支付密码", "付款密码", "验证码", "交易密码", "银行卡", "确认支付", "指纹支付",
        "转账", "余额", "安全键盘", "输入密码",
    )

    fun isSensitive(snapshot: ScreenSnapshot): Boolean {
        val pkg = snapshot.packageName ?: return false
        if (sensitivePackages.any { pkg.contains(it, ignoreCase = true) }) {
            val labels = snapshot.elements.mapNotNull {
                it.text?.takeIf { t -> t.isNotBlank() } ?: it.contentDescription?.takeIf { t -> t.isNotBlank() }
            }
            if (labels.any { label -> sensitiveLabels.any { k -> label.contains(k) } }) return true
        }
        return false
    }
}

/**
 * 数据脱敏：手机号 / 身份证 / 银行卡号在序列化前做端侧掩码。
 * 对应文档“第 12 层 安全与边界 / 数据脱敏”。
 */
object DataSanitizer {

    private val phoneRegex = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val bankRegex = Regex("(?<!\\d)\\d{16,19}(?!\\d)")
    private val idRegex = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")

    fun sanitize(text: String): String {
        var out = text
        // 先脱敏最长的身份证号（18 位），避免其 16~19 位数字被银行卡正则先行匹配而残留
        out = idRegex.replace(out) { it.value.take(6) + "********" + it.value.takeLast(4) }
        out = phoneRegex.replace(out) { it.value.take(3) + "****" + it.value.takeLast(4) }
        out = bankRegex.replace(out) { it.value.take(4) + "****" + it.value.takeLast(4) }
        return out
    }
}