package com.phoneagent.engine

import com.phoneagent.engine.execution.ActionMode
import java.io.File
import org.junit.Test

/**
 * 提示词导出测试（离线，不联网）。
 *
 * 目的：把 [AgentPrompts] 各入口点在**代表性调用参数**下的真实输出原样落盘，
 * 交给外部的真实模型审计脚本（`tests/prompt_audit.mjs`）逐条送审，
 * 从而保证「被测提示词」与「生产提示词」逐字节一致，杜绝手抄漂移。
 *
 * 输出目录：`app/build/prompt_dump/`（不进仓库）
 * - `<id>.txt`：提示词正文
 * - `manifest.json`：id / group / lang / role / withSystem / chars / note
 *
 * 本类只做导出，不做断言：与提示词文案解耦，改文案不会让它失败
 * （文案正确性由 PromptSnapshotTest 的金样本负责）。
 */
class PromptDumpTest {

    private data class Dump(
        val id: String,
        val group: String,
        val lang: PromptLang,
        val role: String,
        /** 生产环境里这条消息之前是否还有 system 消息（决定审计脚本怎么拼 messages） */
        val withSystem: Boolean,
        val note: String,
        val text: String,
    )

    @Test
    fun `导出全部入口点提示词供真实模型审计`() {
        val dumps = buildList { collect(this) }
        val dir = File("build/prompt_dump")
        dir.deleteRecursively()
        dir.mkdirs()
        dumps.forEach { File(dir, "${it.id}.txt").writeText(it.text, Charsets.UTF_8) }
        val manifest = buildString {
            append("{\n  \"generatedBy\": \"PromptDumpTest\",\n  \"count\": ${dumps.size},\n  \"entries\": [\n")
            append(
                dumps.joinToString(",\n") { d ->
                    "    {\"id\": ${q(d.id)}, \"group\": ${q(d.group)}, \"lang\": ${q(d.lang.name)}, " +
                        "\"role\": ${q(d.role)}, \"withSystem\": ${d.withSystem}, " +
                        "\"chars\": ${d.text.length}, \"note\": ${q(d.note)}, \"file\": ${q("${d.id}.txt")}}"
                },
            )
            append("\n  ]\n}\n")
        }
        File(dir, "manifest.json").writeText(manifest, Charsets.UTF_8)
        println("已导出 ${dumps.size} 份提示词到 ${dir.absolutePath}")
    }

    // ==================== 素材 ====================

    private val taskGeneric = "把手机亮度调到 50%"
    private val taskOpen = "打开微信"
    private val taskWeb = "打开百度搜索今天的天气"
    private val taskDoc = "帮我写一份本周工作周报"
    private val taskFetch = "查一下今天的美元兑人民币汇率"
    private val taskOpenFile = "打开这个文件 /sdcard/Download/季度汇报.ppt"

    /** EN 侧的任务素材：语义与上面中文一一对应，保证 TaskKindDetector 在英文关键词表下也能命中 */
    private val taskDocEn = "write a weekly work report for me"
    private val taskOpenFileEn = "open this file /sdcard/Download/report.ppt"
    private val taskWebEn = "look up today's weather on Bing"
    private val taskFetchEn = "what is the USD to CNY exchange rate today"

    private val profile = "用户常在美团点餐、偏好少辣"
    private val installedApps = "微信、支付宝、美团、高德地图、哔哩哔哩、WPS Office"

    /**
     * 合成页面块：必须与生产里 [com.phoneagent.domain.model.ScreenSnapshot.toAiText]
     * 的 `UiElement.describe()` 逐字段一致（元素顺序、字段名、缺省规则、像素坐标）。
     * 之前这里自造了 `bounds=(l,t,r,b)`、`页面类型：`、`[fingerprint]` 等生产根本不存在的字段，
     * 审计时被模型当成"提示词与数据不符"报了一堆假缺陷。
     *
     * 第二个教训是**页内必须自洽**：曾用同一份"搜索框 + 立即购买"的混合页套三条决策条目，
     * 模型逐条报"步骤说在搜索框输入、页面却是商品详情页""商品详情页没有价格/规格/辣度控件"
     * "上一步已进详情页、元素树却还是搜索页"。现在按三条素材各自的 currentStep 各配一页：
     * 搜索页配"输入关键词"、详情页配"点立即购买"、支付页配"选余额并确认支付"，
     * 且任务记忆里的要求（不要辣 / 用余额支付）在对应页面上都有可操作的控件承接。
     */

