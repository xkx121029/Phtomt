package com.phoneagent.execution

import android.util.Log
import com.phoneagent.execution.CapabilityManager.Mode
import com.phoneagent.model.ActionTarget
import com.phoneagent.model.ActionType
import com.phoneagent.model.AgentAction
import com.phoneagent.model.AgentIntent
import com.phoneagent.model.AgentIntentTarget
import com.phoneagent.model.IntentType
import com.phoneagent.model.ScreenSnapshot

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
private data class ResolvedIntentTarget(
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
) {

    /** 转译结果 */
    sealed class TranslationResult {
        /** 转译成功：得到一条内部执行命令 */
        data class Command(val action: AgentAction) : TranslationResult()
        /** 转译失败：给出人话原因（不执行） */
        data class Failed(val reason: String) : TranslationResult()
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
        // 收尾
        put(IntentType.FINISH, passthrough(ActionType.TASK_DONE) { i, a -> a.copy(summary = i.summary ?: "任务完成") })
        put(IntentType.GIVE_UP, passthrough(ActionType.TASK_DONE) { i, a -> a.copy(summary = i.reason ?: "已放弃任务") })
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

    /** 只读模式横切约束：除等待/文档写入外，拒绝自动执行（AI 仍在分析，只是手换成用户） */
    private fun applyReadOnly(result: TranslationResult, mode: Mode): TranslationResult = when {
        result !is TranslationResult.Command -> result
        mode != Mode.READONLY -> result
        result.action.type == ActionType.WAIT || result.action.type == ActionType.WRITE_DOC -> result
        else -> TranslationResult.Failed(
            "当前为只读模式（无 Shizuku 且无障碍未开启），无法自动执行「${result.action.type}」；请手动操作后告诉 AI 继续。",
        )
    }
}