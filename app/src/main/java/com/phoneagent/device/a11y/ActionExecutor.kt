package com.phoneagent.device.a11y

import android.accessibilityservice.AccessibilityService
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.domain.model.UiElement
import com.phoneagent.provider.A11ySelector
import com.phoneagent.provider.ActionResult
import com.phoneagent.provider.IDeviceProvider
import com.phoneagent.overlay.CursorOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 活节点定位线索。
 *
 * 元素树里的 [UiElement] 只是"值快照"，手里没有无障碍节点句柄；要直接对控件 performAction，
 * 只能拿这些线索交给 Provider，由它在**当前活节点树**上重新找一次。字段按 viewId → 文字 → 位置 逐级降级。
 */
data class NodeSelector(
    /** 控件 view id 资源名；匹配按后缀，兼容 "com.x:id/btn" 与 "btn" 两种写法 */
    val viewId: String? = null,
    /** 控件可见文字 / 无障碍描述（容器自身无文字时，标签由后代拼出来） */
    val label: String? = null,
    /** 快照里控件的中心点：作为"压在同一个点上"的兜底匹配与候选排序依据 */
    val centerHint: Pair<Int, Int>? = null,
) {
    val isEmpty: Boolean
        get() = viewId.isNullOrBlank() && label.isNullOrBlank() && centerHint == null

    /** 供日志与失败原因使用的中文描述 */
    fun describe(): String {
        val parts = mutableListOf<String>()
        viewId?.takeIf { it.isNotBlank() }?.let { parts += "viewId=$it" }
        label?.takeIf { it.isNotBlank() }?.let { parts += "label=\"$it\"" }
        if (parts.isEmpty()) centerHint?.let { parts += "位置=(${it.first},${it.second})" }
        return parts.joinToString(" ").ifEmpty { "坐标" }
    }

    /** 转成跨进程定位线索（Provider 侧字段同名同义） */
    internal fun toRemote(): A11ySelector = A11ySelector(
        viewId = viewId?.takeIf { it.isNotBlank() },
        label = label?.takeIf { it.isNotBlank() },
        hintX = centerHint?.first ?: -1,
        hintY = centerHint?.second ?: -1,
    )
}

/**
 * 手势落点命中的控件（"这一下点到了谁"）。
 *
 * 点击前用它审计落点：落点与目标不是同一个控件时，能立刻分清是**选错/坐标偏了**
 * （该重新定位）还是**控件本身无响应**（该换意图），而不是笼统报"未生效"。
 */
data class NodeHit(
    /** 控件标签：自身文字/描述，没有则由后代文字拼出（与 Provider 侧 DerivedLabel 同源） */
    val label: String?,
    val viewId: String?,
    val className: String,
    val clickable: Boolean,
) {
    /** 供日志与失败原因使用的中文描述 */
    fun describe(): String {
        val name = label?.takeIf { it.isNotBlank() } ?: viewId?.takeIf { it.isNotBlank() } ?: className
        return "「$name」${if (clickable) "(可点击)" else "(不可点击)"}"
    }
}

/**
 * 通过 Provider 的远程无障碍通道执行具体的屏幕动作。
 *
 * 公开名称与签名保持与迁移前一致，内部全部改为经 [ProviderManager] 调 IDeviceProvider：
 * 点击/滑动/节点操作/全局键/输入/启动应用/打开链接/语音助手都映射到 AIDL 同名方法。
 * 光标绘制仍留在主应用：Provider 在 ActionResult 里回传真实落点中心（画在 Provider 进程里没有意义），
 * 本类按原实现继续调用 [CursorOverlayService] 的 point/longPress/swipe。
 */
