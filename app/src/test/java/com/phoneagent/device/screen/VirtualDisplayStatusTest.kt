package com.phoneagent.device.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏四态展示口径（设置页开关下面那行字）：
 * 未激活 Shizuku / 系统不支持 / 已启用 display=N / 失败原因。
 *
 * 这些文案直接决定用户"点开关之后能不能看懂发生了什么"，所以按态逐条钉住。
 */
class VirtualDisplayStatusTest {

    @Test
    fun 未激活Shizuku_优先于其它状态() {
        // 即使上一次是"系统不支持"，Shizuku 掉线时也要先提示通道问题，否则用户会去查设备
        val p = VirtualDisplayStatus.Unsupported("创建超时").present(shizukuReady = false)
        assertEquals("未激活 Shizuku", p.title)
        assertTrue(p.isFailure)
    }

    @Test
    fun 有活动副屏_显示具体displayId() {
        val p = VirtualDisplayStatus.Active(2).present(shizukuReady = true)
        assertEquals("已启用 display=2", p.title)
        assertFalse(p.isFailure)
    }

    @Test
    fun 系统不支持_展示原因可诊断() {
        val p = VirtualDisplayStatus.Unsupported("模拟副屏创建超时").present(shizukuReady = true)
        assertEquals("系统不支持", p.title)
        assertEquals("模拟副屏创建超时", p.detail)
        assertTrue(p.isFailure)
    }

    @Test
    fun 创建失败_展示失败原因() {
        val p = VirtualDisplayStatus.Failed("写入模拟显示设置失败：permission denied").present(shizukuReady = true)
        assertEquals("创建失败", p.title)
        assertTrue(p.detail.contains("permission denied"))
        assertTrue(p.isFailure)
    }

    @Test
    fun 就绪但还没创建_不是失败态() {
        val p = VirtualDisplayStatus.Idle.present(shizukuReady = true)
        assertEquals("已就绪", p.title)
        assertFalse(p.isFailure)
    }

    // ---- 降级态：原因必须留在设置页，不能被"已就绪"覆盖 ----

    @Test
    fun 运行中降级_展示原因与发生时间() {
        val p = VirtualDisplayStatus.Degraded("副屏无障碍树不可用", atMillis = 1_700_000_000_000L)
            .present(shizukuReady = true)
        assertEquals("已降级到主屏", p.title)
        assertTrue(p.detail.contains("副屏无障碍树不可用"))
        assertTrue(p.detail.contains("原因："))
        assertTrue(p.isFailure)
    }

    @Test
    fun 降级态优先于Shizuku掉线_不丢原因() {
        // Shizuku 随后掉线很常见（正是降级原因），标题仍要指向降级事实
        val p = VirtualDisplayStatus.Degraded("启动落点不符", atMillis = 1L).present(shizukuReady = false)
        assertEquals("已降级到主屏", p.title)
        assertTrue(p.detail.contains("启动落点不符"))
    }

    @Test
    fun 运行时降级销毁后_状态不回落为就绪() {
        val next = VirtualDisplayAgent.statusAfterDestroy(
            previous = VirtualDisplayStatus.Active(2),
            degradedReason = "副屏截图与元素树都不可用",
            atMillis = 1_700_000_000_000L,
        )
        assertTrue(next is VirtualDisplayStatus.Degraded)
        assertEquals("已降级到主屏", next.present(shizukuReady = true).title)
    }

    @Test
    fun 正常收尾销毁_活动副屏回到就绪() {
        val next = VirtualDisplayAgent.statusAfterDestroy(
            previous = VirtualDisplayStatus.Active(2),
            degradedReason = null,
            atMillis = 0L,
        )
        assertEquals(VirtualDisplayStatus.Idle, next)
    }

    @Test
    fun 非活动失败态销毁_保留原失败原因() {
        val failed = VirtualDisplayStatus.Failed("写入失败")
        val next = VirtualDisplayAgent.statusAfterDestroy(failed, degradedReason = null, atMillis = 0L)
        assertEquals(failed, next)
    }

    // ---- 启动落点：命令成功但应用在主屏时必须判失败 ----

    @Test
    fun 启动落点_在副屏即成功() {
        val r = VirtualDisplayAgent.landingResult(expectedDisplayId = 2, seen = setOf(2), waitedMs = 0)
        assertTrue(r is VirtualDisplayAgent.Outcome.Ok)
    }

    @Test
    fun 启动落点_只在主屏出现判失败() {
        val r = VirtualDisplayAgent.landingResult(expectedDisplayId = 2, seen = setOf(0), waitedMs = 0)
        assertTrue(r is VirtualDisplayAgent.Outcome.Fail)
        val f = r as VirtualDisplayAgent.Outcome.Fail
        assertTrue(f.landingMismatch)
        assertTrue(f.reason.contains("display=0"))
    }

    @Test
    fun 启动落点_始终读不到任务栈判失败() {
        val r = VirtualDisplayAgent.landingResult(expectedDisplayId = 2, seen = emptySet(), waitedMs = 3000)
        assertTrue(r is VirtualDisplayAgent.Outcome.Fail)
        assertTrue((r as VirtualDisplayAgent.Outcome.Fail).landingMismatch)
    }

    @Test
    fun 解析am_stack_list_按RootTask归属display() {
        val output = """
            RootTask id=1 bounds=[0,0][1080,2400] displayId=0 userId=0
              configuration={mBounds=[0,0][1080,2400]}
              taskId=5: com.demo/.Main bounds=[0,0][1080,2400] userId=0
            RootTask id=2 bounds=[0,0][1080,1920] displayId=2 userId=0
              configuration={mBounds=[0,0][1080,1920]}
              taskId=9: com.demo/.Detail bounds=[0,0][1080,1920] userId=0
        """.trimIndent()
        assertEquals(setOf(0, 2), VirtualDisplayAgent.displayIdsInStackList(output, "com.demo"))
    }

    @Test
    fun 解析dumpsys_activity_按displayId分段归属() {
        val output = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
              displayId=0
                Task{abc #5 type=standard I=com.demo/.Main}
              displayId=2
                Task{def #9 type=standard I=com.demo/.Detail}
        """.trimIndent()
        assertEquals(setOf(0, 2), VirtualDisplayAgent.displayIdsInActivityDump(output, "com.demo"))
    }
}
