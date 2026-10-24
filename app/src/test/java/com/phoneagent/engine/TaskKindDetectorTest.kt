package com.phoneagent.engine

import com.phoneagent.engine.prompt.PromptFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三形态判定词表单测（Superagent 融合 Phase 7）。
 *
 * 覆盖 chatHit / codingHit 新词表与旧词表（doc/open/browse/assistant）回归，
 * 以及 [TaskKindDetector.scores] 的计分——多需求并存时 [AgentEngine.routeTask]
 * 按「编码分 >= max(对话分, 操控分)」取最高置信度者（平手偏编码），
 * 这里的计分断言与该取大实现同源。
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

        val s = TaskKindDetector.scores(PromptLang.CN, task)
        assertEquals(3, s.coding)
        assertEquals(0, s.chat)
        assertEquals(0, s.agentOps)
    }

    @Test
    fun `多需求任务_爬虫抓网页_计分与路由取大同源`() {
        val task = "写个爬虫抓网页数据"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue("爬虫命中编码词表", kind.codingHit)
        assertTrue("网页命中浏览词表（操控形态并存）", kind.browseHit)
        assertFalse(kind.chatHit)

        // termux 不可用：coding=1（爬虫），agentOps=1（browse 的"网页"），
        // 路由取大 coding(1) >= max(chat=0, ops=1) → 编码（平手偏编码）
        val s = TaskKindDetector.scores(PromptLang.CN, task, termuxAvailable = false)
        assertEquals(1, s.coding)
        assertEquals(0, s.chat)
        assertEquals(1, s.agentOps)

        // termux 可用：fetch 词表追加命中"网页"+"爬"，agentOps=3 → 编码分不再占优，路由按操控处理
        val sTermux = TaskKindDetector.scores(PromptLang.CN, task, termuxAvailable = true)
        assertEquals(1, sTermux.coding)
        assertEquals(3, sTermux.agentOps)

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

        // python + script 两词命中
        assertEquals(2, TaskKindDetector.scores(PromptLang.EN, task).coding)
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

        val s = TaskKindDetector.scores(PromptLang.CN, task)
        assertEquals(1, s.chat)
        assertEquals(0, s.coding)
        assertEquals(0, s.agentOps)
    }

    @Test
    fun `写文章命中对话词表但与文档词并存时不置对话引导`() {
        // "帮我写一篇文章"+"写一篇文章" 两个对话词都命中；"文章" 同时命中文档词表
        val task = "帮我写一篇文章介绍 AI"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue(kind.chatHit)
        assertTrue("文章应命中文档词表（旧词表行为不变）", kind.docHit)

        // CHAT_TASK 是纯对话引导块：与其他形态并存时不给（与 routeTask 的 chatPure 判定同条件）
        val flags = TaskKindDetector.flagsOf(PromptLang.CN, task)
        assertFalse(PromptFlag.CHAT_TASK in flags)
        assertFalse(PromptFlag.CODE_TASK in flags)

        val s = TaskKindDetector.scores(PromptLang.CN, task)
        assertEquals(2, s.chat)
        assertEquals(0, s.coding)
        // "文章" 与 "写一篇"（DOC_WORDS 内含 "写一个"/"写一篇"）两词命中
        assertEquals(2, s.agentOps)
    }

    @Test
    fun `对话与编码词并存时计分并列_路由平手偏编码`() {
        // "解释一下" 命中对话、"代码" 命中编码 → 多需求并存
        val task = "解释一下这段代码的含义"
        val kind = TaskKindDetector.detect(PromptLang.CN, task)
        assertTrue(kind.chatHit)
        assertTrue(kind.codingHit)

        val s = TaskKindDetector.scores(PromptLang.CN, task)
        assertEquals(1, s.chat)
        assertEquals(1, s.coding)
        assertEquals(0, s.agentOps)

        // coding(1) >= max(chat=1, ops=0) → 路由走编码；提示词只注入 CODE_TASK
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

        // "explain"+"why" 两词命中
        val sky = "explain why the sky is blue"
        assertTrue(TaskKindDetector.detect(PromptLang.EN, sky).chatHit)
        assertEquals(2, TaskKindDetector.scores(PromptLang.EN, sky).chat)
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
        val kind = TaskKindDetector.detect(PromptLang.CN, "明早七点定一个闹钟")
        assertTrue(kind.assistantHit)
        assertFalse(kind.chatHit)
        assertFalse(kind.codingHit)
        assertEquals(setOf(PromptFlag.ASSISTANT_TASK), TaskKindDetector.flagsOf(PromptLang.CN, "明早七点定一个闹钟"))
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

        val s = TaskKindDetector.scores(PromptLang.CN, "")
        assertEquals(0, s.coding)
        assertEquals(0, s.chat)
        assertEquals(0, s.agentOps)
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
