package com.phoneagent.engine.execution

import android.content.Context
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import com.phoneagent.engine.context.ContextInjector
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.engine.runtime.UiBridge
import com.phoneagent.provider.ActionResult
import com.phoneagent.provider.IDeviceProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 启动阶梯的**逐通道接线**（`ExecutionCore.runLaunchLadder`）。
 *
 * 为什么要单独测接线：`ChannelPolicyTest` 证明「候选顺序与停止条件对」，`LaunchLadderPolicyTest`
 * 证明「三态怎么被消费」，`LaunchWatchTest` 证明「三态怎么产出」——但「无障碍失败之后到底有没有
 * 真的去试 shell」「shell 失败之后有没有真的去试本应用直启」「三级都失败时报的是不是失败」
 * 这段接线在真机上才是用户能看见的部分。它藏在私有函数里，只能靠把「无障碍那一步成功还是失败」
 * 换掉才跑得起来，所以 `ProviderManager` 加了注入点（[ProviderManager.transport]，默认就是真实路径）。
 *
 * 每一级的假回执独立可配：无障碍走 AIDL 回执，shell 走 `ShizukuManager.executeShell`，
 * 本应用直启走 `startActivity`；页面回读按「哪一级已经发过指令」返回不同内容。
 */
class ExecutionCoreLaunchLadderTest {

    private lateinit var appContext: Context
    private lateinit var service: AgentAccessibilityService
    private lateinit var shizuku: ShizukuManager
    private lateinit var provider: IDeviceProvider

    /** 阶梯走到哪一级了：页面回读的结果由它决定 */
    private var shellLaunched = false
    private var localLaunched = false

    private fun page(packageName: String) = ScreenSnapshot(
        packageName = packageName,
        screenWidth = 1080,
        screenHeight = 2400,
        elements = listOf(
            UiElement(
                index = 0,
                className = "android.widget.TextView",
                type = "Text",
                x = 100,
                y = 100,
                left = 0,
                top = 50,
                right = 200,
                bottom = 150,
                text = packageName,
            ),
        ),
    )

    private val home = page("com.test.launcher")
    private val target = page("com.demo")

    @Before
    fun setUp() {
        appContext = mockk(relaxed = true)
        service = mockk(relaxed = true)
        shizuku = mockk(relaxed = true)
        provider = mockk(relaxed = true)

        attachService(service)
        // 假通道：把 AIDL 回执直接交给调用方的 block，等价于「Provider 已绑定且这次调用成功返回」
        ProviderManager.transport = object : ProviderManager.Transport {
            override suspend fun <T> call(
                context: Context,
                timeoutMs: Long,
                block: suspend (IDeviceProvider) -> T,
            ): T = block(provider)
        }
        every { shizuku.isAvailable() } returns true
    }

    @After
    fun tearDown() {
        ProviderManager.transport = ProviderManager.RealProviderTransport
        attachService(null)
    }

