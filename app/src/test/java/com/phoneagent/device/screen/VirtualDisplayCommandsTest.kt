package com.phoneagent.device.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 副屏 shell 命令拼装的单测：AI 给的 app 参数会进 shell 通道，必须按 POSIX 单引号转义。
 *
 * 一个 `app=com.foo; reboot` 在未转义时就是「先启动 com.foo，再重启手机」，
 * 所以这里逐条钉住三个命令模板的转义，而不是只断言「调用了 quote」。
 */
class VirtualDisplayCommandsTest {

    @Test
    fun `resolve-activity 的包名被单引号包住`() {
        assertEquals(
            "cmd package resolve-activity --brief 'com.foo; reboot'",
            VirtualDisplayCommands.resolveComponent("com.foo; reboot"),
        )
    }

    @Test
    fun `am start 的组件名被单引号包住`() {
        assertEquals(
            "am start --display 7 -n 'com.foo; reboot'",
            VirtualDisplayCommands.startOnDisplay(7, "com.foo; reboot"),
        )
    }

    @Test
    fun `monkey 退路的包名被单引号包住`() {
        assertEquals(
            "monkey -p 'com.foo; reboot' --display 3 -c android.intent.category.LAUNCHER 1",
            VirtualDisplayCommands.monkeyLaunch(3, "com.foo; reboot"),
        )
    }

    @Test
    fun `包名里的单引号按 POSIX 写法转义`() {
        // 单引号是唯一能跳出单引号串的字符，必须写成 '\''
        assertEquals(
            "monkey -p 'com.foo'\\'' ; reboot #' --display 1 -c android.intent.category.LAUNCHER 1",
            VirtualDisplayCommands.monkeyLaunch(1, "com.foo' ; reboot #"),
        )
    }

    @Test
    fun `正常包名不引入额外空白`() {
        assertEquals(
            "am start --display 0 -n 'com.tencent.mm/.ui.LauncherUI'",
            VirtualDisplayCommands.startOnDisplay(0, "com.tencent.mm/.ui.LauncherUI"),
        )
    }
}
