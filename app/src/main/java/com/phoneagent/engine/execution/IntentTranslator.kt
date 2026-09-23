package com.phoneagent.engine.execution

import android.util.Log
import com.phoneagent.engine.execution.CapabilityManager.Mode
import com.phoneagent.domain.model.ActionTarget
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot

private const val TAG = "IntentTranslator"

/**
 * 单条意图的转译策略：输入"意图 + [TranslationContext]" → 输出端侧命令或失败原因。
 *
 * 每个意图类型对应一颗独立策略，职责单一、可单独测试；新增意图只需实现此接口并注册进策略表。
 */
private fun interface IntentTranslationStrategy {
    fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult
}

/**
 * 一次转译所需上下文：通道模式、页面快照、视觉坐标 hint、以及预填的公共动作基线。
 * 公共基线已含 reasoning/expected/confidence/needsConfirmation/pageFingerprint，
 * 各策略只关心"动作类型 + 专属字段 + reason"。
 */
internal class TranslationContext(
    val mode: Mode,
    val snapshot: ScreenSnapshot,
    val visualCoordinate: Pair<Int, Int>?,
    val base: AgentAction,
)

/** 给命令补充人话 reason：reasoning 优先，其次 reason */
private fun reasonOf(intent: AgentIntent): String? = intent.reasoning ?: intent.reason

/** 通用透传策略：动作类型 + 可选专属字段填充；用于无需定位目标（OPEN/SWIPE/PRESS/WAIT/WRITE_DOC/FINISH/GIVE_UP）的意图 */
private fun passthrough(
    actionType: String,
    enrich: (AgentIntent, AgentAction) -> AgentAction = { _, a -> a },
): IntentTranslationStrategy = IntentTranslationStrategy { intent, ctx ->
    val action = ctx.base.copy(type = actionType, reason = reasonOf(intent))
    IntentTranslator.TranslationResult.Command(enrich(intent, action))
}

/** 目标定位结果整理：元素索引 + 像素坐标 + 动作目标（id/label） */
internal data class ResolvedIntentTarget(
    val elementIndex: Int?,
    val x: Int?,
    val y: Int?,
    val target: ActionTarget?,
)

/** 定位目标并整理成可执行字段；未命中（既无元素也无坐标）返回 null */
private fun resolveTarget(
    intent: AgentIntent,
    ctx: TranslationContext,
    intentResolver: IntentResolver,
): ResolvedIntentTarget? {
    val target = intent.target ?: return null
    val r = intentResolver.resolve(target, ctx.snapshot, ctx.visualCoordinate)
    val elem = r.element
    val actionTarget = when (target.by) {
        "id" -> ActionTarget(method = "id", value = target.value)
        else -> elem?.let { ActionTarget(method = "label", value = target.value) }
    }
    return if (elem != null || (r.x != null && r.y != null)) {
        ResolvedIntentTarget(elementIndex = elem?.index, x = r.x, y = r.y, target = actionTarget)
    } else null
}

private fun targetFailReason(target: AgentIntentTarget?): String {
    val by = target?.by ?: "unknown"
    val value = target?.value ?: ""
    return "目标定位失败: by=$by, value=$value"
}

/**
 * 按优先级在元素树中查找端侧已标注的语义控件（semantic_id）。
 * 命中则返回其索引与中心坐标；全部未命中返回 null（此时应降级为 tap+target 或换操作）。
 */
private fun resolveSemantic(semanticIds: List<String>, snapshot: ScreenSnapshot): ResolvedIntentTarget? {
    val elems = snapshot.elements
    for (id in semanticIds) {
        val e = elems.firstOrNull { it.semanticId == id } ?: continue
        return ResolvedIntentTarget(
            elementIndex = e.index,
            x = e.x,
            y = e.y,
            target = ActionTarget(method = "label", value = e.text?.takeIf { it.isNotBlank() } ?: id),
        )
    }
    return null
}

private fun semanticFailReason(semanticId: String): String =
    "当前页面未找到「$semanticId」语义控件，请改用 tap+target 精确定位或换一个可执行的操作"

