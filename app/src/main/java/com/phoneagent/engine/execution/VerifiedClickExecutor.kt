package com.phoneagent.engine.execution

import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.perception.PageFingerprint
import kotlinx.coroutines.delay

/**
 * 动作的交付状态：是否送到设备、送到之后有没有效果。
 *
 * 区分「没送达」与「送达了但没生效」是重试策略的前提：
 * - 没送达：动作根本没碰到设备，换通道 / 换落点再试是安全的；
 * - 已送达未生效：动作已经落到页面上，原样再执行一遍就是重复副作用（重复提交/重复收藏），
 *   应当换入口 / 换控件 / 换策略，而不是重放。
 *
 * 判定只读结构化字段（[VerifyResult.success] / [VerifyResult.notDelivered]），
 * 不解析 [VerifyResult.reason] 的文案——提示词改一个字不该改变重试行为。
 */
enum class Delivery {
    /** 已交付且确认生效 */
    EFFECTIVE,

    /**
     * 已交付到设备，但**没能读到页面**，因此既不能确认生效、也没有证据说它没生效。
     *
     * 与 [DELIVERED_NO_EFFECT] 的区别是证据的方向：后者看到了"页面照旧"，这里是压根没看见。
     * 它必须是一个独立状态，不能折叠进任何一侧——
     * 折叠进 [EFFECTIVE] 会让执行历史记 ✅、进度与检查点照存、失败计数清零，
     * 于是"连续失败请求用户介入"这条兜底永远不会触发（真机上表现为一整串绿的 ✅ 后面什么都没做成）。
     */
    DELIVERED_UNCONFIRMED,

    /** 已交付到设备，但没有观察到预期效果 */
    DELIVERED_NO_EFFECT,

    /** 根本没交付到设备（通道不可用 / 手势被系统拒绝 / 请求本身不成立） */
    NOT_DELIVERED,
}

/**
 * 执行验证结果：动作是否真正改变了页面。
 */
data class VerifyResult(
    val success: Boolean,
    val reason: String,
    /** 执行前指纹 */
    val beforeFingerprint: String,
    /** 执行后指纹 */
    val afterFingerprint: String,
    /**
     * 执行成功、但需要回注给 AI 的注意项（目前唯一来源：点击落点命中的控件与目标不一致）。
     * 为空表示没有要额外提醒的。刻意与 [reason] 分开：[reason] 是给人看的成败说明，
     * note 是"这步算成了，但下一步要留意"——混在一起会让成功文案带上一串解释。
     */
    val note: String = "",
    /**
     * 动作是否**根本没交付到设备**（无障碍服务不可用 / 手势被系统拒绝 / 启动指令未被接受）。
     * 只有"未交付"才允许换执行通道重试；"已交付但未确认"再走一遍就是重复副作用
     * （对发送/提交/删除这类按钮尤其致命）。
     */
    val notDelivered: Boolean = false,
    /**
     * 端侧判定"重跑同一串动作不会变好"：未知命令、参数非法、通道能力缺口、转译失败等。
     *
     * 由产出方在构造结果时显式标注，**不再**从 [reason] 里匹配关键词——
     * 文案是给人读的，重试行为不该随措辞变化。
     */
    val structural: Boolean = false,
    /**
     * 实际交付动作的通道 id（`a11y` / `shizuku` / `adb` / `termux` / `local`）。
     * 空串表示该动作没有经过通道选择（纯端侧动作或首通道直接成功且未走统一阶梯）。
     * 仅用于日志、遥测与排障，**不进提示词**——执行通道对 AI 保持透明是本项目的既有契约。
     */
    val channel: String = "",
    /** 发生过降级时，首次未交付的通道 id；未降级为空串 */
    val fallbackFrom: String = "",
    /** 通道尝试轨迹（中文，逐条列出"哪条通道、结果、原因"），进日志不进提示词 */
    val trace: String = "",
    /** 因某项设备能力缺失而失败时的稳定编号（见 `DeviceCapability.code`），便于统一回注原因 */
    val capabilityCode: String = "",
    /**
     * 本步是否向**密码框**输入了内容。为 true 时执行层会把动作里的正文换成占位符再留档/回注：
     * 密码只用于这一次执行，不落本地轨迹、不进下一轮提示词。
     */
    /** 本次动作从下发到得出验证结论的墙钟耗时（ms）。原先恒为 0，执行流里看不出哪一步慢 */
    val elapsedMs: Long = 0,
    val sensitiveInput: Boolean = false,
    /**
     * 动作已下发，但**没能读到页面**，变化既没被确认也没被否定。
     *
     * 与 [success] 互斥（为 true 时 [success] 必为 false）：它不是"动作没成功"，
     * 而是"这件事我们没资格下结论"。单独立一个字段的理由见 [Delivery.DELIVERED_UNCONFIRMED]：
     * 过去这个状态只写在 [reason] 的文案里，结构化字段里全被折叠成了成功。
     */
    val unconfirmed: Boolean = false,
) {
    /**
     * 结构化交付结论（由 [success] / [unconfirmed] / [notDelivered] 推导）：
     * 成功 / 已交付未确认 / 已交付未生效 / 未交付。重试与回注提示都按它分流，不读文案。
     */
    val delivery: Delivery
        get() = when {
            unconfirmed -> Delivery.DELIVERED_UNCONFIRMED
            success -> Delivery.EFFECTIVE
            notDelivered -> Delivery.NOT_DELIVERED
            else -> Delivery.DELIVERED_NO_EFFECT
        }
}

