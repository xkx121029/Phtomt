package com.phoneagent.execution

import com.phoneagent.domain.model.IntentType
import com.phoneagent.engine.execution.ActionMode
import com.phoneagent.engine.execution.ActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作模式（授权范围）三档判定的回归用例。
 *
 * 重点在两件事：
 * 1. 风险分档与 [IntentType.ALL] **互相闭合**——新增意图忘了标注档位、或标了不存在的意图，这里会红；
 * 2. 三档放行关系严格单调（保守 ⊂ 均衡 ⊂ 自由），避免出现"升档反而少了能力"的错配。
 */
class ActionModePolicyTest {

    private fun allowed(mode: ActionMode, intent: String) =
        ActionPolicy.allows(mode, intent) is ActionPolicy.Verdict.Allowed

    private fun deniedReason(mode: ActionMode, intent: String): String? =
        (ActionPolicy.allows(mode, intent) as? ActionPolicy.Verdict.Denied)?.reason

    // ---- 分档覆盖度 ----

    @Test
    fun 风险分档_覆盖意图全集_无遗漏无多余() {
        assertTrue("有意图标了档位却不在 IntentType.ALL 里：${ActionPolicy.extraTiers()}", ActionPolicy.extraTiers().isEmpty())
        assertTrue("有意图没标任何档位：${ActionPolicy.missingTiers()}", ActionPolicy.missingTiers().isEmpty())
    }

    @Test
    fun 风险分档_三档之和等于全集() {
        // 低 21 + 中 14 + 高 3 = 38 = IntentType.ALL；自由专属的两项不计入风险档
        assertEquals(38, ActionPolicy.tiered.size)
        assertEquals(38, (IntentType.ALL - ActionPolicy.freeOnly).size)
        assertEquals(21, ActionPolicy.lowRisk.size)
        assertEquals(14, ActionPolicy.midRisk.size)
        assertEquals(3, ActionPolicy.highRisk.size)
    }

    @Test
    fun 风险分档_低中高互不重叠() {
        assertTrue((ActionPolicy.lowRisk intersect ActionPolicy.midRisk).isEmpty())
        assertTrue((ActionPolicy.midRisk intersect ActionPolicy.highRisk).isEmpty())
        assertTrue((ActionPolicy.lowRisk intersect ActionPolicy.highRisk).isEmpty())
        assertTrue((ActionPolicy.tiered intersect ActionPolicy.freeOnly).isEmpty())
    }

    // ---- 三档放行关系 ----

    @Test
    fun 保守模式_只放行低风险() {
        ActionPolicy.lowRisk.forEach { assertTrue("保守模式应放行 $it", allowed(ActionMode.CONSERVATIVE, it)) }
        ActionPolicy.midRisk.forEach { assertFalse("保守模式应拒绝中风险 $it", allowed(ActionMode.CONSERVATIVE, it)) }
        ActionPolicy.highRisk.forEach { assertFalse("保守模式应拒绝高风险 $it", allowed(ActionMode.CONSERVATIVE, it)) }
        ActionPolicy.freeOnly.forEach { assertFalse("保守模式应拒绝自由专属 $it", allowed(ActionMode.CONSERVATIVE, it)) }
    }

    @Test
    fun 均衡模式_放行转译层全部_拒绝自由专属() {
        ActionPolicy.tiered.forEach { assertTrue("均衡模式应放行 $it", allowed(ActionMode.BALANCED, it)) }
        ActionPolicy.freeOnly.forEach { assertFalse("均衡模式应拒绝自由专属 $it", allowed(ActionMode.BALANCED, it)) }
    }

    @Test
    fun 自由模式_全部放行() {
        IntentType.ALL.forEach { assertTrue("自由模式应放行 $it", allowed(ActionMode.FREE, it)) }
    }

    @Test
    fun 放行集合单调_保守包含于均衡包含于自由() {
        val conservative = IntentType.ALL.filter { allowed(ActionMode.CONSERVATIVE, it) }
        val balanced = IntentType.ALL.filter { allowed(ActionMode.BALANCED, it) }
        val free = IntentType.ALL.filter { allowed(ActionMode.FREE, it) }
        assertTrue(conservative.all { it in balanced })
        assertTrue(balanced.all { it in free })
    }

