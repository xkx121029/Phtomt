package com.phoneagent.core.text

/**
 * 端侧"人话翻译器"（v2.2.1 5.1）：把技术术语硬编码映射成普通人能懂的表述。
 * 不调用云端（避免额外调用的不确定性与成本），找不到映射时原样返回原始信息。
 *
 * 同时提供从 AI 原始动作 JSON（receivedText）中抽取 动作类型/目标/置信度/理由，
 * 拼装成一条"人话摘要"。
 */
object HumanTranslator {

    /** 动作类型 → 人话动词 */
    private val actionVerbMap = mapOf(
        "tap" to "点击",
        "click" to "点击",
        "long_press" to "长按",
        "long_click" to "长按",
        "type" to "输入",
        "key" to "按键",
        "wait" to "等待",
        "launch" to "启动应用",
        "open" to "打开",
        "scroll_to" to "滚动查找",
        "scroll" to "滚动",
        "swipe" to "滑动",
        "swipe_up" to "上滑",
        "swipe_down" to "下滑",
        "swipe_left" to "左滑",
        "swipe_right" to "右滑",
        "back" to "返回",
        "home" to "回到桌面",
        "recents" to "调出最近任务",
        "task_done" to "完成任务",
        "task_complete" to "完成任务",
        "abort" to "放弃任务",
        "shell" to "执行命令",
        "write_doc" to "生成文档",
    )

    /** 异常技术描述 → 人话 */
    private val errorMap = mapOf(
        "目标定位失败" to "AI 在页面上找不到要操作的东西",
        "视觉定位超时" to "AI 截图识别时网络或服务超时了",
        "页面无变化" to "AI 点了但页面没反应",
        "未知 shell 命令" to "AI 发出了不存在的命令",
        "命令为空" to "AI 发了条空命令",
        "Shizuku 不可用" to "当前无法使用 ADB 级权限执行命令",
        "无障碍服务不可用" to "无障碍服务未开启，无法操作",
        "云端响应超时" to "AI 大脑（云端）暂时联系不上",
        "执行器不可用" to "当前无法自动操作手机（权限问题）",
    )

    private val pageTypeMap = mapOf(
        "search_page" to "搜索页",
        "search_result_list" to "搜索结果列表",
        "product_detail" to "商品详情页",
        "checkout" to "结算页",
        "dialog_overlay" to "弹窗",
        "ad_with_countdown" to "倒计时广告页",
        "loading" to "加载中页面",
        "completion" to "完成/成功页",
        "error" to "异常页",
        "form" to "表单填写页",
        "content_list" to "内容列表页",
        "generic" to "普通页面",
    )

    /** 动作类型 → 人话动词 */
    fun actionVerb(type: String): String = actionVerbMap[type] ?: type

    /** 动作类型是否可理解 */
    fun knowsAction(type: String): Boolean = actionVerbMap.containsKey(type)

    /** 错误技术描述 → 人话（无映射则原样返回） */
    fun translateError(technical: String): String {
        if (technical.isBlank()) return technical
        errorMap.forEach { (key, human) ->
            if (technical.contains(key)) return human
        }
        return technical
    }

    /** 页面类型 → 人话 */
    fun pageTypeTranslate(t: String): String = pageTypeMap[t] ?: t

    /** 置信度 → 把握话术 */
    fun confidenceWord(conf: Double?): String = when {
        conf == null -> ""
        conf >= 0.9 -> "很确定"
        conf >= 0.75 -> "较确定"
        conf >= 0.6 -> "不太确定"
        else -> "没把握"
    }

    /**
     * 置信度 → 0~1（用于画置信度条颜色占比）。null 返回 null。
     */
    fun confidenceRatio(conf: Double?): Double? = conf?.coerceIn(0.0, 1.0)

    /** 从原始动作 JSON 中抽取置信度（0~1），无则 null */
    fun extractConfidence(rawText: String): Double? =
        regexDouble(rawText, "\"confidence\"\\s*:\\s*([0-9.]+)")?.coerceIn(0.0, 1.0)

    /** 从原始动作 JSON 中抽取 reasoning（AI 第一人称思考用），无则 null */
    fun extractReasoning(rawText: String): String? =
        regexValue(rawText, "\"reasoning\"\\s*:\\s*\"([^\"]*)\"")

    /** 从发送给 AI 的决策文本中，抽取"当前页面"的关键信息（页面类型优先）用于''AI 看到了什么'' */
    fun extractSeen(sentText: String): String {
        if (sentText.isBlank()) return ""
        // 尝试抽取页面类型（中文/英文键）
        val pt = regexValue(sentText, "页面类型\\s*[：:]\\s*([^\\n，;。]+)")
        if (!pt.isNullOrBlank()) {
            val human = HumanTranslator.pageTypeTranslate(pt.trim())
            return if (human != pt.trim()) "页面类型：$human" else "页面类型：$pt"
        }
        val enPt = regexValue(sentText, "page_type\\s*[=:]\\s*([^\\n,\\s]+)")
        if (!enPt.isNullOrBlank()) return "页面类型：${pageTypeTranslate(enPt.trim())}"
        return "已把当前屏幕数据与分析发送给模型"
    }

    /**
     * 从 AI 原始动作 JSON（receivedText）抽取 类型/目标/置信度/理由，拼装人话摘要。
     * 轻量正则解析，不依赖完整 JSON 库，兼容大部分模型输出。
     * @return 形如 "点击「搜索框」· 把握 94% · 理由：……" 的人话；无法解析时返回原始文本截断。
     */
    fun summarizeDecision(rawText: String): String {
        val t = rawText.trim()
        if (t.isBlank()) return ""
        val type = regexValue(t, "\"type\"\\s*:\\s*\"([^\"]+)\"")
        if (type.isNullOrBlank()) return t.take(160)
        val verb = actionVerb(type)
        val target = regexValue(t, "\"target\"\\s*:\\s*\\{[^}]*?\"value\"\\s*:\\s*\"([^\"]*)\"[^}]*\\}")
            ?: regexValue(t, "\"target\"\\s*:\\s*\"([^\"]*)\"")
        val reasoning = regexValue(t, "\"reasoning\"\\s*:\\s*\"([^\"]*)\"")
        val confidence = regexDouble(t, "\"confidence\"\\s*:\\s*([0-9.]+)")

        val sb = StringBuilder(verb)
        if (!target.isNullOrBlank()) sb.append("「$target」")
        if (confidence != null) sb.append(" · ").append(confidenceWord(confidence)).append(" ${(confidence * 100).toInt()}%")
        if (!reasoning.isNullOrBlank()) sb.append(" · 因为：").append(reasoning)
        return sb.toString()
    }

    /** 从原始文本中抽出某 JSON 字段的字符串值（正则） */
    private fun regexValue(text: String, pattern: String): String? {
        val m = Regex(pattern, RegexOption.IGNORE_CASE).find(text) ?: return null
        return m.groupValues[1].replace("\\\"", "\"").take(40)
    }

    /** 从原始文本中抽出某 JSON 字段的 double 值（正则） */
    private fun regexDouble(text: String, pattern: String): Double? {
        val m = Regex(pattern, RegexOption.IGNORE_CASE).find(text) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }
}