package com.phoneagent.engine.execution

import android.app.AlarmManager
import android.content.Context
import com.phoneagent.device.clock.ClockRequest
import com.phoneagent.device.clock.ClockRequests
import com.phoneagent.device.ops.DeviceOpsChannel
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.ScreenSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 系统级工具的端到端链路测试：**可脚本化的假模型 → 转译 → 执行 → 回读 → 结果回灌**。
 *
 * 链路各段的真假分工：
 * - 假：模型决策。这里用固定脚本回放 tool_call（[AgentIntent]），等价于 `cloudDecide` 的正常返回；
 * - 真：[IntentTranslator]（参数校验/中文报错）、[DeviceOpsChannel]（写后回读）、
 *   [ClockFallbackRunner]（派发/去重/多源回读/降级）；
 * - 假：设备。`shell` 与 [ClockIntentDispatcher] 落在一份可变状态 [FakeDevice] 上，
 *   断言的是「这条状态真的变了」，而不是「函数返回了 Ok」。
 *
 * 为什么时钟投递要用假实现：`android.content.Intent` 在 JVM 单测里是空壳（构造后 action/extra
 * 都不保存，见 ProbeIntentTest 的结论），真投递只能在设备上验；设备侧投递与回读已用
 * `am start` + `dumpsys alarm` 实测过。这里验证的是意图参数、去重、回读判定与文案。
 */
class DeviceToolEndToEndTest {

    // ------------------------------------------------------------------ 设闹钟 / 计时器

    @Test
    fun `一句话设闹钟_到系统状态真的变了`() = runTest {
        val h = Harness()

        val out = h.runTask(listOf(intent(IntentType.SET_ALARM, timeText = "07:30")))

        assertEquals(listOf("07:30"), h.fake.alarms)
        assertEquals(1, h.fake.alarmDispatches)
        assertEquals(0, h.fake.pageOpens)
        assertTrue("成功文案应带回读证据：${out.single()}", out.single().contains("已确认"))
    }

    @Test
    fun `一句话设计时器_直接进计时态不经过时钟首页`() = runTest {
        val h = Harness()

        val out = h.runTask(listOf(intent(IntentType.SET_TIMER, durationSeconds = 300)))

        assertEquals(1, h.fake.timerRegistrations)
        assertEquals(1, h.fake.timerDispatches)
        assertEquals("不应先打开时钟首页", 0, h.fake.pageOpens)
        assertTrue("成功文案应带回读证据：${out.single()}", out.single().contains("已确认"))
    }

    @Test
    fun `重复说两遍同一闹钟_只登记一条`() = runTest {
        val h = Harness()
        val command = intent(IntentType.SET_ALARM, timeText = "07:30")

        val out = h.runTask(listOf(command, command))

        assertEquals("只应派发一次", 1, h.fake.alarmDispatches)
        assertEquals("系统里只应有一条", listOf("07:30"), h.fake.alarms)
        assertTrue("第二次应提示已存在：${out[1]}", out[1].contains("没有重复"))
    }

    @Test
    fun `重复说两遍同一计时器_只登记一条`() = runTest {
        val h = Harness()
        val command = intent(IntentType.SET_TIMER, durationSeconds = 300)

        val out = h.runTask(listOf(command, command))

        assertEquals("只应派发一次", 1, h.fake.timerDispatches)
        assertEquals("系统里只应有一条", 1, h.fake.timerRegistrations)
        assertTrue("第二次应说明未重复创建：${out[1]}", out[1].contains("没有重复"))
    }

    // ------------------------------------------------------------------ 参数歧义 / 缺失 / 非法

    @Test
    fun `只说提醒我_给出可补全的中文提示且下一轮能成功`() = runTest {
        val h = Harness()
        val vague = AgentIntent(intent = IntentType.SET_ALARM, text = "提醒我")

        val out = h.runTask(listOf(vague, intent(IntentType.SET_ALARM, timeText = "07:30")))

        assertTrue("缺失提示应说明缺什么、怎么补：${out[0]}", out[0].contains("时间") || out[0].contains("when"))
        assertFalse("缺参数时不应派发", h.fake.alarms.isEmpty())
        assertEquals("按提示补全后应成功", listOf("07:30"), h.fake.alarms)
        assertTrue("补全后应带回读证据：${out[1]}", out[1].contains("已确认"))
    }

    @Test
    fun `只给半小时后_相对时间被正确换算成具体时间`() = runTest {
        val h = Harness()

        val out = h.runTask(listOf(intent(IntentType.SET_ALARM, timeText = "半小时后")))

        val registered = h.fake.alarms.single()
        val deltaMinutes = minutesFromNow(registered)
        assertTrue("应落在 30 分钟后（±2 分钟）：实际 $registered", deltaMinutes in 28..32)
        assertTrue("应带回读证据：${out.single()}", out.single().contains("已确认"))
    }

    @Test
    fun `给 25 点这种非法时间_中文报错且不派发`() = runTest {
        val h = Harness()

        val out = h.runTask(listOf(intent(IntentType.SET_ALARM, timeText = "25:00")))

        assertTrue(h.fake.alarms.isEmpty())
        assertEquals(0, h.fake.alarmDispatches)
        assertTrue("应指出时间无效：${out.single()}", out.single().contains("时间"))
    }

