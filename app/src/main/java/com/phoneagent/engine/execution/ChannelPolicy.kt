package com.phoneagent.engine.execution

import com.phoneagent.device.capability.DeviceCapability
import com.phoneagent.device.capability.DeviceCapabilitySnapshot
import com.phoneagent.domain.model.ActionType

/**
 * 执行通道的**唯一登记口径**。
 *
 * 以前"这个动作还有哪条路能达成"散在三处：转译层策略表、[ExecutionCore] 的启动阶梯、
 * shell 侧的 [ShellRules.pickChannel]。新增动作若忘了在某处补一条，就会出现"界面能用、任务跑不通"。
 * 这里按**目标（[ChannelGoal]）**而不是按动作名声明候选通道与降级顺序，新增动作只要归到一个目标即可，
 * 排序与健康度由本文件统一给出。
 *
 * 边界：本表只回答"按什么顺序试"，不执行动作（副作用仍在 [ExecutionCore]）。
 * Termux 是普通应用权限的 Linux 环境，**不能**执行 `input`/`am` 等系统命令，所以指针、按键、启动类
 * 目标的候选里刻意不含 Termux（宁可降级到本应用直启，也不把系统命令丢给必然失败的通道）。
 */
internal enum class ChannelKind(val id: String, val label: String) {
    /** 无障碍通道：读屏 + 手势 + 节点级操作 */
    ACCESSIBILITY("a11y", "无障碍"),

    /** 内置 Shizuku（shell / adb 权限） */
    SHELL_SHIZUKU("shizuku", "内置 Shizuku"),

    /** 无线调试引导会话（Shizuku server 拉起前后都能执行 shell） */
    SHELL_ADB("adb", "无线调试会话"),

    /** Termux 工具链（普通应用权限，只能跑 curl/python 这类命令） */
    SHELL_TERMUX("termux", "Termux"),

    /** 本应用直启目标 Activity（不依赖任何设备通道，仅适用于启动/打开） */
    LOCAL_ACTIVITY("local", "本应用直启"),
}

/** 达成目标的能力族（不是具体命令，用于通道排序与健康度统计） */
internal enum class ChannelGoal(val label: String) {
    /** 启动应用 */
    LAUNCH("启动应用"),

    /** 深链/协议直达页面 */
    OPEN("打开页面"),

    /** 单点点击 */
    POINTER("点击"),

    /** 长按 */
    LONG_PRESS("长按"),

    /** 滑动/滚动 */
    GESTURE("滑动"),

    /** 文本输入 */
    TEXT("输入文本"),

    /** 系统按键 */
    KEY("系统按键"),

    /** 像素截图 */
    SCREENSHOT("截图"),

    /** 观察屏幕（等待文字/应用出现等只读轮询） */
    OBSERVE("观察屏幕"),

    /** 自由模式 shell 命令 */
    SHELL("命令行");

    companion object {
        /** 动作类型 → 目标。未登记的动作（纯端侧动作、通知、MCP 等）返回 null，不走通道阶梯。 */
        fun of(actionType: String): ChannelGoal? = when (actionType) {
            ActionType.CLICK, ActionType.TAP -> POINTER
            ActionType.LONG_CLICK, ActionType.LONG_PRESS -> LONG_PRESS
            ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT, ActionType.SCROLL, ActionType.SCROLL_TO -> GESTURE
            ActionType.TYPE_TEXT -> TEXT
            ActionType.KEY, ActionType.BACK, ActionType.HOME, ActionType.RECENTS, ActionType.REFRESH -> KEY
            ActionType.LAUNCH -> LAUNCH
            ActionType.OPEN -> OPEN
            ActionType.WAIT_FOR -> OBSERVE
            ActionType.SHELL -> SHELL
            else -> null
        }
    }
}

/** 一次候选：为什么排在这（原因进日志与失败回注，不进提示词） */
internal data class ChannelCandidate(
    val kind: ChannelKind,
    val reason: String,
)

/**
 * 通道候选表。排序原则：
 * 1. **能力优先**：无障碍在线时优先无障碍（节点级校验最精确），不可用时按 shell → 本地降级；
 * 2. **健康度只做降权**：连续失败的通道在存在其它候选时后移（见 [ChannelHealth]），不丢候选；
 * 3. **不编造候选**：调用方给不出事实（例如解析不到本地 Activity）时不要塞进必然失败的通道。
 */
internal object ChannelPolicy {

