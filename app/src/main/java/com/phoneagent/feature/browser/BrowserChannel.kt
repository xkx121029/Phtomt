package com.phoneagent.feature.browser

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.rules.EngineRules

/**
 * 内置浏览器通道：**与转译层（IntentTranslator）同级**的一条独立通道。
 *
 * 三条边界（与提示词一一对应）：
 * 1. **不依赖设备能力**：网页读写走 DOM 脚本（`feature/browser/script/`），
 *    不经过无障碍、Shizuku、Termux，也不进 [com.phoneagent.engine.execution.IntentTranslator]
 *    的策略表——所以"手机没法自动操作"这件事不该影响浏览器能不能用。
 * 2. **只读模式下按不可逆性放行**：只读（既无 Shizuku/无线 ADB 又无无障碍）时，打开网页 / 抓正文 /
 *    滚动 / 后退这类"只看"照常；网页里的普通点击、填表单也放行（这是浏览器相对手机操作的额外权利），
 *    只有命中不可逆词表（[BrowserGuard]）的支付/下单/发送/删除等会被端侧直接拒掉。
 * 3. **通道只管判定与措辞**：具体怎么碰 WebView 交给 [BrowserExecutor]（生产实现 [BridgeExecutor]
 *    转调 [BrowserBridge]），于是这层判定可以纯 JVM 单测。
 *
 * [readOnly] 用函数而不是布尔：模式可能在任务中途变化（用户去开无障碍 / 接上无线 ADB），
 * 每次执行现取，避免两套真相。
 */
