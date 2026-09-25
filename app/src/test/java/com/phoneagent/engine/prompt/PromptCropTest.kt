package com.phoneagent.engine.prompt

import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.TaskKindDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态裁剪单测：证明"按任务裁块"只裁该裁的、绝不误伤安全块。
 *
 * 三条不变式，缺一不可：
 * 1. **安全块恒在场**：铁律 / 授权范围 / 禁止输出 / 能力声明在任意合法标志组合下都不会缺席；
 * 2. **裁剪范围可控**：系统提示里会被裁掉的只有「网页浏览」「打开链接与文件」两个大块；
 * 3. **判定同源**：裁块（[TaskKindDetector.flagsOf]）与附加指导（[TaskKindDetector.detect]）用同一套词表。
 */
class PromptCropTest {

    // ==================== 不变式 1 / 2：穷举全部合法标志组合 ====================

    /**
     * 穷举"业务合法"的标志组合：引擎每轮必定且只会置一个动作模式，其余标志各自独立开关。
     *
     * 不去穷举 11 位的全幂集，是因为"一个动作模式都不给"在引擎里不可能出现——
     * 对不可能的输入做断言只会逼着代码去兼容它。
     */
    private fun legalFlagSets(): List<Set<PromptFlag>> =
        ACTION_FLAGS.flatMap { mode ->
            (0 until (1 shl OPTIONAL_FLAGS.size)).map { mask ->
                buildSet {
                    add(mode)
                    OPTIONAL_FLAGS.forEachIndexed { i, flag -> if (mask and (1 shl i) != 0) add(flag) }
                }
            }
        }

    @Test
    fun `安全块在任意合法标志组合下都不会被裁掉`() {
        val offenders = legalFlagSets()
            .flatMap { PromptAssembler.unsafeCrops(PromptContext(PromptLang.CN, it)) }
            .distinct()
        assertTrue("以下安全块被条件裁掉了，说明目录里给安全块挂了 requires：$offenders", offenders.isEmpty())
    }

    @Test
    fun `系统提示只会裁掉网页浏览与打开链接两个大块`() {
        val allIds = PromptAssembler.blocks(PromptGroup.SYSTEM).map { it.id }.toSet()
        val offenders = legalFlagSets().flatMap { flags ->
            val present = PromptAssembler.select(PromptGroup.SYSTEM, PromptContext(PromptLang.CN, flags)).map { it.id }.toSet()
            (allIds - present) - CROPPABLE
        }.distinct()
        assertTrue("系统提示出现了计划外的裁剪：$offenders", offenders.isEmpty())
        // 反向确认：这两个块确实会被裁掉（否则上一条断言是"永远为真"的假保障）
        val plain = PromptContext(PromptLang.CN, setOf(PromptFlag.ACTION_BALANCED))
        assertEquals(CROPPABLE, (allIds - PromptAssembler.select(PromptGroup.SYSTEM, plain).map { it.id }.toSet()))
    }

    // ==================== 不变式 3：按任务类型裁 / 不裁 ====================

    @Test
    fun `网页任务保留网页浏览块_普通App任务裁掉它且安全块照常在`() {
        val web = AgentPrompts.system(
            PromptLang.CN, "", hasVision = true, shizukuAvailable = true,
            task = "帮我查一下这个网站上的最新消息",
        )
        assertTrue("网页任务不该被裁掉网页浏览块", web.contains("# 网页浏览"))

        val app = AgentPrompts.system(
            PromptLang.CN, "", hasVision = true, shizukuAvailable = true,
            task = "帮我在美团点一份黄焖鸡米饭",
        )
        assertFalse("普通 App 任务应裁掉网页浏览块", app.contains("# 网页浏览"))
        assertTrue("裁掉的是与任务无关的大块，安全块必须照常", app.contains("# 铁律"))
        assertTrue(app.contains("# 禁止输出"))
        assertTrue(app.contains("# 动作模式"))
        assertTrue("裁块后文本必须变短，否则裁剪没有意义", app.length < web.length)
    }

    @Test
    fun `打开文件或网址的任务保留打开链接块`() {
        val file = AgentPrompts.system(
            PromptLang.CN, "", hasVision = true, shizukuAvailable = true,
            task = "用文档软件打开 /sdcard/Download/季度汇报.ppt",
        )
        assertTrue(file.contains("# 打开链接与文件"))

        val url = AgentPrompts.system(
            PromptLang.CN, "", hasVision = true, shizukuAvailable = true,
            task = "用浏览器打开这个网址给用户看看",
        )
        assertTrue(url.contains("# 打开链接与文件"))
    }

    @Test
    fun `任务文本未知时不裁任何块`() {
        // 拿不到任务（预热 / 等价性回归）时"无法判断"，一律保留，宁可多给也不误裁
        val text = AgentPrompts.system(PromptLang.CN, "", hasVision = true, shizukuAvailable = true)
        assertTrue(text.contains("# 网页浏览"))
        assertTrue(text.contains("# 打开链接与文件"))
    }

    @Test
    fun `裁块判定与附加指导判定同源`() {
        val tasks = listOf(
            "帮我查一下这个网站上的最新消息",
            "帮我在美团点一份黄焖鸡米饭",
            "帮我写一份周报",
            "用文档软件打开 /sdcard/Download/季度汇报.ppt",
            "打开美团 App",
        )
        tasks.forEach { task ->
            val flags = TaskKindDetector.flagsOf(PromptLang.CN, task)
            val text = AgentPrompts.system(PromptLang.CN, "", hasVision = true, shizukuAvailable = true, task = task)
            assertEquals(
                "网页浏览块的在/不在必须与 WEB_TASK 一致：$task",
                PromptFlag.WEB_TASK in flags,
                text.contains("# 网页浏览"),
            )
            assertEquals(
                "打开链接块的在/不在必须与 OPEN_TASK 一致：$task",
                PromptFlag.OPEN_TASK in flags,
                text.contains("# 打开链接与文件"),
            )
        }
    }

    @Test
    fun `英文提示词同样按任务裁块`() {
        val web = AgentPrompts.system(
            PromptLang.EN, "", hasVision = true, shizukuAvailable = true,
            task = "look up the latest news on this website",
        )
        assertTrue(web.contains("# Web Browsing"))

        val app = AgentPrompts.system(
            PromptLang.EN, "", hasVision = true, shizukuAvailable = true,
            task = "order braised chicken rice on Meituan",
        )
        assertFalse(app.contains("# Web Browsing"))
        assertTrue(app.contains("# Iron Rules"))
    }

    private companion object {
        /** 引擎每轮必置其一，互斥 */
        val ACTION_FLAGS = listOf(
            PromptFlag.ACTION_CONSERVATIVE,
            PromptFlag.ACTION_BALANCED,
            PromptFlag.ACTION_FREE,
        )

        /** 其余标志各自独立开关 */
        val OPTIONAL_FLAGS = listOf(
            PromptFlag.HAS_VISION,
            PromptFlag.WEB_TASK,
            PromptFlag.OPEN_TASK,
            PromptFlag.HAS_MEMORY,
            PromptFlag.HAS_EVOLVED_RULES,
            PromptFlag.MCP_TOOLS,
            PromptFlag.MCP_SERVER_ONLY,
            PromptFlag.DISABLED_SKILLS,
        )

        /** 唯一允许被裁的两个大块 */
        val CROPPABLE = setOf("sys.web_browse", "sys.open_link")
    }
}