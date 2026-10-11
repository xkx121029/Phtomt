package com.phoneagent.skill

import com.phoneagent.feature.skill.MacroStepExpander
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroStepExpanderTest {

    @Test
    fun 宏入参占位符展开() {
        val out = MacroStepExpander.expand(
            stepArgs = mapOf("app" to "{{目标应用}}", "city" to "{{城市}}"),
            macroArgs = mapOf("目标应用" to "高德地图", "城市" to "上海"),
            stepResults = emptyList(),
        )
        assertEquals("高德地图", out["app"])
        assertEquals("上海", out["city"])
    }

    @Test
    fun 步骤结果占位符展开并截断到200字() {
        val longResult = "长".repeat(500)
        val out = MacroStepExpander.expand(
            stepArgs = mapOf("text" to "上一步说：{{step1}}"),
            macroArgs = emptyMap(),
            stepResults = listOf(longResult),
        )
        assertTrue(out["text"]!!.startsWith("上一步说："))
        // 200 字截断：引用文本部分恰为 200 个「长」
        assertEquals("上一步说：" + "长".repeat(200), out["text"])
    }

    @Test
    fun 引用未来或越界步骤替换为空串() {
        val out = MacroStepExpander.expand(
            stepArgs = mapOf("a" to "{{step2}}", "b" to "{{step9}}", "c" to "{{step0}}"),
            macroArgs = emptyMap(),
            stepResults = listOf("第一步结果"),
        )
        // step2 引用尚未执行的步骤、step9 越界、step0 编号非法 → 全部替换为空串（模板语法绝不透传）
        assertEquals("", out["a"])
        assertEquals("", out["b"])
        assertEquals("", out["c"])
    }

    @Test
    fun 未提供的宏参数替换为空串() {
        val out = MacroStepExpander.expand(
            stepArgs = mapOf("text" to "备注：{{备注}}"),
            macroArgs = emptyMap(),
            stepResults = emptyList(),
        )
        assertEquals("备注：", out["text"])
    }

    @Test
    fun 无占位符的值原样保留() {
        val out = MacroStepExpander.expand(
            stepArgs = mapOf("app" to "微信", "plain" to "a{b}c {{x"),
            macroArgs = mapOf("app" to "支付宝"),
            stepResults = emptyList(),
        )
        assertEquals("微信", out["app"])
        // 单花括号与未闭合双花括号不是占位符语法，原样保留
        assertEquals("a{b}c {{x", out["plain"])
    }

    @Test
    fun 多占位符混排与展开值不再二次展开() {
        val out = MacroStepExpander.expand(
            stepArgs = mapOf("mix" to "{{step1}}-{{城市}}"),
            macroArgs = mapOf("城市" to "{{step1}}"), // 参数值本身含占位符文本也不二次展开
            stepResults = listOf("第一步"),
        )
        assertEquals("第一步-{{step1}}", out["mix"])
    }
}
