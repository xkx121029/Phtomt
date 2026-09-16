package com.phoneagent.task

import com.phoneagent.data.store.TaskTemplate
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.feature.task.TemplateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskStore 模板匹配纯逻辑测试（TemplateMatcher）。
 * 覆盖：bigram 切分、Jaccard 相似度、命中过滤（enabled / failedStreak 健康）。
 */
class TemplateMatcherTest {

    private fun template(
        goal: String,
        enabled: Boolean = true,
        failedStreak: Int = 0,
    ) = TaskTemplate(
        id = "tpl_test_${goal.hashCode()}",
        goal = goal,
        plan = TaskPlan(),
        enabled = enabled,
        failedStreak = failedStreak,
    )

    // ---- biGrams ----

    @Test
    fun biGrams_中文四字_切成三个双字对() {
        val grams = TemplateMatcher.biGrams("打开微信")
        assertEquals(setOf("打开", "开微", "微信"), grams)
    }

    @Test
    fun biGrams_单字符_整体作为单元素() {
        assertEquals(setOf("嗯"), TemplateMatcher.biGrams("嗯"))
    }

    @Test
    fun biGrams_过滤非字母数字_标点不影响切分() {
        val grams = TemplateMatcher.biGrams("发，消息！")
        assertEquals(setOf("发消", "消息"), grams)
    }

    // ---- jaccard ----

    @Test
    fun jaccard_相同集合_为1() {
        assertEquals(1.0, TemplateMatcher.jaccard(setOf("a", "b"), setOf("a", "b")), 0.001)
    }

    @Test
    fun jaccard_无交集_为0() {
        assertEquals(0.0, TemplateMatcher.jaccard(setOf("a"), setOf("b")), 0.001)
    }

    @Test
    fun jaccard_双空集合_为0() {
        assertEquals(0.0, TemplateMatcher.jaccard(emptySet(), emptySet()), 0.001)
    }

    // ---- match ----

    @Test
    fun match_目标高度相似_命中模板() {
        val t = template(goal = "帮我把微信消息全部标为已读")
        val hit = TemplateMatcher.match("将微信消息全部标为已读", listOf(t))
        assertNotNull(hit)
        assertEquals(t.id, hit?.id)
    }

    @Test
    fun match_目标差异大_不命中() {
        val t = template(goal = "打开美团点一份外卖")
        assertNull(TemplateMatcher.match("把手机系统语言改成英文", listOf(t)))
    }

    @Test
    fun match_被禁用模板_被过滤() {
        val t = template(goal = "打开微信给张三发你好", enabled = false)
        assertNull(TemplateMatcher.match("打开微信给张三发你好", listOf(t)))
    }

    @Test
    fun match_连续失败三次_被过滤() {
        val t = template(goal = "打开支付宝转账", failedStreak = 3)
        assertNull(TemplateMatcher.match("打开支付宝转账", listOf(t)))
    }

    @Test
    fun match_多个候选_取相似度最高() {
        val far = template(goal = "查询天气")
        val close = template(goal = "打开微信给张三发你好")
        val hit = TemplateMatcher.match("用微信给张三发你好", listOf(far, close))
        assertNotNull(hit)
        assertEquals(close.id, hit?.id)
    }

    @Test
    fun match_空白goal_返回null() {
        assertNull(TemplateMatcher.match("", listOf(template(goal = "任意"))))
        assertNull(TemplateMatcher.match("   ", listOf(template(goal = "任意"))))
    }

    @Test
    fun match_空模板列表_返回null() {
        assertNull(TemplateMatcher.match("打开微信", emptyList()))
    }

    @Test
    fun match_命中模板_成功次数与失败连续_随原样保留() {
        val t = template(goal = "导出聊天记录", failedStreak = 2)
        val hit = TemplateMatcher.match("帮我导出聊天记录", listOf(t.copy(executionCount = 5, successCount = 3)))
        assertNotNull(hit)
        assertTrue("应保留 executionCount", hit!!.executionCount == 5)
        assertTrue("应保留 successCount", hit.successCount == 3)
        assertEquals(2, hit.failedStreak)
    }
}