    /**
     * `AgentAccessibilityService.instance` 的 setter 是 private（只有 Provider 观察协程会写它）。
     *
     * 这里用反射注入，是为了**不新增第二个产品接缝**：ProviderManager 的注入点已经足够决定
     * 「无障碍这一步成功还是失败」，页面回读则直接喂给服务实例。字段名一旦改名，
     * 这里会立刻抛 `NoSuchFieldException`，不会静默变成「instance 一直是 null 所以测试假绿」。
     */
    private fun attachService(value: AgentAccessibilityService?) {
        // 伴生对象里的 `@Volatile var` 在字节码里是外层类的静态字段（javap 确认：
        // `private static volatile AgentAccessibilityService instance`），所以取外层类。
        val field = AgentAccessibilityService::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, value)
    }

    /** 页面回读：某一级发过启动指令之后才「变了」，之前一直是启动前那一页 */
    private fun pageSwitchesWhen(launched: () -> Boolean) {
        coEvery { service.captureScreenAsync() } answers { if (launched()) target else home }
    }

    private fun core() = ExecutionCore(
        appContext = appContext,
        settings = mockk<AppSettings>(relaxed = true),
        shizukuManager = shizuku,
        adbTransport = null,
        termuxBridge = null,
        documentEngine = null,
        intentResolver = IntentResolver(),
        appNameResolver = AppNameResolver(mockk<Context>(relaxed = true)) { emptyList() },
        store = EngineStore(),
        ui = mockk<UiBridge>(relaxed = true),
        ctx = mockk<ContextInjector>(relaxed = true),
        recordExecMs = {},
        observe = { home },
        confirmIrreversible = { true },
        // 固定 AUTO 偏好：shell 候选由能力决定，不受 DataStore 影响
        settingsSnapshot = { AppSettings.Settings(executionChannel = "AUTO") },
    )

    private fun launch() = AgentAction(type = ActionType.LAUNCH, packageName = "com.demo")

    // ---- 一级成功 ----

    @Test
    fun `无障碍启动并确认到前台_不碰 shell 也不碰本应用直启`() = runTest {
        every { provider.launchApp("com.demo") } returns ActionResult(true)
        coEvery { service.captureScreenAsync() } returns target

        val r = core().executeWithVerify(launch(), home)

        assertTrue("确认到前台就是成功：${r.reason}", r.success)
        assertEquals("a11y", r.channel)
        assertEquals("首通道成功不该有降级来源", "", r.fallbackFrom)
        coVerify(exactly = 0) { shizuku.executeShell(any()) }
        verify(exactly = 0) { appContext.startActivity(any()) }
    }

    // ---- 一级失败 → 二级 ----

    @Test
    fun `无障碍启动失败_真的换 shell 并确认到前台`() = runTest {
        every { provider.launchApp("com.demo") } returns ActionResult(false, "系统静默拦截 startActivity")
        coEvery { shizuku.executeShell(any()) } answers {
            shellLaunched = true
            ShizukuManager.ShellResult.Success("")
        }
        pageSwitchesWhen { shellLaunched }

        val r = core().executeWithVerify(launch(), home)

        assertTrue("shell 启动后确认到前台就是成功：${r.reason}", r.success)
        assertEquals("shizuku", r.channel)
        assertEquals("降级来源必须指向无障碍这一级", "a11y", r.fallbackFrom)
        // shell 兜底只发一条 monkey 命令（入口不再连环试三条）
        coVerify { shizuku.executeShell("monkey -p com.demo 1") }
        // 已经确认到前台就不该再动本应用直启
        verify(exactly = 0) { appContext.startActivity(any()) }
    }

    // ---- 一级、二级都失败 → 三级 ----

    @Test
    fun `shell 也失败_真的换本应用直启并确认到前台`() = runTest {
        every { provider.launchApp("com.demo") } returns ActionResult(false, "系统静默拦截 startActivity")
        coEvery { shizuku.executeShell(any()) } returns ShizukuManager.ShellResult.Failure("monkey: no activities")
        every { appContext.startActivity(any()) } answers { localLaunched = true }
        pageSwitchesWhen { localLaunched }

        val r = core().executeWithVerify(launch(), home)

        assertTrue("本应用直启后确认到前台就是成功：${r.reason}", r.success)
        assertEquals("local", r.channel)
        assertEquals("降级来源是首次未交付的那一级", "a11y", r.fallbackFrom)
        verify { appContext.startActivity(any()) }
    }

    // ---- 三级全失败 ----

    @Test
    fun `三条通道都没把应用带到前台_必须报失败且三态正确`() = runTest {
        every { provider.launchApp("com.demo") } returns ActionResult(false, "系统静默拦截 startActivity")
        coEvery { shizuku.executeShell(any()) } returns ShizukuManager.ShellResult.Failure("monkey: no activities")
        every { appContext.startActivity(any()) } answers { localLaunched = true }
        // 页面全程读得到，但一直是启动前那一页
        coEvery { service.captureScreenAsync() } returns home

        val r = core().executeWithVerify(launch(), home)

        assertFalse("三级都确认不到前台就不能报成功：${r.reason}", r.success)
        assertTrue("三级都试过且没交付，必须标成未交付（允许外层换路）：${r.reason}", r.notDelivered)
        assertFalse("没有证据说「已交付」就不能标未确认", r.unconfirmed)
        assertTrue("失败原因必须点名是启动失败：${r.reason}", r.reason.contains("启动应用失败"))
        // 三级真的都试了
        verify { provider.launchApp("com.demo") }
        coVerify { shizuku.executeShell(any()) }
        verify { appContext.startActivity(any()) }
    }

    // ---- 读不到页面（三态里的 null）----

    @Test
    fun `无障碍启动后读不到页面_按已交付未确认回报且不换通道`() = runTest {
        every { provider.launchApp("com.demo") } returns ActionResult(true)
        coEvery { service.captureScreenAsync() } returns home.copy(missingAccessibility = true)

        val r = core().executeWithVerify(launch(), home)

        assertFalse("读不到页面不能算成功：${r.reason}", r.success)
        assertTrue("必须由结构化字段表达为未确认：${r.reason}", r.unconfirmed)
        assertFalse("启动指令确实下发了，标成未交付会让外层再拉起一次：${r.reason}", r.notDelivered)
        assertEquals(Delivery.DELIVERED_UNCONFIRMED, r.delivery)
        assertEquals("未确认也算已交付，阶梯必须停在无障碍这一级", "a11y", r.channel)
        // 阶梯不许因为「没确认」就再换一条通道重跑
        coVerify(exactly = 0) { shizuku.executeShell(any()) }
        verify(exactly = 0) { appContext.startActivity(any()) }
    }
}
