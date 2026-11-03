package com.phoneagent.engine

import com.phoneagent.engine.prompt.PromptFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词表裁块单测（Superagent 融合 Phase 7；路由改云端后的回归）。
 *
 * 覆盖 chatHit / codingHit 新词表与旧词表（doc/open/browse/assistant）回归。
 * 注意：词表已不承担三形态路由（改由云端 [IntentRouter] 判定），
 * 这里只断言「提示词裁块」的输入——[TaskKindDetector.detect] 与 [TaskKindDetector.flagsOf]。
 */
class TaskKindDetectorTest {

    // ==================== coding 命中 ====================

    @Test
    fun `中文编码任务命中编码词表并注入编码引导块`() {
        // "写个脚本"+"脚本"+"斐波那契" 三词命中（count 按词表逐词 contains，"写个脚本"与"脚本"各计一次）
        val task = "用 python 写个脚本算斐波那契前 10 项"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue("编码任务应命中编码词表", kind.codingHit)
        assertFalse("不含对话词", kind.chatHit)

        val flags = TaskKindDetector.flagsOf(PromptLang.CN, task)
        assertTrue("编码任务应置 CODE_TASK", PromptFlag.CODE_TASK in flags)
        assertFalse("非纯对话不置 CHAT_TASK", PromptFlag.CHAT_TASK in flags)
    }

    @Test
    fun `多需求任务_爬虫抓网页_同时命中编码与浏览`() {
        val task = "写个爬虫抓网页数据"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue("爬虫命中编码词表", kind.codingHit)
        assertTrue("网页命中浏览词表（操控形态并存）", kind.browseHit)
        assertFalse(kind.chatHit)

        // 词表并存仍注入编码引导块（chat 引导只在纯对话时给）
        val flags = TaskKindDetector.flagsOf(PromptLang.CN, task)
        assertTrue(PromptFlag.CODE_TASK in flags)
        assertFalse(PromptFlag.CHAT_TASK in flags)
    }

    @Test
    fun `英文编码任务命中编码词表`() {
        val task = "write a python script to download a file"
        val kind = TaskKindDetector.detect(PromptLang.EN, task)
        assertTrue(kind.codingHit)
        assertFalse(kind.chatHit)

        assertTrue(PromptFlag.CODE_TASK in TaskKindDetector.flagsOf(PromptLang.EN, task))
    }

    // ==================== chat 命中 ====================

    @Test
    fun `中文纯对话命中且只注入对话引导块`() {
        val task = "什么是量子计算"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue("什么是应命中对话词表", kind.chatHit)
        assertFalse(kind.codingHit)
        assertFalse(kind.docHit)
        assertFalse(kind.browseHit)
        assertFalse(kind.assistantHit)

        assertEquals(setOf(PromptFlag.CHAT_TASK), TaskKindDetector.flagsOf(PromptLang.CN, task))
    }

    @Test
    fun `写文章命中对话词表但与文档词并存时不置对话引导`() {
        // "帮我写一篇文章"+"写一篇文章" 两个对话词都命中；"文章" 同时命中文档词表
        val task = "帮我写一篇文章介绍 AI"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue(kind.chatHit)
        assertTrue("文章应命中文档词表（旧词表行为不变）", kind.docHit)

        // CHAT_TASK 是纯对话引导块：与其他形态并存时不给
        val flags = TaskKindDetector.flagsOf(PromptLang.CN, task)
        assertFalse(PromptFlag.CHAT_TASK in flags)
        assertFalse(PromptFlag.CODE_TASK in flags)
    }

    @Test
    fun `对话与编码词并存时只注入编码引导`() {
        // "解释一下" 命中对话、"代码" 命中编码 → 多需求并存
        val task = "解释一下这段代码的含义"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue(kind.chatHit)
        assertTrue(kind.codingHit)

        // 并存时提示词只注入 CODE_TASK
        val flags = TaskKindDetector.flagsOf(PromptLang.CN, task)
        assertTrue(PromptFlag.CODE_TASK in flags)
        assertFalse(PromptFlag.CHAT_TASK in flags)
    }

    @Test
    fun `英文纯对话命中`() {
        assertEquals(
            setOf(PromptFlag.CHAT_TASK),
            TaskKindDetector.flagsOf(PromptLang.EN, "what is quantum computing"),
        )

        val sky = "explain why the sky is blue"
        assertTrue(TaskKindDetector.detect(PromptLang.EN, sky).chatHit)
        assertEquals(setOf(PromptFlag.CHAT_TASK), TaskKindDetector.flagsOf(PromptLang.EN, sky))
    }

