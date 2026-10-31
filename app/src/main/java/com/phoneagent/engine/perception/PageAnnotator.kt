package com.phoneagent.engine.perception

import com.phoneagent.core.security.DataSanitizer
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.domain.rules.PageTypeRules
import com.phoneagent.domain.rules.effectiveLabel

/**
 * 页面标注结果：页面类型推断、语义上下文提示、控件优先级标注。
 * 对应文档“第 2 层 感知层 / 端侧页面标注器”。
 */
data class AnnotatedPage(
    val snapshot: ScreenSnapshot,
    /** 页面类型（search_page / product_detail / dialog_overlay / loading / error / generic ...） */
    val pageType: String,
    /** 语义描述提示，供 AI 理解页面 */
    val contextHint: String,
    /** 按优先级排序后的元素（high/medium/low） */
    val elements: List<UiElement>,
    /** fingerprint 字符串，供执行前后验证 */
    val fingerprint: String,
    /** 元素 index → 语义 id（由 [PageAnnotator.assignSemanticIds] 一次算出，供引擎回填原始快照） */
    val semanticIds: Map<Int, String> = emptyMap(),
)

/**
 * 端侧页面标注器：推断页面类型、生成上下文提示、标注控件优先级。
 */
object PageAnnotator {

    // 弹窗/加载/异常/完成等页面形态词表与整词匹配已下沉至 domain.rules.PageTypeRules
    // （消除 domain→engine 反向依赖，两端共用同一份定义）；此处语义 id 标定词表继续保留。

    /** 返回键（不含"退出"——"退出登录"这类按钮不是系统返回键） */
    private val backWords = listOf("返回", "向上")
    /** 发送 */
    private val sendWords = listOf("发送", "发表")
    /** 确认/保存 */
    private val confirmWords = listOf("确认", "保存", "完成")
    /** 结算/购买 */
    private val checkoutWords = listOf("结算", "去支付", "立即购买", "立即预订", "提交订单", "立即抢购")
    /** 删除 */
    private val deleteWords = listOf("删除", "移除")
    /** 刷新 */
    private val refreshWords = listOf("刷新", "重新加载")
    /** 关闭（页面内右上角关闭按钮，区别于弹窗取消） */
    private val closeWords = listOf("关闭", "✕", "×")
    /** 更多 */
    private val moreWords = listOf("更多", "展开", "··", "…")
    /** 添加/新建 */
    private val addWords = listOf("添加", "新建", "创建")
    /** 下一步 */
    private val nextWords = listOf("下一步", "继续", "下一页")
    /** 登录/注册 */
    private val loginWords = listOf("登录", "登陆", "注册")
    /** 分享 */
    private val shareWords = listOf("分享", "转发")
    /** 收藏 */
    private val collectWords = listOf("收藏")
    /** 复制 */
    private val copyWords = listOf("复制")
    /** 下载 */
    private val downloadWords = listOf("下载")
    /** 上传 */
    private val uploadWords = listOf("上传")
    /** 设置/更多设置入口 */
    private val settingsWords = listOf("设置", "更多设置")
    /** 排序 */
    private val sortWords = listOf("综合排序", "排序", "筛选", "更多筛选")

    fun annotate(snapshot: ScreenSnapshot): AnnotatedPage {
        val pageType = PageTypeRules.inferPageType(snapshot)
        val contextHint = generateContextHint(snapshot, pageType)
        val w = snapshot.screenWidth.takeIf { it > 0 } ?: 1
        val h = snapshot.screenHeight.takeIf { it > 0 } ?: 1
        // 端侧本地控件标定：把常见控件（弹窗按钮/搜索栏）映射为稳定语义 id，供 AI 直接按 target.id 选择
        val semanticIds = assignSemanticIds(snapshot)
        val annotated = snapshot.elements.map { e ->
            e.copy(
                priority = priorityOf(e),
                ratioX = e.centerX.toFloat() / w,
                ratioY = e.centerY.toFloat() / h,
                semanticId = semanticIds[e.index],
            )
        }.sortedWith(compareByDescending<UiElement> { priorityRank(it.priority) }.thenBy { it.index })
        val fingerprint = PageFingerprint.compute(snapshot)
        return AnnotatedPage(snapshot, pageType, contextHint, annotated, fingerprint, semanticIds)
    }