    /**
     * 搜索页：对应 dec.basic 的 currentStep「在搜索框输入关键词」。
     *
     * 输入控件必须**唯一**：曾同时给「Button id=search_box label=搜索」和「EditText id=input」，
     * 模型逐条追问"到底该 tap 哪一个、输入框是哪一个"（第五轮 dec.en.basic high）。现合并为一个可编辑控件。
     */
    private val pageSearch = """
        ## 当前页面
        当前前台应用: com.sankuai.meituan
        屏幕分辨率: 1080x2340
        可交互元素（共 3 个）：
          [#0] android.widget.EditText(edittext) id=search_box label="搜索" center=(540,340) bounds=(120,300)-(960,380)
          [#1] android.widget.TextView(textview) label="热门搜索：麻辣香锅 / 酸菜鱼" center=(540,530) bounds=(40,500)-(1040,560)
          [#2] android.widget.ImageButton(imagebutton) id=back_btn label="返回" center=(72,120) bounds=(24,80)-(120,160)
    """.trimIndent()

    /** 商品详情页：对应 dec.memory_rules 的 currentStep「点『立即购买』」，含辣度控件承接「不要辣」 */
    private val pageDetail = """
        ## 当前页面
        当前前台应用: com.sankuai.meituan
        屏幕分辨率: 1080x2340
        可交互元素（共 6 个）：
          [#0] android.widget.TextView(textview) label="黄焖鸡米饭" center=(540,240) bounds=(40,210)-(1040,270)
          [#1] android.widget.TextView(textview) label="¥28.0" center=(540,330) bounds=(40,300)-(300,360)
          [#2] android.widget.TextView(textview) label="辣度" center=(180,470) bounds=(40,440)-(320,500)
          [#3] android.widget.Button(button) id=switch_toggle label="不辣（已选）" center=(540,470) bounds=(360,440)-(900,500)
          [#4] android.widget.Button(button) label="立即购买" center=(540,2260) bounds=(60,2200)-(1020,2320)
          [#5] android.widget.ImageButton(imagebutton) id=back_btn label="返回" center=(72,120) bounds=(24,80)-(120,160)
    """.trimIndent()

    /** 支付页：对应 dec.full 的 currentStep「选『余额』并点『确认支付』」，含支付方式承接「用余额支付」 */
    private val pagePayment = """
        ## 当前页面
        当前前台应用: com.sankuai.meituan
        屏幕分辨率: 1080x2340
        可交互元素（共 6 个）：
          [#0] android.widget.TextView(textview) label="应付 ¥28.0" center=(540,600) bounds=(40,560)-(1040,640)
          [#1] android.widget.TextView(textview) label="支付方式" center=(180,720) bounds=(40,690)-(320,750)
          [#2] android.widget.Button(button) id=pay_wechat label="微信支付（当前选中）" center=(540,760) bounds=(40,720)-(1040,800)
          [#3] android.widget.Button(button) id=pay_balance label="余额（¥126.5，点击切换）" center=(540,880) bounds=(40,840)-(1040,920)
          [#4] android.widget.Button(button) label="确认支付" center=(540,2260) bounds=(60,2200)-(1020,2320)
          [#5] android.widget.ImageButton(imagebutton) id=back_btn label="返回" center=(72,120) bounds=(24,80)-(120,160)
    """.trimIndent()

    private val sampleTaskMemory = """
        ## 任务记忆（本次任务的既定目标与用户要求，全程不可偏离）
        目标：帮我在美团点一份黄焖鸡米饭
        用户要求：
        1. 不要辣
        2. 用余额支付
    """.trimIndent()

    private val envFactsSample = EnvFacts(
        dateTime = "2026-09-26 周六 15:04",
        network = "Wi-Fi",
        battery = "62%（充电中）",
        foreground = "美团(com.sankuai.meituan)",
        installedCount = 87,
    )

