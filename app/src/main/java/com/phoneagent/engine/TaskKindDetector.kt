package com.phoneagent.engine

import com.phoneagent.engine.prompt.PromptFlag

/**
 * 任务类型判定：**唯一判定点**。
 *
 * 两处消费它，必须同源，否则会出现"提示词按网页任务裁剪、附加指导却按普通任务注入"的错位：
 * - [AgentPrompts.system] 用它决定裁不裁 `sys.web_browse` / `sys.open_link` 两个大块；
 * - [AgentPrompts.situationalExtras] 用它决定注入哪几段「当前任务附加指导」。
 *
 * 纯函数、无 Android 依赖，可 JVM 单测。
 */
object TaskKindDetector {

    /**
     * 任务类型命中结果。各字段含义与原 [AgentPrompts.situationalExtras] 的关键词判定完全一致。
     *
     * @param known 任务文本是否已知。文本为空（例如只做等价性回归、或任务还没拿到）时
     *              [flagsOf] 会一律置位，宁可多给也不误裁。
     */
    data class Kind(
        val known: Boolean,
        val docHit: Boolean,
        val openTargetHit: Boolean,
        val openHit: Boolean,
        val browseHit: Boolean,
        val fetchHit: Boolean,
    )

    fun detect(lang: PromptLang, task: String, termuxAvailable: Boolean = false): Kind {
        val known = task.isNotBlank()
        val docHit = hits(lang, task, DOC_WORDS)
        // 「打开已有链接/文件」：任务里有具体文件扩展名或本地路径，或点名"用某应用打开"。
        // 它与「生成文档」是相反方向（打开已有文件 vs 产出新文档），命中时会压掉 docHit，
        // 否则 write_doc 模板会盖过 open，AI 会去"写"一份文档而不是打开用户给的那个文件。
        val openTargetHit = hits(lang, task, OPEN_TARGET_WORDS)
        val openHit = openTargetHit || hits(lang, task, OPEN_WORDS)
        // 上网类任务：内置浏览器常驻可用，命中即注入 browse_* 用法（不依赖 Termux）
        val browseHit = hits(lang, task, BROWSE_WORDS)
        // 仅当「任务要从网络取内容」且「本机确有 Termux 命令行通道」时，fetch 才可用
        val fetchHit = termuxAvailable && hits(lang, task, FETCH_WORDS)
        return Kind(known, docHit, openTargetHit, openHit, browseHit, fetchHit)
    }

    /**
     * 把任务类型翻译成装配标志位。
     *
     * 关键约定：**任务文本未知时不裁**。[PromptFlag.WEB_TASK] / [PromptFlag.OPEN_TASK]
     * 表达的是"可能与网页/打开相关"，因此 `browseHit || !known` 才置位——
     * 文本为空时等于"无法判断"，此时保留对应区块，避免把能力说明整个裁掉。
     */
    fun flagsOf(lang: PromptLang, task: String, termuxAvailable: Boolean = false): Set<PromptFlag> {
        val kind = detect(lang, task, termuxAvailable)
        val flags = LinkedHashSet<PromptFlag>()
        if (kind.browseHit || !kind.known) flags += PromptFlag.WEB_TASK
        if (kind.openHit || !kind.known) flags += PromptFlag.OPEN_TASK
        return flags
    }

    private fun hits(lang: PromptLang, task: String, words: Map<PromptLang, List<String>>): Boolean =
        words.getValue(lang).any { task.contains(it, ignoreCase = true) }

    private val DOC_WORDS = mapOf(
        PromptLang.CN to listOf(
            "周报", "日报", "清单", "总结", "报告", "资料", "笔记", "文章", "邮件", "方案",
            "攻略", "作业", "简历", "文档", "整理", "ppt", "PPT", "表格", "写一个", "写一篇",
        ),
        PromptLang.EN to listOf(
            "report", "checklist", "summary", "notes", "article", "email", "plan",
            "document", "weekly", "resume",
        ),
    )

    private val OPEN_TARGET_WORDS = mapOf(
        PromptLang.CN to listOf(
            ".ppt", ".pptx", ".doc", ".docx", ".pdf", ".xls", ".xlsx", ".csv", ".txt",
            "/sdcard", "/storage", "file://", "用浏览器打开", "用文档", "用 wps", "用wps",
            "打开这个文件", "打开该文件", "打开这个链接", "打开该链接", "打开该网址",
        ),
        PromptLang.EN to listOf(
            ".ppt", ".pptx", ".doc", ".docx", ".pdf", ".xls", ".xlsx", ".csv", ".txt",
            "/sdcard", "/storage", "file://", "open in browser", "open with",
        ),
    )

    private val OPEN_WORDS = mapOf(
        PromptLang.CN to listOf("打开", "直达", "搜索", "导航", "地图", "排序"),
        PromptLang.EN to listOf("open ", "direct", "navigate", "search for", "launch ", "website", "url"),
    )

    private val BROWSE_WORDS = mapOf(
        PromptLang.CN to listOf(
            "网页", "网址", "网站", "官网", "链接", "上网", "在线", "浏览器", "百度", "必应", "谷歌",
            "http", "www.", "搜索一下", "查一下", "查一查", "看看最新", "最新消息", "资讯", "新闻",
            "汇率", "天气", "股价", "股票", "评分", "百科",
        ),
        PromptLang.EN to listOf(
            "webpage", "web page", "website", "url", "http", "www.", "online", "browser",
            "google", "bing", "look up", "search online", "latest news", "news",
            "exchange rate", "weather", "stock",
        ),
    )

    private val FETCH_WORDS = mapOf(
        PromptLang.CN to listOf(
            "网页", "网址", "链接", "接口", "api", "API", "抓取", "爬", "解析", "json", "JSON",
            "汇率", "天气", "股价", "股票", "新闻", "请求", "页面内容", "网页内容",
        ),
        PromptLang.EN to listOf(
            "webpage", "url", "link", "api", "fetch", "scrape", "parse", "json",
            "exchange rate", "weather", "stock", "news", "request",
        ),
    )
}