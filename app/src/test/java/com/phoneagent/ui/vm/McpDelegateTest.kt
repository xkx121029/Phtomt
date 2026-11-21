package com.phoneagent.ui.vm

import com.phoneagent.data.store.McpStore
import com.phoneagent.feature.mcp.McpCheckResult
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.feature.mcp.McpTool
import com.phoneagent.feature.skill.SkillRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 域的文案与校验接线。
 *
 * `addMcpServer` 是「校验失败就把原因原样交给 UI」的唯一出口，`checkMcpServer` 把结构化结果
 * 拼成用户看到的一句话。这里用真的 [McpManager]（`McpRules` 的校验）与 mock 的结果对象
 * 分别钉住这两层：失败原因不漏、成功时的工具计数不错、空白被裁掉后再判重。
 */
class McpDelegateTest {

    private fun realDelegate(manager: McpManager, store: McpStore, scope: kotlinx.coroutines.CoroutineScope) =
        McpDelegate(manager, store, SkillRegistry(), scope, refreshSkills = {})

    @Test
    fun `合法配置_存入并去掉首尾空白`() = runTest {
        val manager = McpManager(transportFor = { error("本组测试不触达传输层") })
        val store = mockk<McpStore>(relaxed = true)
        coEvery { store.load() } returns emptyList()

        val err = realDelegate(manager, store, this).addMcpServer("  fs  ", "  https://example.com/mcp  ", "  tok  ")

        assertEquals("", err)
        val saved = manager.servers.single()
        assertEquals("fs", saved.name)
        assertEquals("https://example.com/mcp", saved.url)
        assertEquals("tok", saved.token)
    }

    @Test
    fun `各类校验失败_返回原因且不落库`() = runTest {
        val manager = McpManager(transportFor = { error("本组测试不触达传输层") })
        val store = mockk<McpStore>(relaxed = true)
        coEvery { store.load() } returns emptyList()
        val d = realDelegate(manager, store, this)

        assertEquals("服务器名不能为空", d.addMcpServer("   ", "https://example.com"))
        assertEquals("服务器名不能包含空格", d.addMcpServer("my server", "https://example.com"))
        assertEquals("服务器地址不能为空", d.addMcpServer("fs", "   "))
        assertEquals("服务器地址须以 http:// 或 https:// 开头", d.addMcpServer("fs", "ftp://example.com"))

        assertTrue("校验失败不应留下任何服务器", manager.servers.isEmpty())
    }

    @Test
    fun `同名忽略大小写判重`() = runTest {
        val manager = McpManager(transportFor = { error("本组测试不触达传输层") })
        val store = mockk<McpStore>(relaxed = true)
        coEvery { store.load() } returns emptyList()
        val d = realDelegate(manager, store, this)

        assertEquals("", d.addMcpServer("FS", "https://a.example.com"))
        assertEquals("服务器名「fs」已存在", d.addMcpServer("fs", "https://b.example.com"))
        assertEquals(1, manager.servers.size)
    }

    @Test
    fun `连接成功_文案带工具数`() = runTest {
        val manager = mockk<McpManager>(relaxed = true)
        coEvery { manager.check("s") } returns
            McpCheckResult(true, "ok", listOf(McpTool("a"), McpTool("b"), McpTool("c")))
        val d = McpDelegate(manager, mockk(relaxed = true), SkillRegistry(), this, refreshSkills = {})

        assertEquals("连接正常，枚举到 3 个工具", d.checkMcpServer("s"))
    }

    @Test
    fun `连接失败_文案带底层原因`() = runTest {
        val manager = mockk<McpManager>(relaxed = true)
        coEvery { manager.check("s") } returns McpCheckResult(false, "连接被拒绝")
        val d = McpDelegate(manager, mockk(relaxed = true), SkillRegistry(), this, refreshSkills = {})

        assertEquals("连接失败：连接被拒绝", d.checkMcpServer("s"))
    }
}