    @Test
    fun 拒绝原因_带中文换档建议() {
        val reason = deniedReason(ActionMode.CONSERVATIVE, IntentType.TAP)
        assertNotNull(reason)
        assertTrue("原因里应点明当前档位", reason!!.contains("保守模式"))
        assertTrue("原因里应给出可用意图清单", reason.contains(IntentType.WAIT))
    }

    @Test
    fun 未知意图_不越权拦截() {
        // 未知意图交给转译层按"未知意图"失败，门控不当第二个裁判
        assertTrue(allowed(ActionMode.CONSERVATIVE, "not_an_intent"))
        assertTrue(allowed(ActionMode.BALANCED, ""))
    }

    // ---- 档位解析 ----

    @Test
    fun 档位解析_非法值回落均衡() {
        assertEquals(ActionMode.BALANCED, ActionMode.fromKey(null))
        assertEquals(ActionMode.BALANCED, ActionMode.fromKey(""))
        assertEquals(ActionMode.BALANCED, ActionMode.fromKey("TURBO"))
        assertEquals(ActionMode.DEFAULT, ActionMode.fromKey("garbage"))
    }

    @Test
    fun 档位解析_大小写与空白宽容() {
        assertEquals(ActionMode.FREE, ActionMode.fromKey(" free "))
        assertEquals(ActionMode.CONSERVATIVE, ActionMode.fromKey("conservative"))
        assertEquals(ActionMode.BALANCED, ActionMode.fromKey("Balanced"))
    }

    // ---- 无障碍端点白名单 ----

    @Test
    fun 端点白名单_名称唯一且非空() {
        val names = ActionPolicy.a11yEndpoints.map { it.name }
        assertEquals("端点名必须唯一", names.size, names.distinct().size)
        assertTrue(names.none { it.isBlank() })
    }

    @Test
    fun 端点查找_忽略大小写与空白() {
        assertEquals("click_node", ActionPolicy.endpointOf(" Click_Node ")?.name)
        assertNull(ActionPolicy.endpointOf("not_an_endpoint"))
        assertNull(ActionPolicy.endpointOf(null))
    }

    @Test
    fun 端点参数写法_必填在前可选带问号() {
        val clickNode = ActionPolicy.endpointOf("click_node")!!
        assertEquals("target,long_click?", clickNode.argsText())
        // 无参端点不能渲染成空串，否则提示词表格里是一格空白
        assertEquals("—", ActionPolicy.endpointOf("back")!!.argsText())
    }

    @Test
    fun 端点描述_全部非空且不重复端点名() {
        ActionPolicy.a11yEndpoints.forEach {
            assertTrue("端点 ${it.name} 缺说明", it.description.isNotBlank())
            assertFalse("端点 ${it.name} 说明不应混入必填标记", it.description.contains("?"))
        }
    }

    @Test
    fun 端点英文说明_非空且为纯英文() {
        // 英文提示词的端点表直接取 descriptionEn，混进中文就等于英文提示词被污染
        ActionPolicy.a11yEndpoints.forEach {
            assertTrue("端点 ${it.name} 缺英文说明", it.descriptionEn.isNotBlank())
            assertFalse("端点 ${it.name} 英文说明混入中文", it.descriptionEn.hasCjk())
        }
    }

    @Test
    fun 档位文案_中英各一套且英文纯英文() {
        ActionMode.entries.forEach {
            assertTrue("${it.key} 缺中文档名", it.label.isNotBlank())
            assertTrue("${it.key} 缺英文档名", it.labelEn.isNotBlank() && !it.labelEn.hasCjk())
            assertTrue("${it.key} 缺英文档说明", it.summaryEn.isNotBlank() && !it.summaryEn.hasCjk())
        }
    }

    private fun String.hasCjk(): Boolean = any { it.code in 0x4E00..0x9FFF }
}