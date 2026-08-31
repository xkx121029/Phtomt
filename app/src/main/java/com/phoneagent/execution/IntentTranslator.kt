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
 * 意图转译器（对应 HPA动作执行逻辑优化文档 v2.1 四、IntentTranslator）。
 *
 * 把 AI 的"意图"（做什么）转译为端侧可执行的"命令"（怎么做），并依据当前授权模式选择通道：
 * - SHIZUKU → 生成 shell 友好命令（input tap / monkey 等）
 * - ACCESSIBILITY → 生成无障碍动作（ActionExecutor performAction / launchApp）
 * - READONLY → 除分析外拒绝执行
 *
 * 目标定位交由 [IntentResolver]，应用名→包名交由 [AppNameResolver]。
 * AI 对通道选择与坐标完全无感知。
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
        val unified = intent.reasoning ?: intent.reason
        // 先按意图类型确定 action.type，避免未处理意图泄漏 "intent" 占位符
        val baseType = IntentType.TO_ACTION[intent.intent] ?: intent.intent
        val common = AgentAction(
            type = baseType,
            reasoning = intent.reasoning,
            expected = intent.expected,
            confidence = intent.confidence,
            needsUserConfirmation = intent.needsConfirmation,
            pageFingerprint = intent.pageFingerprint,
        )

        Log.d(TAG, "translate: intent=${intent.intent} app=${intent.app} target=${intent.target} mode=$mode")

        return when (intent.intent) {
            // ---- 应用启动：应用名/包名 → 端侧解析包名，按通道启动 ----
            IntentType.OPEN_APP -> translateOpenApp(intent, mode, common)
            // ---- 深链/协议直达 ----
            IntentType.OPEN -> TranslationResult.Command(
                common.copy(type = ActionType.OPEN, uri = intent.uri, app = intent.app, page = intent.page, reason = unified),
            )
            // ---- 点击 / 长按 / 输入 / 滚动查找：需要定位目标 ----
            IntentType.TAP -> translateTap(intent, snapshot, visualCoordinate, common)
            IntentType.LONG_PRESS -> translateLongPress(intent, snapshot, visualCoordinate, common)
            IntentType.INPUT -> translateInput(intent, snapshot, visualCoordinate, common)
            IntentType.SCROLL_TO -> translateScrollTo(intent, snapshot, common)
            // ---- 滑动 / 按键 / 等待：无需定位 ----
            IntentType.SWIPE -> TranslationResult.Command(
                common.copy(type = ActionType.SWIPE, direction = intent.direction, distancePx = intent.distancePx, reason = unified),
            )
            IntentType.PRESS -> TranslationResult.Command(
                common.copy(type = ActionType.KEY, keycode = normalizeKey(intent.key), reason = unified),
            )
            IntentType.WAIT -> TranslationResult.Command(
                common.copy(type = ActionType.WAIT, timeoutMs = intent.waitMs ?: intent.durationMs, reason = unified),
            )
            // ---- 文档写入 / 完成 / 放弃 ----
            IntentType.WRITE_DOC -> TranslationResult.Command(
                common.copy(type = ActionType.WRITE_DOC, text = intent.text, summary = intent.summary, reason = unified),
            )
            IntentType.FINISH -> TranslationResult.Command(
                common.copy(type = ActionType.TASK_DONE, summary = intent.summary ?: "任务完成", reason = unified),
            )
            IntentType.GIVE_UP -> TranslationResult.Command(
                common.copy(type = ActionType.TASK_DONE, summary = intent.reason ?: "已放弃任务", reason = unified),
            )
            else -> TranslationResult.Failed("未知意图：${intent.intent}")
        }.let { result ->
            // 只读模式：除等待/文档写入外，一律拒绝自动执行（AI 仍在分析，只是手换成用户）
            if (result is TranslationResult.Command && mode == Mode.READONLY &&
                result.action.type != ActionType.WAIT && result.action.type != ActionType.WRITE_DOC
            ) {
                TranslationResult.Failed("当前为只读模式（无 Shizuku 且无障碍未开启），无法自动执行「${intent.intent}」；请手动操作后告诉 AI 继续。")
            } else result
        }
    }

    private fun translateOpenApp(intent: AgentIntent, mode: Mode, common: AgentAction): TranslationResult {
        val pkg = appNameResolver.resolve(intent.app)
            ?: run {
                Log.w(TAG, "无法解析应用「${intent.app}」的包名（未匹配到已安装应用）")
                return TranslationResult.Failed("无法解析应用「${intent.app}」的包名（未匹配到已安装应用）。请确认该应用已安装，或直接在 app 字段填包名，例如 \"com.tencent.mm\"")
            }
        Log.d(TAG, "open_app 解析成功: app=${intent.app} → pkg=$pkg mode=$mode")
        return when (mode) {
            Mode.SHIZUKU -> TranslationResult.Command(
                common.copy(type = ActionType.SHELL, command = "launch $pkg", packageName = pkg, reason = intent.reasoning, expected = intent.expected),
            )
            else -> TranslationResult.Command(
                common.copy(type = ActionType.LAUNCH, packageName = pkg, reason = intent.reasoning, expected = intent.expected),
            )
        }
    }

    private fun translateTap(intent: AgentIntent, snapshot: ScreenSnapshot, visual: Pair<Int, Int>?, common: AgentAction): TranslationResult {
        val r = resolveTargetIntent(intent, snapshot, visual)
            ?: return TranslationResult.Failed(targetFailReason(intent.target))
        return TranslationResult.Command(
            common.copy(type = ActionType.TAP, elementIndex = r.elementIndex, x = r.x, y = r.y, target = r.target, reason = intent.reasoning),
        )
    }

    private fun translateLongPress(intent: AgentIntent, snapshot: ScreenSnapshot, visual: Pair<Int, Int>?, common: AgentAction): TranslationResult {
        val r = resolveTargetIntent(intent, snapshot, visual)
            ?: return TranslationResult.Failed(targetFailReason(intent.target))
        return TranslationResult.Command(
            common.copy(type = ActionType.LONG_CLICK, elementIndex = r.elementIndex, x = r.x, y = r.y, target = r.target, durationMs = intent.durationMs, reason = intent.reasoning),
        )
    }

    private fun translateInput(intent: AgentIntent, snapshot: ScreenSnapshot, visual: Pair<Int, Int>?, common: AgentAction): TranslationResult {
        val r = resolveTargetIntent(intent, snapshot, visual)
            ?: return TranslationResult.Failed(targetFailReason(intent.target))
        return TranslationResult.Command(
            common.copy(type = ActionType.TYPE_TEXT, elementIndex = r.elementIndex, x = r.x, y = r.y, target = r.target, text = intent.text, reason = intent.reasoning),
        )
    }

    private fun translateScrollTo(intent: AgentIntent, snapshot: ScreenSnapshot, common: AgentAction): TranslationResult {
        val r = intentResolver.resolve(intent.target, snapshot, null)
        // scroll 不要求目标已可见：以屏幕中心滚动查找，text 作为查找方向兜底
        val elem = r.element
        // 确定滚动方向：目标在下方则向上滚（swipe up），反之向下滚
        val direction = when {
            intent.target?.by == "text" && intent.target.value.contains("上方") -> "up"
            intent.target?.by == "text" && intent.target.value.contains("下方") -> "down"
            else -> "up" // 默认向上滚，查找目标
        }
        return TranslationResult.Command(
            common.copy(
                type = ActionType.SCROLL,
                elementIndex = elem?.index,
                text = if (intent.target?.by == "text") intent.target.value else null,
                direction = direction,
                reason = intent.reasoning,
            ),
        )
    }

    /** 定位目标并整理成 AgentEngine 可识别的内部字段 */
    private fun resolveTargetIntent(intent: AgentIntent, snapshot: ScreenSnapshot, visual: Pair<Int, Int>?): ResolvedIntentTarget? {
        val target = intent.target ?: return null
        val r = intentResolver.resolve(target, snapshot, visual)
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

    private data class ResolvedIntentTarget(
        val elementIndex: Int?,
        val x: Int?,
        val y: Int?,
        val target: ActionTarget?,
    )
}