/**
 * 动作执行后的取样节奏：**唯一定义点**。
 *
 * 点击（[ClickRunner]）与滑动/滚动/输入（[VerifiedClickExecutor]）共用同一套取样时刻——
 * 两处各写一份的后果很实际：同一条任务里"响应快的页面"一半在短轮询下秒判成功、
 * 另一半还在固定等待里干等 600ms，判定标准与耗时都不一致。
 */
internal object VerifyTiming {
    /** 累计等待时刻（毫秒）：首次取样就能确认的页面不必等满，两次取样都没变化才判失败 */
    val SAMPLE_DELAYS_MS = longArrayOf(200L, 500L)

    /**
     * 判失败前的**延迟复核**等待（毫秒）。
     *
     * 网络/加载类页面常在首轮观察窗（约 0.7s）之后才定型。早判失败不只是"显示不准"：
     * AI 收到"未生效"会重放动作，点提交/收藏/加购这类操作就被执行了两遍，副作用不可撤销。
     * 只在**失败路径**多等这一拍：成功路径一旦取样到变化就立即返回，不受影响。
     */
    const val LATE_CONFIRM_DELAY_MS = 900L
}

/**
 * 控件的"关键状态"字符串：选中态 / 当前值 / 文字 / 无障碍描述。
 *
 * [ClickRunner] 与 [VerifiedClickExecutor] **共用这一份口径**：两处各写一份的后果很实际——
 * 同一个动作在点击链路上判"已生效"、在滑动/输入链路上却因指纹未变被判失败，标准不一致。
 */
internal fun controlStateOf(element: UiElement): String =
    "${element.isSelected}|${element.currentValue}|${element.text}|${element.contentDescription}"

/**
 * 带验证的执行器：执行动作并通过页面指纹对比确认是否生效。
 * 对应文档“第 4 层 带验证的点击执行器”。
 *
 * 执行前记录指纹 → 执行 → 短轮询取样 → 对比指纹。
 * 指纹变化 → 成功（一旦发现变化立即返回，不必等满整个观察窗）；不变 → 失败。
 *
 * 读不到页面时按"已执行但未确认"算成功：此时报失败会误触发"连续失败请求用户介入"，
 * 而下一步的观察本来就会给出真实结果。与 [ClickRunner] 同一口径。
 */
