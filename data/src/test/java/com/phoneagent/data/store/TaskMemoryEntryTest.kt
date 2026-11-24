package com.phoneagent.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务记忆纯函数单测。
 * 目标是：用户要求与已验证做法只增不重、超量时丢最旧的、空白输入不产生脏条目。
 * 不测 DataStore 部分（依赖 Android Context），那部分靠集成验证。
 */
class TaskMemoryEntryTest {

    private val TASK = "帮我在美团点一份黄焖鸡米饭"

    /** 与引擎里的创建方式保持一致：任务原文既是目标，也是第一条用户要求 */
    private fun entry() = TaskMemoryEntry(
        taskId = 1L,
        taskName = TASK,
        goal = TASK,
        requirements = listOf(TASK),
        createdAt = 1L,
        updatedAt = 1L,
    )

    // ---- withRequirement ----

    @Test
    fun `追加用户要求_按追加顺序保留`() {
        val e = entry().withRequirement("用我妈的账号登录").withRequirement("不要加辣")
        assertEquals(listOf(TASK, "用我妈的账号登录", "不要加辣"), e.requirements)
    }

    @Test
    fun `追加用户要求_完全相同则跳过`() {
        val e = entry().withRequirement("不要加辣").withRequirement("不要加辣")
        assertEquals(2, e.requirements.size)
    }

    @Test
    fun `追加用户要求_只是换了说法也跳过`() {
        val e = entry().withRequirement("不要加辣椒")
        val again = e.withRequirement("不要加辣椒。")
        assertEquals(e.requirements, again.requirements)
    }

    /**
     * 回归：措辞与任务原文相近的追加指令必须被保留。
     * 这两句 bigram 相似度恰好 0.5，早期用模糊去重会被判为重复而静默丢掉，等于没记住用户中途的要求。
     */
    @Test
    fun `追加用户要求_与任务原文相近也不能被丢掉`() {
        val e = entry().withRequirement("帮我再点一份黄焖鸡米饭")
        assertTrue(e.requirements.contains("帮我再点一份黄焖鸡米饭"))
        assertEquals(2, e.requirements.size)
    }

    @Test
    fun `追加用户要求_空白输入被忽略`() {
        val e = entry()
        assertSame(e, e.withRequirement(""))
        assertSame(e, e.withRequirement("   "))
    }

    @Test
    fun `追加用户要求_首尾空白被裁掉`() {
        val e = entry().withRequirement("  用我妈的账号登录  ")
        assertEquals("用我妈的账号登录", e.requirements.last())
    }

    @Test
    fun `追加用户要求_超过八条丢最旧的`() {
        val texts = listOf(
            "要求甲第一个", "要求乙第二个", "要求丙第三个", "要求丁第四个",
            "要求戊第五个", "要求己第六个", "要求庚第七个", "要求辛第八个", "要求壬第九个",
        )
        // 首条是任务原文，再追加 9 条不同文本（共 10 条），最终应只剩最近 8 条
        val e = texts.fold(entry()) { acc, t -> acc.withRequirement(t) }
        assertEquals(8, e.requirements.size)
        assertTrue(TASK !in e.requirements)
        assertEquals("要求乙第二个", e.requirements.first())
        assertEquals("要求壬第九个", e.requirements.last())
    }

    // ---- withMethod ----

    @Test
    fun `追加完成方法_去重且保留顺序`() {
        val e = entry().withMethod("第1步: 打开应用").withMethod("第2步: 点击搜索")
        assertEquals(listOf("第1步: 打开应用", "第2步: 点击搜索"), e.methods)
        assertEquals(e.methods, e.withMethod("第1步: 打开应用").methods)
    }

    @Test
    fun `追加完成方法_空白输入被忽略`() {
        val e = entry()
        assertSame(e, e.withMethod(""))
        assertSame(e, e.withMethod("  "))
    }

    @Test
    fun `追加完成方法_超过十条丢最旧的`() {
        // 用语义互不相似的步骤，避免被去重规则合并掉，测的才是裁剪逻辑
        val steps = listOf(
            "打开应用", "搜索商品", "选择规格", "加入购物车", "填写收货地址", "选择支付方式",
            "输入验证码", "确认下单", "提交订单", "查看订单详情", "返回首页",
        )
        val e = steps.fold(entry()) { acc, s -> acc.withMethod(s) }
        assertEquals(10, e.methods.size)
        assertEquals("搜索商品", e.methods.first())
        assertEquals("返回首页", e.methods.last())
    }

    // ---- withStatus ----

    @Test
    fun `写入状态_同时更新已完成步数`() {
        val e = entry().withStatus(TaskMemoryEntry.STATUS_SUCCESS, 6)
        assertEquals(TaskMemoryEntry.STATUS_SUCCESS, e.status)
        assertEquals(6, e.completedSteps)
    }

    @Test
    fun `写入状态_推进更新时间以便乱序落库时判定新旧`() {
        val e = entry().withStatus(TaskMemoryEntry.STATUS_FAILED, 3)
        assertTrue(e.updatedAt >= e.createdAt)
    }

    // ---- statusLabel ----

    @Test
    fun `状态文案_覆盖全部终态`() {
        assertEquals("进行中", entry().statusLabel())
        assertEquals("已完成", entry().withStatus(TaskMemoryEntry.STATUS_SUCCESS, 1).statusLabel())
        assertEquals("未完成", entry().withStatus(TaskMemoryEntry.STATUS_FAILED, 1).statusLabel())
        assertEquals("已中断", entry().withStatus(TaskMemoryEntry.STATUS_ABORTED, 1).statusLabel())
    }
}