    /**
     * 本地控件标定：识别常见控件并分配稳定语义 id。
     * 识别类别：dlg_allow/dlg_dismiss(弹窗)、ad_skip(跳过广告)、back_btn(返回)、close_btn(页面内关闭)、
     * search_box/search_btn(搜索)、refresh_btn(刷新)、send_btn(发送)、confirm_btn(确认/保存)、
     * checkout_btn(结算/购买)、delete_btn(删除)、switch_toggle(开关)、select_box(勾选)、
     * more_btn(更多)、add_btn(添加)、next_btn(下一步)、login_btn(登录)、share_btn(分享)、
     * collect_btn(收藏)、copy_btn(复制)、download_btn(下载)、upload_btn(上传)、
     * settings_btn(设置)、sort_btn(排序/筛选)、clear_input(清除输入)。
     * 每个类别取首个命中元素；同一元素只分配首个命中的类别（用 putIfAbsent 保证不覆盖）。
     * @return 元素 index → 语义 id 的映射
     */
    private fun assignSemanticIds(snapshot: ScreenSnapshot): Map<Int, String> {
        val ids = LinkedHashMap<Int, String>()
        // 元素特征一次性提取：原实现按 26 个类别各做一次全量扫描，谓词里反复调用 effectiveLabel()；
        // 长列表页（200+ 元素）会重复生成数千个字符串。这里预计算一次，后续判定只读字段。
        val sigs = snapshot.elements.map { e -> ElementSig(e, e.effectiveLabel() ?: "") }
        fun putOnce(value: String, pred: (ElementSig) -> Boolean) {
            val first = sigs.firstOrNull(pred) ?: return
            ids.putIfAbsent(first.element.index, value)
        }

        // 1) 弹窗正向按钮（允许/同意/确定/授权…）
        putOnce("dlg_allow") { s ->
            s.action && PageTypeRules.dialogPositive.any { PageTypeRules.matchesWord(s.label, it) }
        }
        // 2) 弹窗关闭按钮：仅当确认为"真弹窗"（有正向按钮且元素稀疏）才标定，避免误判普通页"取消"
        val isDialog = ids.values.contains("dlg_allow") && snapshot.elements.size <= 8
        if (isDialog) {
            putOnce("dlg_dismiss") { s ->
                s.action && PageTypeRules.dialogDismiss.any { PageTypeRules.matchesWord(s.label, it) }
            }
        }
        // 3) 跳过广告（倒计时/广告页）
        putOnce("ad_skip") { s -> s.action && s.label.contains("跳过") }
        // 4) 返回键（图标/文字）
        putOnce("back_btn") { s ->
            s.action &&
                (backWords.any { s.label.contains(it, ignoreCase = true) && !s.label.contains("退回") } ||
                    s.viewId.endsWith("back", ignoreCase = true) ||
                    s.viewId == "home_up")
        }
        // 5) 页面内关闭按钮（非弹窗的右上角关闭/×）
        putOnce("close_btn") { s ->
            s.action && (closeWords.any { s.label.contains(it) } ||
                s.viewId.contains("close", ignoreCase = true) && s.viewId.contains("btn", ignoreCase = true))
        }
        // 6) 搜索框：可编辑输入框，语义含"搜索"
        putOnce("search_box") { s ->
            s.element.editable && s.label.ifEmpty { s.viewId }.let { l ->
                l.contains("搜索") || l.contains("search", ignoreCase = true)
            }
        }
        // 7) 搜索按钮：可点击非输入，文字为"搜索"
        putOnce("search_btn") { s ->
            s.action && !s.element.editable &&
                (s.label.contains("搜索") || s.label.equals("search", ignoreCase = true))
        }
        // 8) 刷新（下拉刷新/重新加载入口）
        putOnce("refresh_btn") { s ->
            s.action && refreshWords.any { s.label.contains(it, ignoreCase = true) }
        }
        // 9) 发送（聊天/评论）
        putOnce("send_btn") { s ->
            s.action && sendWords.any { s.label.contains(it, ignoreCase = true) }
        }
        // 10) 确认/保存（区别于弹窗 dlg_allow：弹窗类已被优先占用同一元素，此处捕获普通页的确认/保存）
        putOnce("confirm_btn") { s ->
            s.action && ids[s.element.index] == null &&
                confirmWords.any { s.label.contains(it, ignoreCase = true) }
        }
        // 11) 结算/去支付（购物场景）
        putOnce("checkout_btn") { s ->
            s.action && checkoutWords.any { s.label.contains(it, ignoreCase = true) }
        }
        // 12) 删除/移除
        putOnce("delete_btn") { s ->
            s.action && deleteWords.any { s.label.contains(it, ignoreCase = true) }
        }
        // 13) 开关（Switch/Toggle）
        putOnce("switch_toggle") { s ->
            (s.className.contains("switch", ignoreCase = true) || s.className.contains("toggle", ignoreCase = true) ||
                s.viewId.contains("switch", ignoreCase = true)) && (s.element.clickable || s.element.isEnabled)
        }
        // 14) 勾选（CheckBox/Radio/多选）
        putOnce("select_box") { s ->
            (s.className.contains("checkbox", ignoreCase = true) || s.className.contains("radio", ignoreCase = true) ||
                s.type.contains("checkbox", ignoreCase = true)) && s.element.clickable
        }
        // 15) 更多/展开（更多操作区）
        putOnce("more_btn") { s ->
            s.action && moreWords.any { s.label.contains(it) || s.label == it }
        }
        // 16) 添加/新建（含图标加号）
        putOnce("add_btn") { s ->
            s.action && (addWords.any { s.label.contains(it) } ||
                s.desc.contains("加") || s.viewId.contains("fab", ignoreCase = true) ||
                s.viewId.endsWith("add", ignoreCase = true))
        }
        // 17) 下一步/继续
        putOnce("next_btn") { s ->
            s.action && nextWords.any { s.label.contains(it) && !s.label.contains("取消") }
        }
        // 18) 登录/注册
        putOnce("login_btn") { s ->
            s.action && loginWords.any { s.label.contains(it) }
        }
        // 19) 分享/转发
        putOnce("share_btn") { s ->
            s.action && shareWords.any { s.label.contains(it) }
        }
        // 20) 收藏
        putOnce("collect_btn") { s ->
            s.action && collectWords.any { s.label.contains(it) }
        }
        // 21) 复制
        putOnce("copy_btn") { s ->
            s.action && copyWords.any { s.label.contains(it) }
        }
        // 22) 下载
        putOnce("download_btn") { s ->
            s.action && downloadWords.any { s.label.contains(it) }
        }
        // 23) 上传
        putOnce("upload_btn") { s ->
            s.action && uploadWords.any { s.label.contains(it) }
        }
        // 24) 设置入口（齿轮/更多设置）
        putOnce("settings_btn") { s ->
            s.action && (settingsWords.any { s.label.contains(it) } ||
                s.viewId.contains("settings", ignoreCase = true))
        }
        // 25) 排序/筛选
        putOnce("sort_btn") { s ->
            s.action && sortWords.any { s.label.contains(it) }
        }
        // 26) 清除输入（输入框内 X；不含 delete——btn_delete_item 这类删除按钮不是清空输入框）
        putOnce("clear_input") { s ->
            s.element.clickable && (s.label.contains("清除") || s.label.contains("清空") ||
                s.viewId.contains("clear", ignoreCase = true))
        }
        return ids
    }