    /**
     * 确认订单页（**尚未提交**）：给 sess.* 用，让"改上一轮那一份"有**当下真能走的入口**。
     *
     * 缺了页面模型只能问"要联系商家？取消重下？操作路径完全缺失"（第四轮 sess.cn 2 条 high）；
     * 而只补页面还不够——上一轮若标「已完成」、页面又写「订单已完成」，美团已完成订单本就改不了规格，
     * 于是模型判"规则自相矛盾：既说在既有结果上接着改，又给不出可行路径"（第五轮 sess.cn 4 条 high）。
     * 现在上一轮**没下成单**、单子还停在确认页，「未完成」与「改辣度」两边都成立。
     */
    private val pageCart = """
        ## 当前页面
        当前前台应用: com.sankuai.meituan
        屏幕分辨率: 1080x2340
        可交互元素（共 5 个）：
          [#0] android.widget.TextView(textview) label="黄焖鸡米饭 ¥28.0（正常辣）" center=(540,300) bounds=(40,270)-(1040,330)
          [#1] android.widget.Button(button) id=spicy_toggle label="辣度：正常辣（点击修改）" center=(540,380) bounds=(60,340)-(1020,420)
          [#2] android.widget.Button(button) id=edit_spec label="修改规格" center=(540,700) bounds=(60,660)-(1020,740)
          [#3] android.widget.Button(button) label="提交订单" center=(540,820) bounds=(60,780)-(1020,860)
          [#4] android.widget.ImageButton(imagebutton) id=back_btn label="返回" center=(72,120) bounds=(24,80)-(120,160)
    """.trimIndent()

    /**
     * 会话承接素材：上一轮是同一件事（追问场景用它，followUp=true）。
     * 首条**必须**是「未完成」：追问说的是"接着改那一份"，若上一轮已下单收尾，
     * 就只能重新下单，与块里"不是把整件事从头重做"直接冲突。
     */
    private val previousFollowUp = listOf(
        PreviousTask("帮我在美团点一份黄焖鸡米饭", "未完成"),
        PreviousTask("查一下明天天气", "已完成"),
    )

    /**
     * 会话承接素材：上一轮是**另一件无关的事**。
     * dec.full 必须用它——那里本轮任务是全新下单，若沿用"上一轮已下单黄焖鸡米饭"，
     * 就会和「已完成 = 那一步不用再走一遍」正面冲突，被模型报成真缺陷（第三轮 dec.en.full 就是这么来的）。
     */
    private val previousUnrelated = listOf(PreviousTask("查一下明天天气", "已完成"))

    // ==================== 采集 ====================

    private fun collect(out: MutableList<Dump>) {
        // ---- SYSTEM：系统提示主体（含动作模式段） ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            val tag = lang.name.lowercase()
            out += sys(lang, "sys.$tag.balanced.generic", ActionMode.BALANCED, taskGeneric, "普通任务（不裁网页/打开两块）")
            out += sys(lang, "sys.$tag.balanced.open", ActionMode.BALANCED, taskOpen, "打开类任务（带 sys.open_link）")
            out += sys(lang, "sys.$tag.balanced.web", ActionMode.BALANCED, taskWeb, "网页类任务（带 sys.web_browse）")
            out += sys(lang, "sys.$tag.free.doc", ActionMode.FREE, taskDoc, "自由模式 + 文档任务")
            out += sys(lang, "sys.$tag.conservative.generic", ActionMode.CONSERVATIVE, taskGeneric, "保守模式")
            out += sys(lang, "sys.$tag.unknown_task", ActionMode.BALANCED, "", "任务文本未知 → 两块都保留（最大体量）")
            out += Dump(
                "sys.$tag.custom", "SYSTEM", lang, "system", true,
                "用户自定义系统提示 + 技能区块",
                AgentPrompts.system(
                    lang, "你是一个简洁的助手，只输出 JSON。", hasVision = true, shizukuAvailable = true,
                    skills = AgentPrompts.skillSection(lang, emptyList(), listOf("打开应用"), hasMcpServer = false),
                    actionMode = ActionMode.BALANCED, task = taskGeneric,
                ),
            )
        }

