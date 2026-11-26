package com.phoneagent.coding.workspaces

import com.phoneagent.coding.api.CodingMessageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话登记表状态机的单测：只钉 [CodingSessionRegistry] 的转移规则，不碰磁盘、不碰 Android。
 *
 * 四条路径各有用例：创建（进表头 + 成为活动会话）、复用（同 id 不再重复、标题不被覆盖）、
 * 失效（悬空指针判成「没有活动会话」）、清理（删会话时指针该清就清、不该清就不动）。
 * 每条断言都对着「实现改坏就会红」的行为，而不是把实现抄一遍。
 */
class CodingSessionRegistryTest {

    private fun meta(id: String, title: String = "会话$id", updatedAt: Long = 0L, count: Int = 0) =
        CodingWorkspaces.SessionMeta(
            id = id,
            title = title,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            count = count,
        )

    private fun user(text: String) = CodingMessageView(key = "k-$text", role = "user", text = text)

    // ---------- 创建 ----------

    @Test
    fun `新建会话进表头并成为活动会话`() {
        val state = CodingWorkspaces.WorkspaceState(
            activeSessionId = "a",
            sessions = listOf(meta("a", updatedAt = 1), meta("b", updatedAt = 2)),
        )

        val fresh = CodingSessionRegistry.newSession(now = 1_000L, id = "u_new")
        val next = CodingSessionRegistry.openCreated(state, fresh)

        assertEquals(listOf("u_new", "a", "b"), next.sessions.map { it.id })
        assertEquals("u_new", next.activeSessionId)
        assertEquals(1_000L, fresh.createdAt)
        assertEquals(1_000L, fresh.updatedAt)
        assertEquals(0, fresh.count)
        assertEquals("新会话", fresh.title)
    }

    @Test
    fun `创建只产出新状态不就地改旧状态`() {
        val state = CodingWorkspaces.WorkspaceState(activeSessionId = "a", sessions = listOf(meta("a")))

        CodingSessionRegistry.openCreated(state, CodingSessionRegistry.newSession(1_000L, "u_new"))

        assertEquals(listOf("a"), state.sessions.map { it.id })
        assertEquals("a", state.activeSessionId)
    }

    @Test
    fun `新会话 id 缺省时每次都不重样`() {
        val first = CodingSessionRegistry.newSession(1_000L)
        val second = CodingSessionRegistry.newSession(1_000L)

        assertTrue(first.id.startsWith("u_"))
        assertEquals(first.id.length, 14)
        assertTrue(first.id != second.id)
    }

    // ---------- 复用 ----------

    @Test
    fun `同一会话再落盘不重复且标题与活动指针不被冲掉`() {
        val state = CodingWorkspaces.WorkspaceState(
            activeSessionId = "s1",
            sessions = listOf(meta("s1", title = "用户改过的标题", updatedAt = 100L, count = 2)),
        )

        val next = CodingSessionRegistry.upsert(
            state = state,
            sessionId = "s1",
            messages = listOf(user("接着聊"), CodingMessageView(key = "k2", role = "assistant", text = "好")),
            now = 500L,
        )

        assertEquals(1, next.sessions.size)
        assertEquals(listOf("s1"), next.sessions.map { it.id })
        assertEquals("用户改过的标题", next.sessions.single().title)
        assertEquals(2, next.sessions.single().count)
        assertEquals(500L, next.sessions.single().updatedAt)
        assertEquals("s1", next.activeSessionId)
    }

    @Test
    fun `首次落盘的会话按第一条用户消息命名并进表头`() {
        val longText = "把编码页的会话登记表搬到新包，顺手补四条路径的单测"
        val state = CodingWorkspaces.WorkspaceState(
            activeSessionId = "a",
            sessions = listOf(meta("a", updatedAt = 900L)),
        )

        val next = CodingSessionRegistry.upsert(
            state = state,
            sessionId = "b",
            messages = listOf(user(longText), user("  第二句不应该当标题  ")),
            now = 1_000L,
        )

        assertEquals(listOf("b", "a"), next.sessions.map { it.id })
        assertEquals(longText.take(24), next.sessions.first().title)
        assertEquals(2, next.sessions.first().count)
        assertEquals(1_000L, next.sessions.first().createdAt)
    }

    @Test
    fun `复用后该会话按最近活动时间排到表头`() {
        val state = CodingWorkspaces.WorkspaceState(
            activeSessionId = "fresh",
            sessions = listOf(meta("fresh", updatedAt = 900L), meta("old", updatedAt = 100L)),
        )

        val next = CodingSessionRegistry.upsert(
            state = state,
            sessionId = "old",
            messages = listOf(user("回来了")),
            now = 1_000L,
        )

        assertEquals(listOf("old", "fresh"), next.sessions.map { it.id })
    }

    // ---------- 失效 ----------

    @Test
    fun `活动指针悬空时兜底判定为没有活动会话`() {
        val dangling = CodingWorkspaces.WorkspaceState(
            activeSessionId = "gone",
            sessions = listOf(meta("a")),
        )
        val healthy = CodingWorkspaces.WorkspaceState(
            activeSessionId = "a",
            sessions = listOf(meta("a"), meta("b")),
        )
        val none = CodingWorkspaces.WorkspaceState(activeSessionId = null, sessions = listOf(meta("a")))

        assertNull(CodingSessionRegistry.activeSession(dangling))
        assertEquals("a", CodingSessionRegistry.activeSession(healthy))
        assertNull(CodingSessionRegistry.activeSession(none))
    }

    // ---------- 清理 ----------

    @Test
    fun `删掉的正是活动会话时把指针清空`() {
        val state = CodingWorkspaces.WorkspaceState(
            activeSessionId = "a",
            sessions = listOf(meta("a"), meta("b")),
        )

        val next = CodingSessionRegistry.remove(state, "a")

        assertNull(next.activeSessionId)
        assertEquals(listOf("b"), next.sessions.map { it.id })
    }

    @Test
    fun `删掉非活动会话时活动指针不动`() {
        val state = CodingWorkspaces.WorkspaceState(
            activeSessionId = "a",
            sessions = listOf(meta("a"), meta("b")),
        )

        val next = CodingSessionRegistry.remove(state, "b")

        assertEquals("a", next.activeSessionId)
        assertEquals(listOf("a"), next.sessions.map { it.id })
    }
}
