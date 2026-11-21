package com.phoneagent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配锁判据测试：每一条「残留」通道都要单独立住一条用例。
 *
 * 这些用例的价值在于变异实验——把 `InstallLock.judge` 里对应的分支删掉或放宽，
 * 对应用例立刻变红。所以断言都落在 [InstallLock.Verdict] 的类型与原因上，不是抄实现。
 */
class InstallLockTest {

    private val now = 1_000_000L
    private val selfPid = 4242
    private val selfToken = "self-token"

    private fun holder(
        pid: Int = selfPid,
        token: String = selfToken,
        acquired: Long = now - 10_000,
        heartbeat: Long = now - 1_000,
    ) = InstallLock.Holder(pid, token, acquired, heartbeat, 1_700_000_000_000L)

    private fun judge(
        holder: InstallLock.Holder?,
        at: Long = now,
        peer: (Int) -> InstallLock.Peer = { InstallLock.Peer.ALIVE },
    ) = InstallLock.judge(holder, at, selfPid, selfToken, peer)

    @Test
    fun `自己持有的新鲜锁视为在跑`() {
        val verdict = judge(holder())
        assertTrue("自己正在装配时不能被判成残留", verdict is InstallLock.Verdict.Live)
        assertEquals(selfPid, (verdict as InstallLock.Verdict.Live).pid)
    }

    @Test
    fun `锁记录缺失视为残留`() {
        val verdict = judge(null)
        assertTrue(verdict is InstallLock.Verdict.Stale)
        assertTrue((verdict as InstallLock.Verdict.Stale).reason.contains("缺失"))
    }

    @Test
    fun `单调时钟倒退说明设备重启过`() {
        val verdict = judge(holder(acquired = now + 5_000))
        assertTrue(verdict is InstallLock.Verdict.Stale)
        assertTrue((verdict as InstallLock.Verdict.Stale).reason.contains("重启"))
    }

    @Test
    fun `同一 pid 但换了会话说明上一轮没走 finally`() {
        val verdict = judge(holder(token = "上一个进程留下的"))
        assertTrue("pid 复用不能被当成自己正在装", verdict is InstallLock.Verdict.Stale)
        assertTrue((verdict as InstallLock.Verdict.Stale).reason.contains("上一轮"))
    }

    @Test
    fun `持有者进程已消失就接管`() {
        val verdict = judge(holder(pid = 999), peer = { InstallLock.Peer.GONE })
        assertTrue(verdict is InstallLock.Verdict.Stale)
        assertTrue((verdict as InstallLock.Verdict.Stale).reason.contains("999"))
    }

    @Test
    fun `别的进程真的在装配就等它`() {
        val verdict = judge(holder(pid = 999), peer = { InstallLock.Peer.ALIVE })
        assertTrue(verdict is InstallLock.Verdict.Live)
        assertEquals(999, (verdict as InstallLock.Verdict.Live).pid)
        assertEquals(1_000L, verdict.heartbeatAgeMs)
    }

    @Test
    fun `持有者心跳停了就算残留`() {
        val stuck = holder(pid = 999, heartbeat = now - InstallLock.STALE_HEARTBEAT_MS - 1)
        val verdict = judge(stuck, peer = { InstallLock.Peer.ALIVE })
        assertTrue("心跳停摆是判断「被系统冻结/杀掉」的最后一道闸", verdict is InstallLock.Verdict.Stale)
        assertTrue((verdict as InstallLock.Verdict.Stale).reason.contains("心跳"))
    }

    @Test
    fun `心跳刚好在阈值上不算残留`() {
        val edge = holder(pid = 999, heartbeat = now - InstallLock.STALE_HEARTBEAT_MS)
        assertTrue(judge(edge, peer = { InstallLock.Peer.ALIVE }) is InstallLock.Verdict.Live)
    }

    @Test
    fun `proc 读不到时退回心跳判定`() {
        val fresh = judge(holder(pid = 999), peer = { InstallLock.Peer.UNKNOWN })
        assertTrue("探不清就按「在跑」处理，宁可等待也别踩掉别人", fresh is InstallLock.Verdict.Live)

        val stuck = holder(pid = 999, heartbeat = now - InstallLock.STALE_HEARTBEAT_MS - 1)
        assertTrue(judge(stuck, peer = { InstallLock.Peer.UNKNOWN }) is InstallLock.Verdict.Stale)
    }

    @Test
    fun `proc 探针把 pid 回收识别成已消失`() {
        val name = "com.phoneagent.env"
        assertEquals(InstallLock.Peer.GONE, InstallLock.classifyPeer(exists = false, cmdline = null, selfProcessName = name))
        assertEquals(
            InstallLock.Peer.UNKNOWN,
            InstallLock.classifyPeer(exists = true, cmdline = null, selfProcessName = name),
        )
        assertEquals(
            InstallLock.Peer.ALIVE,
            InstallLock.classifyPeer(exists = true, cmdline = "$name\u0000", selfProcessName = name),
        )
        assertEquals(
            "cmdline 是别的程序说明 pid 已被回收",
            InstallLock.Peer.GONE,
            InstallLock.classifyPeer(exists = true, cmdline = "com.other.app\u0000", selfProcessName = name),
        )
    }

    @Test
    fun `记录编解码可往返`() {
        val original = holder()
        assertEquals(original, InstallLock.decode(InstallLock.encode(original)))
        assertNull(InstallLock.decode(""))
        assertNull(InstallLock.decode("pid=1\ntoken=abc\n"))
        assertNull(InstallLock.decode("v=1\npid=abc\ntoken=t\nboot=1\n"))
    }
}
