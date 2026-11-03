package com.phoneagent.engine.runtime

/**
 * 主循环护栏（重构阶段 A）：把散落在 runInner 里、靠局部变量或 EngineStore 字段手工维护的
 * "跨步状态机"收口为一个不依赖 Android 的可单测组件。
 *
 * 阶段 A 的目的不是改行为，而是给阶段 B（把主循环外迁到 TaskRunner）留一张安全网：
 * 这些护栏的阈值/升级时机一旦被搬错，任务就会从"卡住后自己纠正"退化成"无限打转"或"过早收尾"，
 * 而这几种情况在真机上极难复现。护栏行为落成纯函数后，迁移动刀前先有回归测试兜底。
 *
 * 只持有状态、不做日志与 UI（日志文案与推送仍留在引擎侧），时钟可注入便于测看门狗。
 */
internal class LoopGuard(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal companion object {
        /** 同一广告关闭目标连续自动点击达到该次数即放弃自动点击（点了广告还在，说明点了没用） */
        internal const val AD_CLICK_GIVE_UP_STREAK = 2

        /** 同签名动作连续出现该次数：注入"换一种做法"的纠偏提示 */
        internal const val REPEAT_HINT_STREAK = 4

        /** 同签名动作连续出现该次数：提示已被无视，升级为请求用户指导 */
        internal const val REPEAT_ESCALATE_STREAK = 6

        /** 连续端侧决策达到该次数：判定疑似死循环，本步强制走一次云端复核 + 提醒一次 */
        internal const val LOCAL_DECISION_REVIEW_STREAK = 5
    }

    /** 同签名动作的纠偏升级级别 */
    internal enum class RepeatEscalation {
        /** 未到阈值，不干预 */
        NONE,

        /** 第 4 次：注入换方案提示 */
        SWITCH_APPROACH,

        /** 第 6 次：升级为请求用户指导 */
        ASK_USER,
    }

    // ---- 广告自动点击护栏 ----
    private var lastAdClickTargetKey: String? = null
    private var adClickStreak = 0

    // ---- 同签名动作熔断 ----
    private var lastVerifiedActionSig: String? = null
    private var sameSigStreak = 0

    // ---- 长时间无进展看门狗 ----
    private var lastProgressAt = 0L
    private var noProgressNotified = false

    // ---- 端侧决策节流（重构阶段 B2：从 EngineStore 字段迁入） ----
    private var localDecisionStreak = 0
    private var localLoopNotified = false

    /** 同一广告目标已被自动点击的连续次数（供日志展示） */
    internal val adClickStreakValue: Int get() = adClickStreak

    /** 同签名动作已连续出现的次数（供日志/提示文案引用） */
    internal val sameSignatureStreak: Int get() = sameSigStreak

    /** 当前连续端侧决策次数（供日志/提示文案引用） */
    internal val localDecisionStreakValue: Int get() = localDecisionStreak

    /** 任务开始 / 重新计时：刷新进展时间并允许下一次无进展提醒 */
    internal fun start() {
        lastProgressAt = clock()
        noProgressNotified = false
    }

    /** 任务级全量复位：上一个任务的护栏状态绝不能带进下一个任务 */
    internal fun reset() {
        lastAdClickTargetKey = null
        adClickStreak = 0
        lastVerifiedActionSig = null
        sameSigStreak = 0
        localDecisionStreak = 0
        localLoopNotified = false
        start()
    }

    /**
     * 连续端侧决策是否需要本步强制云端复核（在决策前调用，用的是上一步累计的 streak）。
     * 云端复核一轮后 [onLocalDecision] 会因 fromLocal=false 清零，端侧节流自然恢复。
     */
    internal fun shouldForceCloudReview(): Boolean =
        localDecisionStreak >= LOCAL_DECISION_REVIEW_STREAK

    /**
     * 记录一次决策来源。
     *
     * @param fromLocal true = 本步由端侧决策（累计 streak）；false = 云端决策（清零 streak）
     * @return true = 本次刚好达到阈值且尚未提醒过，应提醒用户一次（每任务至多一次）
     */
    internal fun onLocalDecision(fromLocal: Boolean): Boolean {
        if (!fromLocal) {
            localDecisionStreak = 0
            return false
        }
        localDecisionStreak++
        if (localDecisionStreak >= LOCAL_DECISION_REVIEW_STREAK && !localLoopNotified) {
            localLoopNotified = true
            return true
        }
        return false
    }

    /**
     * 广告自动点击护栏：同一目标第一次命中允许自动关闭，连续第二次说明点了没效果，
     * 应放弃自动点击并把真实页面交还 AI 决策。
     *
     * @return true = 允许自动点击；false = 连续命中同一目标，停止自动点击
     */
    internal fun shouldAutoClickAd(targetKey: String): Boolean {
        if (targetKey == lastAdClickTargetKey) adClickStreak++ else {
            lastAdClickTargetKey = targetKey
            adClickStreak = 1
        }
        return adClickStreak < AD_CLICK_GIVE_UP_STREAK
    }

    /**
     * 同签名动作熔断：动作"执行成功"但页面纹丝不动时，AI 会在同一位置反复点同一个控件。
     * 相同签名连续 4 次注入纠偏提示，6 次升级为协助请求。
     *
     * @param signature 本步动作签名；null 表示"本步生效但不参与计数"（只复位，如 wait/say）
     */
    internal fun onVerifiedAction(signature: String?): RepeatEscalation {
        if (signature == null) {
            lastVerifiedActionSig = null
            sameSigStreak = 0
            return RepeatEscalation.NONE
        }
        if (signature == lastVerifiedActionSig) sameSigStreak++ else {
            lastVerifiedActionSig = signature
            sameSigStreak = 1
        }
        return when (sameSigStreak) {
            REPEAT_HINT_STREAK -> RepeatEscalation.SWITCH_APPROACH
            REPEAT_ESCALATE_STREAK -> RepeatEscalation.ASK_USER
            else -> RepeatEscalation.NONE
        }
    }

    /** 一步生效：刷新进展时间，允许下一次无进展提醒 */
    internal fun onProgress() = start()

    /**
     * 长时间无进展是否需要提醒：每任务至多提醒一次；一个已完成步骤都没有时不提醒
     * （任务刚起步还没到"卡住"的程度，提醒只会打扰用户）。
     */
    internal fun shouldNotifyNoProgress(completedSteps: Int): Boolean {
        if (completedSteps <= 0 || noProgressNotified) return false
        if (clock() - lastProgressAt <= EngineLimits.NO_PROGRESS_MS) return false
        noProgressNotified = true
        return true
    }
}