    @Test
    fun `亮度超出 0 到 100_转译层直接回中文范围提示`() = runTest {
        val h = Harness()

        val out = h.runTask(
            listOf(
                AgentIntent(intent = IntentType.DEVICE_CONTROL, op = "brightness", args = mapOf("level" to "200")),
                AgentIntent(intent = IntentType.DEVICE_CONTROL, op = "brightness", args = mapOf("level" to "50")),
            ),
        )

        assertTrue("应给出取值范围：${out[0]}", out[0].contains("0") && out[0].contains("100"))
        assertEquals("非法值不应改设备", 50.0, h.fake.brightnessPercent, 0.01)
        assertTrue("纠正后应回读一致：${out[1]}", out[1].contains("回读 50"))
    }

    // ------------------------------------------------------------------ 其余系统能力

    @Test
    fun `调亮度_回读一致才算成功`() = runTest {
        val h = Harness()

        val out = h.runTask(
            listOf(AgentIntent(intent = IntentType.DEVICE_CONTROL, op = "brightness", args = mapOf("level" to "50"))),
        )

        assertEquals(50.0, h.fake.brightnessPercent, 0.01)
        assertTrue("应带实测回读值：${out.single()}", out.single().contains("回读 50"))
    }

    @Test
    fun `开勿扰_以 zen_mode 回读为准`() = runTest {
        val h = Harness()

        val out = h.runTask(
            listOf(
                AgentIntent(
                    intent = IntentType.DEVICE_CONTROL,
                    op = "dnd",
                    args = mapOf("enable" to "true"),
                ),
            ),
        )

        assertEquals(1, h.fake.zenMode)
        assertTrue("应带 zen_mode 回读：${out.single()}", out.single().contains("zen_mode=1"))
    }

    @Test
    fun `开深色模式_以 uimode night 回读为准`() = runTest {
        val h = Harness()

        val out = h.runTask(
            listOf(
                AgentIntent(
                    intent = IntentType.DEVICE_CONTROL,
                    op = "dark_mode",
                    args = mapOf("enable" to "true"),
                ),
            ),
        )

        assertEquals(1, h.fake.nightMode)
        assertTrue("应带 uimode 回读：${out.single()}", out.single().contains("uimode"))
    }

    @Test
    fun `没有注入通道时_开关类说明缺口并给替代做法`() = runTest {
        val h = Harness(shellAvailable = false)

        val out = h.runTask(
            listOf(
                AgentIntent(
                    intent = IntentType.DEVICE_CONTROL,
                    op = "wifi",
                    args = mapOf("enable" to "false"),
                ),
            ),
        )

        assertTrue("不能报成功：${out.single()}", out.single().contains("注入通道") || out.single().contains("无法确认"))
        assertTrue("必须给出下一步：${out.single()}", out.single().contains("设置") || out.single().contains("控制中心"))
    }

    @Test
    fun `NFC 读不到状态时_报无法确认而不是成功`() = runTest {
        val h = Harness()

        val out = h.runTask(
            listOf(
                AgentIntent(
                    intent = IntentType.DEVICE_CONTROL,
                    op = "nfc",
                    args = mapOf("enable" to "true"),
                ),
            ),
        )

        assertTrue("不能报成功：${out.single()}", out.single().contains("无法确认"))
        assertTrue("必须给出下一步：${out.single()}", out.single().contains("控制中心"))
    }

    // ------------------------------------------------------------------ 工具方法

    private fun intent(
        type: String,
        timeText: String? = null,
        durationSeconds: Long? = null,
    ) = AgentIntent(intent = type, timeText = timeText, durationSeconds = durationSeconds)

    /** 把 "HH:mm" 换算成「距离现在多少分钟」（跨天按 +24h 计） */
    private fun minutesFromNow(hhmm: String): Int {
        val (hour, minute) = hhmm.split(":").map { it.toInt() }
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var delta = ((target.timeInMillis - System.currentTimeMillis()) / 60_000L).toInt()
        if (delta < -120) delta += 24 * 60
        return delta
    }

    /**
     * 假设备：一份可变状态 + 把「命令/意图」翻译成状态变化。
     * 断言看的是这份状态，而不是返回值，才能证明「真的设置成功」。
     */
    private class FakeDevice {
        var brightnessPercent = 12.0
        var zenMode = 0
        var wifiOn = 1
        var nightMode = 0
        var nextAlarmAt: Long? = null
        val alarms = mutableListOf<String>()
        var alarmDispatches = 0
        var timerDispatches = 0
        var timerRegistrations = 0
        var pageOpens = 0

