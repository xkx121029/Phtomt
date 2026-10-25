package com.phoneagent.provider.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.phoneagent.provider.A11ySelector
import com.phoneagent.provider.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 手势落点命中的控件（"这一下点到了谁"）。
 *
 * 点击前用它审计落点：落点与目标不是同一个控件时，调用方能立刻分清是**选错/坐标偏了**
 * （该重新定位）还是**控件本身无响应**（该换意图），而不是笼统报"未生效"。
 * 经 IDeviceProvider.hitTest 以 JSON {label,viewId,className,clickable} 跨进程回传。
 */
data class NodeHit(
    /** 控件标签：自身文字/描述，没有则由后代文字拼出（与 [DerivedLabel] 同源） */
    val label: String?,
    val viewId: String?,
    val className: String,
    val clickable: Boolean,
)

/**
 * 通过无障碍服务执行具体的屏幕动作（Provider 进程内）。
 * 点击/滑动使用手势，返回键/主页/最近任务使用全局按键，文本输入使用 ACTION_SET_TEXT。
 *
 * 与主应用旧版 ActionExecutor 的差异：光标绘制（CursorOverlayService）已整体移除——
 * 光标画在主应用侧，每个动作在知道真实落点的现场把中心坐标填进 [ActionResult] 回执，
 * 主应用按回执 x/y 自行画光标/轨迹；x/y 取不到时为 -1，失败时一律 -1（没有发生落点）。
 */
class ProviderActionExecutor(val service: ProviderAccessibilityService) {

    /** 成功回执：reason 按契约成功时为空串，成功描述不再跨进程回传 */
    private fun ok(x: Int = -1, y: Int = -1) = ActionResult(true, "", x, y)

    private fun fail(reason: String) = ActionResult(false, reason)