class ActionExecutor(
    private val service: AgentAccessibilityService,
) {

    sealed class Result {
        data class Success(val description: String = "") : Result()
        data class Failure(val reason: String) : Result()
    }

    /** 点击屏幕坐标 */
    suspend fun click(x: Int, y: Int): Result {
        // 让用户看到光标飞向点击点（并行/同步由设置驱动态控制闭环内）
        CursorOverlayService.point(x, y)
        val result = remote { it.click(x, y) } ?: return unavailable("点击")
        return result.toResult("已点击", "点击")
    }

    /**
     * 长按。
     *
     * @param durationMs 按压时长（毫秒）。上层（AI 决策）通过 AgentAction.durationMs 指定时按它执行；
     *   传 null 时沿用默认 [LONG_PRESS_MS]，保证既有调用点的稳定性不变。
     */
    suspend fun longClick(x: Int, y: Int, durationMs: Long? = null): Result {
        // 上层指定了时长就用它（让 AI 的 durationMs 生效）；未指定则沿用默认按压时长 [LONG_PRESS_MS]，
        // 保证既有调用点的稳定性不变（800ms 足够长，必然触发长按）
        val hold = durationMs ?: LONG_PRESS_MS
        // 光标形态为"按住不放 + 呼吸光环"，保持时长取真实按压时长，两者才对得上
        CursorOverlayService.longPress(x, y, hold)
        // durationMs <= 0 交给 Provider 用其默认按压时长
        val result = remote { it.longClick(x, y, hold.toInt()) } ?: return unavailable("长按")
        return result.toResult("已长按", "长按")
    }

    /**
     * 按控件的定位线索，让 Provider 在**活节点树**上找到它并直接 `performAction(ACTION_CLICK)`。
     *
     * 相比坐标手势：手势点的是"像素"，控件被弹窗/键盘/系统栏顶动几像素就落到旁边去了；
     * 节点直点交给系统分发到控件本身，不受坐标漂移影响。Provider 命中后会把控件中心回传，
     * 主应用据此补光标——节点直点没有坐标手势，不补光标的话用户只看到页面自己变了。
     */
    suspend fun clickNode(selector: NodeSelector, longClick: Boolean = false, durationMs: Long? = null): Result {
        val hold = durationMs ?: LONG_PRESS_MS
        val result = remote {
            it.clickNode(selector.toRemote(), longClick, if (longClick) hold.toInt() else 0)
        } ?: return unavailable("节点${if (longClick) "长按" else "点击"}")
        if (result.success && result.x >= 0 && result.y >= 0) {
            if (longClick) {
                CursorOverlayService.longPress(result.x, result.y, hold)
            } else {
                CursorOverlayService.point(result.x, result.y)
            }
        }
        return result.toResult(
            "已${if (longClick) "长按" else "点击"}控件(${selector.describe()})",
            "节点${if (longClick) "长按" else "点击"}",
        )
    }

    /**
     * 目标控件**此刻**的中心点：把定位线索重新落到 Provider 的活节点树上，取命中节点自身的中心。
     *
     * 供上层在手势点击前取最新落点——AI 思考的数秒里页面可能已滚动/被顶动，
     * 快照坐标早已偏离；活节点位置才是"现在它到底在哪"。
     */
    fun liveCenter(selector: NodeSelector): Pair<Int, Int>? {
        val center = blocking(LIVE_QUERY_TIMEOUT_MS) { it.liveCenter(selector.toRemote()) } ?: return null
        if (center.size < 2 || center[0] < 0 || center[1] < 0) return null
        return center[0] to center[1]
    }

    /**
     * 落点审计：屏幕上承接 (x, y) 这一下的控件是谁（最内层、优先可点击）。
     *
     * 只读探测，不派发任何动作；读不到节点树时返回 null（调用方按"无法审计"处理）。
     */
    fun hitTest(x: Int, y: Int): NodeHit? =
        SnapshotJsonCodec.decodeNodeHit(blocking(LIVE_QUERY_TIMEOUT_MS) { it.hitTest(x, y) })

    /** 滑动 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Result {
        // 光标沿轨迹推进，时长与手势一致，轨迹才和手指同步
        CursorOverlayService.swipe(x1, y1, x2, y2, durationMs)
        val result = remote { it.swipe(x1, y1, x2, y2, durationMs.toInt()) } ?: return unavailable("滑动")
        return result.toResult("已滑动", "滑动")
    }

    /** 方向滑动（补齐屏幕中心点） */
    suspend fun swipeDirection(x1: Int, y1: Int, x2: Int, y2: Int): Result = swipe(x1, y1, x2, y2)

    /** 滚动（在元素范围内纵向滑动） */
    suspend fun scroll(elem: UiElement?, direction: String): Result {
        val metrics = service.applicationContext.resources.displayMetrics
        val x1 = elem?.centerX ?: metrics.widthPixels / 2
        val y1 = elem?.centerY ?: metrics.heightPixels / 2
        // ±600px 的终点可能越出屏幕边界，越界手势会被系统拒绝：钳制在屏内
        val clamped = (if (direction == "up") y1 - 600 else y1 + 600)
            .coerceIn(0, metrics.heightPixels - 1)
        // 钳制后可能与起点重合（元素贴边），保留至少 1px 位移，避免零长轨迹手势被拒
        val y2 = if (clamped == y1) {
            (y1 + if (direction == "up") 1 else -1).coerceIn(0, metrics.heightPixels - 1)
        } else clamped
        return swipe(x1, y1, x1, y2, 300)
    }

    /**
     * 在页面的**可滚动容器**内滚动一屏（ACTION_SCROLL_FORWARD / BACKWARD）。
     *
     * 比坐标滑动精确：坐标滑动是从屏幕中心盲划，碰到横向轮播、悬浮按钮、非滚动区域时要么划不动、
     * 要么划错容器；直接对容器下发滚动动作，完全不受坐标与手势落点影响。
     *
     * 这条路径没有手势，但**不能因此没有光标**：Provider 回传容器中心，主应用据此补一段
     * "滚了一下"的示意轨迹（Provider 只给中心，上下留边由本机按屏高比例推导）。
     *
     * @param direction up=往下翻看后面的内容；down=往上翻回前面的内容
     */
    suspend fun scrollContainer(direction: String): Result {
        val result = remote { it.scrollContainer(direction) } ?: return unavailable("滚动")
        if (result.success && result.x >= 0 && result.y >= 0) {
            val h = service.applicationContext.resources.displayMetrics.heightPixels
            // 上下各留一段边距：容器边缘常有固定头部或悬浮按钮，那里本来就不跟着滚
            val half = (h * CONTAINER_TRACE_HALF_RATIO).toInt().coerceAtLeast(1)
            val top = (result.y - half).coerceIn(0, h - 1)
            val bottom = (result.y + half).coerceIn(0, h - 1)
            // up（往下翻看后面的内容）= 手指自下而上带；down 反之
            val fromY = if (direction == "down") top else bottom
            val toY = if (direction == "down") bottom else top
            if (fromY != toY) CursorOverlayService.swipe(result.x, fromY, result.x, toY, CONTAINER_TRACE_MS)
        }
        return result.toResult("已在可滚动容器内滚动", "滚动")
    }

    /** 全局按键 */
    fun globalAction(action: Int): Result {
        val ok = blocking(GLOBAL_TIMEOUT_MS) { it.globalAction(action) } ?: return unavailable("全局按键")
        return if (ok) Result.Success("已执行全局按键") else Result.Failure("全局按键执行失败")
    }

    fun back() = globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun home() = globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun recents() = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /** 启动应用（Provider 内按 LAUNCHER activity 精确拉起，失败原因中文回传） */
    fun launchApp(packageName: String): Result {
        val result = blocking(APP_TIMEOUT_MS) { it.launchApp(packageName) } ?: return unavailable("启动应用")
        return result.toResult("已启动应用 $packageName", "启动应用")
    }

    /**
     * 唤起系统语音助手（assistant 意图的执行出口）。
     * 多入口依次唤醒 + 拉起后验证全部在 Provider 内完成，主应用只收成功/失败回执。
     */
    suspend fun launchAssistant(text: String?): Result {
        val result = remote(ASSISTANT_TIMEOUT_MS) { it.launchAssistant(text) }
            ?: return unavailable("唤醒语音助手")
        return result.toResult("已唤醒语音助手", "唤醒语音助手")
    }

    /** 供引擎在外部方式（如 shell 兜底）拉起助手后单独调用传话；返回中文结果说明 */
    suspend fun handoffToAssistant(text: String?): String =
        ProviderManager.call(service.applicationContext, HANDOFF_TIMEOUT_MS) { it.handoffToAssistant(text) }
            ?: "无障碍通道不可用，未能向语音助手传话"

    /**
     * 按包名轮询确认语音助手窗口已出现；pkg 未知时恒为 true（尽力而为）。
     *
     * 跨进程后读不到 Provider 的窗口列表，只能以「当前活动窗口的包名」作为近似判据；
     * 以悬浮层形态出现的助手可能漏判，此时由上层按"仅拉起"处理。
     */
    suspend fun assistantVisible(pkg: String?, timeoutMs: Long = ASSISTANT_WAKE_TIMEOUT_MS): Boolean {
        if (pkg.isNullOrBlank()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (assistantInForeground(pkg)) return true
            delay(ASSISTANT_WAKE_POLL_MS)
        }
        return assistantInForeground(pkg)
    }

    private suspend fun assistantInForeground(pkg: String): Boolean =
        service.captureScreenAsync().packageName == pkg

    /**
     * 用系统应用打开链接/文件（ACTION_VIEW）——"交给系统软件打开"的统一出口。
     * 目标归一化（本地文件转 content://、按扩展名补 MIME）与系统应用优选都在 Provider 内完成。
     *
     * @param pkg 指定用哪个应用打开（已解析的包名）；为空时**优先系统自带应用**
     */
    fun openUri(uri: String, pkg: String? = null): Result {
        val raw = uri.trim().takeIf { it.isNotBlank() } ?: return Result.Failure("打开目标为空")
        val result = blocking(APP_TIMEOUT_MS) {
            it.openUri(raw, pkg?.trim()?.takeIf { target -> target.isNotBlank() })
        } ?: return unavailable("打开 $raw")
        return result.toResult("已打开 $raw", "打开 $raw")
    }

    /** 用系统 Intent Action 直达指定设置页（如 Wi-Fi/蓝牙/显示） */
    fun openSettingsAction(action: String): Result {
        val result = blocking(APP_TIMEOUT_MS) { it.openSettingsAction(action) } ?: return unavailable("打开设置页")
        return result.toResult("已直达系统设置页", "打开设置页")
    }

    /**
     * 向输入框填充文本（整段覆写，ACTION_SET_TEXT）。
     *
     * 两层策略（Provider 内实现）：能按坐标/控件定位到可编辑节点就直接填充；
     * 读不到输入框（如 WebView/自绘控件）时先点击坐标聚焦，等聚焦后再填充。
     */
    suspend fun typeText(text: String, target: UiElement?, x: Int? = null, y: Int? = null): Result {
        val cx = x ?: target?.centerX
        val cy = y ?: target?.centerY
        // x/y < 0 表示无坐标线索，仅靠元素树定位
        val result = remote(TYPE_TIMEOUT_MS) { it.typeText(text, cx ?: -1, cy ?: -1) }
            ?: return unavailable("输入文本")
        if (result.success && result.x >= 0 && result.y >= 0) {
            CursorOverlayService.point(result.x, result.y)
        }
        return result.toResult("已向输入框填充文本", "输入文本")
    }

    // ==================== AIDL 调用与结果映射 ====================

    /** 挂起调用：Provider 不可用/超时/断连一律返回 null，由各方法转成中文失败 */
    private suspend fun remote(
        timeoutMs: Long = ACTION_TIMEOUT_MS,
        block: (IDeviceProvider) -> ActionResult,
    ): ActionResult? = ProviderManager.call(service.applicationContext, timeoutMs) { block(it) }

    /** 同步方法（globalAction / launchApp / openUri / liveCenter / hitTest）的阻塞兜底 */
    private fun <T> blocking(timeoutMs: Long, block: (IDeviceProvider) -> T): T? =
        runBlocking { ProviderManager.call(service.applicationContext, timeoutMs) { block(it) } }

    /** provider.ActionResult → 本地 Result（成功描述统一用中文文案，失败原因直接沿用 Provider 的中文 reason） */
    private fun ActionResult.toResult(description: String, action: String): Result =
        if (success) Result.Success(description) else Result.Failure(reason.ifBlank { "${action}失败" })

    private fun unavailable(action: String): Result =
        Result.Failure("${action}失败：Provider 未安装或无障碍通道未连接")

    private companion object {
        /** 手势类动作的等待上限：Provider 内手势回执超时 5s，这里留出跨进程往返余量 */
        const val ACTION_TIMEOUT_MS = 8_000L

        /** 输入文本会先点击聚焦并等待 350ms 再找输入框，比单纯点击慢 */
        const val TYPE_TIMEOUT_MS = 8_000L

        /** 全局按键回执在毫秒级 */
        const val GLOBAL_TIMEOUT_MS = 5_000L

        /** 启动应用 / 打开链接 / 设置页：startActivity 很快，超时只防 binder 卡死 */
        const val APP_TIMEOUT_MS = 8_000L

        /** 活节点查询（liveCenter / hitTest）为只读遍历，不需要长时间等待 */
        const val LIVE_QUERY_TIMEOUT_MS = 4_000L

        /** 语音助手多入口唤醒：Provider 内最多尝试数个入口，每个含 3s 唤醒验证 + 6s 传话 */
        const val ASSISTANT_TIMEOUT_MS = 30_000L

        /** 单独传话：等待助手出现输入框最多 6s，再找发送键 */
        const val HANDOFF_TIMEOUT_MS = 15_000L

        /** 长按手势的按压时长；光标"按住不放"的动效时长与它同源，改一处即可两处对齐 */
        const val LONG_PRESS_MS = 800L

        /** 容器滚动示意轨迹的时长：这条路径没有真实手势可同步，只是一个"滚了一下"的可视回执 */
        const val CONTAINER_TRACE_MS = 300L

        /** 容器滚动示意轨迹的纵向半径（屏高比例）：Provider 只回传容器中心，几何由本机推导 */
        const val CONTAINER_TRACE_HALF_RATIO = 0.2f

        /** 单个入口拉起后确认助手窗口出现的最长时间（毫秒；厂商助手冷启动较慢，2s 会误判） */
        const val ASSISTANT_WAKE_TIMEOUT_MS = 3000L

        /** 唤醒验证的轮询间隔（毫秒） */
        const val ASSISTANT_WAKE_POLL_MS = 250L
    }
}
