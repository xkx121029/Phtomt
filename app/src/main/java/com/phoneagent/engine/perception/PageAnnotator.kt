package com.phoneagent.engine.perception.PageAnnotator

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement

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
)

/**
 * 端侧页面标注器：推断页面类型、生成上下文提示、标注控件优先级。
 */
object PageAnnotator {

    /** 弹窗正向按钮关键词 */
    private val dialogPositive = listOf("允许", "同意", "确定", "知道了", "始终允许", "授权", "resume", "continue", "ok")
    /** 弹窗关闭按钮关键词 */
    private val dialogDismiss = listOf("关闭", "取消", "以后再说", "跳过", "稍后", "x", "✕", "no", "cancel")
    /** 加载中关键词 */
    private val loadingWords = listOf("加载中", "请稍候", "请稍等", "loading")
    /** 异常页关键词 */
    private val errorWords = listOf("网络异常", "加载失败", "连接失败", "点击重试", "重试", "error", "失败")
    /** 完成页关键词（只匹配明确完成态短语，避免"完成/成功"裸词误判普通按钮） */
    private val completionWords = listOf("提交成功", "支付成功", "下单成功", "预约成功", "发布成功", "注册成功", "办理成功", "操作成功", "交易成功", "已完成")
    /** 倒计时广告关键词 */
    private val countdownWords = listOf("跳过", "秒", "广告")

    // ---- 语义 id 标定词表（扩展类别） ----
    /** 返回键 */
    private val backWords = listOf("返回", "退出", "向上")
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
        val pageType = inferPageType(snapshot)
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
        return AnnotatedPage(snapshot, pageType, contextHint, annotated, fingerprint)
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
        fun putOnce(value: String, pred: (UiElement) -> Boolean) {
            val first = snapshot.elements.firstOrNull(pred) ?: return
            ids.putIfAbsent(first.index, value)
        }
        // 可点击（操作类）元素的快捷谓词
        fun UiElement.isAction(): Boolean = clickable || longClickable

