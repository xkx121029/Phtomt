package com.phoneagent.domain.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话承接判定单测。
 * 目标是：明显的指代/承接短句被判为追问，自带完整目标的新任务不被误判（否则会被上一轮任务带偏）。
 */
class SessionContextTest {

    @Test
    fun `指代词开头视为追问`() {
        assertTrue(SessionContext.isFollowUp("再改一下"))
        assertTrue(SessionContext.isFollowUp("接着刚才的继续"))
        assertTrue(SessionContext.isFollowUp("换成不要辣的"))
        assertTrue(SessionContext.isFollowUp("这个也加上"))
        assertTrue(SessionContext.isFollowUp("同样的再来一份"))
    }

    /** "再"单独出现且句子很短时算追问；长句自带完整目标，不该被上一轮带偏 */
    @Test
    fun `弱承接词仅在短句里算追问`() {
        assertTrue(SessionContext.isFollowUp("再买一张票"))
        assertFalse(SessionContext.isFollowUp("再帮我订一张明天下午三点的电影票，要 IMAX 厅"))
    }

    @Test
    fun `自带完整目标的新任务不算追问`() {
        assertFalse(SessionContext.isFollowUp("打开微信"))
        assertFalse(SessionContext.isFollowUp("帮我在美团点一份黄焖鸡米饭"))
        assertFalse(SessionContext.isFollowUp("把聊天记录截图保存"))
    }

    @Test
    fun `空白输入不算追问`() {
        assertFalse(SessionContext.isFollowUp(""))
        assertFalse(SessionContext.isFollowUp("   "))
    }
}