/** press 按键归一化：宽松匹配到 executeWithVerify 支持的字面量 */
private fun normalizeKey(key: String?): String = when (key?.trim()?.uppercase()) {
    "BACK" -> "BACK"
    "HOME" -> "HOME"
    "ENTER", "OK", "CONFIRM" -> "ENTER"
    "RECENTS", "RECENT", "APP_SWITCH", "RECENT_TASKS" -> "RECENTS"
    else -> key?.trim()?.uppercase() ?: "BACK"
}

/** 应用启动策略：应用名/包名 → 端侧解析包名，按通道决定启动命令 */
internal class OpenAppStrategy(private val appNameResolver: AppNameResolver) : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        if (intent.app == null) {
            return IntentTranslator.TranslationResult.MissingParam(
                field = "app",
                reason = "AI 输出了 open_app 但未提供 app（应用名或包名）。请补全 app 字段，例如 {\"app\":\"美团\"} 或 {\"app\":\"com.sankuai.meituan\"}。",
            )
        }
        val pkg = appNameResolver.resolve(intent.app)
            ?: return IntentTranslator.TranslationResult.Failed(
                "无法解析应用「${intent.app}」的包名（未匹配到已安装应用）。请确认该应用已安装，或直接在 app 字段填包名，例如 \"com.tencent.mm\"",
            )
        Log.d(TAG, "open_app 解析成功: app=${intent.app} → pkg=$pkg mode=${ctx.mode}")
        return when (ctx.mode) {
            Mode.SHIZUKU -> IntentTranslator.TranslationResult.Command(
                ctx.base.copy(
                    type = ActionType.SHELL,
                    command = "launch $pkg",
                    packageName = pkg,
                    reason = intent.reasoning ?: intent.reason,
                ),
            )
            else -> IntentTranslator.TranslationResult.Command(
                ctx.base.copy(type = ActionType.LAUNCH, packageName = pkg, reason = intent.reasoning ?: intent.reason),
            )
        }
    }
}

/**
 * 目标型定位策略（tap / long_press / input 共用）：定位目标控件 → 组装带 elementIndex/x/y/target 的动作。
 * @param withDepth 命中后填充该意图的专属字段（long_press 时长 / input 文本）
 */
internal class TargetLocationStrategy(
    private val intentResolver: IntentResolver,
    private val type: String,
    private val withDepth: (AgentAction, AgentIntent) -> AgentAction = { a, _ -> a },
) : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        if (intent.target == null) {
            // 缺 target：可追问补全（如"AI 只输出 tap 但没说点哪里"）
            return IntentTranslator.TranslationResult.MissingParam(
                field = "target",
                reason = "AI 输出了 $type 但未提供 target（缺少操作目标控件）。请补全 target:{\"by\":\"text\",\"value\":\"控件文字\"}。",
            )
        }
        val r = resolveTarget(intent, ctx, intentResolver)
            ?: return IntentTranslator.TranslationResult.Failed(targetFailReason(intent.target))
        val action = ctx.base.copy(
            type = type,
            elementIndex = r.elementIndex,
            x = r.x,
            y = r.y,
            target = r.target,
            reason = reasonOf(intent),
        )
        return IntentTranslator.TranslationResult.Command(withDepth(action, intent))
    }
}

/** 滚动查找策略：不要求目标已可见，以屏幕中心滚动查找，text 作为查找方向兜底 */
internal class ScrollToStrategy(private val intentResolver: IntentResolver) : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        if (intent.target == null) {
            return IntentTranslator.TranslationResult.MissingParam(
                field = "target",
                reason = "AI 输出了 scroll_to 但未提供 target（缺少要滚动查找的目标）。请补全 target:{\"by\":\"text\",\"value\":\"控件文字\"}。",
            )
        }
        val r = intentResolver.resolve(intent.target, ctx.snapshot, null)
        val elem = r.element
        // 确定滚动方向：目标在下方则向上滚（swipe up），反之向下滚
        val direction = when {
            intent.target?.by == "text" && intent.target.value.contains("上方") -> "up"
            intent.target?.by == "text" && intent.target.value.contains("下方") -> "down"
            else -> "up" // 默认向上滚，查找目标
        }
        return IntentTranslator.TranslationResult.Command(
            ctx.base.copy(
                type = ActionType.SCROLL,
                elementIndex = elem?.index,
                text = if (intent.target?.by == "text") intent.target.value else null,
                direction = direction,
                reason = reasonOf(intent),
            ),
        )
    }
}

