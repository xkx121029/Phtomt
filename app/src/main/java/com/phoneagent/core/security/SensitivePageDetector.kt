package com.phoneagent.core.security

import com.phoneagent.domain.model.ScreenSnapshot

/**
 * 敏感页面检测：银行 / 支付密码 / 金融确认页 → 只读模式，拒绝执行任何动作。
 * 对应文档“第 12 层 安全与边界 / 敏感页面检测”。
 */
object SensitivePageDetector {

    /** 敏感应用包名特征 */
    private val sensitivePackages = listOf(
        "com.unionpay", "com.alipay", "com.icbc", "com.ccb",
        "com.cmbchina", "com.bankcomm", "com.android.bank", "cn.com.spdb",
        // 中行/建行系：子应用与插件统一挂在 com.chinamworld.* 前缀下
        "com.chinamworld",
        // 邮储银行手机银行
        "com.yitong.mbank.psbc",
        // 中信银行手机银行
        "com.ecitic.bank.mobile",
    )

    /** 敏感页面关键词（银行/金融类应用：宽匹配，进密码页即只读） */
    private val sensitiveLabels = listOf(
        "支付密码", "付款密码", "验证码", "交易密码", "银行卡", "确认支付", "指纹支付",
        "转账", "余额", "安全键盘", "输入密码",
    )

    /**
     * 微信（com.tencent.mm）专用关键词表：微信是通用应用，聊天正文里也常出现
     * 「银行卡 / 余额 / 转账」这类词，套用宽表会把普通聊天误判成支付页、整页锁死。
     * 微信只认明确的支付场景词。
     */
    private val weChatPayLabels = listOf(
        "确认支付", "付款", "收银台", "支付密码", "转账确认", "零钱明细",
    )

    fun isSensitive(snapshot: ScreenSnapshot): Boolean {
        val pkg = snapshot.packageName ?: return false
        val isWeChat = pkg.contains("com.tencent.mm", ignoreCase = true)
        val hitPackage = isWeChat || sensitivePackages.any { pkg.contains(it, ignoreCase = true) }
        if (!hitPackage) return false
        val labels = snapshot.elements.mapNotNull {
            it.text?.takeIf { t -> t.isNotBlank() } ?: it.contentDescription?.takeIf { t -> t.isNotBlank() }
        }
        // 微信走专用窄表，其余敏感应用走通用宽表
        val keywords = if (isWeChat) weChatPayLabels else sensitiveLabels
        return labels.any { label -> keywords.any { k -> label.contains(k) } }
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
    /** 模型 API Key（sk- 前缀，51 位上下）。实测泄漏路径：用户把 Key 停在设置页，
     *  页面控件文本被读进决策提示词，再被诊断报告原样导出到「下载/」。 */
    private val apiKeyRegex = Regex("sk-[A-Za-z0-9_\\-]{12,}")
    /**
     * 验证码引导词。原来只认「验证码」，而银行/支付短信模板普遍写「校验码 / 动态码」，
     * 只认一种叫法等于对另外几种叫法完全不设防。
     * 英文 code 要求前后不接字母，否则 barcode / decode / codec 都会被当成引导词。
     */
    private val codeWord = "验证码|校验码|动态码|动态密码|校验密码|(?<![A-Za-z])[Cc]ode(?![A-Za-z])"

    /** 验证码：引导词后 8 字符内跟 4~8 位数字（避免误伤普通金额/时间） */
    private val smsCodeRegex = Regex("($codeWord)(\\D{0,8})(\\d{4,8})")

    /** 页面上出现这些字样 → 整页按「有验证码/密码输入」处理 */
    private val secretContextRegex = Regex("验证码|校验码|动态码|动态密码|支付密码|安全键盘")

    /** 独立的 4~8 位数字（前后都不接数字）：验证码的典型形态，含金额误伤但方向安全 */
    private val standaloneDigitsRegex = Regex("(?<![0-9])\\d{4,8}(?![0-9])")

    /** 整条正文只有一个 4~8 位数字：短信/银行类通知里这就是验证码本体 */
    private val onlyDigitsRegex = Regex("^\\s*\\d{4,8}\\s*$")

    /**
     * 会直接把验证码当正文推过来的来源（短信、手机银行、支付）。
     * 这类通知的引导词常被系统折进标题，正文只剩「884213」，靠上下文正则抓不到。
     */
    private val authCodeSourcePackages = listOf(
        "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.miui.smsextra", "com.huawei.mms", "com.android.messaging",
        "com.eg.android.alipaygphone", "com.unionpay", "com.icbc", "com.ccb", "com.cmbchina",
        "com.bankcomm", "com.chinamworld", "cn.com.spdb", "com.yitong.mbank.psbc",
        "com.ecitic.bank.mobile", "com.android.bankabc",
    )

    fun sanitize(text: String): String {
        // Key 与验证码的判断不看数字总量（Key 里可能只有零星数字），先做这两条
        var out = text
        out = apiKeyRegex.replace(out) { it.value.take(6) + "****" + it.value.takeLast(2) }
        out = smsCodeRegex.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + "******" }
        // 快速通道：手机号 11 位、身份证 17 位起、银行卡 16 位起——少于 11 个数字的文本
        // 不可能命中任何一条脱敏规则，直接返回。控件标签/日志正文绝大多数不含数字，
        // 这一步省掉的是每步数百次无意义的正则扫描。
        var digits = 0
        for (c in out) {
            if (c in '0'..'9') digits++
            if (digits >= 11) break
        }
        if (digits < 11) return out

        // 先脱敏最长的身份证号（18 位），避免其 16~19 位数字被银行卡正则先行匹配而残留
        out = idRegex.replace(out) { it.value.take(6) + "********" + it.value.takeLast(4) }
        out = phoneRegex.replace(out) { it.value.take(3) + "****" + it.value.takeLast(4) }
        out = bankRegex.replace(out) { it.value.take(4) + "****" + it.value.takeLast(4) }
        return out
    }

    /**
     * 整页脱敏：把一页元素文本当成一个整体来判断上下文。
     *
     * 逐元素脱敏在结构上就抓不到「控件 A 是『验证码』标签、控件 B 是『123456』」这种页面：
     * 数字单独成一个控件时，它自身既没有引导词、也没有 11 位数字，两个正则都不会命中。
     * 所以页面上只要出现验证码/密码提示字样，或存在密码输入框，就把整页「独立的 4~8 位数字」
     * 全部按验证码掩码。
     *
     * 取舍：不用「拼接成一条带分隔符的整页串 → 跑一次 → 再按分隔符切回」。切回要求掩码前后
     * 长度与位置可逆，而验证码掩码是变长的；更要紧的是 [com.phoneagent.engine.perception.PageAnnotator]
     * 会把元素按优先级重排，拼接顺序不保证空间相邻，切回还可能错位。用「整页上下文布尔值 +
     * 逐条掩码」既覆盖同样的场景，又不依赖元素顺序。
     */
    fun sanitizePage(text: String, hasPasswordField: Boolean = false): String {
        val out = sanitize(text)
        if (!hasPasswordField && !secretContextRegex.containsMatchIn(text)) return out
        return standaloneDigitsRegex.replace(out, "******")
    }

    /** [sanitizePage] 的多标签版本：按元素顺序进、按同样的顺序出，逐条用整页上下文掩码 */
    fun sanitizePageLabels(labels: List<String>, hasPasswordField: Boolean = false): List<String> {
        if (labels.isEmpty()) return labels
        val pageHasSecretContext = hasPasswordField || labels.any { secretContextRegex.containsMatchIn(it) }
        return labels.map { label ->
            val masked = sanitize(label)
            if (pageHasSecretContext) standaloneDigitsRegex.replace(masked, "******") else masked
        }
    }

    /** 来源是否属于「短信/银行/支付」这类会把验证码当正文推送的应用 */
    fun isAuthCodeSource(packageName: String?): Boolean {
        val pkg = packageName?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return authCodeSourcePackages.any { pkg.contains(it) }
    }

    /**
     * 通知正文脱敏：常规 [sanitize] 之外，短信/银行类来源的「整条只有一个 4~8 位数字」
     * 按验证码整条遮蔽——银行推送常把「您的校验码是」这半句放在标题里，正文只剩数字。
     */
    fun sanitizeNotificationBody(packageName: String?, body: String): String {
        val masked = sanitize(body)
        if (!isAuthCodeSource(packageName)) return masked
        return if (onlyDigitsRegex.matches(masked)) "******" else masked
    }
}