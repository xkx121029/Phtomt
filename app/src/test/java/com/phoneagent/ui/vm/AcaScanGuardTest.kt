package com.phoneagent.ui.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACA 技能目录扫描的 TTL / 重入判定。
 *
 * 扫描本身要读 `filesDir`、跑在 IO 线程，只能在设备上跑；这里钉的是进入扫描前的决策：
 * 扫描中必跳过、TTL 内且未强制跳过、超过 TTL 或 force 必扫、从未扫描必扫。
 * 主页每次切回 Tab 都会触发一次扫描，这几条错了就会要么反复扫盘、要么永远不刷新。
 */
class AcaScanGuardTest {

    private val ttl = 60_000L
    private val now = 1_000_000L

    @Test
    fun `正在扫描时一律跳过_重入保护`() {
        assertTrue(acaScanSkipped(scanning = true, nowMs = now, scannedAtMs = 0L, force = false))
        assertTrue(acaScanSkipped(scanning = true, nowMs = now, scannedAtMs = now - 1, force = true))
    }

    @Test
    fun `从未扫描过_无论 force 都要扫`() {
        assertFalse(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = 0L, force = false))
        assertFalse(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = 0L, force = true))
    }

    @Test
    fun `TTL 内且未强制_跳过`() {
        assertTrue(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - 1, force = false))
        assertTrue(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - (ttl - 1), force = false))
    }

    @Test
    fun `TTL 内但强制刷新_照扫`() {
        assertFalse(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - 1, force = true))
    }

    @Test
    fun `恰好到达 TTL 边界_不再命中缓存`() {
        // 判定是严格小于：差值正好等于 TTL 时已过期，必须扫
        assertFalse(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - ttl, force = false))
        assertFalse(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - (ttl + 1), force = false))
    }

    @Test
    fun `时钟回拨使差值为负_按新鲜处理不重复扫`() {
        assertTrue(acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now + 5_000, force = false))
    }

    @Test
    fun `自定义 TTL 生效`() {
        assertTrue(
            acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - 999, force = false, ttlMs = 1_000),
        )
        assertFalse(
            acaScanSkipped(scanning = false, nowMs = now, scannedAtMs = now - 1_000, force = false, ttlMs = 1_000),
        )
    }
}
