package com.phoneagent.prompt

import com.phoneagent.data.store.PromptTemplateStore
import com.phoneagent.engine.prompt.PromptTemplate
import com.phoneagent.engine.prompt.PromptTemplateEngine
import com.phoneagent.engine.prompt.PromptVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateEngineTest {

    @Test
    fun 占位符替换() {
        val vars = PromptVars(task = "订外卖", controlsJson = """[{"id":"ctl_0","name":"美团"}]""",
            skills = "open_app, tap", mcpTools = "read_file", lastResult = "成功")
        val out = PromptTemplateEngine.render("任务:{task}\n控件:{controls}\n技能:{skills}\nMCP:{mcpTools}\n上步:{lastResult}", vars)
        assertTrue(out.contains("任务:订外卖"))
        assertTrue(out.contains("\"id\":\"ctl_0\""))
        assertTrue(out.contains("技能:open_app, tap"))
        assertTrue(out.contains("MCP:read_file"))
        assertTrue(out.contains("上步:成功"))
    }

    @Test
    fun 未知占位符保留原样() {
        val out = PromptTemplateEngine.render("你好{unknown}世界", PromptVars())
        assertEquals("你好{unknown}世界", out)
    }

    @Test
    fun 空白模板回退null() {
        assertNull(PromptTemplateEngine.effectiveBody("   "))
        assertNotNull(PromptTemplateEngine.effectiveBody("有内容"))
    }

    @Test
    fun 模板库增改删与回退() {
        val store = PromptTemplateStore(PromptTemplateStore.defaults())
        assertNotNull(store.byId("system"))
        // 内置可改正文
        store.upsert(PromptTemplate("system", "系统提示", "改 {task}", isBuiltIn = true))
        assertEquals("改 {task}", store.byId("system")?.body)
        // 内置不可删
        assertTrue(!store.remove("system"))
        // 自定义增删
        store.upsert(PromptTemplate("my", "我的模板", "自定义 {goal}"))
        assertNotNull(store.byId("my"))
        assertTrue(store.remove("my"))
        assertNull(store.byId("my"))
    }

    @Test
    fun 模板导出导入回环() {
        val a = PromptTemplateStore(PromptTemplateStore.defaults())
        a.upsert(PromptTemplate("custom1", "自定义1", "正文 {task} 结尾"))
        val exported = a.exportJson()
        assertTrue(exported.contains("custom1"))

        val b = PromptTemplateStore()
        val n = b.importJson(exported)
        assertTrue(n >= 5) // 默认4 + 自定义1
        assertEquals("正文 {task} 结尾", b.byId("custom1")?.body)
    }
}
