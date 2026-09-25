package com.phoneagent.engine

import com.phoneagent.domain.model.AppPageIndex
import com.phoneagent.domain.rules.ShellCommands
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
import com.phoneagent.engine.prompt.PromptAssembler
import com.phoneagent.engine.prompt.PromptContext
import com.phoneagent.engine.prompt.PromptFlag
import com.phoneagent.engine.prompt.PromptGroup
import com.phoneagent.engine.prompt.PromptVars
import com.phoneagent.feature.browser.BrowserGuard

/**
 * 提示词语言。用户可在设置中手动切换。
 */
enum class PromptLang(val label: String) {
    CN("中文"),
    EN("English"),
}

/** 注入给 AI 的环境事实（端侧采集，已是本地化后的可读结论） */
data class EnvFacts(
    /** 日期时间，如「2026-09-20 周六 15:04」 */
    val dateTime: String = "",
    /** 网络，如「Wi-Fi」 */
    val network: String = "",
    /** 电量，如「62%（充电中）」 */
    val battery: String = "",
    /** 前台应用，如「微信(com.tencent.mm)」 */
    val foreground: String = "",
    /** 已安装可启动应用数量 */
    val installedCount: Int = 0,
)

/** 会话承接用的上一轮任务要点（端侧从任务记忆里抽取） */
data class PreviousTask(
    val goal: String,
    val statusLabel: String,
    /** 任务结论（完成说明），未完成时为空 */
    val conclusion: String = "",
)

/**
 * HPA 动作执行逻辑的双语提示词入口。
 *
 * **本类只是门面**：正文全部住在 [com.phoneagent.engine.prompt.PromptCatalog]（区块清单）与
 * 其中的 `PromptBodies`（正文），由 [PromptAssembler] 按"语言 + 条件标志 + 变量"现场装配。
 * 这里不再有任何硬编码文案，只做三件事：把形参翻译成标志位与变量、调装配器、按历史结构拼出最终文本。
 *
 * 这样做的收益是**按任务裁块**：与当下无关的大块（如非网页任务里的「网页浏览」27 行说明）
 * 默认自动缺席，而铁律 / 授权范围 / 禁止输出 / 能力声明等安全块永不缺席
 * （由 PromptCropTest 穷举断言）。全开时输出与重构前逐字节相同，由 PromptSnapshotTest 金样本锁定。
 */
object AgentPrompts {

    // ==================== 共享常量 ====================

    /** 国产应用速查（中文名=英文名/包名）。open_app 的 app 字段可直接写中文名，端侧负责转包名。 */
    private const val COMMON_CN_APPS =
        "微信=WeChat(com.tencent.mm)、QQ=QQ(com.tencent.mobileqq)、支付宝=Alipay(com.eg.android.AlipayGphone)、" +
            "淘宝=Taobao(com.taobao.taobao)、京东=JD(com.jingdong.app.mall)、拼多多=Pinduoduo(com.xunmeng.pinduoduo)、" +
            "抖音=Douyin(com.ss.android.ugc.aweme)、快手=Kuaishou(com.smile.gifmaker)、哔哩哔哩=Bilibili(tv.danmaku.bili)、" +
            "微博=Weibo(com.sina.weibo)、小红书=Xiaohongshu/RED(com.xingin.xhs)、美团=Meituan(com.sankuai.meituan)、" +
            "饿了么=Ele.me(me.ele)、滴滴出行=DiDi(com.sdu.didi.psnger)、高德地图=Amap(com.autonavi.minimap)、百度地图=Baidu Maps(com.baidu.BaiduMap)、" +
            "百度=Baidu(com.baidu.searchbox)、网易云音乐=NetEase Cloud Music(com.netease.cloudmusic)、腾讯视频=Tencent Video(com.tencent.qqlive)、" +
            "爱奇艺=iQIYI(com.qiyi.video)、优酷=Youku(com.youku.phone)、钉钉=DingTalk(com.alibaba.android.rimet)、企业微信=WeCom(com.tencent.wework)"

