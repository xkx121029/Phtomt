package com.phoneagent.engine.execution

import com.phoneagent.device.a11y.ActionExecutor
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.a11y.NodeHit
import com.phoneagent.device.a11y.NodeSelector
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.PageFingerprint
import com.phoneagent.engine.perception.effectiveLabel
import kotlinx.coroutines.delay

/**
 * 点击流水线（替代原「算一次坐标 → 派发一次手势」的点击方式）。
 *
 * 为什么要有它：
 * 1. 过去点击只有"坐标手势"一条路，坐标又是按**观察那一刻**的元素树算好的，中间隔着一次 AI 请求
 *    （可能数秒）。这期间只要页面有布局变化（系统栏显隐、键盘弹出、列表滚动、启动动画），
 *    旧坐标就整体偏移，典型表现是"点到目标旁边的控件上"；
 * 2. 点空之后没有任何补救，只能靠外层把整条动作重跑一遍，而重跑用的还是同一个坐标。
 *
 * 执行阶梯，**只在动作根本没交付时才升级**（同一个控件被按两遍，对发送/提交/删除就是重复副作用）：
 *   ① 活节点直点：在**此刻**的活节点树上重新找到控件，直接 performAction(ACTION_CLICK)
 *      （控件本身不可点则上溯最近的可点击祖先）。交付成功就以它为准，不再补手势
 *   ② 手势点控件最新位置：①找不到节点/不可点（=没交付）时才降级。落点按"此刻"取——
 *      活节点当前位置 → 元素最新位置 → 快照坐标，逐个夹进屏幕可见区（元素只露一半时
 *      它的中心可能已在屏幕外）；只有手势**没派发出去**（系统拒绝/被取消）才换下一个落点
 *   ③ 滚动一屏重查：仅在①之前、且"自始至终没定位到目标"时启用
 *
 * 手势派发前先做**落点审计**（[ActionExecutor.hitTest]）：问清"这一下会落到谁身上"。
 * 落点不是目标控件时——"无变化"会把差异写进失败原因（是定位/坐标偏了，该重新定位；
 * 还是控件本身无响应，该换意图，AI 下一步的做法完全不同）；"已生效"则只记日志不动结果，
 * 免得 AI 把已经生效的点击当可疑再按一次（发送/删除这类不可逆按钮就是重复副作用）。
 *
 * 成功判定分层（见 [judge]）：先看目标控件自身状态（选中/值/文字/描述）有没有变——
 * 在列表里勾选一项并不会改变整页指纹，只看指纹会把成功误判成失败；再看整页指纹；
 * 前台应用都换了则直接算成功。
 *
 * 确认用**短轮询**（见 [VERIFY_DELAYS_MS]，与 [VerifyTiming] 同源）：响应快的页面一取样就能确认，
 * 不必无论快慢都干等；慢页面（动画/网络）也仍有观察窗。
 */
