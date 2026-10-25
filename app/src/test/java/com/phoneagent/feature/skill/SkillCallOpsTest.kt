package com.phoneagent.feature.skill

import com.phoneagent.feature.skill.SkillCallOps
import com.phoneagent.feature.skill.SkillCallRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调用历史纯逻辑：追加淘汰 / 查询 / 摘要（存储层依赖 DataStore，纯函数在此单测） */
class SkillCallOpsTest {

    private fun rec(id: String, skillId: String = "s1", ts: Long, success: Boolean = true, fail: String = "") =
        SkillCallRecord(id = id, skillId = skillId, skillName = skillId, success = success, failReason = fail, ts = ts)

    @Test
    fun 追加后按时间戳新在前并淘汰超限() {
        var list = listOf<SkillCallRecord>()
        repeat(5) { i -> list = SkillCallOps.append(list, rec("r$i", ts = i.toLong()), 3) }
        assertEquals(listOf("r4", "r3", "r2"), list.map { it.id })
    }

    @Test
    fun 乱序写入按ts排序() {
        val merged = SkillCallOps.append(
            listOf(rec("old", ts = 100)),
            rec("new", ts = 200),
            10,
        )
        assertEquals("new", merged.first().id)
    }

    @Test
    fun recentFor只取本技能最近n条() {
        val records = listOf(
            rec("a1", skillId = "s1", ts = 3),
            rec("b1", skillId = "s2", ts = 2),
            rec("a2", skillId = "s1", ts = 1),
        )
        val got = SkillCallOps.recentFor(records, "s1", 1)
        assertEquals(listOf("a1"), got.map { it.id })
    }

    @Test
    fun recentFailures只取失败条目() {
        val records = listOf(
            rec("ok", ts = 3, success = true),
            rec("bad", ts = 2, success = false, fail = "连接超时"),
            rec("ok2", ts = 1, success = true),
        )
        val got = SkillCallOps.recentFailures(records, 5)
        assertEquals(listOf("bad"), got.map { it.id })
    }

    @Test
    fun 摘要成功带耗时参数_失败带原因_单条200字截断() {
        val ok = SkillCallOps.summarize(
            listOf(SkillCallRecord(id = "1", skillId = "s", skillName = "s", argsJson = "{\"path\":\"/a\"}", success = true, elapsedMs = 320, ts = 1)),
        )
        assertTrue(ok.contains("上次成功") && ok.contains("320ms") && ok.contains("/a"))

        val longFail = SkillCallRecord(id = "2", skillId = "s", skillName = "s", success = false, failReason = "错".repeat(300), ts = 2)
        val failLine = SkillCallOps.summarize(listOf(longFail))
        assertTrue(failLine.contains("上次失败"))
        assertTrue("单条摘要必须 ≤200 字", failLine.length <= 200)
    }
}
