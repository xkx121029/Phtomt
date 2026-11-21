package com.phoneagent.feature.entry

/**
 * 入口可信性判据：这次从系统入口进来的请求，能不能证明是本应用自己发起的。
 *
 * 为什么不能用「Intent 形状」当判据：自定义 scheme（`hpa://`）谁都能构造，
 * `TASK_TEXT` / `RESUME` 这类 extras 更是任何应用一行 `startActivity` 就能塞进来。
 * 按形状判等于给攻击者留一条指定通道——只要挑没被确认门覆盖的那种形状，
 * 就能替用户下任务，而任务一旦开跑，后面的动作全都算「用户自己的任务」，
 * 今天所有确认门就都白装了。所以判据只能是「来源可证」：
 * 证明不了来自本应用的一律按外部处理（fail-closed），走同一个确认入口。
 *
 * Android 能给的来源证据只有一条：调用方 uid。`Activity.getLaunchedFromUid()`
 * 只在「调用方主动共享身份」或「调用方与本应用同 uid」时返回真实 uid，
 * 其余（第三方应用、launcher 快捷方式、adb、被其他应用转发的 Intent）一律是
 * `INVALID_UID`。也就是说这条信号天然只有两档：同 uid（可证是自己）/ 未知（按外部）。
 * API 34 以下根本没有这条信号，同样按外部处理。
 */
object EntryTrust {

    /** `android.os.Process.INVALID_UID`：调用方身份取不到时的取值 */
    const val INVALID_UID = -1

    /**
     * 来源是否可证来自本应用。
     *
     * @param sdkInt `Build.VERSION.SDK_INT`；低于 34 没有任何调用方身份，恒判外部
     * @param callerUid `Activity.getLaunchedFromUid()`，取不到时传 [INVALID_UID]
     * @param selfUid 本进程 uid（`Process.myUid()`）
     */
    fun isProvenInternal(sdkInt: Int, callerUid: Int, selfUid: Int): Boolean =
        sdkInt >= 34 && callerUid != INVALID_UID && callerUid == selfUid

    /**
     * 该入口是否必须先过用户确认（复用深链那套确认卡）。
     *
     * 外部来源里只有「真的会开始做事」的目标才要确认：
     * - 任务文本（extras / 深链 / 分享文字）：会执行设备动作 → 确认；
     * - 续跑（`hpa://resume` / 快捷方式）：会恢复执行 → 确认；
     * - 只打开记忆页：纯导航，不改设备状态 → 不确认；
     * - 分享进来的文件：只是落进临时编码区，用户还要在编码页自己点发送 → 不确认。
     *
     * 本应用自己发起的入口免确认：那条路径上的身份是可证的（见 [isProvenInternal]），
     * 而可信来源再走一遍确认只会让用户对确认卡脱敏。
     */
    fun needsUserConfirmation(target: TaskEntry.Target, provenInternal: Boolean): Boolean = when {
        provenInternal -> false
        target is TaskEntry.Target.SharedFiles -> false
        target is TaskEntry.Target.Navigate && target.memory && !target.resume -> false
        else -> true
    }
}