    // ==================== 装配上下文 ====================

    /** 当前语言下不可逆操作词表（与端侧门控 [BrowserGuard] 同源，杜绝提示词与判定漂移） */
    private fun irreversibleWords(lang: PromptLang): String =
        if (lang == PromptLang.CN) BrowserGuard.promptWords() else BrowserGuard.promptWordsEn()

    /**
     * 系统提示主体的装配上下文。
     *
     * [task] 决定裁不裁「网页浏览」「打开链接与文件」两个大块（见 [TaskKindDetector.flagsOf]）；
     * 任务文本为空（拿不到任务时的等价性回归 / 预热）一律不裁，宁可多给也不误裁。
     */
    private fun systemCtx(lang: PromptLang, actionMode: ActionMode, task: String): PromptContext =
        PromptContext(
            lang = lang,
            flags = TaskKindDetector.flagsOf(lang, task),
            vars = PromptVars(
                ironRule2 = if (lang == PromptLang.CN) ironRule2CN(actionMode) else ironRule2EN(actionMode),
                commonCnApps = COMMON_CN_APPS,
                irreversibleWords = irreversibleWords(lang),
            ),
        )

    /** 授权范围段落的装配上下文：三档互斥，天然只命中一块 */
    private fun modeCtx(lang: PromptLang, mode: ActionMode): PromptContext =
        PromptContext(
            lang = lang,
            flags = setOf(
                when (mode) {
                    ActionMode.CONSERVATIVE -> PromptFlag.ACTION_CONSERVATIVE
                    ActionMode.BALANCED -> PromptFlag.ACTION_BALANCED
                    ActionMode.FREE -> PromptFlag.ACTION_FREE
                },
            ),
            vars = PromptVars(
                modeLabel = mode.label,
                modeSummary = mode.summary,
                modeLabelEn = mode.labelEn,
                modeKey = mode.key,
                modeSummaryEn = mode.summaryEn,
                lowRisk = ActionPolicy.lowRisk.joinToString("/"),
                shellCommands = ShellCommands.promptDoc(if (lang == PromptLang.CN) "CN" else "EN"),
                a11yTable = ActionPolicy.a11yEndpoints.joinToString("\n") {
                    "| ${it.name} | ${it.argsText()} | ${if (lang == PromptLang.CN) it.description else it.descriptionEn} |"
                },
            ),
        )

    // ==================== 一、系统 Prompt ====================

    /**
     * @param skills 技能区块（见 [skillSection]），追加在系统提示末尾；为空则不加，不增加 AI 负担
     * @param actionMode 本次任务生效的动作模式（保守/均衡/自由），决定授权范围那一段怎么写、铁律 2 是否放开
     * @param task 用户任务原文，仅用于**裁剪与当下无关的大块**（不参与正文渲染，正文里的任务文本由各阶段各自注入）
     */
    fun system(
        lang: PromptLang,
        custom: String,
        hasVision: Boolean,
        shizukuAvailable: Boolean,
        skills: String = "",
        actionMode: ActionMode = ActionMode.DEFAULT,
        task: String = "",
    ): String {
        // hasVision / shizukuAvailable 保留形参：视觉能力声明已独立成 capabilitiesLang（独立 system 消息），
        // 系统主体正文不使用它们（正文里没有对应占位符），但调用点契约不变。
        val base = custom.ifBlank { PromptAssembler.assemble(PromptGroup.SYSTEM, systemCtx(lang, actionMode, task)) }
        // 授权范围一段无论用户是否自定义提示词都必须追加：它是端侧真实拒绝逻辑的说明书，
        // 缺了它 AI 会按自己的想象发请求，然后在门控那里反复撞墙
        val withMode = "$base\n\n${actionModeSection(lang, actionMode)}"
        return if (skills.isBlank()) withMode else "$withMode\n\n$skills"
    }

