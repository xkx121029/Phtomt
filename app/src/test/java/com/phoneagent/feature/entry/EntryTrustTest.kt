package com.phoneagent.feature.entry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口可信性判据的单测：判据必须是「来源可证」，不是「Intent 形状」。
 *
 * 背景（这条测试在钉什么）：以前只有 `hpa://` 深链弹确认卡，`TASK_TEXT` / `RESUME`
 * 这类 extras 直接开跑——任意第三方应用一行 startActivity 就能替用户下任务。
 * 下面三条正例（外部 extras / 外部深链 / 本应用内部）就是那次漏洞的回归测试。
 */
class EntryTrustTest {

    private val selfUid = 10123

    // ---- 来源判定 ----

    @Test
    fun `同 uid 才算可证来自本应用`() {
        assertTrue(EntryTrust.isProvenInternal(sdkInt = 34, callerUid = selfUid, selfUid = selfUid))
    }

    @Test
    fun `拿不到调用方身份一律按外部`() {
        // 第三方应用 / launcher / adb / 被转发的 Intent 都落在这一档
        assertFalse(EntryTrust.isProvenInternal(34, EntryTrust.INVALID_UID, selfUid))
        // API 34 以下没有这条信号，同样按外部处理
        assertFalse(EntryTrust.isProvenInternal(33, selfUid, selfUid))
        assertFalse(EntryTrust.isProvenInternal(26, EntryTrust.INVALID_UID, selfUid))
    }

    @Test
    fun `别的应用的 uid 不可信`() {
        assertFalse(EntryTrust.isProvenInternal(sdkInt = 34, callerUid = 2000, selfUid = selfUid))
        assertFalse(EntryTrust.isProvenInternal(sdkInt = 34, callerUid = 0, selfUid = selfUid))
    }

    // ---- 确认门：外部 extras / 外部深链 / 内部发起 ----

    @Test
    fun `外部 extras 下发的任务必须确认`() {
        val target = TaskEntry.parseExtras(openMemory = false, resume = false, taskText = "给张三转 100 元")
        assertTrue(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }

    @Test
    fun `外部 extras 的续跑必须确认`() {
        val target = TaskEntry.parseExtras(openMemory = false, resume = true, taskText = null)
        assertTrue(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }

    @Test
    fun `外部深链任务必须确认`() {
        val target = TaskEntry.parseDeepLink("hpa", "task", "打开微信给张三发红包")
        assertTrue(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }

    @Test
    fun `外部深链续跑必须确认`() {
        val target = TaskEntry.parseDeepLink("hpa", "resume", null)
        assertTrue(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }

    @Test
    fun `外部分享文字也走确认门`() {
        // 分享面板由用户亲手触发，但第三方应用同样能直接 startActivity 一条 ACTION_SEND，
        // 两种来源在系统层面不可区分——所以按外部来源处理
        val target = TaskEntry.parseShare(android.content.Intent.ACTION_SEND, "帮我静默装个应用", emptyList())
        assertTrue(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }

    @Test
    fun `本应用内部发起的入口不确认`() {
        val task = TaskEntry.parseExtras(openMemory = false, resume = false, taskText = "打开微信")
        assertFalse(EntryTrust.needsUserConfirmation(task!!, provenInternal = true))
        val resume = TaskEntry.parseDeepLink("hpa", "resume", null)
        assertFalse(EntryTrust.needsUserConfirmation(resume!!, provenInternal = true))
        val shared = TaskEntry.parseShare(android.content.Intent.ACTION_SEND, null, listOf("content://a"))
        assertFalse(EntryTrust.needsUserConfirmation(shared!!, provenInternal = true))
    }

    // ---- 外部来源里的免确认档：不改设备状态 ----

    @Test
    fun `只打开记忆页不用确认`() {
        val target = TaskEntry.parseExtras(openMemory = true, resume = false, taskText = null)
        assertFalse(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }

    @Test
    fun `记忆页带续跑仍要确认`() {
        // parseExtras 里 memory 优先、会吞掉 resume（见 TaskEntryTest「memory wins over resume」），
        // 所以这里直接构造同时带两个位的目标：确认门不能因为「主要副作用是导航」就放过续跑
        val target = TaskEntry.Target.Navigate(memory = true, resume = true)
        assertTrue(EntryTrust.needsUserConfirmation(target, provenInternal = false))
    }

    @Test
    fun `分享文件不用确认`() {
        // 文件只是落进临时编码区，用户还要在编码页自己点发送
        val target = TaskEntry.parseShare(android.content.Intent.ACTION_SEND, null, listOf("content://a"))
        assertFalse(EntryTrust.needsUserConfirmation(target!!, provenInternal = false))
    }
}