        // 1) 弹窗正向按钮（允许/同意/确定/授权…）
        putOnce("dlg_allow") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                dialogPositive.any { l.contains(it, ignoreCase = true) }
            }
        }
        // 2) 弹窗关闭按钮：仅当确认为"真弹窗"（有正向按钮且元素稀疏）才标定，避免误判普通页"取消"
        val isDialog = ids.values.contains("dlg_allow") && snapshot.elements.size <= 8
        if (isDialog) {
            putOnce("dlg_dismiss") { e ->
                e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                    dialogDismiss.any { l.contains(it, ignoreCase = true) }
                }
            }
        }
        // 3) 跳过广告（倒计时/广告页）
        putOnce("ad_skip") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").contains("跳过")
        }
        // 4) 返回键（图标/文字）
        putOnce("back_btn") { e ->
            val label = e.effectiveLabel() ?: ""
            val vi = e.viewId ?: ""
            e.isAction() &&
                (backWords.any { label.contains(it, ignoreCase = true) && !label.contains("退回") } ||
                    vi.endsWith("back", ignoreCase = true) ||
                    vi == "home_up")
        }
        // 5) 页面内关闭按钮（非弹窗的右上角关闭/×）
        putOnce("close_btn") { e ->
            val label = e.effectiveLabel() ?: ""
            val vi = e.viewId ?: ""
            e.isAction() && (closeWords.any { label.contains(it) } ||
                vi.contains("close", ignoreCase = true) && vi.contains("btn", ignoreCase = true))
        }
        // 6) 搜索框：可编辑输入框，语义含"搜索"
        putOnce("search_box") { e ->
            e.editable && (e.effectiveLabel() ?: e.viewId ?: "").let { l ->
                l.contains("搜索") || l.contains("search", ignoreCase = true)
            }
        }
        // 7) 搜索按钮：可点击非输入，文字为"搜索"
        putOnce("search_btn") { e ->
            e.isAction() && !e.editable && (e.effectiveLabel() ?: "").let { l ->
                l.contains("搜索") || l.equals("search", ignoreCase = true)
            }
        }
        // 8) 刷新（下拉刷新/重新加载入口）
        putOnce("refresh_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                refreshWords.any { l.contains(it, ignoreCase = true) }
            }
        }
        // 9) 发送（聊天/评论）
        putOnce("send_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                sendWords.any { l.contains(it, ignoreCase = true) }
            }
        }
        // 10) 确认/保存（区别于弹窗 dlg_allow：弹窗类已被优先占用同一元素，此处捕获普通页的确认/保存）
        putOnce("confirm_btn") { e ->
            e.isAction() && ids[e.index] == null && (e.effectiveLabel() ?: "").let { l ->
                confirmWords.any { l.contains(it, ignoreCase = true) }
            }
        }
        // 11) 结算/去支付（购物场景）
        putOnce("checkout_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                checkoutWords.any { l.contains(it, ignoreCase = true) }
            }
        }
        // 12) 删除/移除
        putOnce("delete_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                deleteWords.any { l.contains(it, ignoreCase = true) }
            }
        }
        // 13) 开关（Switch/Toggle）
        putOnce("switch_toggle") { e ->
            val cn = e.className ?: ""
            val vi = e.viewId ?: ""
            (cn.contains("switch", ignoreCase = true) || cn.contains("toggle", ignoreCase = true) ||
                vi.contains("switch", ignoreCase = true)) && (e.clickable || e.isEnabled)
        }
        // 14) 勾选（CheckBox/Radio/多选）
        putOnce("select_box") { e ->
            val cn = e.className ?: ""
            val typ = e.type ?: ""
            (cn.contains("checkbox", ignoreCase = true) || cn.contains("radio", ignoreCase = true) ||
                typ.contains("checkbox", ignoreCase = true)) && e.clickable
        }
        // 15) 更多/展开（更多操作区）
        putOnce("more_btn") { e ->
            val label = e.effectiveLabel() ?: ""
            e.isAction() && moreWords.any { label.contains(it) || label == it }
        }
        // 16) 添加/新建（含图标加号）
        putOnce("add_btn") { e ->
            val label = e.effectiveLabel() ?: ""
            val cd = e.contentDescription ?: ""
            val vi = e.viewId ?: ""
            e.isAction() && (addWords.any { label.contains(it) } ||
                cd.contains("加") || vi.contains("fab", ignoreCase = true) || vi.endsWith("add", ignoreCase = true))
        }
        // 17) 下一步/继续
        putOnce("next_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                nextWords.any { l.contains(it) && !l.contains("取消") }
            }
        }
        // 18) 登录/注册
        putOnce("login_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                loginWords.any { l.contains(it) }
            }
        }
        // 19) 分享/转发
        putOnce("share_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                shareWords.any { l.contains(it) }
            }
        }
        // 20) 收藏
        putOnce("collect_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                collectWords.any { l.contains(it) }
            }
        }
        // 21) 复制
        putOnce("copy_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                copyWords.any { l.contains(it) }
            }
        }
        // 22) 下载
        putOnce("download_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                downloadWords.any { l.contains(it) }
            }
        }
        // 23) 上传
        putOnce("upload_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                uploadWords.any { l.contains(it) }
            }
        }
        // 24) 设置入口（齿轮/更多设置）
        putOnce("settings_btn") { e ->
            val label = e.effectiveLabel() ?: ""
            val vi = e.viewId ?: ""
            e.isAction() && (settingsWords.any { label.contains(it) } ||
                vi.contains("settings", ignoreCase = true))
        }
        // 25) 排序/筛选
        putOnce("sort_btn") { e ->
            e.isAction() && (e.effectiveLabel() ?: "").let { l ->
                sortWords.any { l.contains(it) }
            }
        }
        // 26) 清除输入（输入框内 X）
        putOnce("clear_input") { e ->
            val label = e.effectiveLabel() ?: ""
            val vi = e.viewId ?: ""
            e.clickable && (label.contains("清除") || label.contains("清空") ||
                vi.contains("clear", ignoreCase = true) || vi.contains("delete", ignoreCase = true))
        }
        return ids
    }

    /**
     * 把已标定的已知控件渲染成给 AI 的清单文本。
     * AI 可直接用 target:{method:"id", value:"语义id"} 精确定位选择这些控件。
     */
    fun knownControlsText(elements: List<UiElement>): String {
        val known = elements.filter { !it.semanticId.isNullOrBlank() }
        if (known.isEmpty()) return ""
        val lines = known.joinToString("\n") { e ->
            val label = (e.text ?: e.contentDescription)?.takeIf { it.isNotBlank() } ?: e.type
            "- ${e.semanticId}: $label（#${e.index}, viewId=${e.viewId ?: "-"}）"
        }
        return "\n# 端侧已识别控件（可直接用 target:{method:\"id\",value:\"语义id\"} 选择）\n$lines"
    }

    /** 页面类型推断 */
    fun inferPageType(snapshot: ScreenSnapshot): String {
        val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }
        val text = labels.joinToString(" ")

        // 弹窗：正向/关闭按钮共存，且元素稀疏
        val hasPositive = labels.any { label -> dialogPositive.any { k -> label.contains(k, ignoreCase = true) } }
        val hasDismiss = labels.any { label -> dialogDismiss.any { k -> label.contains(k, ignoreCase = true) } }
        if (hasPositive && hasDismiss && snapshot.elements.size <= 8) return "dialog_overlay"

        // 倒计时广告：跳过按钮 + 数字
        val hasSkip = labels.any { it.contains("跳过") }
        val hasCount = labels.any { it.contains(Regex("[0-9]+")) && countdownWords.any { w -> it.contains(w) } }
        if (hasSkip && hasCount) return "ad_with_countdown"

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

/** 元素的有效标签（text 优先，其次 contentDescription） */
fun UiElement.effectiveLabel(): String? =
    text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }