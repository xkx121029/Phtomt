package com.phoneagent.domain.rules

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement

/**
 * 页面类型规则（纯 domain 层，无任何框架依赖）：
 * 弹窗 / 倒计时广告 / 加载中 / 异常页 / 完成页等页面形态的关键词词表与判定逻辑。
 *
 * 原 engine.perception.PageAnnotator 中的纯函数部分下沉至此——消除 domain→engine 反向依赖，
 * 端侧决策引擎（LocalDecisionEngine）与感知标注器（PageAnnotator）共用同一份词表与判定，
 * 词表仍然只有一个定义点。
 */
object PageTypeRules {

    /** 弹窗正向按钮关键词（英文词走整词匹配，避免子串误伤） */
    internal val dialogPositive = listOf("允许", "同意", "确定", "知道了", "始终允许", "授权", "resume", "continue", "ok")
    /** 弹窗关闭按钮关键词（英文词走整词匹配，避免子串误伤） */
    internal val dialogDismiss = listOf("关闭", "取消", "以后再说", "跳过", "稍后", "x", "✕", "no", "cancel")
    /** 加载中关键词 */
    private val loadingWords = listOf("加载中", "请稍候", "请稍等", "loading")
    /** 异常页关键词 */
    private val errorWords = listOf("网络异常", "加载失败", "连接失败", "点击重试", "重试", "error", "失败")
    /** 完成页关键词（只匹配明确完成态短语，避免"完成/成功"裸词误判普通按钮） */
    private val completionWords = listOf("提交成功", "支付成功", "下单成功", "预约成功", "发布成功", "注册成功", "办理成功", "操作成功", "交易成功", "已完成")
    /** 倒计时广告关键词 */
    private val countdownWords = listOf("跳过", "秒", "广告")

    /**
     * 整词匹配：中文词用 contains；纯 ASCII 词用 \b 词边界（大小写不敏感）。
     * 避免 "resume" 命中 "preserved"、"ok" 命中 "broker" 这类英文子串误伤。
     */
    internal fun matchesWord(label: String, token: String): Boolean =
        if (token.all { it.code < 128 }) {
            Regex("""\b${Regex.escape(token)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)
        } else {
            label.contains(token)
        }

    /** 页面类型推断 */
    fun inferPageType(snapshot: ScreenSnapshot): String {
        val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }
        val text = labels.joinToString(" ")

        // 弹窗：正向/关闭按钮共存，且元素稀疏
        val hasPositive = labels.any { label -> dialogPositive.any { matchesWord(label, it) } }
        val hasDismiss = labels.any { label -> dialogDismiss.any { matchesWord(label, it) } }
        if (hasPositive && hasDismiss && snapshot.elements.size <= 8) return "dialog_overlay"

        // 倒计时广告：跳过按钮 + 数字
        val hasSkip = labels.any { it.contains("跳过") }
        // 数字与倒计时词分离判定："跳过"按钮和"5 秒"倒计时常常在不同控件上，
        // 要求同一 label 同时含两者会漏判（2026-08-15 深层排查 L1）
        val hasNumber = labels.any { it.contains(Regex("[0-9]+")) }
        val hasCountdownWord = labels.any { l -> countdownWords.any { w -> l.contains(w) } }
        if (hasSkip && hasNumber && hasCountdownWord) return "ad_with_countdown"

        // 加载中：加载关键词 + 元素少
        if (labels.any { loadingWords.any { w -> it.contains(w) } } && snapshot.elements.size < 3) return "loading"

        // 异常页
        if (labels.any { errorWords.any { w -> it.contains(w) } }) return "error"

        // 完成页
        if (labels.any { completionWords.any { w -> it.contains(w) } }) return "completion"

        // 输入框多 → 搜索/表单
        val editable = snapshot.elements.count { it.editable }
        if (editable > 0) {
            if (labels.any { it.contains("搜索") || it.contains("search") }) return "search_page"
            return "form"
        }
        // 可滚动 + 多个可点击卡片 → 列表
        if (snapshot.elements.any { it.scrollable } && snapshot.elements.count { it.clickable } > 3) return "content_list"
        return "generic"
    }
}

/** 元素的有效标签（text 优先，其次 contentDescription） */
fun UiElement.effectiveLabel(): String? =
    text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
