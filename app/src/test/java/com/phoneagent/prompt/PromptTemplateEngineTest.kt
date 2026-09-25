package com.phoneagent.prompt

import com.phoneagent.engine.prompt.PromptTemplateEngine
import com.phoneagent.engine.prompt.PromptVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占位符渲染单测。引擎只有 [PromptTemplateEngine.renderOnce] 一个真入口
 * （[com.phoneagent.engine.prompt.PromptAssembler] 整组装配后调它一次），故这里只测它。
 */
class PromptTemplateEngineTest {

    @Test
    fun 占位符替换() {
        val vars = PromptVars(task = "订外卖", lastResult = "成功")
        val out = PromptTemplateEngine.renderOnce("任务:{task}\n上步:{lastResult}", vars.map)
        assertTrue(out.contains("任务:订外卖"))
        assertTrue(out.contains("上步:成功"))
    }

    @Test
    fun 未知占位符保留原样() {
        val out = PromptTemplateEngine.renderOnce("你好{unknown}世界", PromptVars().map)
        assertEquals("你好{unknown}世界", out)
    }

    @Test
    fun `正文里的 JSON 花括号不受影响`() {
        // 区块正文里有大量 {"intent":...} 示例，占位符正则只认标识符，不能碰它们
        val body = """示例：{"intent":"tap"} 与 {task}"""
        val out = PromptTemplateEngine.renderOnce(body, PromptVars(task = "打开美团").map)
        assertEquals("""示例：{"intent":"tap"} 与 打开美团""", out)
    }

    @Test
    fun 单趟替换不会二次展开() {
        // 用户任务里恰好写了 {lastResult} 时，它必须原样保留，不能被当成占位符再展开一遍
        val vars = PromptVars(task = "帮我处理 {lastResult}", lastResult = "上一步成功")
        val out = PromptTemplateEngine.renderOnce("任务:{task}", vars.map)
        assertEquals("任务:帮我处理 {lastResult}", out)
    }
}