package com.phoneagent.ui.vm

import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 技能域的 id 分配逻辑。
 *
 * `nextSkillId` 从 1 起找第一个未被占用的 `${prefix}_$i`：这条规则决定用户「新增技能」时
 * 会不会撞号、会不会在所有空号前面留下空洞。它只依赖 [SkillRegistry] 的读数，可在 JVM 直测。
 */
class SkillDelegateTest {

    private fun delegate(vararg ids: String): SkillDelegate {
        val registry = SkillRegistry(ids.map { Skill(id = it, name = it) })
        return SkillDelegate(registry, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun `空注册表_从 1 号开始分配`() {
        assertEquals("skill_user_1", delegate().nextSkillId())
    }

    @Test
    fun `连续占用_取下一个空号`() {
        assertEquals("skill_user_3", delegate("skill_user_1", "skill_user_2").nextSkillId())
    }

    @Test
    fun `中间有空洞_补空号而不是追加到末尾`() {
        assertEquals("skill_user_3", delegate("skill_user_1", "skill_user_2", "skill_user_4").nextSkillId())
        assertEquals("skill_user_2", delegate("skill_user_1", "skill_user_3").nextSkillId())
    }

    @Test
    fun `自定义前缀各算各的`() {
        val d = delegate("skill_user_1", "mcp_fs_1", "mcp_fs_2")
        assertEquals("skill_user_2", d.nextSkillId())
        assertEquals("mcp_fs_3", d.nextSkillId("mcp_fs"))
        assertEquals("macro_1", d.nextSkillId("macro"))
    }

    @Test
    fun `新增技能后立刻避开已占用的号`() {
        // 走的是活的注册表：分配一号后真正加入一条，下次分配必须跳过它
        val registry = SkillRegistry()
        val d = SkillDelegate(registry, CoroutineScope(Dispatchers.Unconfined))

        assertEquals("skill_user_1", d.nextSkillId())
        registry.add(Skill(id = "skill_user_1", name = "s1"))
        assertEquals("skill_user_2", d.nextSkillId())
    }
}