    /**
     * 动作模式（授权范围）区块：三档各写一份，与 [ActionPolicy] 的判定逐条对应。
     *
     * 这是"提示词 ↔ 端侧门控"的同源点：AI 在这一段看到的可用意图清单，
     * 就是 [ActionPolicy] 真实放行的那一批，不会出现"说了能用其实被拒"。
     */
    fun actionModeSection(lang: PromptLang, mode: ActionMode): String =
        PromptAssembler.assemble(PromptGroup.ACTION_MODE, modeCtx(lang, mode))

    /** 铁律 2：默认禁止输出命令/坐标；只有在自由模式下才放开"自写命令 + 直调端点"，其余约束不变 */
    private fun ironRule2CN(mode: ActionMode): String = if (mode == ActionMode.FREE) {
        "字段名只能是 intent（禁止 type/action）；禁止输出像素坐标——定位与算坐标仍由端侧本地完成。" +
            "自由模式额外允许 intent=shell（自写命令，写在 command 里）与 intent=a11y（直调无障碍端点），" +
            "但这两类只是兜底：能用意图表表达的一律用意图表，且不得输出无障碍实现细节（如 performAction、节点对象）。"
    } else {
        "字段名只能是 intent（禁止 type/action）；禁止输出 shell 命令、无障碍指令、像素坐标——" +
            "\"怎么做\"（选通道、定位、算坐标、转命令）全由端侧本地完成，你永远看不到也不需要知道命令长什么样。"
    }

    private fun ironRule2EN(mode: ActionMode): String = if (mode == ActionMode.FREE) {
        "The field name MUST be \"intent\" (NOT type/action); NEVER output pixel coordinates — locating and coordinate computing stay on-device. " +
            "Free mode additionally allows intent=shell (self-written command in `command`) and intent=a11y (direct accessibility endpoint call), " +
            "but both are fallbacks: use the intent tables whenever they can express it, and never emit accessibility implementation details (performAction, node objects)."
    } else {
        "The field name MUST be \"intent\" (NOT type/action); NEVER output shell commands, accessibility instructions, or pixel coordinates — " +
            "\"how\" (channel, locating, coordinates, command translation) happens locally on-device; you never see or need to know the command."
    }

    /**
     * 技能区块：告诉 AI「技能名/id 也能表达做什么」以及「哪些 MCP 技能可以调用、怎么传参」。
     *
     * 内置技能与上表意图一一对应，故不重复罗列（只说明等价关系），避免提示词翻倍、AI 理解负担上升；
     * 真正需要额外说明的是 MCP 技能（参数名 AI 猜不到）与"已停用技能"（避免 AI 白试一轮）。
     *
     * @param mcpLines 已启用 MCP 技能的一行式说明（由 [com.phoneagent.feature.skill.SkillExecutionGateway.mcpSkillLine] 生成）
     * @param disabledNames 已被用户停用的技能名
     * @param hasMcpServer 是否已配置并启用 MCP 服务器（用于提示"已配置但未绑定技能"）
     */
    fun skillSection(
        lang: PromptLang,
        mcpLines: List<String>,
        disabledNames: List<String>,
        hasMcpServer: Boolean,
    ): String {
        val flags = buildSet {
            if (mcpLines.isNotEmpty()) add(PromptFlag.MCP_TOOLS)
            // 只在"配了服务器却一个工具都没绑定"时提示，避免和 MCP 表同时出现
            if (mcpLines.isEmpty() && hasMcpServer) add(PromptFlag.MCP_SERVER_ONLY)
            if (disabledNames.isNotEmpty()) add(PromptFlag.DISABLED_SKILLS)
        }
        val vars = PromptVars(
            mcpTable = mcpLines.joinToString("\n"),
            disabledNames = if (lang == PromptLang.CN) disabledNames.joinToString("、") else disabledNames.joinToString(", "),
        )
        // 每块自带尾随换行，整组装配后统一裁掉，与原文 sb.toString().trimEnd() 一致
        return PromptAssembler.assemble(PromptGroup.SKILLS, PromptContext(lang, flags, vars)).trimEnd()
    }