/**
 * 高层语义接口策略：把"端侧已识别语义控件"的高级意图转译为对该控件的点击命令。
 * AI 只需说"做什么"（refresh/confirm/close/share...），端侧按语义ID优先级定位并落地执行。
 * @param semanticIds 语义ID按优先级列表（端侧 PageAnnotator 标注的 semantic_id）
 * @param actionType 落地动作类型（默认点击语义控件）
 * @param irreversible 不可逆操作（如删除）时自动置 needsUserConfirmation=true
 * @param intentResolver 语义控件未命中但 AI 同时给出 target 时，回退到 target 一次定位（减少 AI 重试）
 */
internal class SemanticActionStrategy(
    private val semanticIds: List<String>,
    private val actionType: String,
    private val irreversible: Boolean = false,
    private val intentResolver: IntentResolver? = null,
) : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        // 语义优先；语义未命中且 AI 给了 target 时用 target 兜底；两者都无则转译失败
        val semantic = resolveSemantic(semanticIds, ctx.snapshot)
        val r = semantic
            ?: intentResolver?.takeIf { intent.target != null }?.let { resolveTarget(intent, ctx, it) }
            ?: return IntentTranslator.TranslationResult.Failed(semanticFailReason(semanticIds.first()))
        var action = ctx.base.copy(
            type = actionType,
            elementIndex = r.elementIndex,
            x = r.x,
            y = r.y,
            target = r.target,
            reason = reasonOf(intent),
        )
        if (irreversible) action = action.copy(needsUserConfirmation = true)
        return IntentTranslator.TranslationResult.Command(action)
    }
}

/** 返回策略：优先点击语义返回按钮；找不到则退化为系统返回键（无需命中元素） */
internal class BackStrategy : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        val r = resolveSemantic(listOf("back_btn"), ctx.snapshot)
        val action = if (r != null) {
            ctx.base.copy(
                type = ActionType.TAP,
                elementIndex = r.elementIndex,
                x = r.x, y = r.y, target = r.target,
                reason = reasonOf(intent),
            )
        } else {
            ctx.base.copy(type = ActionType.KEY, keycode = "BACK", reason = reasonOf(intent))
        }
        return IntentTranslator.TranslationResult.Command(action)
    }
}

/** 回到桌面策略：系统性 HOME 键 */
internal class HomeStrategy : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult =
        IntentTranslator.TranslationResult.Command(
            ctx.base.copy(type = ActionType.KEY, keycode = "HOME", reason = reasonOf(intent)),
        )
}

/**
 * 记忆写入策略：AI 表达"这条信息值得长期记住"，端侧落到本地记忆库。
 *
 * 与 WAIT / WRITE_DOC 同类——不触碰设备、不需要通道与坐标，故在只读模式下同样放行。
 * 内容放在 text、分类放在 summary，无需新增意图字段（序列化天然兼容）。
 */
internal class RememberStrategy : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        val content = intent.text?.trim().orEmpty()
        if (content.isBlank()) {
            return IntentTranslator.TranslationResult.MissingParam(
                field = "text",
                reason = "AI 输出了 remember 但未提供 text（要记住的内容）。请补全 text，" +
                    "例如 {\"text\":\"用户偏好简洁的界面\",\"summary\":\"preference\"}。",
            )
        }
        return IntentTranslator.TranslationResult.Command(
            ctx.base.copy(
                type = ActionType.REMEMBER,
                text = content,
                summary = intent.summary?.trim()?.takeIf { it.isNotBlank() } ?: "general",
                reason = reasonOf(intent),
            ),
        )
    }
}

/**
 * 说话策略：AI 表达"要对用户说一句人话，而不是操作手机"。
 * 内容放 text，正文支持 Markdown；不需要 target / 通道 / 坐标。
 *
 * 与 REMEMBER / WAIT / WRITE_DOC 同类——不触碰设备、不需要通道与坐标，故在只读模式下同样放行。
 */
