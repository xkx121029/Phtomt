package com.phoneagent.skill

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillCompat
import com.phoneagent.feature.skill.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看图追问」（see）归一化的回归用例：
 * 1. 扁平字段 text 与 args.text 两种写法都要能拿到问题；
 * 2. target 扁平字段与 args 兼容；
 * 3. 缺问题 → 中文原因拒绝；
 * 4. 技能被停用 → 拒绝。
 */
class SkillSeeNormalizeTest {

    private fun registry() = SkillRegistry(SkillCatalog.builtins())

    @Test
    fun see_扁平text字段归一化为Vision() {
        val intent = AgentIntent(intent = IntentType.SEE, text = "图中转盘指针指向哪个扇区")
        val r = SkillCompat.normalize(intent, registry())
        assertTrue("应归一化为 Vision：$r", r is SkillCompat.Normalized.Vision)
        val v = r as SkillCompat.Normalized.Vision
        assertEquals("skill_see", v.skill.id)
        assertEquals("图中转盘指针指向哪个扇区", v.question)
        assertEquals(null, v.target)
    }

    @Test
    fun see_技能id加args写法_问题与目标都拿到() {
        val intent = AgentIntent(
            intent = "skill_see",
            args = mapOf("text" to "图里哪个是确认按钮", "target" to "确认按钮"),
        )
        val r = SkillCompat.normalize(intent, registry())
        assertTrue(r is SkillCompat.Normalized.Vision)
        val v = r as SkillCompat.Normalized.Vision
        assertEquals("图里哪个是确认按钮", v.question)
        assertEquals("确认按钮", v.target)
    }

    @Test
    fun see_target扁平字段优先于args() {
        val intent = AgentIntent(
            intent = IntentType.SEE,
            text = "定位搜索图标",
            target = AgentIntentTarget(by = "hint", value = "右上角的搜索图标"),
        )
        val r = SkillCompat.normalize(intent, registry())
        val v = r as SkillCompat.Normalized.Vision
        assertEquals("右上角的搜索图标", v.target)
    }

    @Test
    fun see_缺问题_中文原因拒绝() {
        val intent = AgentIntent(intent = IntentType.SEE)
        val r = SkillCompat.normalize(intent, registry())
        assertTrue(r is SkillCompat.Normalized.Error)
        assertTrue("原因应点明「问题」参数", (r as SkillCompat.Normalized.Error).reason.contains("问题"))
    }

    @Test
    fun see_技能停用_拒绝() {
        val reg = SkillRegistry(SkillCatalog.builtins())
        reg.setEnabled("skill_see", false)
        val intent = AgentIntent(intent = IntentType.SEE, text = "图里有什么")
        val r = SkillCompat.normalize(intent, reg)
        assertTrue(r is SkillCompat.Normalized.Error)
        assertTrue((r as SkillCompat.Normalized.Error).reason.contains("停用"))
    }
}