    /** 功能可用性说明（双语）：视觉声明是独立的一条 system 消息，故单独成组 */
    fun capabilitiesLang(lang: PromptLang, hasVision: Boolean): String =
        PromptAssembler.assemble(
            PromptGroup.CAPABILITIES,
            PromptContext(lang, if (hasVision) setOf(PromptFlag.HAS_VISION) else emptySet()),
        )

    // ==================== 环境上下文（每步/规划都会注入） ====================

    /**
     * 环境上下文：告诉 AI「现在几点、在哪个应用、网络电量如何、装了多少应用」。
     *
     * 这些是页面元素树里读不到的事实（日期决定"明天"是哪天，前台应用决定它面前这一页属于谁），
     * 缺了它们 AI 只能靠猜。完整应用清单刻意不给——体积大、绝大多数步骤用不上，
     * 需要时由 AI 自己用 device_query 查（见系统提示的独占路由规则 3）。
     */
    fun environment(lang: PromptLang, env: EnvFacts): String = when (lang) {
        PromptLang.CN -> buildString {
            append("\n\n# 环境上下文（端侧实时采集）")
            if (env.dateTime.isNotBlank()) append("\n- 当前时间：${env.dateTime}")
            if (env.foreground.isNotBlank()) append("\n- 前台应用：${env.foreground}")
            val net = env.network.ifBlank { "未知" }
            val bat = env.battery.ifBlank { "未知" }
            append("\n- 网络：$net；电量：$bat")
            append("\n- 已安装应用：${env.installedCount} 个（清单未提供，需要时输出 device_query 查 kind=apps，可用 filter 按关键词缩小）")
        }
        PromptLang.EN -> buildString {
            append("\n\n# Environment (collected on-device, live)")
            if (env.dateTime.isNotBlank()) append("\n- Current time: ${env.dateTime}")
            if (env.foreground.isNotBlank()) append("\n- Foreground app: ${env.foreground}")
            val net = env.network.ifBlank { "unknown" }
            val bat = env.battery.ifBlank { "unknown" }
            append("\n- Network: $net; battery: $bat")
            append("\n- Installed apps: ${env.installedCount} (list not provided; query it with device_query kind=apps, narrow it with filter)")
        }
    }

    // ==================== 会话承接（连续对话的上一轮任务） ====================

    /**
     * 会话承接块：把**本对话内**更早的往来摆给 AI，让"再改一下"这类追问有据可依。
     * 素材由引擎按对话边界筛选后传入（跨对话的记录不会出现在这里）。
     *
     * 只在确有往来时注入；[followUp] 为真（用户这轮用了指代词）时额外强调"本轮说的是上一轮"。
     */
    fun sessionContext(lang: PromptLang, previous: List<PreviousTask>, followUp: Boolean): String {
        if (previous.isEmpty()) return ""
        val sb = StringBuilder()
        when (lang) {
            PromptLang.CN -> {
                sb.append("\n\n# 会话承接（本对话中更早的往来，仅供理解用户意图，无关时忽略）")
                previous.forEachIndexed { i, p ->
                    val head = if (i == 0) "上一轮" else "更早的第 ${i} 轮"
                    sb.append("\n- $head：${p.goal.take(120)} —— ${p.statusLabel}")
                    if (p.conclusion.isNotBlank()) sb.append("；结论：${p.conclusion.take(120)}")
                }
                if (followUp) {
                    sb.append("\n⚠️ 本轮输入含指代词（再/接着/刚才/这个等），判定为对上一轮的追问：")
                    sb.append("必须以「上一轮」为目标主体规划与执行，承接它的目标与已完成结果，不要重复已完成的部分。")
                } else {
                    sb.append("\n本轮是新一轮输入：与上面的往来有关就承接其目标与结果，无关就当作独立任务。")
                }
            }
            PromptLang.EN -> {
                sb.append("\n\n# Conversation Carry-over (earlier exchanges in this conversation; only for understanding intent, ignore if unrelated)")
                previous.forEachIndexed { i, p ->
                    val head = if (i == 0) "Previous turn" else "Earlier turn $i"
                    sb.append("\n- $head: ${p.goal.take(120)} — ${p.statusLabel}")
                    if (p.conclusion.isNotBlank()) sb.append("; outcome: ${p.conclusion.take(120)}")
                }
                if (followUp) {
                    sb.append("\n⚠️ This input references the previous turn (再/接着/刚才/这个…), so treat it as a follow-up:")
                    sb.append(" plan and execute against the previous turn's goal, carry over its results, and do not redo what is already done.")
                } else {
                    sb.append("\nThis is a new input: carry over the goal/results above when related, otherwise treat it as independent.")
                }
            }
        }
        return sb.toString()
    }