    /**
     * 按目标生成**有序**候选通道。
     *
     * @param snapshot 当前能力快照（同一目标的所有候选基于同一份事实）
     * @param preference 用户执行通道偏好（只影响 [ChannelGoal.SHELL]，其余目标由能力决定）
     * @param termuxToolCommand 命令是否属于 Termux 工具链（curl/python 等）
     * @param includeLocalLaunch 调用方是否真的能解析出本地 Activity
     */
    @Suppress("LongParameterList")
    fun candidates(
        goal: ChannelGoal,
        snapshot: DeviceCapabilitySnapshot,
        preference: String = "AUTO",
        termuxToolCommand: Boolean = false,
        includeLocalLaunch: Boolean = false,
    ): List<ChannelCandidate> {
        val canA11y = snapshot.isUsable(DeviceCapability.ACCESSIBILITY)
        val systemShell = systemShellCandidates(snapshot)
        return when (goal) {
            ChannelGoal.SCREENSHOT, ChannelGoal.OBSERVE ->
                if (canA11y) listOf(ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍在线")) else emptyList()

            ChannelGoal.SHELL -> shellCandidates(preference, snapshot, termuxToolCommand, systemShell)

            ChannelGoal.LAUNCH, ChannelGoal.OPEN -> buildList {
                if (canA11y) add(ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍在线"))
                addAll(systemShell)
                if (includeLocalLaunch) add(ChannelCandidate(ChannelKind.LOCAL_ACTIVITY, "本应用直接拉起"))
            }

            ChannelGoal.POINTER, ChannelGoal.LONG_PRESS, ChannelGoal.GESTURE, ChannelGoal.KEY, ChannelGoal.TEXT ->
                buildList {
                    if (canA11y) add(ChannelCandidate(ChannelKind.ACCESSIBILITY, "无障碍在线"))
                    addAll(systemShell)
                }
        }
    }

    /** 系统命令可用的 shell 通道（Termux 不在此列：跑不了 am/input/settings） */
    private fun systemShellCandidates(snapshot: DeviceCapabilitySnapshot): List<ChannelCandidate> = buildList {
        if (snapshot.isUsable(DeviceCapability.SHELL_SHIZUKU)) {
            add(ChannelCandidate(ChannelKind.SHELL_SHIZUKU, "内置 Shizuku 已就绪"))
        }
        if (snapshot.isUsable(DeviceCapability.SHELL_ADB)) {
            add(ChannelCandidate(ChannelKind.SHELL_ADB, "无线调试会话已连接"))
        }
    }

    /** shell 目标的候选（尊重显式偏好与 Termux 工具链分类，语义与 [ShellRules.pickChannel] 一致） */
    private fun shellCandidates(
        preference: String,
        snapshot: DeviceCapabilitySnapshot,
        termuxToolCommand: Boolean,
        systemShell: List<ChannelCandidate>,
    ): List<ChannelCandidate> {
        val shizuku = snapshot.isUsable(DeviceCapability.SHELL_SHIZUKU)
        val termux = snapshot.isUsable(DeviceCapability.SHELL_TERMUX)

        // Termux 工具链命令：Termux 优先（adb shell 里没有 curl/python/jq）
        if (termuxToolCommand && termux) {
            return listOf(ChannelCandidate(ChannelKind.SHELL_TERMUX, "Termux 工具链命令"))
        }
        return when (preference) {
            ShellRules.CHANNEL_SHIZUKU ->
                if (shizuku) listOf(ChannelCandidate(ChannelKind.SHELL_SHIZUKU, "用户指定内置 Shizuku")) else emptyList()

            ShellRules.CHANNEL_TERMUX ->
                if (termux) listOf(ChannelCandidate(ChannelKind.SHELL_TERMUX, "用户指定 Termux")) else emptyList()

            else -> buildList {
                addAll(systemShell)
                if (termux) add(ChannelCandidate(ChannelKind.SHELL_TERMUX, "Termux 兜底"))
            }
        }
    }
}

/**
 * 通道健康度（同一目标的通道连续失败后**降权而非禁用**）。
 *
 * 解决的现实问题：某台机器上无障碍手势"看起来能用"，实际点击不生效——以前会一路重试同一条路，
 * 每步都白等一个超时。这里记录每（目标，通道）的连续失败次数，排序时把连续失败的候选后移，
 * 让执行层优先尝试另一条路；只要成功一次就清零。
 *
 * 刻意不做的事：
 * - 不做时间窗口冷却（跨步的时钟窗口在长任务里容易把"刚恢复的通道"也压下去）；
 * - 不设"彻底禁用"（可能是页面特定问题，禁用会让后续步骤全都失败）。
 *
 * 线程模型：引擎执行是单线程串行的（[ExecutionCore] 由主循环顺序调用），用普通 HashMap 即可，
 * 但读写仍加锁，避免将来并行执行时静默出错。
 */
internal class ChannelHealth(
    /** 连续失败到该次数即降权（仍会尝试，只是排到其它候选之后） */
    private val demoteAfter: Int = 2,
) {

    private val failures = HashMap<String, Int>()
    private val lock = Any()

    /** 连续失败次数 */
    fun consecutiveFailures(goal: ChannelGoal, kind: ChannelKind): Int =
        synchronized(lock) { failures[key(goal, kind)] ?: 0 }

    /** 是否应降权 */
    fun isDemoted(goal: ChannelGoal, kind: ChannelKind): Boolean =
        consecutiveFailures(goal, kind) >= demoteAfter

    /** 记录一次失败 */
    fun recordFailure(goal: ChannelGoal, kind: ChannelKind) {
        synchronized(lock) { failures[key(goal, kind)] = (failures[key(goal, kind)] ?: 0) + 1 }
    }

    /** 记录一次成功（清零该通道的连续失败） */
    fun recordSuccess(goal: ChannelGoal, kind: ChannelKind) {
        synchronized(lock) { failures.remove(key(goal, kind)) }
    }

    /** 通道状态发生了实质变化（重连/断开）时清空统计，避免陈旧失败记录拖着新会话后腿 */
    fun reset() {
        synchronized(lock) { failures.clear() }
    }

    /**
     * 按健康度**稳定**重排候选：被降权的候选整体后移，同组内保持原顺序。
     * 用稳定分区而非排序，保证在健康度相同的情况下顺序完全可预测（便于单测与排查）。
     */
    fun rank(goal: ChannelGoal, candidates: List<ChannelCandidate>): List<ChannelCandidate> {
        if (candidates.size <= 1) return candidates
        val (demoted, healthy) = candidates.partition { isDemoted(goal, it.kind) }
        return healthy + demoted
    }

    private fun key(goal: ChannelGoal, kind: ChannelKind): String = "${goal.name}:${kind.name}"
}
