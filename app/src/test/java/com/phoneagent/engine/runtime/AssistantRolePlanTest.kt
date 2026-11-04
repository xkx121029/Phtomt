package com.phoneagent.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助理角色规划器契约测试：四种状态 × 首选动作 × 跳转候选链 × 文案完整性。
 * 纯 JVM，不依赖 RoleManager；真机差异只体现在传入的 facts。
 */
class AssistantRolePlanTest {

    private fun facts(
        api: Int = 34,
        available: Boolean = true,
        held: Boolean = false,
        holder: String? = null,
    ) = AssistantRoleFacts(
        apiLevel = api,
        roleAvailable = available,
        roleHeld = held,
        roleHolderPackage = holder,
        selfPackage = "com.phoneagent",
    )

    @Test
    fun 未持有_首选弹系统授权框() {
        val plan = AssistantRolePlanner.plan(facts())
        assertEquals(AssistantRoleState.NOT_HELD, plan.state)
        assertEquals(AssistantPrimaryAction.REQUEST_ROLE, plan.primaryAction)
        assertTrue(plan.canRequestRole)
        assertNull(plan.failureKey)
        assertTrue(plan.explanation.contains("系统授权框"))
        assertTrue(plan.primaryLabel.isNotBlank())
    }

    @Test
    fun 已持有_无需动作且不提供失败原因() {
        val plan = AssistantRolePlanner.plan(facts(held = true))
        assertEquals(AssistantRoleState.HELD, plan.state)
        assertEquals(AssistantPrimaryAction.NONE, plan.primaryAction)
        assertFalse(plan.canRequestRole)
        assertNull(plan.failureKey)
        assertNull(plan.failureText)
    }

    @Test
    fun 持有者是自己_也视为已持有() {
        val plan = AssistantRolePlanner.plan(facts(held = false, holder = "com.phoneagent"))
        assertEquals(AssistantRoleState.HELD, plan.state)
    }

    @Test
    fun 被其他应用占用_优先弹替换授权框并说明占用者() {
        val plan = AssistantRolePlanner.plan(facts(holder = "com.google.android.googlequicksearchbox"))
        assertEquals(AssistantRoleState.OCCUPIED, plan.state)
        assertEquals(AssistantPrimaryAction.REQUEST_ROLE, plan.primaryAction)
        assertTrue(plan.explanation.contains("com.google.android.googlequicksearchbox"))
        assertNotNull(plan.failureKey)
        assertEquals(AssistantRoleMessages.OCCUPIED, plan.failureText)
    }

    @Test
    fun 低版本_不给授权框_直接去设置并说明原因() {
        val plan = AssistantRolePlanner.plan(facts(api = 28))
        assertEquals(AssistantRoleState.UNSUPPORTED, plan.state)
        assertEquals(AssistantPrimaryAction.OPEN_SETTINGS, plan.primaryAction)
        assertFalse(plan.canRequestRole)
        assertTrue(plan.explanation.contains("Android 10"))
        assertEquals(AssistantRoleMessages.LOW_API, plan.failureText)
        assertNotNull(plan.requestBlockedReason)
    }

    @Test
    fun 角色不可用_直接去设置且候选链仍完整() {
        val plan = AssistantRolePlanner.plan(facts(available = false))
        assertEquals(AssistantRoleState.UNSUPPORTED, plan.state)
        assertEquals(AssistantPrimaryAction.OPEN_SETTINGS, plan.primaryAction)
        assertEquals(AssistantRoleMessages.ROLE_UNAVAILABLE, plan.failureText)
        assertTrue(plan.redirects.isNotEmpty())
    }

    @Test
    fun 跳转候选_顺序固定且按API过滤() {
        val plan = AssistantRolePlanner.plan(facts(api = 34))
        assertEquals(
            listOf("voice_interaction", "default_apps", "voice_input", "system_settings"),
            plan.redirects.map { it.key },
        )
        // API 24 只能看到默认应用与系统设置兜底
        val api24 = AssistantRolePlanner.plan(facts(api = 24)).redirects.map { it.key }
        assertEquals(listOf("default_apps", "system_settings"), api24)
        // 候选键唯一，action 不重复
        assertEquals(plan.redirects.size, plan.redirects.map { it.action }.toSet().size)
    }

    @Test
    fun 所有状态都有中文首选文案与说明() {
        val plans = listOf(
            AssistantRolePlanner.plan(facts()),
            AssistantRolePlanner.plan(facts(held = true)),
            AssistantRolePlanner.plan(facts(holder = "other.app")),
            AssistantRolePlanner.plan(facts(api = 28)),
            AssistantRolePlanner.plan(facts(available = false)),
        )
        plans.forEach { plan ->
            assertTrue("${plan.state} 缺少首选文案", plan.primaryLabel.isNotBlank())
            assertTrue("${plan.state} 缺少说明", plan.explanation.isNotBlank())
            // 说明必须是中文成品，不能是键名
            assertFalse("${plan.state} 的说明像键名", plan.explanation.contains("assistant_role_"))
        }
    }

    @Test
    fun 失败文案键与默认值一一对应且全部中文() {
        val keys = listOf(
            AssistantRoleMessages.KEY_LOW_API,
            AssistantRoleMessages.KEY_ROLE_UNAVAILABLE,
            AssistantRoleMessages.KEY_OCCUPIED,
            AssistantRoleMessages.KEY_DENIED,
            AssistantRoleMessages.KEY_NO_SETTINGS_PAGE,
        )
        keys.forEach { key ->
            val text = AssistantRoleMessages.textOf(key)
            assertNotNull("$key 没有默认文案", text)
            assertTrue("$key 的文案像键名", text!!.contains("。"))
        }
        assertNull(AssistantRoleMessages.textOf(null))
        // 拒绝授权必须给出可执行的下一步，而不是只说失败
        assertTrue(AssistantRoleMessages.DENIED.contains("再试一次"))
        assertTrue(AssistantRoleMessages.DENIED.contains("设置入口"))
    }

    @Test
    fun 最终兜底页_不需要resolve() {
        val last = AssistantRolePlanner.REDIRECTS.last()
        assertEquals("system_settings", last.key)
        assertFalse(last.requiresResolve)
        assertTrue(AssistantRolePlanner.ACTION_SETTINGS.startsWith("android.settings."))
    }
}