    // ==================== 二、歧义检测 + 规划 ====================

    fun planning(lang: PromptLang, task: String, profile: String, installedApps: String): String =
        PromptAssembler.assemble(
            PromptGroup.PLANNING,
            PromptContext(
                lang = lang,
                vars = PromptVars(
                    task = task,
                    profile = profile.ifBlank { if (lang == PromptLang.CN) "无" else "none" },
                    installedApps = installedApps.ifBlank { if (lang == PromptLang.CN) "未知" else "unknown" },
                    commonCnApps = COMMON_CN_APPS,
                    irreversibleWords = irreversibleWords(lang),
                ),
            ),
        )

    // ==================== 三、每步决策 ====================

    /** 审核者系统提示：独立 AI 复核执行者意图是否基于当前页面真实证据，防止脑补现状 */
    fun reviewSystem(lang: PromptLang): String =
        PromptAssembler.assemble(PromptGroup.REVIEW, PromptContext(lang))

    fun decision(
        lang: PromptLang,
        task: String,
        stepIndex: Int,
        totalSteps: Int,
        currentStep: String,
        lastStepResult: String,
        consecutiveFailures: Int,
        contextHint: String,
        /** 记忆简报（见 MemoryBrief），空串表示无记忆可注入 */
        memory: String = "",
        /** 本次命中的经验规则（见 RuleScoper），空串表示无命中 */
        evolvedRules: String = "",
    ): String {
        val flags = buildSet {
            if (memory.isNotBlank()) add(PromptFlag.HAS_MEMORY)
            if (evolvedRules.isNotBlank()) add(PromptFlag.HAS_EVOLVED_RULES)
        }
        return PromptAssembler.assemble(
            PromptGroup.DECISION,
            PromptContext(
                lang = lang,
                flags = flags,
                vars = PromptVars(
                    task = task,
                    stepIndex = "$stepIndex",
                    totalSteps = "$totalSteps",
                    currentStep = currentStep,
                    lastResult = lastStepResult.ifBlank { if (lang == PromptLang.CN) "无" else "none" },
                    failures = "$consecutiveFailures",
                    contextHint = contextHint,
                    memory = memory,
                    evolvedRules = evolvedRules,
                ),
            ),
        )
    }

    // ==================== 三·五、记忆提炼 ====================

    /**
     * 任务结束后的记忆提炼：独立于主决策上下文，只回传任务与执行摘要，不喂原始提示词。
     * 要求极简输出，宁缺勿滥——没有值得长期保留的就返回空数组。
     */
    fun memoryDistill(lang: PromptLang, task: String, outcome: String, stepsSummary: String): String =
        PromptAssembler.assemble(
            PromptGroup.DISTILL,
            PromptContext(
                lang = lang,
                vars = PromptVars(
                    task = task,
                    outcome = outcome,
                    stepsSummary = stepsSummary.ifBlank { if (lang == PromptLang.CN) "无" else "none" },
                ),
            ),
        )