internal class SayStrategy : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        val text = intent.text?.trim().orEmpty()
        if (text.isBlank()) {
            return IntentTranslator.TranslationResult.MissingParam(
                field = "text",
                reason = "AI 输出了 say 但未提供 text（要说的话）。请补全 text，" +
                    "例如 {\"intent\":\"say\",\"text\":\"我准备先打开设置，再定位到显示项\"}。",
            )
        }
        return IntentTranslator.TranslationResult.Command(
            ctx.base.copy(type = ActionType.SAY, text = text, reason = reasonOf(intent)),
        )
    }
}

/**
 * 本机信息查询策略：AI 想知道"装了哪些应用 / 现在几点 / 电量网络如何 / 存储还剩多少"时，
 * 不必靠猜，也不必用一连串点击去翻设置页。
 *
 * 与 REMEMBER 同类——纯本地读取、不触碰设备、不需要通道与坐标，故只读模式下同样放行。
 * 类别放 kind、过滤词放 filter；可选值由端侧白名单收口，AI 写错即失败并回报可选值。
 */
internal class DeviceQueryStrategy : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        val kind = intent.kind?.trim()?.lowercase().orEmpty().ifBlank { KIND_ALL }
        if (kind !in DEVICE_QUERY_KINDS) {
            return IntentTranslator.TranslationResult.Failed(
                "device_query 不支持 kind=$kind；可选值：${DEVICE_QUERY_KINDS.joinToString("/")}。",
            )
        }
        return IntentTranslator.TranslationResult.Command(
            ctx.base.copy(
                type = ActionType.DEVICE_QUERY,
                text = kind,
                summary = intent.filter?.trim()?.take(40),
                reason = reasonOf(intent),
            ),
        )
    }

    companion object {
        const val KIND_ALL = "all"
        /** 可查询的信息类别：应用清单 / 时间 / 电量 / 网络 / 存储 / 全部 */
        val DEVICE_QUERY_KINDS = setOf("apps", "time", "battery", "network", "storage", KIND_ALL)
    }
}

/**
 * Termux 命令行取数策略：把"取网页 / 接口正文"这类**图形界面做不到或很笨拙**的事，
 * 落到 Termux 的 curl 上。
 *
 * 这是"把 Termux 命令纳入转译层"的落点：新增能力对 AI 的暴露面仅是一个意图名 + uri 字段，
 * AI 依旧只表达"做什么"（要哪个地址的内容），命令由端侧拼装、通道由端侧决定，
 * AI 既不写命令也不选通道（与铁律 9 一致）。
 */
internal class TermuxFetchStrategy(
    private val termuxAvailable: () -> Boolean,
) : IntentTranslationStrategy {
    override fun translate(intent: AgentIntent, ctx: TranslationContext): IntentTranslator.TranslationResult {
        val uri = intent.uri?.trim().orEmpty()
        if (uri.isBlank()) {
            return IntentTranslator.TranslationResult.MissingParam(
                field = "uri",
                reason = "AI 输出了 fetch 但未提供 uri（要获取的网页/接口地址）。请补全 uri，例如 {\"uri\":\"https://example.com\"}。",
            )
        }
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            return IntentTranslator.TranslationResult.Failed("fetch 仅支持 http/https 地址：$uri")
        }
        if (!termuxAvailable()) {
            return IntentTranslator.TranslationResult.Failed(
                "本机未安装或未授权 Termux，无法用命令行取数；请改用 UI 意图在当前页面完成。",
            )
        }
        // 白名单过滤地址字符，杜绝把引号 / 反引号 / 命令替换符拼进命令
        val safe = uri.filter { it.isLetterOrDigit() || it in ":/?&=#%._-+~@[]!()*,;" }
        return IntentTranslator.TranslationResult.Command(
            ctx.base.copy(
                type = ActionType.SHELL,
                // raw 前缀：跳过 AI 友好命令解析，原样交给通道执行
                // （执行层按命令名判定 curl 属 Termux 工具链，自动走 Termux 通道）
                command = "raw curl -sL --max-time 20 -- \"$safe\"",
                // 带上原始网址：执行层拿 curl 回来的 HTML 转 Markdown 时，要用它把相对链接绝对化
                // （translate() 构造的 base 不含 uri，这里必须显式补）
                uri = uri,
                reason = reasonOf(intent),
            ),
        )
    }
}