    /** 点击屏幕坐标；落点就是坐标本身 */
    suspend fun click(x: Int, y: Int): ActionResult {
        val (success, reason) = dispatchGesture(GestureDescription.Builder().run {
            addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 60))
            build()
        })
        return if (success) ok(x, y) else fail(reason)
    }

    /**
     * 长按。
     *
     * @param durationMs 按压时长（毫秒）。上层（AI 决策）通过 AIDL 指定时按它执行；
     *   传 <=0 时沿用默认 [LONG_PRESS_MS]，保证既有调用点的稳定性不变。
     */
    suspend fun longClick(x: Int, y: Int, durationMs: Int): ActionResult {
        // 上层指定了时长就用它（让 AI 的 durationMs 生效）；未指定则沿用默认按压时长 [LONG_PRESS_MS]，
        // 保证既有调用点的稳定性不变（800ms 足够长，必然触发长按）
        val hold = if (durationMs > 0) durationMs.toLong() else LONG_PRESS_MS
        val (success, reason) = dispatchGesture(GestureDescription.Builder().run {
            addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                    0,
                    hold,
                ),
            )
            build()
        })
        return if (success) ok(x, y) else fail(reason)
    }

    /**
     * 按控件的定位线索，在**活节点树**上找到它并直接 `performAction(ACTION_CLICK)`。
     *
     * 相比坐标手势：手势点的是"像素"，控件被弹窗/键盘/系统栏顶动几像素就落到旁边去了；
     * 节点直点交给系统分发到控件本身，不受坐标漂移影响，也无需等待手势回调。
     *
     * 命中的可能是"没有文字的容器"（真正的可点击控件在它祖先上），所以失败时沿父链
     * **上溯最近的可点击祖先**；仍不行才返回失败，由上层降级为坐标手势。
     *
     * 节点直点没有坐标手势，屏幕上不留任何痕迹：回执里带上命中节点的 bounds 中心，
     * 主应用据此把光标送到控件上，用户才看得到 AI 点了哪里。
     *
     * @param durationMs 仅长按语义提示时长；节点直点走 ACTION_LONG_CLICK 由系统定义按压，
     *   旧版它只驱动光标动效，光标移到主应用后此处不再消费
     */
    suspend fun clickNode(selector: A11ySelector, longClick: Boolean, durationMs: Int): ActionResult {
        val root = service.rootInActiveWindow
            ?: return fail("无障碍读不到当前页面节点树")
        val found = findNode(root, selector)
            ?: return fail("活节点树中没有控件(${selector.describe()})")
        val bounds = Rect()
        found.getBoundsInScreen(bounds)
        val nx = if (bounds.width() > 0 && bounds.height() > 0) bounds.centerX() else -1
        val ny = if (bounds.width() > 0 && bounds.height() > 0) bounds.centerY() else -1
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var node: AccessibilityNodeInfo? = found
        var hops = 0
        while (node != null && hops <= MAX_CLICKABLE_ANCESTOR_HOPS) {
            if (node.performAction(action)) {
                return ok(nx, ny)
            }
            node = node.parent
            hops++
        }
        return fail("控件(${selector.describe()})不可点击（已上溯 $MAX_CLICKABLE_ANCESTOR_HOPS 层祖先）")
    }

    /** 在活节点树上按线索找目标控件：viewId → 文字精确 → 文字包含 → 位置兜底，逐级降级 */
    private fun findNode(root: AccessibilityNodeInfo, selector: A11ySelector): AccessibilityNodeInfo? {
        val all = collectVisibleNodes(root) ?: return null

        // 逐级降级：只有上一级完全没命中才启用下一级，避免"id 明明在、却被同文字的别的控件抢先"
        selector.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            pickBy(all.filter { (it.viewIdResourceName ?: "").endsWith(id, ignoreCase = true) }, selector)?.let { return it }
        }
        selector.label?.trim()?.takeIf { it.isNotEmpty() }?.let { label ->
            pickBy(labelCandidates(all, label, exact = true), selector)?.let { return it }
            // 容器自身没有文字、标签由后代拼出来的情况，只能按"包含"兜底
            pickBy(labelCandidates(all, label, exact = false), selector)?.let { return it }
        }
        if (selector.hasHint) {
            pickBy(NodesAtPoint.of(all, selector.hintX, selector.hintY), selector)?.let { return it }
        }
        return null
    }

    /** 活节点树上的可见节点（遍历一次）；读不到节点树返回 null */
    private fun collectVisibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectVisible(root, 0, all)
        return all.ifEmpty { null }
    }

    /**
     * 按标签挑候选：节点的**自身**文字/描述，或**后代文字拼出的派生标签**（[DerivedLabel]）命中即可。
     *
     * 派生标签这一层不能少：可点击容器常常没有自己的文字（微信聊天列表行就是这样，标签挂在
     * 不可点击的子控件上），元素树里 AI 看到的标签正是拼出来的那串。只比节点自身文字，
     * 这类目标在活节点树上永远匹配不到，「节点直点」这一级就静默失效，退化成按坐标猜。
     */
    private fun labelCandidates(
        all: List<AccessibilityNodeInfo>,
        label: String,
        exact: Boolean,
    ): List<AccessibilityNodeInfo> = all.filter { node ->
        val texts = textsOf(node) + listOfNotNull(DerivedLabel.of(node))
        texts.any { if (exact) it.equals(label, ignoreCase = true) else it.contains(label, ignoreCase = true) }
    }

    private fun textsOf(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).filter { it.isNotBlank() }

    /**
     * 目标控件**此刻**的中心点：把定位线索重新落到活节点树上，取命中节点自身的中心。
     *
     * 供调用方在手势点击前取最新落点——AI 思考的数秒里页面可能已滚动/被顶动，
     * 快照坐标早已偏离；活节点位置才是"现在它到底在哪"。
     * 命中的是内层图标也无妨：其中心必然落在承接点击的祖先容器内。
     */
    fun liveCenter(selector: A11ySelector): Pair<Int, Int>? {
        val root = service.rootInActiveWindow ?: return null
        val node = findNode(root, selector) ?: return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return rect.centerX() to rect.centerY()
    }

    /**
     * 落点审计：屏幕上承接 (x, y) 这一下的控件是谁（最内层、优先可点击）。
     *
     * 只读探测，不派发任何动作；读不到节点树时返回 null（调用方按"无法审计"处理）。
     */
    fun hitTest(x: Int, y: Int): NodeHit? {
        val root = service.rootInActiveWindow ?: return null
        val all = collectVisibleNodes(root) ?: return null
        val node = pickBy(NodesAtPoint.of(all, x, y), A11ySelector(hintX = x, hintY = y)) ?: return null
        return NodeHit(
            label = textsOf(node).firstOrNull() ?: DerivedLabel.of(node),
            viewId = node.viewIdResourceName?.substringAfterLast('/'),
            className = node.className?.toString() ?: "Unknown",
            clickable = node.isClickable,
        )
    }

    private fun collectVisible(node: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > MAX_NODE_DEPTH || out.size >= MAX_COLLECT_NODES) return
        if (node.isVisibleToUser) out += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectVisible(it, depth + 1, out) }
        }
    }

    /**
     * 候选取舍：可点击的优先（那才是能按的），其次面积最小的优先（越具体越贴近视觉位置），
     * 最后取离快照位置最近的（同一 viewId / 同一段文字会出现在列表的每一行上）。
     */
    private fun pickBy(candidates: List<AccessibilityNodeInfo>, selector: A11ySelector): AccessibilityNodeInfo? {
        if (candidates.isEmpty()) return null
        val hasHint = selector.hasHint
        val rect = Rect()
        return candidates.minWithOrNull(
            compareBy(
                { if (it.isClickable) 0 else 1 },
                { node -> node.getBoundsInScreen(rect); rect.width() * rect.height() },
                { node ->
                    if (!hasHint) {
                        0
                    } else {
                        node.getBoundsInScreen(rect)
                        val dx = rect.centerX() - selector.hintX
                        val dy = rect.centerY() - selector.hintY
                        dx * dx + dy * dy
                    }
                },
            ),
        )
    }

    /** 滑动；落点取轨迹中点（主应用画光标轨迹的锚点） */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ActionResult {
        val (success, reason) = dispatchGesture(GestureDescription.Builder().run {
            addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
                    0,
                    durationMs.toLong(),
                ),
            )
            build()
        })
        return if (success) ok((x1 + x2) / 2, (y1 + y2) / 2) else fail(reason)
    }

    /**
     * 在页面的**可滚动容器**内滚动一屏（ACTION_SCROLL_FORWARD / BACKWARD）。
     *
     * 比坐标滑动精确：坐标滑动是从屏幕中心盲划，碰到横向轮播、悬浮按钮、非滚动区域时要么划不动、
     * 要么划错容器；直接对容器下发滚动动作，完全不受坐标与手势落点影响。
     *
     * 这条路径没有手势，但**不能没有可视回执**：AI 表达滚动时并不给坐标（`swipe` 只说方向，
     * `scroll_to` 只说"要找什么控件"），而"滚动查找"走的正是这里。回执带上容器中心，
     * 主应用据此补一段"滚了一下"的光标示意轨迹——用户才看得出 AI 在动手机。
     *
     * @param direction up=往下翻看后面的内容；down=往上翻回前面的内容
     */
    suspend fun scrollContainer(direction: String): ActionResult {
        val root = service.rootInActiveWindow ?: return fail("无障碍读不到当前页面节点树")
        val container = findScrollable(root) ?: return fail("当前页面没有可滚动容器")
        val action = if (direction == "down") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        if (!container.performAction(action)) return fail("容器不接受滚动动作")
        // 滚动是内容动、手指不动，这次没有手势可跟——示意轨迹的几何（上下留边避开固定头部）
        // 由主应用按容器中心自行推导，Provider 只回传中心点
        val rect = Rect()
        container.getBoundsInScreen(rect)
        val cx = if (rect.width() > 0 && rect.height() > 0) rect.centerX() else -1
        val cy = if (rect.width() > 0 && rect.height() > 0) rect.centerY() else -1
        return ok(cx, cy)
    }

    /** 找页面里最像"主内容区"的可滚动容器：可见且可滚动，取面积最大的那个 */
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectVisible(root, 0, all)
        val rect = Rect()
        return all.filter { it.isScrollable }.maxByOrNull {
            it.getBoundsInScreen(rect)
            rect.width() * rect.height()
        }
    }

    /** 全局按键（GLOBAL_ACTION_* 常量由主应用经 AIDL 传入） */
    fun globalAction(action: Int): Boolean = service.performGlobalAction(action)

    /** 启动应用（多种策略兜底，确保兼容不同 Android 版本） */
    fun launchApp(packageName: String): ActionResult {
        return try {
            val pm = service.applicationContext.packageManager
            // 策略1：精确查找 LAUNCHER activity
            val query = pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                },
                0,
            )
            if (query.isNotEmpty()) {
                val ri = query[0]
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.applicationContext.startActivity(intent)
                return ok()
            }
            // 无 LAUNCHER 入口时直接诚实失败：发 MAIN+LAUNCHER 广播并不会启动 Activity，
            // 无论应用是否存在 sendBroadcast 都"成功"，会让 AI 误以为已打开而不再换方案
            return fail("无法启动：未找到该应用的可启动入口（LAUNCHER Activity）")
        } catch (e: Exception) {
            fail("启动应用失败：${e.message} (pkg=$packageName)")
        }
    }

    /**
     * 唤起系统语音助手（assistant 意图的执行出口）。
     *
     * 多入口依次唤醒 + 拉起后验证：部分设备上后台 startActivity 会被**静默拦截**
     * （不抛异常但窗口根本不出现），所以每个入口拉起后都确认助手窗口真的到了前台，
     * 没到就换下一个入口（语音指令入口 → 默认助理入口 → 厂商助手兜底）。
     * 唤醒成功后尽力传话；全部入口都失败才返回 Failure。
     */
    suspend fun launchAssistant(text: String?): ActionResult {
        val targets = VoiceAssistantDetector(service.applicationContext).probeAll()
        if (targets.isEmpty()) {
            return fail("本机未找到系统语音助手（已尝试语音指令、默认助理与常见厂商助手）；请确认设备已启用语音助手，或改用其他方式完成")
        }
        var lastReason = "未知原因"
        for (target in targets) {
            if (wakeAssistant(target)) {
                passToAssistant(text)
                return ok()
            }
            lastReason = "「${target.via.label}」入口（${target.packageName ?: "系统默认"}）拉起后未见助手窗口"
        }
        return fail(
            "无法唤醒语音助手：已尝试 ${targets.size} 个入口，最后失败原因——$lastReason。" +
                "可在系统设置里把默认语音助手设为可用的助手应用后重试，或改用其他方式完成任务",
        )
    }

    /** 供外部方式（如 shell 兜底）拉起助手后单独调用传话；返回中文结果说明 */
    suspend fun handoffToAssistant(text: String?): String = passToAssistant(text)

    /** 按包名轮询确认语音助手窗口已出现；pkg 未知时恒为 true（尽力而为）。供 shell 兜底路径复用 */
    suspend fun assistantVisible(pkg: String?, timeoutMs: Long = ASSISTANT_WAKE_TIMEOUT_MS): Boolean =
        assistantWindowVisible(pkg, timeoutMs)

    /** 拉起单个助手入口并验证窗口真的出现；返回 false 表示这条路不通（没抛异常但窗口没来） */
    private suspend fun wakeAssistant(target: VoiceAssistantDetector.Target): Boolean {
        val ctx = service.applicationContext
        val pm = ctx.packageManager
        val intent = when (target.via) {
            VoiceAssistantDetector.Via.VOICE_COMMAND ->
                android.content.Intent(android.content.Intent.ACTION_VOICE_COMMAND)
            VoiceAssistantDetector.Via.ASSIST ->
                android.content.Intent(android.content.Intent.ACTION_ASSIST)
            VoiceAssistantDetector.Via.VENDOR ->
                pm.getLaunchIntentForPackage(target.packageName ?: return false)
        }?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // 系统入口探测到具体包名时钉住，避免被第三方应用抢答（厂商兜底自带包名，无需再钉）
            if (target.via != VoiceAssistantDetector.Via.VENDOR && !target.packageName.isNullOrBlank()) {
                setPackage(target.packageName)
            }
        } ?: return false
        return try {
            ctx.startActivity(intent)
            assistantWindowVisible(target.packageName, ASSISTANT_WAKE_TIMEOUT_MS)
        } catch (e: Exception) {
            false
        }
    }

    /** 轮询确认助手窗口已出现；pkg 未知时视为成功（尽力而为，与原「仅拉起」语义一致） */
    private suspend fun assistantWindowVisible(pkg: String?, timeoutMs: Long): Boolean {
        if (pkg.isNullOrBlank()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (assistantInWindows(pkg)) return true
            delay(ASSISTANT_WAKE_POLL_MS)
        }
        return assistantInWindows(pkg)
    }

    /** 助手包名的窗口是否已在无障碍窗口列表/活动窗口里（助手常以悬浮层出现，两个来源都要看） */
    private fun assistantInWindows(pkg: String): Boolean =
        windowRoots().any { it?.packageName == pkg }

    /**
     * 全部无障碍窗口的根节点（活动窗口在前，悬浮层在后）。
     * 传话与唤醒验证共用：语音助手的输入框/窗口经常不在活动窗口里。
     */
    private fun windowRoots(): List<AccessibilityNodeInfo?> = buildList {
        add(service.rootInActiveWindow)
        runCatching { service.windows }.getOrNull()?.forEach { w ->
            runCatching { w.root }.getOrNull()?.let { add(it) }
        }
    }

    /**
     * 传话（尽力而为）：等助手界面出现输入框 → 填入文字 → 找"发送"类控件点击。
     * 任何一环失败都降级为"仅唤醒"的中文说明，不抛失败。
     */
    private suspend fun passToAssistant(text: String?): String {
        if (text.isNullOrBlank()) return "已唤醒语音助手"
        // 等待助手界面出现可编辑节点（语音优先的助手界面，输入框可能延迟渲染）；
        // 跨全部无障碍窗口扫描——助手的输入框常在悬浮层里，不一定在活动窗口
        var box: AccessibilityNodeInfo? = null
        for (attempt in 0 until ASSISTANT_PROBE_ROUNDS) {
            box = windowRoots().firstNotNullOfOrNull { findFirstEditable(it) }
            if (box != null) break
            delay(ASSISTANT_PROBE_INTERVAL_MS)
        }
        if (box == null || !setText(box, text)) {
            return "已唤醒语音助手（未能自动传话，请直接对它说话）"
        }
        return if (tapSendControl()) {
            "已唤醒语音助手，并把「$text」转交给了它"
        } else {
            "已唤醒语音助手，已填入文字但未找到发送按钮，请在助手界面确认"
        }
    }

    /** 在活节点树里找"发送"类可点击控件并点击；找不到返回 false，由调用方降级为"已填入" */
    private suspend fun tapSendControl(): Boolean {
        // 词表覆盖文字按钮与图标 description 两种形态；"完成/确定/Go"是部分助手发送键的实际文案
        val keywords = listOf("发送", "send", "搜索", "提问", "→", "➤", "完成", "确定", "go", "开始")
        val node = windowRoots().firstNotNullOfOrNull { findSendNode(it, keywords, 0) } ?: return false
        val r = Rect()
        node.getBoundsInScreen(r)
        // 部分助手"发送"控件自身不可点击但祖先容器可点，上溯兜底（层数与 clickNode 同源）
        var target = node
        var hops = 0
        while (!target.isClickable && hops < MAX_CLICKABLE_ANCESTOR_HOPS) {
            target = target.parent ?: break
            hops++
        }
        return if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            true
        } else {
            click(r.centerX(), r.centerY()).success
        }
    }

    /** 递归找文字/描述命中发送词表且可见的节点（深度上限与活节点遍历同源） */
    private fun findSendNode(node: AccessibilityNodeInfo?, keywords: List<String>, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_NODE_DEPTH) return null
        if (node.isVisibleToUser) {
            val label = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
            if (keywords.any { label.contains(it, ignoreCase = true) }) return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findSendNode(child, keywords, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 用系统应用打开链接/文件（ACTION_VIEW）——"交给系统软件打开"的统一出口。
     *
     * 三类目标都由 [OpenTarget] 归一化后交给系统：
     * 1. 网址（http/https）→ 系统浏览器；
     * 2. 本地文件（`/sdcard/x.ppt`、`file://…`）→ 转 `content://` + 按扩展名补 MIME，
     *    否则文档软件不会被列为候选（详见 [OpenTarget]）；
     * 3. App 私有 scheme / 系统页 → 原样直发。
     *
     * @param pkg 指定用哪个应用打开（已解析的包名）；为空时**优先系统自带应用**，
     *            没有任何系统应用可处理才交回系统默认/选择器
     */
    fun openUri(uri: String, pkg: String? = null): ActionResult {
        val raw = uri.trim().takeIf { it.isNotBlank() } ?: return fail("打开目标为空")
        val data = android.net.Uri.parse(OpenTarget.normalize(raw))
        val base = android.content.Intent(android.content.Intent.ACTION_VIEW, data).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            OpenTarget.mimeOf(raw)?.let { setDataAndType(data, it) }
        }
        val target = pkg?.trim()?.takeIf { it.isNotBlank() } ?: systemHandlerOf(base)
        return try {
            service.applicationContext.startActivity(if (target != null) android.content.Intent(base).setPackage(target) else base)
            ok()
        } catch (e: Exception) {
            // 没有任何应用能处理这个链接/文件（如本机没装文档阅读器）——给出可执行的中文原因
            fail("没有应用能打开 $raw（${e.javaClass.simpleName}）：本机可能缺少能处理该类型的应用")
        }
    }

    /**
     * 未指定应用时优先挑**系统自带**应用（用户偏好：打开软件优先用系统软件）。
     * 查不到系统应用（或 Android 11+ 未授予包可见性）返回 null，交回系统的默认应用/选择器。
     */
    private fun systemHandlerOf(intent: android.content.Intent): String? {
        val pm = service.applicationContext.packageManager
        val hits = runCatching {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        return hits.firstOrNull { ri ->
            val app = ri.activityInfo?.applicationInfo ?: return@firstOrNull false
            app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }?.activityInfo?.packageName
    }

    /** 用系统 Intent Action 直达指定设置页（如 Wi-Fi/蓝牙/显示） */
    fun openSettingsAction(action: String): ActionResult {
        return try {
            val intent = android.content.Intent(action).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.applicationContext.startActivity(intent)
            ok()
        } catch (e: Exception) {
            fail("打开设置页失败：${e.message}")
        }
    }

    /**
     * 向输入框填充文本（整段覆写，ACTION_SET_TEXT）。
     *
     * 两层策略：
     * 1. 若活节点树中能找到目标坐标处的可编辑节点 → 直接对其填充；
     * 2. 若无障碍读不到输入框（如 WebView/自绘控件）：先点击坐标聚焦，
     *    等待聚焦后再查可编辑节点并填充。
     *
     * @param x/y 命中节点时回执其 bounds 中心（主应用画光标用）；x/y < 0 表示仅靠元素树定位
     */
    suspend fun typeText(text: String, x: Int?, y: Int?): ActionResult {
        val cx = x?.takeIf { it >= 0 }
        val cy = y?.takeIf { it >= 0 }
        // 策略1：直接命中活节点树中的可编辑节点
        findEditableNode(cx, cy)?.let { node ->
            if (setText(node, text)) return nodeCenterOf(node)?.let { ok(it.first, it.second) } ?: ok()
        }
        // 策略2：点击坐标聚焦后填充（弥补元素树读不到输入框的场景）
        if (cx != null && cy != null) {
            click(cx, cy)
            delay(350)
            val root = service.rootInActiveWindow
            findFirstEditable(root)?.let { node ->
                if (setText(node, text)) {
                    // 填充落点优先取节点中心；节点 bounds 异常时退回聚焦点击的坐标
                    val c = nodeCenterOf(node)
                    return if (c != null) ok(c.first, c.second) else ok(cx, cy)
                }
            }
        }
        return fail("未找到可输入文本的输入框")
    }

    /** 命中节点的 bounds 中心；bounds 异常（零尺寸）时返回 null（回执落点 -1） */
    private fun nodeCenterOf(node: AccessibilityNodeInfo): Pair<Int, Int>? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return rect.centerX() to rect.centerY()
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun findEditableNode(x: Int?, y: Int?): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (x != null && y != null) {
            findNodeAt(root, x, y)?.let { return it }
        }
        return findFirstEditable(root)
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node.isEditable) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.contains(x, y)) return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeAt(child, x, y)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findFirstEditable(child)?.let { return it }
            }
        }
        return null
    }

    /** 派发手势并等待结果，回执 (成功, 中文失败原因)。
     *  必须限时：服务被系统回收/授权被清（如 vivo 安全守护）后 dispatchGesture 可能返回 true
     *  但回调永远不来，协程会在此永久挂起——跨进程表现为 Binder 调用卡在某一步、无失败也无超时（真机 BUG 21）。
     *  超时即按失败返回，交给上层「连续失败 → 请求介入」链路处理。 */
    private suspend fun dispatchGesture(gesture: GestureDescription): Pair<Boolean, String> =
        withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // 取消标记：协程取消（任务 stop）后系统回调仍可能触发，
                // 注册 invokeOnCancellation 把取消意图显式化，回调里双重判活，
                // 避免取消后回调再走 resume 造成挂起/回调泄漏（2026-08-15 B12）
                var cancelled = false
                cont.invokeOnCancellation { cancelled = true }
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (!cancelled && cont.isActive) cont.resume(true to "")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (!cancelled && cont.isActive) cont.resume(false to "手势被取消")
                    }
                }
                val dispatched = service.dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    if (cont.isActive) cont.resume(false to "手势派发失败")
                }
            }
        } ?: (false to "手势执行超时（${GESTURE_TIMEOUT_MS}ms 内无回调，无障碍服务可能已断开）")

    private companion object {
        /** 上溯可点击祖先的最大层数：再往上就是整卡片/整屏级容器，点它等于点错地方 */
        const val MAX_CLICKABLE_ANCESTOR_HOPS = 3

        /** 手势派发的回调等待上限：正常手势 400~1500ms 内必有回调，5s 仍无回调即判定服务异常（见 dispatchGesture） */
        const val GESTURE_TIMEOUT_MS = 5000L

        /** 活节点树遍历深度上限，防极深布局拖慢执行 */
        const val MAX_NODE_DEPTH = 64

        /** 活节点树单次遍历收录的节点数上限，防超长列表把遍历拖成秒级 */
        const val MAX_COLLECT_NODES = 800

        /** 长按手势的默认按压时长；AIDL 未指定 durationMs（<=0）时用它 */
        const val LONG_PRESS_MS = 800L

        /** 传话等待助手界面出输入框的轮询轮数（10 × 600ms = 6s 内没等到就降级为仅唤醒；
         *  语音优先的助手界面输入框渲染较慢，2.5s 经常不够） */
        const val ASSISTANT_PROBE_ROUNDS = 10

        /** 传话轮询间隔（毫秒） */
        const val ASSISTANT_PROBE_INTERVAL_MS = 600L

        /** 单个入口拉起后确认助手窗口出现的最长时间（毫秒；厂商助手冷启动较慢，2s 会误判） */
        const val ASSISTANT_WAKE_TIMEOUT_MS = 3000L

        /** 唤醒验证的轮询间隔（毫秒） */
        const val ASSISTANT_WAKE_POLL_MS = 250L
    }
}

/** 定位线索的中文描述，供日志与失败原因使用（原 NodeSelector.describe 的迁移） */
internal fun A11ySelector.describe(): String {
    val parts = mutableListOf<String>()
    viewId?.takeIf { it.isNotBlank() }?.let { parts += "viewId=$it" }
    label?.takeIf { it.isNotBlank() }?.let { parts += "label=\"$it\"" }
    if (parts.isEmpty() && hasHint) parts += "位置=($hintX,$hintY)"
    return parts.joinToString(" ").ifEmpty { "坐标" }
}
