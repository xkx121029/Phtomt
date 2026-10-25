package com.phoneagent.feature.skill

import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillCallRecord
import com.phoneagent.feature.skill.SkillInferContext
import com.phoneagent.feature.skill.SkillParam
import com.phoneagent.feature.skill.SkillParamInferencer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺参自动推断：来源优先级（历史 → 上一步结果 → 任务原文 → 页面文本）与 select 严格约束。
 * 推断错了比不推断更糟，select 类候选值必须 ∈ options。
 */
class SkillParamInferencerTest {

    private fun skill(vararg params: SkillParam) = Skill(
        id = "m_query", name = "查余额", source = com.phoneagent.feature.skill.SkillSource.MCP,
        params = params.toList(),
    )

    @Test
    fun 历史成功参数优先于其他来源() {
        val s = skill(SkillParam("path", "路径", "text", true))
        val ctx = SkillInferContext(
            skill = s,
            missing = listOf(s.params[0]),
            taskPrompt = "path=/错的值",
            lastResultText = "path=/也是错的",
            history = listOf(
                SkillCallRecord(id = "1", skillId = "m_query", skillName = "查余额", argsJson = "{\"path\":\"/a/b\"}", success = true),
                SkillCallRecord(id = "2", skillId = "m_query", skillName = "查余额", argsJson = "{\"path\":\"/旧的\"}", success = false),
            ),
        )
        assertEquals(mapOf("path" to "/a/b"), SkillParamInferencer.infer(ctx))
    }

    @Test
    fun 上一步结果的键值写法可推断() {
        val s = skill(SkillParam("account", "账号", "text", true))
        val ctx = SkillInferContext(
            skill = s, missing = listOf(s.params[0]),
            lastResultText = "您的账号: 6222020200112233，余额 100 元",
        )
        assertEquals("6222020200112233", SkillParamInferencer.inferSingle(s.params[0], ctx))
    }

    @Test
    fun select类型只接受候选项内的值() {
        val s = skill(SkillParam("city", "城市", "select", true, options = listOf("北京", "上海")))
        val bad = SkillInferContext(skill = s, missing = listOf(s.params[0]), taskPrompt = "我想去广州出差")
        assertNull("候选项外的值不得采用", SkillParamInferencer.inferSingle(s.params[0], bad))
        val good = SkillInferContext(skill = s, missing = listOf(s.params[0]), taskPrompt = "查一下上海的天气")
        assertEquals("上海", SkillParamInferencer.inferSingle(s.params[0], good))
    }

    @Test
    fun select类型可从页面文本精确命中() {
        val s = skill(SkillParam("city", "城市", "select", true, options = listOf("北京", "上海")))
        val ctx = SkillInferContext(
            skill = s, missing = listOf(s.params[0]),
            screenTexts = listOf("北京", "上海", "广州"),
        )
        assertEquals("北京", SkillParamInferencer.inferSingle(s.params[0], ctx))
    }

    @Test
    fun 全部来源为空返回空map() {
        val s = skill(SkillParam("a", "甲", "text", true), SkillParam("b", "乙", "text", true))
        val ctx = SkillInferContext(skill = s, missing = listOf(s.params[0], s.params[1]))
        assertTrue(SkillParamInferencer.infer(ctx).isEmpty())
    }

    @Test
    fun 已有值的参数不会被推断覆盖() {
        val s = skill(SkillParam("path", "路径", "text", true))
        val ctx = SkillInferContext(
            skill = s,
            missing = emptyList(),  // 调用方只传缺的参数
            taskPrompt = "path=/task/value",
        )
        assertTrue(SkillParamInferencer.infer(ctx).isEmpty())
    }
}