    // ==================== 九、按需附加指导（动态增减提示词） ====================
    /**
     * 根据当前任务命中情况，动态追加完整模板/索引，避免把与任务无关的长段落全量塞给 AI。
     * 未命中任何场景时返回空串，不增加任何负担。
     * - 文档类任务（周报/清单/总结/报告/笔记等）→ 注入 write_doc 完整模板 + 铁律
     * - 直达/开启类任务（打开网页/应用/搜索/导航）→ 注入 open 直达说明 + 软件页面索引
     * - 上网类任务（网页/网址/查资料/资讯等）→ 注入 browse_* 用法 + 边界（内置浏览器常驻可用，不依赖 Termux）
     * - 取数类任务（纯文本接口/汇率/天气等）且本机有 Termux 通道 → 注入 fetch 用法 + 边界（网页界面一律走 browse_*）
     *
     * 判定一律走 [TaskKindDetector]，与系统提示的裁块判定同源：否则会出现
     * "提示词按网页任务裁了块、附加指导却按普通任务注入"的错位。
     */
    fun situationalExtras(
        lang: PromptLang,
        task: String,
        termuxAvailable: Boolean = false,
    ): String {
        val kind = TaskKindDetector.detect(lang, task, termuxAvailable)
        val docHit = kind.docHit && !kind.openTargetHit
        val openHit = kind.openHit
        val browseHit = kind.browseHit
        val fetchHit = kind.fetchHit

        val sb = StringBuilder()
        if (docHit) {
            sb.append("\n\n## 当前任务附加指导 · 文档生成\n")
            if (lang == PromptLang.CN) {
                sb.append("检测到本任务需要生成/整理文档。必须直接输出 write_doc，禁止在屏幕上打字、打开记事本/便签、或用 shell 写文件。模板：\n")
                sb.append("""{"intent":"write_doc","text":"完整文档内容（Markdown）","summary":"文件名.md","reasoning":"生成文档并在 Agent 页预览","expected":"文档已生成","confidence":0.95}""")
            } else {
                sb.append("This task requires generating/compiling a document. Must output write_doc directly; do NOT type on screen, open a notes app, or use shell to write files. Template:\n")
                sb.append("""{"intent":"write_doc","text":"full document content (Markdown)","summary":"filename.md","reasoning":"generate document, preview on the Agent page","expected":"document generated","confidence":0.95}""")
            }
        }
        if (openHit) {
            sb.append("\n\n## 当前任务附加指导 · 页面直达(open)\n")
            if (lang == PromptLang.CN) {
                sb.append("若目标页面有稳定直达方式，优先用 open 一键直达，减少逐步点击。")
                sb.append("App 内页/系统页用 uri，公开 scheme 用官方 scheme；封闭 App（如微信聊天）不发明 scheme，改用 open_app 逐步。")
                sb.append("要把链接/文件交给系统应用打开也用它：网址给系统浏览器，本地文件（/sdcard/…、file://…）给系统文档/图片/播放器，端侧自动补类型；泛指不必填 app（端侧优先系统自带应用），用户点名了具体应用才填 app。")
                sb.append("""模板：{"intent":"open","uri":"/sdcard/Download/季度汇报.ppt","reasoning":"用文档软件打开PPT","expected":"文档应用显示该PPT","confidence":0.9}""")
                sb.append("\n要读网页内容仍用 browse_*（见上网与网页操作）；uri 必须是用户给的或上一步结果里真实出现的，禁止编造路径。")
                sb.append("以下软件页面可直达（用 open 的 app+page 字段，先声明软件与页面再填页码）：\n${AppPageIndex.indexText()}")
            } else {
                sb.append("If the target page has a stable direct open, prefer open to jump there directly. Use uri for in-app/system pages; official scheme for public schemes; do NOT invent schemes for closed apps — use open_app instead.")
                sb.append("Use it as well to hand a link/file to a system app: URLs go to the system browser, local files (/sdcard/…, file://…) to the system document/image/player app; the device fills in the MIME type. Leave app empty for generic targets (the device prefers system apps); set app only when the user named a specific app.")
                sb.append("""Template: {"intent":"open","uri":"/sdcard/Download/report.ppt","reasoning":"open the PPT with a document app","expected":"document app shows the PPT","confidence":0.9}""")
                sb.append("\nReading page content still goes through browse_* (see the browsing section); uri MUST be given by the user or appear in the last step result — never invent a path.")
                sb.append("Directly openable software pages (use open's app+page fields):\n${AppPageIndex.indexText()}")
            }
        }
        if (browseHit) {
            sb.append("\n\n## 当前任务附加指导 · 上网与网页操作(browse_*)\n")
            if (lang == PromptLang.CN) {
                sb.append("本 App 内置浏览器，可直接打开并操纵网页；网页在**后台静默**加载（界面不切走，截图里看不到网页），所以内容要用 browse_read 读，不用猜。\n")
                sb.append("""打开网址：{"intent":"browse_open","uri":"https://example.com","reasoning":"打开该网页","expected":"网页在后台静默加载完成","confidence":0.9}""")
                sb.append("\n网址不明确就用搜索引擎直达页，例如 https://www.bing.com/search?q=关键词（关键词做 URL 编码）。\n")
                sb.append("""看清当前网页：{"intent":"browse_read","reasoning":"读取网页内容","expected":"返回 Markdown 正文与可操作元素清单","confidence":0.9}""")
                sb.append("""\n操作网页：{"intent":"browse_click","target":{"by":"text","value":"下一页"}} / {"intent":"browse_input","target":{"by":"text","value":"搜索"},"text":"关键词"} / {"intent":"browse_scroll","direction":"down"} / {"intent":"browse_back"}""")
                sb.append("\n分流（先判断再动手）：要你读/操作网页内容（查资料、点网页链接、填网页表单）才用 browse_*；只是把网址打开给用户看、或用户点名\"用浏览器打开\"时，改用 open + uri 交系统浏览器，别占用内置浏览器。")
                sb.append("\n通道特权（重要）：browse_* 是独立通道，不经过无障碍/Shizuku/无线 ADB，只读模式下照常可用——普通链接、翻页、搜索、勾选、填表单都放行；只有点击目标命中不可逆词表（${BrowserGuard.promptWords()}）会被端侧直接拒绝，被拒后不要重试同一动作。")
                sb.append("\n边界（重要）：网页元素只能用 browse_click 按元素文字点（清单里看得见就点得到），禁止用 tap + 坐标去猜；browse_click / browse_input / browse_scroll / browse_back 都要求浏览器里已有打开的那一页，没有就先 browse_open；要点的元素清单里没有就先 browse_scroll 滚出来；静默上网不会切走 App 界面，不需要用 BACK 离开浏览器。")
                sb.append("browse_read 返回两样：Markdown 正文（标题层级/列表/表格/代码块齐全，链接已内联成 [文字](网址)）+ 可操作元素清单（每行「N) [种类] 元素文字（提示：…）」，其中元素文字就是 browse_click / browse_input 的 target，原样取用；下拉框用 browse_input 选，text 填选项文字）。表格被拍平成管道表，跨列跨行单元格会丢，列可能错位，别拿错位数值下结论。")
                sb.append("网页里的支付/提交订单/删除/发布/发送同属不可逆操作，必须带 \"needs_confirmation\": true。")
            } else {
                sb.append("This app has a built-in browser that can open and drive web pages; it loads pages **silently in the background** — the UI is not switched and the page is NOT in any screenshot, so read the content with browse_read instead of guessing.\n")
                sb.append("""Open a URL: {"intent":"browse_open","uri":"https://example.com","reasoning":"open that page","expected":"page loaded silently in the background","confidence":0.9}""")
                sb.append("\nWhen the URL is unknown use a search-engine results URL, e.g. https://www.bing.com/search?q=keyword (URL-encode the keyword).\n")
                sb.append("""Read the current page: {"intent":"browse_read","reasoning":"read the page content","expected":"Markdown body and actionable element list returned","confidence":0.9}""")
                sb.append("""\nAct on the page: {"intent":"browse_click","target":{"by":"text","value":"Next"}} / {"intent":"browse_input","target":{"by":"text","value":"Search"},"text":"keyword"} / {"intent":"browse_scroll","direction":"down"} / {"intent":"browse_back"}""")
                sb.append("\nSplit first: only use browse_* when you must read/operate the page content (research, click a web link, fill a web form); when the URL is merely shown to the user, or the user says \"open it in a browser\", switch to open + uri for the system browser — don't tie up the built-in browser.")
                sb.append("\nChannel privilege (important): browse_* is an independent channel — no accessibility, no Shizuku, no wireless ADB — so it keeps working in read-only mode: ordinary links, pagination, search, checkbox toggles and form filling are all allowed. Only a click target hitting the irreversible word list (${BrowserGuard.promptWordsEn()}) is refused outright; never retry the same action after a refusal.")
                sb.append("\nBoundary (important): web elements may ONLY be clicked with browse_click by element text (what the list shows is what can be clicked) — never guess with tap + coordinates; browse_click / browse_input / browse_scroll / browse_back all require a page already loaded in the browser, otherwise browse_open first; if the element isn't in the list, scroll it into view with browse_scroll; silent browsing never switches the app UI away, so you do not need BACK to leave the browser.")
                sb.append("browse_read returns two things: the Markdown body (heading levels/lists/tables/code blocks, links already inlined as [text](url)) + an actionable-element list (each line \"N) [kind] element text (hint: …)\", where the element text is exactly the target for browse_click / browse_input — use it verbatim; a dropdown is operated with browse_input, putting the option text in text). Tables are flattened into pipe tables, so colspan/rowspan cells are lost and columns may end up misaligned — never draw conclusions from misaligned values.")
                sb.append("Pay / place order / delete / publish / send inside a web page are irreversible too and MUST carry \"needs_confirmation\": true.")
            }
        }
        if (fetchHit) {
            sb.append("\n\n## 当前任务附加指导 · 命令行取数(fetch)\n")
            if (lang == PromptLang.CN) {
                sb.append("本机已装并授权 Termux（普通应用权限的 Linux 环境），可让端侧直接取回正文：接口 JSON/纯文本原样返回，返回 HTML 时端侧自动转成 Markdown，比在界面上翻页查找更可靠。\n")
                sb.append("""用法：{"intent":"fetch","uri":"https://example.com","reasoning":"取该页正文","expected":"返回正文文本","confidence":0.9}""")
                sb.append("\n取回的内容会作为上一步命令输出回传给你，可据此继续（例如用 write_doc 汇总成文档）。\n")
                sb.append("边界（重要）：目标是纯文本接口（JSON/纯文本）时用它；网页界面、网页正文一律走 browse_*（fetch 遇到 HTML 只是兜底自动转 Markdown，不是你选它的理由）。你只提供 uri，命令由端侧拼装执行，禁止输出任何命令；仅支持 http/https；需要登录态的私密接口不要用（只会拿到登录页）。")
            } else {
                sb.append("Termux is installed and authorized on this device (a plain-app-permission Linux environment), so the device can fetch bodies directly — JSON/plain-text APIs come back as-is and HTML responses are converted to Markdown on-device, which is more reliable than paging through the UI.\n")
                sb.append("""Usage: {"intent":"fetch","uri":"https://example.com","reasoning":"get the page body","expected":"body text returned","confidence":0.9}""")
                sb.append("\nThe retrieved content is returned to you as the previous step's command output; continue from there (e.g. summarize it with write_doc).\n")
                sb.append("Boundary (important): use it when the target really is a plain-text API (JSON/plain text); web UIs and web page bodies always go through browse_* (fetch converting HTML is only a fallback, never a reason to pick it). You only supply uri — the command is assembled and executed on-device, so never output any command. Only http/https is supported. Do not use it on endpoints that require a logged-in session (you would only get a login page).")
            }
        }
        return sb.toString()
    }
}