package com.phoneagent.provider.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 活节点树定位判据（[NodeMatch]）：provider 模块里 `findNode / pickBy` 的取舍部分。
 *
 * 为什么单独测这一层：真机上「点错控件还判成功」和「找不到控件退化成按坐标猜」都只能事后从
 * 录屏里猜原因。判定本身是纯函数，可以直接用一串节点事实把每条规则钉住：
 * 三级降级（viewId → 文字精确 → 文字包含）、候选排序（可点击 → 面积小 → 离提示点近）、
 * 以及"位置只排序、不授权"。
 *
 * 纯 JVM 单测（provider 模块已有 junit）：不需要 Robolectric，也不碰 instrumented。
 */
class NodeMatchTest {

    private fun node(
        texts: List<String> = emptyList(),
        viewId: String? = null,
        clickable: Boolean = true,
        width: Int = 100,
        height: Int = 50,
        centerX: Int = 0,
        centerY: Int = 0,
    ) = NodeMatch.Facts(
        viewId = viewId,
        texts = texts,
        clickable = clickable,
        width = width,
        height = height,
        centerX = centerX,
        centerY = centerY,
    )

    private fun find(nodes: List<NodeMatch.Facts>, viewId: String? = null, label: String? = null, x: Int = -1, y: Int = -1) =
        NodeMatch.find(nodes, viewId, label, x, y)

    // ---- 三级降级 ----

    @Test
    fun viewId命中优先于同文字的其他控件() {
        // 列表里三行都写着「详情」，只有第三行的 id 是目标：id 命中时不许被文字抢先
        val nodes = listOf(
            node(texts = listOf("详情"), viewId = "row_1_detail"),
            node(texts = listOf("详情"), viewId = "row_2_detail"),
            node(texts = listOf("详情"), viewId = "want_this"),
        )
        assertEquals(2, find(nodes, viewId = "want_this", label = "详情"))
    }

    @Test
    fun viewId按后缀匹配且大小写不敏感() {
        val nodes = listOf(
            node(texts = listOf("确定"), viewId = "com.demo:id/btn_ok"),
            node(texts = listOf("确定"), viewId = "com.demo:id/btn_cancel"),
        )
        // 元素树里 AI 看到的是短名（btn_ok），活节点树上是完整资源名
        assertEquals(0, find(nodes, viewId = "btn_ok"))
        assertEquals(0, find(nodes, viewId = "BTN_OK"))
    }

    @Test
    fun viewId没命中才退回文字精确() {
        val nodes = listOf(
            // 这一行文字是「删除全部」：包含「删除」但不是精确等于
            node(texts = listOf("删除全部"), viewId = "bulk_delete"),
            node(texts = listOf("删除"), viewId = "delete_one"),
        )
        assertEquals(
            "精确命中必须排在包含命中之前，否则会点到「删除全部」",
            1,
            find(nodes, label = "删除"),
        )
    }

    @Test
    fun 文字精确没命中才退回文字包含() {
        val nodes = listOf(
            node(texts = listOf("提交订单（3 件）")),
            node(texts = listOf("提交订单后不可修改")),
        )
        // 两条都是"包含"：交给候选排序（面积相同 → 取先出现的）
        assertEquals(0, find(nodes, label = "提交订单"))
    }

    @Test
    fun 三级全不命中返回null_绝不按位置兜底() {
        // 位置只用于排序：给一个"压在某个控件上"的提示点，也不许因此返回任何节点
        val nodes = listOf(node(texts = listOf("设置"), centerX = 540, centerY = 1200))
        assertNull(find(nodes, label = "删除", x = 540, y = 1200))
        assertNull(find(nodes, viewId = "btn_delete"))
        assertNull(find(nodes))
    }

    @Test
    fun 空列表与空线索都不命中() {
        assertNull(find(emptyList(), viewId = "btn_ok", label = "确定"))
        assertNull(find(listOf(node(texts = listOf("确定"))), viewId = "  ", label = " "))
    }

    // ---- 标签口径 ----

    @Test
    fun 派生标签参与匹配() {
        // 可点击容器自身没有文字，标签由后代拼出来（微信聊天列表行）
        val nodes = listOf(
            node(texts = listOf("末影箱 / 测试消息 / 昨天")),
            node(texts = listOf("文件传输助手 / 好")),
        )
        assertEquals(0, find(nodes, label = "末影箱 / 测试消息 / 昨天"))
    }

    @Test
    fun 文字比对大小写不敏感() {
        val nodes = listOf(node(texts = listOf("Delete Account")))
        assertEquals(0, find(nodes, label = "delete account"))
    }

    // ---- 候选排序 ----

    @Test
    fun 可点击优先于不可点击() {
        val nodes = listOf(
            // 不可点击的文字控件先出现，但真正能按的是后面的容器
            node(texts = listOf("立即购买"), clickable = false, width = 400, height = 60),
            node(texts = listOf("立即购买"), clickable = true, width = 1000, height = 200),
        )
        assertEquals(1, find(nodes, label = "立即购买"))
    }

    @Test
    fun 同样可点击时面积小的优先() {
        val nodes = listOf(
            node(texts = listOf("确定"), width = 1000, height = 200),
            node(texts = listOf("确定"), width = 200, height = 100),
        )
        assertEquals("面积小的更贴近视觉位置", 1, find(nodes, label = "确定"))
    }

    @Test
    fun 面积相同时离提示点近的优先() {
        val nodes = listOf(
            node(texts = listOf("详情"), centerX = 100, centerY = 2000),
            node(texts = listOf("详情"), centerX = 500, centerY = 300),
        )
        assertEquals(1, find(nodes, label = "详情", x = 540, y = 340))
        assertEquals(0, find(nodes, label = "详情", x = 80, y = 1980))
    }

    @Test
    fun 没有提示点时不做距离取舍_取先出现的那个() {
        // 无 hint 时距离键恒为 0：并列取先出现的（排序稳定，行为可预期）
        val nodes = listOf(
            node(texts = listOf("详情"), centerX = 100, centerY = 2000),
            node(texts = listOf("详情"), centerX = 500, centerY = 300),
        )
        assertEquals(0, find(nodes, label = "详情"))
    }

    @Test
    fun 可点击与面积两条键的优先级不能颠倒() {
        // 面积小但不可点击的图标 vs 面积大但可点击的容器：能按的那个才该赢
        val nodes = listOf(
            node(texts = listOf("播放"), clickable = false, width = 40, height = 40),
            node(texts = listOf("播放"), clickable = true, width = 400, height = 400),
        )
        assertEquals(1, find(nodes, label = "播放"))
    }

    @Test
    fun best在已筛过的候选里按同一套键取最优() {
        // 落点审计（hitTest）的入口：候选由调用方按坐标筛好，这里只排序
        val nodes = listOf(
            node(texts = listOf("A"), clickable = false),
            node(texts = listOf("B"), clickable = true),
        )
        assertEquals(1, NodeMatch.best(nodes, hint = null))
        assertNull(NodeMatch.best(emptyList(), hint = 10 to 10))
    }
}
