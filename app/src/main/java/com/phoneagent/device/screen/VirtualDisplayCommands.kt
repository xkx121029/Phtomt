package com.phoneagent.device.screen

import com.phoneagent.device.ops.ShellQuote

/**
 * 副屏 shell 命令的拼装（纯函数，JVM 可测）。
 *
 * 这些命令里唯一的不可信来源是「要启动的应用」：AI 给的 `app` 参数既可能是应用名，
 * 也可能是包名或 `包名/Activity` 全名，[com.phoneagent.device.ops.DeviceOpsChannel]
 * 解析不到包名时就原样透传。拼进 shell 前必须按 POSIX 单引号转义（与 settingsOp 的
 * quote 同口径），否则一个 `app=com.foo; reboot` 就能在 shell 通道下执行任意命令。
 */
internal object VirtualDisplayCommands {

    /** `cmd package resolve-activity --brief` 解析入口 Activity */
    fun resolveComponent(target: String): String =
        "cmd package resolve-activity --brief ${ShellQuote.of(target)}"

    /** 在指定副屏上 `am start` 一个 Activity 全名 */
    fun startOnDisplay(displayId: Int, component: String): String =
        "am start --display $displayId -n ${ShellQuote.of(component)}"

    /** resolve 不到入口时的退路：monkey 同样支持指定 display */
    fun monkeyLaunch(displayId: Int, pkg: String): String =
        "monkey -p ${ShellQuote.of(pkg)} --display $displayId -c android.intent.category.LAUNCHER 1"
}
