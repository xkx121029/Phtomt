package com.phoneagent.engine

import com.phoneagent.engine.prompt.PromptFlag

/**
 * 任务类型判定：**唯一判定点**。
 *
 * 两处消费它，必须同源，否则会出现"提示词按网页任务裁剪、附加指导却按普通任务注入"的错位：
 * - [AgentPrompts.system] 用它决定裁不裁 `sys.web_browse` / `sys.open_link` 两个大块，
 *   并决定是否注入 `sys.assistant`（语音助手直达引导）；
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
        val assistantHit: Boolean,
        /** 普通对话类词命中（什么是/为什么/讲讲/翻译…→ 直答形态） */
        val chatHit: Boolean,
        /** 编码类词命中（写代码/脚本/编程/调试…→ vibecoding 委托形态） */
        val codingHit: Boolean,
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
        // 语音助手事务：定闹钟/打电话/发消息/控制硬件等系统级指令
        val assistantHit = hits(lang, task, ASSISTANT_WORDS)
        // 普通对话（直答）与编码（vibecoding 委托）两类新词表：与旧词表同构，互不改动旧字段语义；
        // chat 与 coding 同时命中视为多需求，由路由侧（见 AgentEngine.routeTask）按命中数取舍
        val chatHit = hits(lang, task, CHAT_WORDS)
        val codingHit = hits(lang, task, CODING_WORDS)
        return Kind(known, docHit, openTargetHit, openHit, browseHit, fetchHit, assistantHit, chatHit, codingHit)
    }

    /**
     * 把任务类型翻译成装配标志位。
     *
     * 关键约定：**任务文本未知时不裁**。[PromptFlag.WEB_TASK] / [PromptFlag.OPEN_TASK]
     * 表达的是"可能与网页/打开相关"，因此 `browseHit || !known` 才置位——
     * 文本为空时等于"无法判断"，此时保留对应区块，避免把能力说明整个裁掉。
     *
     * [PromptFlag.ASSISTANT_TASK] 则相反：它是**引导性块**而非能力声明，
     * 只在任务明确命中语音助手事务时注入，未知任务不置位（省 token，也不误导）。
     */
    fun flagsOf(lang: PromptLang, task: String, termuxAvailable: Boolean = false): Set<PromptFlag> {
        val kind = detect(lang, task, termuxAvailable)
        val flags = LinkedHashSet<PromptFlag>()
        if (kind.browseHit || !kind.known) flags += PromptFlag.WEB_TASK
        if (kind.openHit || !kind.known) flags += PromptFlag.OPEN_TASK
        if (kind.assistantHit) flags += PromptFlag.ASSISTANT_TASK
        // 引导块（与 ASSISTANT_TASK 同策略）：仅任务明确命中时置位，未知任务不误导也不占 token
        if (kind.codingHit) flags += PromptFlag.CODE_TASK
        // 纯对话：chat 命中且无任何其他形态命中（doc/open/browse/fetch/assistant/coding 全 false）
        if (kind.chatHit && !kind.codingHit && !kind.docHit && !kind.openHit &&
            !kind.browseHit && !kind.fetchHit && !kind.assistantHit
        ) {
            flags += PromptFlag.CHAT_TASK
        }
        return flags
    }

    /** 三形态的命中词数计分（Superagent 融合 Phase 4 多需求路由的简单置信度，见 AgentEngine.routeTask） */
    data class Scores(
        /** 编码词表命中数 */
        val coding: Int,
        /** 对话词表命中数 */
        val chat: Int,
        /** 操作类词表（doc/open/browse/fetch/assistant）命中数合计 */
        val agentOps: Int,
    )

    /** 按词表命中词数给三形态计分：多需求并存时路由侧据此取最高置信度者 */
    fun scores(lang: PromptLang, task: String, termuxAvailable: Boolean = false): Scores = Scores(
        coding = count(lang, task, CODING_WORDS),
        chat = count(lang, task, CHAT_WORDS),
        agentOps = count(lang, task, DOC_WORDS) +
            count(lang, task, OPEN_TARGET_WORDS) +
            count(lang, task, OPEN_WORDS) +
            count(lang, task, BROWSE_WORDS) +
            (if (termuxAvailable) count(lang, task, FETCH_WORDS) else 0) +
            count(lang, task, ASSISTANT_WORDS),
    )

    private fun hits(lang: PromptLang, task: String, words: Map<PromptLang, List<String>>): Boolean =
        words.getValue(lang).any { task.contains(it, ignoreCase = true) }

    private fun count(lang: PromptLang, task: String, words: Map<PromptLang, List<String>>): Int =
        words.getValue(lang).count { task.contains(it, ignoreCase = true) }

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

    private val ASSISTANT_WORDS = mapOf(
        PromptLang.CN to listOf(
            // 闹钟/计时/提醒
            "闹钟", "闹铃", "定时", "倒计时", "提醒",
            // 通讯
            "打电话", "打给", "拨打", "拨号", "呼叫", "回电", "发消息", "发微信", "发短信", "回复消息",
            // 媒体与硬件
            "播放", "放歌", "放一首", "暂停", "手电筒", "截屏", "录屏", "音量", "静音", "飞行模式", "蓝牙", "亮度",
        ),
        PromptLang.EN to listOf(
            // alarm / timer / reminder
            "alarm", "timer", "countdown", "remind me", "reminder",
            // communication
            "call ", "dial", "text ", "send a message", "message to", "make a call",
            // media & hardware
            "play music", "play a song", "play the song", "pause the music", "flashlight",
            "screenshot", "screen recording", "volume", "mute", "airplane mode", "bluetooth", "brightness",
        ),
    )

    /** 编码类词（vibecoding 委托形态）。与 DOC_WORDS 等旧词表重叠时（如「代码」）由多需求路由按命中数取舍，不改旧词表。 */
    private val CODING_WORDS = mapOf(
        PromptLang.CN to listOf(
            "写代码", "写个脚本", "写个程序", "编程", "跑一下", "运行脚本", "脚本",
            "函数", "报错", "调试", "代码", "算法", "斐波那契", "爬虫", "正则",
        ),
        PromptLang.EN to listOf(
            "python", "node", "javascript", "hello world", "algorithm",
            "compile", "debug", "script", "bug", "regex",
        ),
    )

    /** 普通对话类词（直答形态）。与 coding 的边界：「写一篇文章」→ 对话直答，「写个脚本」→ 编码；两表同时命中视为多需求。 */
    private val CHAT_WORDS = mapOf(
        PromptLang.CN to listOf(
            "什么是", "为什么", "怎么理解", "解释一下", "帮我写一篇文章", "写一篇文章",
            "讲讲", "介绍一下", "翻译", "是什么意思",
        ),
        PromptLang.EN to listOf(
            "who is", "what is", "why", "explain", "translate",
        ),
    )
}