class VerifiedClickExecutor(
    private val resolver: IntentResolver = IntentResolver(),
    /**
     * 取一份当前快照；读不到（无障碍断开、Provider 取树超时）返回 null。
     *
     * 抽成参数是为了能单测"读不到页面"这条分支：走 `AgentAccessibilityService.instance`
     * 这个全局静态时，这条分支在 JVM 单测里根本进不去，于是它错了也无人发现。
     */
    private val capture: suspend () -> ScreenSnapshot? = {
        AgentAccessibilityService.instance?.captureScreenAsync()
    },
) {

    /**
     * 执行动作并验证页面是否发生变化。
     *
     * @param target 本次动作的目标控件；指纹未变时用它兜底判定（见下）。为 null 表示无特定目标（如纯手势滑动）。
     */
    suspend fun executeAndVerify(
        snapshot: ScreenSnapshot,
        target: UiElement? = null,
        perform: suspend () -> Boolean,
    ): VerifyResult {
        val before = PageFingerprint.computeMeaningful(snapshot)
        val executed = perform()
        // 执行调用本身返回失败 = 动作没交付出去，允许上层换通道重试
        if (!executed) return VerifyResult(false, "动作执行返回失败", before, before, notDelivered = true)

        var after = before
        var captured = false
        for (delayMs in VerifyTiming.SAMPLE_DELAYS_MS) {
            delay(delayMs)
            // 对齐 ClickRunner.captureQuietly：过滤掉无障碍断开时的缺失态快照，
            // 避免拿"空壳快照"比对指纹误判成"页面未变化"
            val afterSnapshot = runCatching { capture() }
                .getOrNull()?.takeUnless { it.missingAccessibility } ?: break
            captured = true
            after = PageFingerprint.computeMeaningful(afterSnapshot)
            if (before != after) return VerifyResult(true, "页面已变化", before, after)
            // 指纹未变时的兜底：目标控件自身关键状态变化也算已生效。
            // 勾选/滑块/纯动画页不会改变整页指纹，只看指纹会把成功误判成失败——
            // 这里与 ClickRunner.judge 的控件态口径保持一致（共用 controlStateOf）。
            if (target != null && targetStateChanged(target, afterSnapshot)) {
                return VerifyResult(true, "目标控件状态已变化", before, after)
            }
        }
        if (!captured) {
            // 与 ClickRunner 同一口径：读不到页面只算"未确认"，既不是成功也不是失败
            return VerifyResult(
                false,
                "已执行；此刻读不到页面，变化未能确认，以下一步观察为准",
                "",
                "",
                unconfirmed = true,
            )
        }
        // 判失败前再复核一次：网络/加载类页面常在首轮观察窗之后才定型，
        // 早判"未生效"会让 AI 重放动作（重复提交/重复滑动），副作用不可撤销
        delay(VerifyTiming.LATE_CONFIRM_DELAY_MS)
        val lateSnapshot = runCatching { capture() }
            .getOrNull()?.takeUnless { it.missingAccessibility }
        if (lateSnapshot != null) {
            val late = PageFingerprint.computeMeaningful(lateSnapshot)
            after = late
            if (before != late) return VerifyResult(true, "页面已变化（延迟生效）", before, late)
            if (target != null && targetStateChanged(target, lateSnapshot)) {
                return VerifyResult(true, "目标控件状态已变化（延迟生效）", before, late)
            }
        }
        return VerifyResult(false, "页面未变化，动作可能未生效", before, after)
    }

    /** 目标控件自身的关键状态（选中/值/文字/描述）在新快照里是否已经变化 */
    private fun targetStateChanged(target: UiElement, snapshot: ScreenSnapshot): Boolean {
        val now = resolver.matchInFresh(target, snapshot) ?: return false
        return controlStateOf(target) != controlStateOf(now)
    }
}