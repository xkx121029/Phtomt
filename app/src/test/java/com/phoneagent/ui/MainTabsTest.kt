package com.phoneagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底部 Tab 合成与持久化编解码单测（Superagent 融合 Phase 7）。
 *
 * [MainTabs.visibleTabs] 是界面渲染唯一入口：脏 id / 缺 Tab / 重复 id / 钉住兜底都在这里收敛；
 * [TabPrefsCodec] 是 AppSettings.tab_order / tab_visible 的分号序列化，round-trip 必须无损。
 */
class MainTabsTest {

    private val allIds = MainTabs.all.map { it.id }

    private fun idsOf(order: List<String>, visible: List<String>) =
        MainTabs.visibleTabs(order, visible).map { it.id }

    // ==================== visibleTabs 合成 ====================

    @Test
    fun `默认配置产出全部Tab且顺序一致`() {
        assertEquals(allIds, idsOf(allIds, allIds))
        assertEquals(listOf("home", "chat", "coding", "agent", "memory", "settings"), idsOf(allIds, allIds))
    }

    @Test
    fun `脏id被滤掉_缺省Tab按默认顺序补齐`() {
        val result = idsOf(
            order = listOf("home", "bogus_tab", "coding", "settings"),
            visible = allIds,
        )
        // bogus 滤掉；order 里没有的 chat/agent/memory 按默认顺序追加在尾部
        assertEquals(listOf("home", "coding", "settings", "chat", "agent", "memory"), result)
    }

    @Test
    fun `顺序串重复id去重`() {
        val result = idsOf(
            order = listOf("home", "chat", "chat", "agent"),
            visible = allIds,
        )
        assertEquals(listOf("home", "chat", "agent", "coding", "memory", "settings"), result)
    }

    @Test
    fun `钉住Tab即使配置为隐藏也强制在场`() {
        val result = idsOf(order = allIds, visible = listOf("chat"))
        // 用户只勾了 chat 可见，home/settings 不可隐藏
        assertEquals(listOf("home", "chat", "settings"), result)
    }

    @Test
    fun `空输入只保留钉住Tab`() {
        assertEquals(listOf("home", "settings"), idsOf(order = emptyList(), visible = emptyList()))
    }

    @Test
    fun `可见串里的脏id不影响结果`() {
        val result = idsOf(order = allIds, visible = listOf("chat", "junk", "coding"))
        assertEquals(listOf("home", "chat", "coding", "settings"), result)
    }

    // ==================== TabPrefsCodec round-trip ====================

    @Test
    fun `编解码round_trip无损`() {
        val ids = listOf("home", "chat", "coding", "agent", "memory", "settings")
        assertEquals(ids, TabPrefsCodec.decode(TabPrefsCodec.encode(ids)))
        val shuffled = listOf("agent", "home", "memory", "settings")
        assertEquals(shuffled, TabPrefsCodec.decode(TabPrefsCodec.encode(shuffled)))
    }

    @Test
    fun `默认串与全量Tab定义同源`() {
        val ids = TabPrefsCodec.decode(TabPrefsCodec.DEFAULT)
        assertEquals(allIds, ids)
        assertEquals(TabPrefsCodec.DEFAULT, TabPrefsCodec.encode(ids))
    }

    @Test
    fun `解码null回退默认`() {
        assertEquals(allIds, TabPrefsCodec.decode(null))
    }

    @Test
    fun `解码剔除空白与空段`() {
        assertEquals(listOf("home", "chat", "coding"), TabPrefsCodec.decode("home; chat ;;coding"))
        // 空串按原内联语义返回空列表（不回退默认），兜底交给 visibleTabs 的钉住逻辑
        assertEquals(emptyList<String>(), TabPrefsCodec.decode(""))
    }
}