/**
 * 意图转译器（对应 HPA动作执行逻辑优化文档 v2.1 四、IntentTranslator）。
 *
 * 策略化拆分：把 AI 的"意图"（做什么）转译为端侧可执行的"命令"（怎么做）。
 * - [translate] 只负责「意图类型 → 策略」分派，并收口只读守卫；
 * - 每个意图类型由一颗独立的 [IntentTranslationStrategy] 处理，便于扩展与单测；
 * - 目标定位交由 [IntentResolver]，应用名→包名交由 [AppNameResolver]；AI 对通道选择与坐标完全无感知。
 * - 只读（READONLY）模式作为横切约束统一拦截，不在各策略里各自实现。
 */
class IntentTranslator(
    private val capabilityManager: CapabilityManager,
    private val appNameResolver: AppNameResolver,
    private val intentResolver: IntentResolver,
    /** 当前是否具备 Termux 命令行通道（普通应用权限）；由调用方注入，转译层据此决定 fetch 能否落地 */
    private val termuxAvailable: () -> Boolean = { false },
) {

    /** 转译结果 */
    sealed class TranslationResult {
        /** 转译成功：得到一条内部执行命令 */
        data class Command(val action: AgentAction) : TranslationResult()
        /** 转译失败：给出人话原因（不执行） */
        data class Failed(val reason: String) : TranslationResult()
        /**
         * 参数缺失：AI 输出的意图缺少执行所必需的参数（如 tap 无 target、open_app 无 app）。
         * 与 [Failed] 的区别：这是"可追问补全"而非"页面问题"。调用方（如 AgentEngine）应向 AI
         * 发起一次补充请求拿到缺失字段后重新转译，而不是直接放弃。
         * @param field 缺失的意图字段名（如 target / app）
         * @param reason 人话说明 + 引导 AI 补全的提示
         */
        data class MissingParam(val field: String, val reason: String) : TranslationResult()
    }

    /** 意图类型 → 转译策略 的分派表（集中注册，新增意图只在此登记） */
    private val strategies: Map<String, IntentTranslationStrategy> = buildMap {
        // 深链/协议直达
        put(IntentType.OPEN, passthrough(ActionType.OPEN) { i, a -> a.copy(uri = i.uri, app = i.app, page = i.page) })
        // 无需定位的交互
        put(IntentType.SWIPE, passthrough(ActionType.SWIPE) { i, a -> a.copy(direction = i.direction, distancePx = i.distancePx) })
        put(IntentType.PRESS, passthrough(ActionType.KEY) { i, a -> a.copy(keycode = normalizeKey(i.key)) })
        put(IntentType.WAIT, passthrough(ActionType.WAIT) { i, a -> a.copy(timeoutMs = i.waitMs ?: i.durationMs) })
        // 目标型交互
        put(IntentType.TAP, TargetLocationStrategy(intentResolver, ActionType.TAP))
        put(IntentType.LONG_PRESS, TargetLocationStrategy(intentResolver, ActionType.LONG_CLICK) { a, i -> a.copy(durationMs = i.durationMs) })
        put(IntentType.INPUT, TargetLocationStrategy(intentResolver, ActionType.TYPE_TEXT) { a, i -> a.copy(text = i.text) })
        put(IntentType.SCROLL_TO, ScrollToStrategy(intentResolver))
        // 应用启动
        put(IntentType.OPEN_APP, OpenAppStrategy(appNameResolver))
        // 文档写入
        put(IntentType.WRITE_DOC, passthrough(ActionType.WRITE_DOC) { i, a -> a.copy(text = i.text, summary = i.summary) })
        // 记忆写入（本地写库，不触碰设备）
        put(IntentType.REMEMBER, RememberStrategy())

        // 说话（对用户输出一句人话，不触碰设备）
        put(IntentType.SAY, SayStrategy())

        put(IntentType.DEVICE_QUERY, DeviceQueryStrategy())
        // 命令行取数（Termux）：图形界面做不到的事落到 Linux 工具链，命令由端侧拼装
        put(IntentType.FETCH, TermuxFetchStrategy(termuxAvailable))
        // 内置浏览器（WebView 可见页）不在此表：browse_* 由 BrowserChannel 这条独立通道处理，
        // 不依赖无障碍/Shizuku/Termux，也不产生 ActionType。
        // 收尾
        put(IntentType.FINISH, passthrough(ActionType.TASK_DONE) { i, a -> a.copy(summary = i.summary ?: "任务完成") })
        put(IntentType.GIVE_UP, passthrough(ActionType.TASK_DONE) { i, a -> a.copy(summary = i.reason ?: "已放弃任务") })
        // ---- 高层语义接口（端侧 PageAnnotator 已标注 semantic_id，AI 只表达"做什么"，端侧定位）----
        put(IntentType.BACK, BackStrategy())
        put(IntentType.HOME, HomeStrategy())
        put(IntentType.REFRESH, SemanticActionStrategy(listOf("refresh_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.SEARCH, SemanticActionStrategy(listOf("search_box", "search_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.SEND, SemanticActionStrategy(listOf("send_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.CONFIRM, SemanticActionStrategy(listOf("dlg_allow", "confirm_btn", "checkout_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.CLOSE, SemanticActionStrategy(listOf("close_btn", "dlg_dismiss", "ad_skip"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.SHARE, SemanticActionStrategy(listOf("share_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.COLLECT, SemanticActionStrategy(listOf("collect_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.COPY, SemanticActionStrategy(listOf("copy_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.DOWNLOAD, SemanticActionStrategy(listOf("download_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.ADD, SemanticActionStrategy(listOf("add_btn"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.SWITCH, SemanticActionStrategy(listOf("switch_toggle"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.CLEAR_INPUT, SemanticActionStrategy(listOf("clear_input"), ActionType.TAP, intentResolver = intentResolver))
        put(IntentType.DELETE, SemanticActionStrategy(listOf("delete_btn"), ActionType.TAP, irreversible = true, intentResolver = intentResolver))
    }

    /**
     * 把意图转译为内部执行命令。
     * @param intent AI 输出的意图
     * @param snapshot 当前页面（目标定位 + 坐标换算用）
     * @param visualCoordinate hint 目标经视觉定位得到的像素坐标（decision 阶段已算出）
     */
    fun translate(
        intent: AgentIntent,
        snapshot: ScreenSnapshot,
        visualCoordinate: Pair<Int, Int>? = null,
    ): TranslationResult {
        val mode = capabilityManager.currentMode()
        // 公共基线：意图类型占位（各策略覆写），统一透传 AI 级别的公共字段
        val base = AgentAction(
            type = intent.intent,
            reasoning = intent.reasoning,
            expected = intent.expected,
            confidence = intent.confidence,
            needsUserConfirmation = intent.needsConfirmation,
            pageFingerprint = intent.pageFingerprint,
        )
        val ctx = TranslationContext(mode, snapshot, visualCoordinate, base)

        Log.d(TAG, "translate: intent=${intent.intent} app=${intent.app} target=${intent.target} mode=$mode")

        val strategy = strategies[intent.intent]
        val result = if (strategy != null) {
            strategy.translate(intent, ctx)
        } else {
            TranslationResult.Failed("未知意图：${intent.intent}")
        }
        return applyReadOnly(result, mode)
    }

    /** 只读模式横切约束：除等待/文档写入/记忆写入外，拒绝自动执行（AI 仍在分析，只是手换成用户） */
    private fun applyReadOnly(result: TranslationResult, mode: Mode): TranslationResult = when {
        result !is TranslationResult.Command -> result
        mode != Mode.READONLY -> result
        // WAIT / WRITE_DOC / REMEMBER / DEVICE_QUERY / SAY 都不触碰设备
        // （等待、本地生成文档、本地写记忆库、本地读设备信息、直接对用户说话），只读模式下同样放行
        result.action.type == ActionType.WAIT ||
            result.action.type == ActionType.WRITE_DOC ||
            result.action.type == ActionType.REMEMBER ||
            result.action.type == ActionType.DEVICE_QUERY ||
            result.action.type == ActionType.SAY -> result
        else -> TranslationResult.Failed(
            "当前为只读模式（无 Shizuku 且无障碍未开启），无法自动执行「${result.action.type}」；请手动操作后告诉 AI 继续。",
        )
    }
}