internal class BrowserChannel(
    private val readOnly: () -> Boolean,
    private val executor: BrowserExecutor = BridgeExecutor(),
) {

    /** 一次通道执行的结果 */
    sealed class Outcome {
        /** 成功：text 是给 AI 看的网页结果（正文/清单/操作回执），会作为"上一步结果"回注 */
        data class Ok(val text: String) : Outcome()
        /** 缺参：可向 AI 追问补全（field 即缺失字段名） */
        data class Missing(val field: String, val reason: String) : Outcome()
        /** 被护栏拒绝：只读模式下的不可逆操作，理由已是可执行的中文说明 */
        data class Refused(val reason: String) : Outcome()
        /** 执行失败：中文原因（可直接回注决策上下文） */
        data class Failed(val reason: String) : Outcome()
    }

    /** 这条意图是否归浏览器通道管（引擎据此在转译层之前分流） */
    fun handles(intent: String): Boolean = intent in INTENTS

    /** 步骤中文名（与模板/留档展示同源，见 [EngineRules.actionLabel]） */
    fun label(intent: String): String = EngineRules.actionLabel(intent)

    /** 执行一条浏览器意图：先校验参数，再过只读护栏，最后才落到 [executor] */
    suspend fun execute(intent: AgentIntent): Outcome {
        val op = intent.intent
        return when (op) {
            IntentType.BROWSE_OPEN -> {
                val uri = intent.uri?.trim().orEmpty()
                when {
                    uri.isBlank() -> Outcome.Missing(
                        field = "uri",
                        reason = "AI 输出了 browse_open 但未提供 uri（要打开的网址）。请补全 uri，" +
                            "例如 {\"intent\":\"browse_open\",\"uri\":\"https://example.com\"}。",
                    )
                    !uri.startsWith("http://") && !uri.startsWith("https://") ->
                        Outcome.Failed("browse_open 只支持 http/https 网址：$uri")
                    else -> from(executor.open(uri))
                }
            }
            // 抓正文 / 网页后退：无参数，吃的是浏览器里"当前已打开的那一页"
            IntentType.BROWSE_READ -> from(executor.read())
            IntentType.BROWSE_BACK -> from(executor.back())
            IntentType.BROWSE_SCROLL -> {
                val dir = intent.direction?.trim()?.lowercase().orEmpty().ifBlank { "down" }
                if (dir !in DIRECTIONS) {
                    Outcome.Failed("browse_scroll 不支持 direction=$dir；可选值：${DIRECTIONS.joinToString("/")}。")
                } else {
                    from(executor.scroll(dir))
                }
            }
            IntentType.BROWSE_CLICK, IntentType.BROWSE_INPUT -> {
                val target = intent.target
                when {
                    target == null || target.value.isBlank() -> Outcome.Missing(
                        field = "target",
                        reason = "AI 输出了 $op 但未提供 target（网页里的目标元素）。网页元素优先按文字定位：" +
                            "{\"by\":\"text\",\"value\":\"登录\"}；元素无文字时用 CSS 选择器：{\"by\":\"id\",\"value\":\"#login\"}。",
                    )
                    op == IntentType.BROWSE_INPUT && intent.text.isNullOrBlank() -> Outcome.Missing(
                        field = "text",
                        reason = "AI 输出了 browse_input 但未提供 text（要填写的文字）。请补全 text。",
                    )
                    else -> {
                        val by = target.by.takeIf { it in TARGET_BY } ?: "text"
                        val value = target.value.trim()
                        guard(by, value, intent) ?: run {
                            if (op == IntentType.BROWSE_CLICK) {
                                // 只读下让脚本对"真正被定位到的元素"再探一次词表：
                                // AI 给的文字未必等于元素真实文字（如 by=id），这一步是兜底
                                from(executor.click(by, value, readOnly()))
                            } else {
                                from(executor.input(by, value, intent.text.orEmpty()))
                            }
                        }
                    }
                }
            }
            else -> Outcome.Failed("未知的浏览器操作：$op")
        }
    }

    /**
     * 只读护栏：返回非空即"拒绝执行"。
     * 三重判定取或——AI 自报不可逆、目标文字/选择器命中词表、以及（脚本侧的）元素真实文字命中词表。
     */
    private fun guard(by: String, value: String, intent: AgentIntent): Outcome? {
        if (!readOnly()) return null
        if (intent.needsConfirmation) {
            return Outcome.Refused(
                "只读模式下该网页操作被标记为不可逆（needs_confirmation），已拒绝：" +
                    "请先告知用户手动完成，或改用非不可逆路径；不要重试同一动作。",
            )
        }
        val hit = BrowserGuard.match(value)
        if (hit != null) return refusal(value, hit)
        // 选择器定位在执行前看不到元素文字，只能先按选择器命名嗅一道（#pay-btn / .checkout-submit）
        if (by == "id" && BrowserGuard.selectorSuspicious(value)) return refusal(value, null)
        return null
    }

    private fun refusal(shown: String, hit: String?): Outcome.Refused {
        val why = if (hit != null) "（命中「$hit」）" else "（目标命名疑似不可逆）"
        return Outcome.Refused(
            "只读模式下「$shown」属不可逆操作$why，已拒绝。" +
                "请先告知用户手动完成，或改用非不可逆路径；不要重试同一动作。",
        )
    }

    /** 执行结果 → 通道结果（脚本侧探针的拒绝单独成一类，便于上层按 WARN 记账） */
    private fun from(result: BrowseResult): Outcome = when {
        result.refused -> Outcome.Refused(result.text)
        result.ok -> Outcome.Ok(result.text)
        else -> Outcome.Failed(result.text)
    }

    companion object {
        /** 本通道持有的意图（复用 [IntentType] 常量，不写字面量） */
        val INTENTS: Set<String> = setOf(
            IntentType.BROWSE_OPEN, IntentType.BROWSE_READ, IntentType.BROWSE_CLICK,
            IntentType.BROWSE_INPUT, IntentType.BROWSE_SCROLL, IntentType.BROWSE_BACK,
        )

        /** 点击/输入的目标定位方式：text=元素文字（优先），id=CSS 选择器 */
        val TARGET_BY = setOf("text", "id", "hint")

        /** 滚动方向白名单 */
        val DIRECTIONS = setOf("up", "down", "top", "bottom")
    }
}