class ClickRunner(
    private val service: AgentAccessibilityService,
    private val executor: ActionExecutor,
    private val resolver: IntentResolver,
    /** 执行日志出口：接到引擎的 AgentLog 上，出现"点错位置"时靠它分清是选错控件还是坐标算错 */
    private val logger: (String) -> Unit = {},
) {

    suspend fun run(action: AgentAction, snapshot: ScreenSnapshot, longClick: Boolean = false): VerifyResult {
        val verb = if (longClick) "长按" else "点击"
        val pkg = snapshot.packageName ?: "未知应用"
        val resolved = resolver.resolveAction(action, snapshot)
        val targetName = action.target?.value?.takeIf { it.isNotBlank() }
            ?: resolved.element?.effectiveLabel()
            ?: "坐标"

        var element = resolved.element
        var selector = resolver.nodeSelectorOf(action, element)
        var cx = resolved.x
        var cy = resolved.y

        // 执行前抓一次"此刻"的页面：既用来重定位控件，也作为"这一串点击到底有没有产生变化"的判定基准。
        // 这一抓不能省——AI 思考的数秒里页面可能已经切走，不核对就会把点击落到别的应用上。
        val current = captureQuietly()
        if (current != null && switchedApp(current, snapshot)) {
            return VerifyResult(false, "点击前页面已切到 ${current.packageName}，已放弃在 $pkg 上的$verb", "", "")
        }
        element = current?.let { resolver.relocateOnLatest(resolved.element, snapshot, it) } ?: element
        resolver.nodeSelectorOf(action, element)?.let { selector = it }
        cx = element?.centerX ?: cx
        cy = element?.centerY ?: cy
        val baseline = current ?: snapshot

        // ③ 前置：自始至终没定位到目标 —— 先把页面滚一屏，再在滚动后的页面上重查一次（目标多半在下一屏）
        if (element == null && selector == null && (cx == null || cy == null)) {
            logger("未定位到「$targetName」，先滚动一屏查找（$pkg）")
            scrollOneScreen()
            val after = captureQuietly()
                ?: return VerifyResult(
                    false,
                    "当前页面($pkg)没有控件($targetName)：滚动查找时读不到页面，目标应用若未打开，先 launch 到该应用再操作",
                    "", "",
                )
            if (switchedApp(after, snapshot)) {
                return VerifyResult(false, "滚动后页面已切到 ${after.packageName}，已放弃在 $pkg 上的$verb", "", "")
            }
            val retry = resolver.resolveAction(action, after)
            element = retry.element
            selector = resolver.nodeSelectorOf(action, element)
            cx = retry.x
            cy = retry.y
        }

        if (element == null && selector == null && (cx == null || cy == null)) {
            // 保留原失败文案的关键特征：前台应用 + 缺失的控件名，供 AI 判断是"没打开目标应用"还是"控件不存在"
            return VerifyResult(
                false,
                "当前页面($pkg)没有控件($targetName)：目标应用若未打开，先 launch 到该应用再操作，禁止点击不存在的控件",
                "", "",
            )
        }

        // 记下"命中了哪个控件、bounds 是多少"，出现"点错位置"时靠这一行就能分清是选错控件还是坐标算错
        val moved = resolved.element != null && element != null &&
            (element.centerX != resolved.element.centerX || element.centerY != resolved.element.centerY)
        logger(
            "点击目标：$targetName " + (
                element?.let {
                    "[#${it.index}] ${it.effectiveLabel() ?: it.className} " +
                        "bounds=(${it.left},${it.top})-(${it.right},${it.bottom})"
                } ?: "坐标 (${resolved.x}, ${resolved.y})"
                ) + if (moved) "（已按当前页面重定位，快照坐标为 (${resolved.x}, ${resolved.y})）" else "",
        )

        val baselineState = element
        var lastReason = ""

        // ① 活节点直点：交付到控件本身，不受坐标漂移影响，代价也最小
        if (selector != null) {
            when (val r = executor.clickNode(selector, longClick)) {
                is ActionExecutor.Result.Success -> {
                    val v = confirm(baseline, baselineState, verb)
                    if (v.success) return v
                    // 点击已交付到控件本身，再补一次手势就是把它按第二遍。这里就此打住，
                    // 把"未确认"如实回报：AI 下一步重新观察页面后，自会决定是重试还是换路子
                    return VerifyResult(
                        false,
                        "当前页面($pkg)的控件($targetName)${verb}已交付到控件本身，但页面与控件状态均无变化" +
                            "（可能该控件本就无响应，或页面变化较慢）：请先观察当前页面再决定下一步",
                        v.beforeFingerprint, v.afterFingerprint,
                    )
                }
                is ActionExecutor.Result.Failure -> {
                    lastReason = r.reason
                    logger("节点直点未交付（降级为手势）：${r.reason}")
                }
            }
        }

        // ② 手势：落点一律取**此刻**的位置，且必须落在屏幕可见区内
        //    （元素只露一半时它的中心可能在屏幕外，派发到屏幕外等于白点；只按时间点定序不按坐标猜）
        val sx = resolved.x
        val sy = resolved.y
        val candidates = mutableListOf<Pair<String, Pair<Int, Int>>>()
        if (selector != null) {
            // 活节点位置最新——AI 思考的数秒里页面可能已滚动/被顶动，快照坐标早已偏移
            executor.liveCenter(selector)?.let { candidates += "活节点当前位置" to it }
        }
        if (cx != null && cy != null) candidates += "元素最新位置" to Pair(cx, cy)
        if (sx != null && sy != null) candidates += "快照坐标" to Pair(sx, sy)
        val points = candidates
            .map { (name, p) -> name to clampToScreen(p, snapshot) }
            .distinctBy { it.second }

        for ((name, point) in points) {
            // 落点审计：先问清"这一下会落到谁身上"。点错时能立刻分清是定位/坐标偏了
            // （该重新定位）还是控件本身无响应（该换意图），不写清楚 AI 只会把同一下再点一遍
            val hit = executor.hitTest(point.first, point.second)
            logger(
                "$verb (${point.first}, ${point.second})：$name" +
                    (hit?.let { "；落点命中 ${it.describe()}" } ?: "；该点下读不到控件（按坐标盲点）"),
            )
            val dispatched = if (longClick) {
                executor.longClick(point.first, point.second)
            } else {
                executor.click(point.first, point.second)
            }
            if (dispatched is ActionExecutor.Result.Failure) {
                // 手势根本没派发出去（系统拒绝/被取消）＝没交付，换个落点重试是有意义的
                lastReason = dispatched.reason
                logger("$name 未派发成功：${dispatched.reason}")
                continue
            }
            val v = confirm(baseline, baselineState, verb)
            if (v.success) {
                // 页面变了不等于点对了地方：落点与目标不一致时只记日志。
                // 刻意不动结果——已经生效的点击若被判成"可疑"，AI 很可能重按一次，
                // 对发送/删除这类不可逆按钮就是重复副作用；而"没生效"那条路径下面会写明落点差异
                if (hit != null && isMismatch(hit, element, selector)) {
                    logger("注意：$verb 落点命中的 ${hit.describe()} 与目标「$targetName」不一致，页面变化可能来自别的控件")
                }
                return v
            }
            return VerifyResult(
                false,
                missReason(pkg, targetName, verb, point, hit, element, selector),
                v.beforeFingerprint, v.afterFingerprint,
            )
        }

        // 走到这里说明一路都没交付出去（无落点可派发 / 手势全被系统拒绝）
        return VerifyResult(
            false,
            "当前页面($pkg)的控件($targetName)${verb}未能交付（${lastReason.ifBlank { "无可用的点击方式" }}）：" +
                "目标应用若未打开，先 launch 到该应用再操作，禁止点击不存在的控件",
            "", "",
        )
    }

    /**
     * 执行后确认：按 [VERIFY_DELAYS_MS] 取样，一旦发现变化立即返回，全部取样都没变才判失败。
     *
     * 读不到页面时按"已执行但未确认"算成功：此时继续升级就等于再点一次（可能重复副作用），
     * 而下一步的观察本来就会给出真实结果，报失败反而会误触发"连续失败请求用户介入"。
     */
    private suspend fun confirm(before: ScreenSnapshot, beforeState: UiElement?, verb: String): VerifyResult {
        val beforeFp = PageFingerprint.computeMeaningful(before)
        var afterFp = beforeFp
        var reason = "页面与控件状态均无变化"
        var captured = false
        for (delayMs in VERIFY_DELAYS_MS) {
            delay(delayMs)
            val after = captureQuietly() ?: break
            captured = true
            afterFp = PageFingerprint.computeMeaningful(after)
            val verdict = judge(before, beforeState, after, beforeFp, afterFp)
            if (verdict.first) return VerifyResult(true, "已$verb（${verdict.second}）", beforeFp, afterFp)
            reason = verdict.second
        }
        if (!captured) return VerifyResult(true, "已$verb；此刻读不到页面，变化未能确认，以下一步观察为准", "", "")
        return VerifyResult(false, reason, beforeFp, afterFp)
    }

    /** 分层判定：前台应用 → 控件自身状态 → 整页指纹 */
    private fun judge(
        before: ScreenSnapshot,
        beforeState: UiElement?,
        after: ScreenSnapshot,
        beforeFp: String,
        afterFp: String,
    ): Pair<Boolean, String> {
        // 第一层：前台应用都换了 → 点击必然生效（导航/跳转）
        val appBefore = before.packageName
        val appAfter = after.packageName
        if (appAfter != null && appBefore != null && appAfter != appBefore) {
            return true to "页面已切换到 $appAfter"
        }
        // 第二层：目标控件自身状态变化（勾选、滑块值、文字、无障碍描述）——
        // 列表内勾选/切换这类操作不会改变整页指纹，只看指纹会误判成"没生效"
        if (beforeState != null) {
            val now = resolver.matchInFresh(beforeState, after)
            if (now != null && stateOf(beforeState) != stateOf(now)) return true to "目标控件状态已变化"
        }
        // 第三层：整页指纹
        if (beforeFp != afterFp) return true to "页面已变化"
        return false to "页面与控件状态均无变化"
    }

    /** 控件的"可比较状态"：选中态 / 当前值 / 文字 / 无障碍描述 */
    private fun stateOf(element: UiElement): String =
        "${element.isSelected}|${element.currentValue}|${element.text}|${element.contentDescription}"

    /**
     * 滚动一屏查找目标：优先在页面里的**可滚动容器**内滚（精确，不受坐标与手势落点影响），
     * 没有容器才退回屏幕中心手势（盲划，可能划到横向轮播或非滚动区域）。
     */
    private suspend fun scrollOneScreen() {
        when (val r = executor.scrollContainer("up")) {
            is ActionExecutor.Result.Success -> logger("已在可滚动容器内滚动一屏")
            is ActionExecutor.Result.Failure -> {
                logger("可滚动容器不可用（${r.reason}），退回屏幕中心手势")
                executor.scroll(null, "up")
            }
        }
        delay(SCROLL_SETTLE_MS)
    }

    /** 取一份当前快照；读不到（无障碍断开等）返回 null，不抛异常打断任务 */
    private fun captureQuietly(): ScreenSnapshot? =
        runCatching { service.captureScreen() }.getOrNull()?.takeUnless { it.missingAccessibility }

    /**
     * 把落点夹进屏幕可见区。
     *
     * 元素树里的"可见"只保证它露了一部分：列表底部半截可见的行，中心点可能已经在屏幕外，
     * 手势派发到屏幕外的点要么被系统拒绝、要么落在别处，表现出来就是"点了没反应"。
     */
    private fun clampToScreen(point: Pair<Int, Int>, snapshot: ScreenSnapshot): Pair<Int, Int> {
        val w = snapshot.screenWidth.takeIf { it > 0 } ?: DEFAULT_SCREEN_W
        val h = snapshot.screenHeight.takeIf { it > 0 } ?: DEFAULT_SCREEN_H
        return point.first.coerceIn(0, w - 1) to point.second.coerceIn(0, h - 1)
    }

    /**
     * 落点命中的控件是不是"我们要点的那个"。
     *
     * 两侧都按包含比对，不做严格相等：目标可能是外层容器、落点可能是它内层的图标/文字
     * （点下去照样算点中），反过来也一样。
     *
     * 不可点击的落点一律算"无法判定"：此时点击其实由祖先容器承接，据此报"点错"就是误报。
     */
    private fun isMismatch(hit: NodeHit, element: UiElement?, selector: NodeSelector?): Boolean {
        if (!hit.clickable) return false
        val labels = listOfNotNull(element?.text, element?.contentDescription, selector?.label)
            .map { it.trim() }.filter { it.isNotEmpty() }
        val ids = listOfNotNull(element?.viewId, selector?.viewId).filter { it.isNotBlank() }
        if (labels.isEmpty() && ids.isEmpty()) return false
        val hitLabel = hit.label?.trim().orEmpty()
        if (hitLabel.isNotEmpty() &&
            labels.any { it.equals(hitLabel, true) || it.contains(hitLabel, true) || hitLabel.contains(it, true) }
        ) {
            return false
        }
        val hitId = hit.viewId.orEmpty()
        if (hitId.isNotEmpty() && ids.any { hitId.endsWith(it, true) || it.endsWith(hitId, true) }) return false
        return true
    }

    /**
     * "已派发但页面无变化"的失败原因：把落点命中的控件一并写明。
     *
     * 两种情况下一步完全不同——落点不是目标控件的，是定位/坐标偏差，该重新观察页面重新定位；
     * 落点就是目标控件的，说明控件本身无响应（或页面变化慢），该换意图或等待。
     * 只回一句笼统的"未生效"，AI 多半会把同一下再点一遍。
     */
    private fun missReason(
        pkg: String,
        targetName: String,
        verb: String,
        point: Pair<Int, Int>,
        hit: NodeHit?,
        element: UiElement?,
        selector: NodeSelector?,
    ): String {
        val base = "当前页面($pkg)的控件($targetName)${verb}已派发到 (${point.first}, ${point.second})，但页面与控件状态均无变化"
        if (hit == null) return "$base：请先观察当前页面再决定下一步"
        return if (isMismatch(hit, element, selector)) {
            "$base——落点实际命中的是 ${hit.describe()}，与目标不一致（定位或坐标偏差）：" +
                "请先观察当前页面并重新定位目标，不要直接重试同一下"
        } else {
            "$base——落点即目标控件，该控件可能本就无响应或页面变化较慢：" +
                "请先观察当前页面，必要时换一个意图"
        }
    }

    /** 页面是否已切到别的应用（包名读到才判定，读不到时不阻断执行） */
    private fun switchedApp(after: ScreenSnapshot, before: ScreenSnapshot): Boolean {
        val a = after.packageName ?: return false
        val b = before.packageName ?: return false
        return a != b
    }

    private companion object {
        /**
         * 点击后取样的累计等待时刻（毫秒）：短轮询让响应快的页面尽早定型（首取样即可确认），
         * 不必像固定等待那样无论快慢都干等；两次取样都没变化才判失败。
         * 与 [VerifyTiming] 同源：点击与滑动/输入共用一套取样节奏，不出现两套标准。
         */
        val VERIFY_DELAYS_MS = VerifyTiming.SAMPLE_DELAYS_MS

        /** 滚动后的稳定等待：等滚动动画/惯性结束再抓页面 */
        const val SCROLL_SETTLE_MS = 400L

        /** 屏幕尺寸读不到时的兜底尺寸（仅用于把落点夹进屏幕内） */
        const val DEFAULT_SCREEN_W = 1080
        const val DEFAULT_SCREEN_H = 2400
    }
}