        // ---- ACTION_MODE：单独送审，便于中小体量精读 ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            ActionMode.entries.forEach { mode ->
                out += Dump(
                    "mode.${lang.name.lowercase()}.${mode.key}", "ACTION_MODE", lang, "system", true,
                    "动作模式授权范围（${mode.label}）",
                    AgentPrompts.actionModeSection(lang, mode),
                )
            }
        }

        // ---- CAPABILITIES ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            listOf(true, false).forEach { vision ->
                out += Dump(
                    "cap.${lang.name.lowercase()}.${if (vision) "vision" else "novision"}",
                    "CAPABILITIES", lang, "system", true,
                    "功能可用性声明（视觉 ${if (vision) "已启用" else "未启用"}）",
                    AgentPrompts.capabilitiesLang(lang, vision),
                )
            }
        }

        // ---- SKILLS ----
        val mcpLines = listOf(
            "mcp_filesystem_read_file | 读文件 | 读取本地文件内容 | path(必填)",
            "mcp_weather_now | 查天气 | 查询指定城市实时天气 | city(必填)、unit(可选)",
        )
        out += Dump(
            "skills.cn.none", "SKILLS", PromptLang.CN, "system", true,
            "无 MCP、无停用 → 预期为空串（审计脚本跳过）",
            AgentPrompts.skillSection(PromptLang.CN, emptyList(), emptyList(), hasMcpServer = false),
        )
        out += Dump(
            "skills.cn.mcp_disabled", "SKILLS", PromptLang.CN, "system", true,
            "有 MCP 技能 + 有停用清单",
            AgentPrompts.skillSection(PromptLang.CN, mcpLines, listOf("打开应用", "点一下"), hasMcpServer = true),
        )
        out += Dump(
            "skills.cn.server_only", "SKILLS", PromptLang.CN, "system", true,
            "配了 MCP 服务器但零绑定",
            AgentPrompts.skillSection(PromptLang.CN, emptyList(), emptyList(), hasMcpServer = true),
        )
        out += Dump(
            "skills.en.mcp_disabled", "SKILLS", PromptLang.EN, "system", true,
            "有 MCP 技能 + 有停用清单",
            AgentPrompts.skillSection(PromptLang.EN, mcpLines, listOf("open_app", "tap"), hasMcpServer = true),
        )
        out += Dump(
            "skills.en.server_only", "SKILLS", PromptLang.EN, "system", true,
            "配了 MCP 服务器但零绑定",
            AgentPrompts.skillSection(PromptLang.EN, emptyList(), emptyList(), hasMcpServer = true),
        )

        // ---- PLANNING ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            val tag = lang.name.lowercase()
            out += Dump("plan.$tag.generic", "PLANNING", lang, "user", false, "普通 App 内任务",
                AgentPrompts.planning(lang, "帮我在美团点一份黄焖鸡米饭", profile, installedApps))
            out += Dump("plan.$tag.doc", "PLANNING", lang, "user", false, "文档类任务",
                AgentPrompts.planning(lang, taskDoc, profile, installedApps))
            out += Dump("plan.$tag.web", "PLANNING", lang, "user", false, "上网查资料任务",
                AgentPrompts.planning(lang, taskWeb, "", ""))
        }

        // ---- DECISION ----
        // 素材铁律：
        // 1) last_result 必须用生产 [AgentEngine.lastStepResultText] 的真实格式（✅/⚠️/❌ 前缀 + 动作名）。
        //    自造文案（曾用「上一步点击未生效」）会让模型纠结"这属于三态中的哪一个"，属素材缺陷。
        // 2) stepIndex 必须与「已批准的执行计划」里 currentStep 的序号一致，否则模型报"步号矛盾"。
        // 3) context_hint 用生产的 `页面类型：xxx` 形式（见 PageAnnotator.generateContextHint）。
        // 4) 决策条目一律补页面段：生产 user 消息总是带「## 当前页面」，缺了会被报成"页面数据缺失"。
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            val tag = lang.name.lowercase()
            val en = lang == PromptLang.EN
            val memory = "- 用户常在美团点黄焖鸡米饭\n- 用户不吃辣，辣度要选「不辣」"
            val rules = "[美团] 商品详情页的「辣度」控件会显示当前选择；下单前先确认它是「不辣」，不是就先点它改掉"
            out += Dump("dec.$tag.basic", "DECISION", lang, "user", true, "无记忆、无经验规则（含页面段）",
                AgentPrompts.decision(lang, if (en) "order a huangmenji rice bowl on Meituan" else "帮我在美团点一份黄焖鸡米饭",
                    // 步骤文本必须把要输入的内容写全：只写"输入关键词"会被报"'keywords'未指定具体文本"（第三轮 dec.en.basic 3 条 high）
                    2, 5, if (en) "type \"huangmenji rice bowl\" in the search box" else "在搜索框输入「黄焖鸡米饭」",
                    if (en) "✅ verified: open_app(Meituan)" else "✅ 已确认成功: open_app(美团)",
                    0, "页面类型：search_page") +
                    "\n\n$pageSearch")
            out += Dump("dec.$tag.memory_rules", "DECISION", lang, "user", true, "带记忆 + 带经验规则（含页面段）",
                AgentPrompts.decision(lang, if (en) "order a huangmenji rice bowl on Meituan" else "帮我在美团点一份黄焖鸡米饭",
                    4, 5, if (en) "tap Buy Now" else "点「立即购买」",
                    if (en) "❌ failed: tap(Buy Now)" else "❌ 未生效: 点「立即购买」",
                    2, "页面类型：product_detail", memory = memory, evolvedRules = rules) +
                    "\n\n$pageDetail")
            val plan = if (en) {
                "\n\n## Approved plan\n1. open Meituan\n2. search huangmenji rice bowl\n3. open product detail\n4. tap Buy Now\n5. switch the payment method to Balance\n6. tap Confirm Payment"
            } else {
                "\n\n## 已批准的执行计划\n1. 打开美团\n2. 搜索黄焖鸡米饭\n3. 进入商品详情\n4. 点立即购买\n5. 把支付方式切到「余额」\n6. 点「确认支付」"
            }
            // 本轮任务是**全新下单**，故任务、计划、当前步骤、页面、任务记忆必须五者同指一件事：
            // task/任务记忆 = 下单；currentStep 5/6 = 切到余额（承接"用余额支付"，第 6 步才是点「确认支付」）；
            // 上一步 = tap(立即购买)（即计划第 4 步刚做完）；页面 = 支付页（含支付方式控件）。
            // 曾把 task 改成"上一单太辣了改成不辣的"，与计划 1-5 步和会话承接正面冲突，被报了一串真缺陷。
            // 经验规则必须与当前页面**当场可验证**：曾写"支付按钮常被优惠弹窗遮挡，先关弹窗再点"，
            // 而支付页元素树里根本没有弹窗，模型直接报"规则要求关一个不存在的弹窗"（第四轮 dec.en.full 2 条 high）。
            // 现改成与任务记忆「用余额支付」同指一件事、且页面上确实有该控件的规则。
            val fullRules = if (en) {
                "[Meituan] On the payment page WeChat Pay is selected by default; when paying with balance, switch to Balance first"
            } else {
                "[美团] 支付页默认选中「微信支付」，要用余额付得先切到「余额」"
            }
            out += Dump("dec.$tag.full", "DECISION", lang, "user", true, "完整 user 消息（决策段 + 计划 + 页面 + 附加指导 + 任务记忆 + 环境 + 会话承接）",
                AgentPrompts.decision(lang, if (en) "order a huangmenji rice bowl on Meituan" else "帮我在美团点一份黄焖鸡米饭",
                    5, 6, if (en) "tap Balance to switch the payment method" else "点「余额」，把支付方式切换成余额",
                    if (en) "✅ verified: tap(Buy Now)" else "✅ 已确认成功: tap(立即购买)",
                    0, "页面类型：payment_page", memory = memory, evolvedRules = fullRules) +
                plan + "\n\n$pagePayment" +
                AgentPrompts.situationalExtras(lang, if (en) "order a huangmenji rice bowl on Meituan" else "帮我在美团点一份黄焖鸡米饭", termuxAvailable = true) +
                "\n$sampleTaskMemory" +
                AgentPrompts.environment(lang, envFactsSample) +
                // 会话承接只用"无关的上一轮"：本轮是新下单，上一轮若也是同一单就会和「已完成 = 不必再走一遍」打架
                AgentPrompts.sessionContext(lang, previousUnrelated, followUp = false))
        }

        // ---- REVIEW ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            out += Dump("rev.${lang.name.lowercase()}", "REVIEW", lang, "system", false, "审核者系统提示",
                AgentPrompts.reviewSystem(lang))
        }

        // ---- DISTILL ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            out += Dump("dist.${lang.name.lowercase()}", "DISTILL", lang, "user", false, "记忆提炼",
                AgentPrompts.memoryDistill(lang, "帮我在美团点一份黄焖鸡米饭", "已完成", "- 打开美团\n- 搜索黄焖鸡米饭\n- 点立即购买\n- 确认支付"))
        }

        // ---- ENVIRONMENT ----
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            out += Dump("env.${lang.name.lowercase()}", "ENVIRONMENT", lang, "user", true, "环境上下文",
                AgentPrompts.environment(lang, envFactsSample))
        }

        // ---- SESSION ----
        // 会话承接块在生产里是**追加在本轮任务原文之后**的（同一条 user 消息），单独导出会缺"本轮要做什么"，
        // 模型会报"没有告知本轮用户输入"（第三轮 sess.cn 就是这么来的）——故前置一行本轮任务，还原真实消息形态。
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            val en = lang == PromptLang.EN
            // 措辞统一用「上一轮」：曾用「上一单」，模型追问"'上一单'与'上一轮'是否等同"（第四轮 sess.cn 1 条 high）
            val task = if (en) "make the huangmenji rice bowl from the last turn not spicy" else "把上一轮那份黄焖鸡米饭改成不辣的"
            val head = if (en) "Task: $task" else "本轮任务：$task"
            // 必须附页面段：块里明说"屏幕此刻长什么样看随附的当前页面"，不附页面就会被报"声称有却根本没有"（第四轮 sess.cn/sess.en 共 3 条 high）
            out += Dump("sess.${lang.name.lowercase()}", "SESSION", lang, "user", true, "会话承接（判定为追问，前置本轮任务 + 附当前页面）",
                head + AgentPrompts.sessionContext(lang, previousFollowUp, followUp = true) + "\n\n" + pageCart)
        }

        // ---- SITUATIONAL EXTRAS ----
        // 任务文本必须与 lang 同语：TaskKindDetector 按语言查关键词，中文任务在 EN 下命中不到任何场景，
        // 导出结果会是空串（第一轮 EN 三条就这样被跳过，白丢了审计覆盖）
        listOf(PromptLang.CN, PromptLang.EN).forEach { lang ->
            val tag = lang.name.lowercase()
            val en = lang == PromptLang.EN
            out += Dump("sit.$tag.doc", "SITUATIONAL", lang, "user", true, "文档类附加指导",
                AgentPrompts.situationalExtras(lang, if (en) taskDocEn else taskDoc))
            out += Dump("sit.$tag.open", "SITUATIONAL", lang, "user", true, "直达类附加指导",
                AgentPrompts.situationalExtras(lang, if (en) taskOpenFileEn else taskOpenFile))
            out += Dump("sit.$tag.browse", "SITUATIONAL", lang, "user", true, "上网类附加指导",
                AgentPrompts.situationalExtras(lang, if (en) taskWebEn else taskWeb, termuxAvailable = true))
            out += Dump("sit.$tag.fetch", "SITUATIONAL", lang, "user", true, "取数类附加指导",
                AgentPrompts.situationalExtras(lang, if (en) taskFetchEn else taskFetch, termuxAvailable = true))
        }
    }

    private fun sys(lang: PromptLang, id: String, mode: ActionMode, task: String, note: String) = Dump(
        id, "SYSTEM", lang, "system", true, note,
        AgentPrompts.system(lang, "", hasVision = true, shizukuAvailable = true, actionMode = mode, task = task),
    )

    /** 最小 JSON 字符串转义（避免为一处导出引入序列化依赖） */
    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
