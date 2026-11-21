package com.phoneagent.ui.vm

import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.device.shell.TermuxBridge
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux 通道的用户可见状态文案。
 *
 * 探测/授权是两次独立的异步动作，结果都写成 [com.phoneagent.device.shell.TermuxStatus]；
 * 用户只看这几句话判断「到底通没通、下一步该点哪」。这里钉住各分支的文案与 ready/probed 标记，
 * 尤其「成功但输出为空」要回退成可读文案，而不是把空串直接甩到界面上。
 */
class PermissionsDelegateTermuxTest {

    private val termux = mockk<TermuxBridge>(relaxed = true)
    private val shizuku = mockk<ShizukuManager>(relaxed = true)
    private val delegate = PermissionsDelegate(shizuku, termux, CoroutineScope(Dispatchers.Unconfined))

    private fun status() = delegate.termuxStatus.value

    @Test
    fun `探测成功且输出为空_回退为探测通过文案`() {
        every { termux.isInstalled() } returns true
        every { termux.hasRunCommandPermission() } returns true
        coEvery { termux.probe() } returns ShizukuManager.ShellResult.Success("")

        delegate.probeTermux()

        assertTrue(status().installed)
        assertTrue(status().permissionGranted)
        assertTrue(status().probed)
        assertTrue(status().ready)
        assertEquals("探测通过", status().message)
    }

    @Test
    fun `探测成功有输出_原样展示输出`() {
        coEvery { termux.probe() } returns ShizukuManager.ShellResult.Success("hello")

        delegate.probeTermux()

        assertTrue(status().ready)
        assertEquals("hello", status().message)
    }

    @Test
    fun `探测失败_ready 为假并展示底层原因`() {
        coEvery { termux.probe() } returns ShizukuManager.ShellResult.Failure("没有 allow-external-apps")

        delegate.probeTermux()

        assertTrue(status().probed)
        assertFalse(status().ready)
        assertEquals("没有 allow-external-apps", status().message)
    }

    @Test
    fun `授权成功且权限生效_给出确认文案`() {
        every { termux.hasRunCommandPermission() } returns true
        coEvery { termux.grantRunCommandPermission() } returns ShizukuManager.ShellResult.Success("")

        delegate.grantTermuxPermission()

        assertEquals("已授予执行权限，可点「实跑探测」确认", status().message)
    }

    @Test
    fun `授权命令成功但权限仍未生效_提示改用系统设置`() {
        every { termux.hasRunCommandPermission() } returns false
        coEvery { termux.grantRunCommandPermission() } returns ShizukuManager.ShellResult.Success("")

        delegate.grantTermuxPermission()

        assertEquals("命令已执行但权限仍未生效，请改用「在系统设置中授权」", status().message)
    }

    @Test
    fun `授权失败_文案带底层原因`() {
        coEvery { termux.grantRunCommandPermission() } returns ShizukuManager.ShellResult.Failure("denied")

        delegate.grantTermuxPermission()

        assertEquals("授权失败：denied", status().message)
    }
}