    /**
     * 元素特征快照：只缓存"需要拼接才能得到"的 label，其余字段直接读原元素（纯字段访问、不分配）。
     * 供 26 个语义类别复用，避免谓词里重复生成 label 字符串。
     */
    private class ElementSig(val element: UiElement, val label: String) {
        val desc: String get() = element.contentDescription ?: ""
        val viewId: String get() = element.viewId ?: ""
        val className: String get() = element.className
        val type: String get() = element.type
        val action: Boolean get() = element.clickable || element.longClickable
    }

    /**
     * 把已标定的已知控件渲染成给 AI 的清单文本。
     * AI 可直接用 target:{by:"id", value:"语义id"} 精确定位选择这些控件（契约字段是 by，见 AgentIntentTarget）。
     */
    fun knownControlsText(elements: List<UiElement>): String {
        val known = elements.filter { !it.semanticId.isNullOrBlank() }
        if (known.isEmpty()) return ""
        val lines = known.joinToString("\n") { e ->
            val raw = (e.text ?: e.contentDescription)?.takeIf { it.isNotBlank() } ?: e.type
            // 脱敏铁律：控件文字与 toAiText 同路上云，必须过 DataSanitizer——
            // 页面上的手机号/身份证/卡号若以原文进 prompt，等于绕过端侧脱敏明文外送
            val label = DataSanitizer.sanitize(raw)
            "- ${e.semanticId}: $label（#${e.index}, viewId=${e.viewId ?: "-"}）"
        }
        return "\n# 端侧已识别控件（可直接用 target:{by:\"id\",value:\"语义id\"} 选择）\n$lines"
    }

    // 页面类型推断（inferPageType）已下沉至 domain.rules.PageTypeRules，
    // 端侧决策引擎与感知层共用同一份判定，词表仅此一处定义。

    /** 生成语义上下文提示 */
    private fun generateContextHint(snapshot: ScreenSnapshot, pageType: String): String {
        val sb = StringBuilder("页面类型：$pageType")
        when (pageType) {
            "dialog_overlay" -> sb.append("；检测到弹窗，需先处理（点击正向或关闭按钮）")
            "ad_with_countdown" -> sb.append("；【⚠️ 疑似倒计时广告】请尽快点击跳过按钮")
            "loading" -> sb.append("；页面加载中，请等待")
            "error" -> sb.append("；页面出现异常，可尝试重试或返回")
            "completion" -> sb.append("；任务可能已完成")
            else -> {
                val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }
                if (labels.isNotEmpty()) sb.append("；主要控件：${labels.take(6).joinToString("、")}")
            }
        }
        return sb.toString()
    }

    /** 控件优先级：high/medium/low */
    private fun priorityOf(e: UiElement): String = when {
        e.editable -> "high"
        e.clickable -> "medium"
        else -> "low"
    }

    private fun priorityRank(p: String): Int = when (p) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }
}