        /** 注入通道：模拟 Shizuku 能跑的系统命令 */
        fun exec(command: String): String = when {
            command.startsWith("cmd display set-brightness") -> {
                Regex("set-brightness (\\d+)").find(command)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?.let { brightnessPercent = it }
                ""
            }
            command.startsWith("cmd display get-brightness") -> brightnessPercent.toString()
            command.startsWith("cmd notification set_dnd") -> {
                zenMode = if (command.contains("off")) 0 else 1
                ""
            }
            command == "settings get global zen_mode" -> zenMode.toString()
            command == "settings get global wifi_on" -> wifiOn.toString()
            command.startsWith("svc wifi") -> {
                wifiOn = if (command.trim().endsWith("enable")) 1 else 0
                ""
            }
            command == "cmd uimode night" -> "Night mode: " + if (nightMode == 1) "yes" else "no"
            command.startsWith("cmd uimode night ") -> {
                nightMode = if (command.contains("yes")) 1 else 0
                ""
            }
            command == "dumpsys alarm" ->
                buildString { repeat(timerRegistrations) { appendLine("RTC_WAKEUP com.android.deskclock TIMER_EXPIRED") } }
            command.startsWith("content query") -> "No result."
            else -> ""
        }

        /** 假时钟 App：收到意图就真的登记（对应 DeskClock 收到 SET_ALARM 后写 AlarmManager 的行为） */
        val dispatcher = object : ClockIntentDispatcher {
            override fun dispatch(request: ClockRequest, skipUi: Boolean): String? {
                when (request.action) {
                    android.provider.AlarmClock.ACTION_SET_ALARM -> {
                        alarmDispatches++
                        val hour = request.hour ?: 0
                        val minute = request.minute ?: 0
                        alarms += "%02d:%02d".format(hour, minute)
                        nextAlarmAt = triggerAt(hour, minute)
                    }
                    android.provider.AlarmClock.ACTION_SET_TIMER -> {
                        timerDispatches++
                        timerRegistrations++
                    }
                }
                return null
            }

            override fun openPage(request: ClockRequest): Boolean {
                pageOpens++
                return true
            }
        }

        private fun triggerAt(hour: Int, minute: Int): Long =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
    }

    /**
     * 最小可用的「跑一次任务」骨架：脚本当决策，其余全真。
     *
     * 每一步的结果都会进入 [messages]，与 `ExecutionPhase` 把工具结果写回消息列表的行为一致，
     * 因此「模型看到报错后改口」这件事是可以被测出来的（见带两轮脚本的用例）。
     */
    private class Harness(shellAvailable: Boolean = true) {
        val fake = FakeDevice()

        private val context: Context = mockk(relaxed = true)
        private val alarmManager: AlarmManager = mockk(relaxed = true)

        /** null = 没有注入通道（模拟没装 Shizuku / 无线 ADB 未连接） */
        private val shellText: suspend (String) -> String? =
            if (shellAvailable) {
                { command -> fake.exec(command) }
            } else {
                { null }
            }

        init {
            every { context.getSystemService(AlarmManager::class.java) } returns alarmManager
            every { alarmManager.nextAlarmClock } answers {
                fake.nextAlarmAt?.let { at ->
                    mockk<AlarmManager.AlarmClockInfo> { every { triggerTime } returns at }
                }
            }
        }

        private val ops = DeviceOpsChannel(
            context = context,
            shell = { command -> shellText(command) },
            channelAdvice = { "测试通道不可用" },
        )

        private val clock = ClockFallbackRunner(
            context = context,
            executor = null,
            shell = { command -> ShizukuManager.ShellResult.Success(shellText(command).orEmpty()) },
            caps = ClockCapabilities(accessibility = false, shell = true),
            dispatcher = fake.dispatcher,
        )

        private val translator = IntentTranslator(
            capabilityManager = CapabilityManager(context, shizukuManager = null, adbConnectedProvider = { true }),
            appNameResolver = AppNameResolver(context) { emptyList() },
            intentResolver = IntentResolver(),
        )

        val messages = mutableListOf<String>()

        suspend fun runTask(script: List<AgentIntent>): List<String> {
            for (decision in script) {
                val result = when (val t = translator.translate(decision, ScreenSnapshot())) {
                    is IntentTranslator.TranslationResult.MissingParam -> t.reason
                    is IntentTranslator.TranslationResult.Failed -> t.reason
                    is IntentTranslator.TranslationResult.Command -> execute(t.action)
                }
                messages += result
            }
            return messages.toList()
        }

        private suspend fun execute(action: AgentAction): String = when (action.type) {
            ActionType.SET_ALARM -> clock.run(
                ClockRequests.alarm(
                    hour = action.hour ?: 0,
                    minute = action.minute ?: 0,
                    repeatDays = null,
                    label = action.text,
                ),
            ).message()

            ActionType.SET_TIMER -> clock.run(
                ClockRequests.timer(seconds = (action.durationMs ?: 0L) / 1000, label = action.text),
            ).message()

            ActionType.DEVICE_OPS -> when (val r = ops.run(action.endpoint.orEmpty(), action.op, action.args.orEmpty())) {
                is DeviceOpsChannel.OpsResult.Ok -> r.text
                is DeviceOpsChannel.OpsResult.Fail -> r.reason
            }

            else -> "未覆盖的动作：${action.type}"
        }

        private fun ClockOutcome.message(): String = when (this) {
            is ClockOutcome.Done -> message
            is ClockOutcome.Manual -> message
            is ClockOutcome.Failed -> reason
        }
    }
}