    // ==================== 旧词表回归（doc / open / browse / assistant 行为不变） ====================

    @Test
    fun `打开任务行为不变`() {
        val kind = TaskKindDetector.detect(PromptLang.CN, "打开美团 App")
        assertTrue("打开应命中 open 词表", kind.openHit)
        assertFalse("未点名具体文件时不命中 openTarget", kind.openTargetHit)
        assertEquals(setOf(PromptFlag.OPEN_TASK), TaskKindDetector.flagsOf(PromptLang.CN, "打开美团 App"))
    }

    @Test
    fun `文档任务行为不变_命中但不引导任何块`() {
        val kind = TaskKindDetector.detect(PromptLang.CN, "帮我写一份周报")
        assertTrue("周报应命中文档词表", kind.docHit)
        assertFalse(kind.chatHit)
        // doc 是操作类形态而非引导块：命中不注入任何引导 flag（行为与 Phase 4 之前一致）
        assertEquals(emptySet<PromptFlag>(), TaskKindDetector.flagsOf(PromptLang.CN, "帮我写一份周报"))
    }

    @Test
    fun `浏览器任务行为不变`() {
        val kind = TaskKindDetector.detect(PromptLang.CN, "查一下今天的汇率")
        assertTrue("查一下与汇率应命中浏览词表", kind.browseHit)
        assertEquals(setOf(PromptFlag.WEB_TASK), TaskKindDetector.flagsOf(PromptLang.CN, "查一下今天的汇率"))

        // fetch 词表只在 termux 可用时置位，且 fetch 不改变 flagsOf（无专属 flag，行为不变）
        assertFalse(TaskKindDetector.detect(PromptLang.CN, "查一下今天的汇率", termuxAvailable = false).fetchHit)
        assertTrue(TaskKindDetector.detect(PromptLang.CN, "查一下今天的汇率", termuxAvailable = true).fetchHit)
        assertEquals(
            TaskKindDetector.flagsOf(PromptLang.CN, "查一下今天的汇率", termuxAvailable = false),
            TaskKindDetector.flagsOf(PromptLang.CN, "查一下今天的汇率", termuxAvailable = true),
        )
    }

    @Test
    fun `语音助手任务行为不变`() {
        val kind = TaskKindDetector.detect(PromptLang.CN, "给小张发消息说我晚点到")
        assertTrue(kind.assistantHit)
        assertFalse(kind.chatHit)
        assertFalse(kind.codingHit)
        assertEquals(setOf(PromptFlag.ASSISTANT_TASK), TaskKindDetector.flagsOf(PromptLang.CN, "给小张发消息说我晚点到"))
    }

    @Test
    fun `定闹钟不再引导语音助手`() {
        // 闹钟/倒计时已改为 set_alarm / set_timer 的本地直达能力，不能再注入"交给语音助手"的引导块
        val kind = TaskKindDetector.detect(PromptLang.CN, "明早七点定一个闹钟")
        assertFalse("定闹钟不应命中 assistant 词表", kind.assistantHit)
        assertEquals(emptySet<PromptFlag>(), TaskKindDetector.flagsOf(PromptLang.CN, "明早七点定一个闹钟"))
        assertFalse(TaskKindDetector.detect(PromptLang.EN, "set an alarm for 7am tomorrow").assistantHit)
    }

    // ==================== 未知任务 ====================

    @Test
    fun `空任务不置引导性flag`() {
        val kind = TaskKindDetector.detect(PromptLang.CN, "")
        assertFalse("空任务视为未知", kind.known)

        // 能力声明块（WEB/OPEN）未知时保留——宁可多给也不误裁；
        // 引导性块（ASSISTANT/CODE/CHAT）未知时不置位——不误导也不占 token
        val flags = TaskKindDetector.flagsOf(PromptLang.CN, "")
        assertTrue(PromptFlag.WEB_TASK in flags)
        assertTrue(PromptFlag.OPEN_TASK in flags)
        assertFalse(PromptFlag.ASSISTANT_TASK in flags)
        assertFalse(PromptFlag.CODE_TASK in flags)
        assertFalse(PromptFlag.CHAT_TASK in flags)
    }

    @Test
    fun `纯空白任务同样视为未知`() {
        val flags = TaskKindDetector.flagsOf(PromptLang.CN, "   ")
        assertTrue(PromptFlag.WEB_TASK in flags)
        assertTrue(PromptFlag.OPEN_TASK in flags)
        assertFalse(PromptFlag.CHAT_TASK in flags)
        assertFalse(PromptFlag.CODE_TASK in flags)
    }
}
