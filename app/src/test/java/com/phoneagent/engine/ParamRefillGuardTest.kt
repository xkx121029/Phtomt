package com.phoneagent.engine

import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.AgentIntentTarget
import com.phoneagent.domain.model.IntentType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数补全轮不得换掉整条意图（HAPP-32）。
 *
 * 补全请求只说"你漏了 target/text，补上再发一遍"，但旧实现无条件采纳 AI 的返回：
 * 一次补全就能把 `tap` 换成 `shell`/`input` 之类完全不同的意图，端侧照单执行，
 * 而时间线记录的仍是原意图——执行的东西和用户看到的记录不是一件事。
 */
class ParamRefillGuardTest {

    @Test
    fun `补全后的意图与原意图相同_可以采纳`() {
        val original = AgentIntent(intent = IntentType.TAP)
        val refilled = AgentIntent(
            intent = IntentType.TAP,
            target = AgentIntentTarget(by = "text", value = "搜索"),
        )
        assertNull("补上 target 属于正常补全", paramRefillRejection(original, refilled))
    }

    @Test
    fun `补全后换成别的意图_丢弃并说明原因`() {
        val original = AgentIntent(intent = IntentType.TAP)
        val refilled = AgentIntent(intent = IntentType.SHELL, command = "rm -rf /sdcard/Download")

        val reason = paramRefillRejection(original, refilled)

        assertNotNull("换意图必须被拒绝", reason)
        assertTrue("原因要点名换掉了什么，实际: $reason", reason!!.contains("tap") && reason.contains("shell"))
    }

    @Test
    fun `input 缺 text 补全时若改成 swipe_同样丢弃`() {
        val original = AgentIntent(intent = IntentType.INPUT, target = AgentIntentTarget(by = "text", value = "搜索"))
        val refilled = AgentIntent(intent = IntentType.SWIPE, direction = "up")

        assertNotNull(paramRefillRejection(original, refilled))
    }
}
