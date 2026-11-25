package com.phoneagent.coding.workspaces

import com.phoneagent.coding.api.CodingMessageView
import java.util.UUID

/**
 * 会话登记表的纯状态机：只做「工作区状态 → 工作区状态」的转移，不碰磁盘、不碰 Android。
 *
 * 落盘（JSON 读写、目录指针）留在 [CodingWorkspaces]，判定「会话怎么建、怎么复用、
 * 指针失效后怎么办、删掉后留什么」留在这里——所以这部分能在 JVM 单测里直接钉住。
 *
 * 语义与改造前逐条一致（从 [CodingWorkspaces.saveMessages] / `deleteSession` 原样抽出），
 * 抽出来只是为了让它们可测，不是为了改行为。
 */
object CodingSessionRegistry {

    /** 会话 id 的随机段长度（hex 字符数，不含 `u_` 前缀） */
    private const val SESSION_ID_LENGTH = 12

    /** 会话标题取第一条用户消息的前若干个字符 */
    private const val TITLE_MAX_LENGTH = 24

    /**
     * 新建一个会话条目：id 同时用作 host 侧 sessionId
     * （host 收到未知 id 会就地创建，所以不必先去问它）。
     */
    fun newSession(
        now: Long,
        id: String = "u_${UUID.randomUUID().toString().replace("-", "").take(SESSION_ID_LENGTH)}",
    ): CodingWorkspaces.SessionMeta = CodingWorkspaces.SessionMeta(
        id = id,
        title = "新会话",
        createdAt = now,
        updatedAt = now,
        count = 0,
    )

    /** 把一个新建会话放到表头并设为活动会话（首次进入工作区、点「新会话」都走这条）。 */
    fun openCreated(
        state: CodingWorkspaces.WorkspaceState,
        session: CodingWorkspaces.SessionMeta,
    ): CodingWorkspaces.WorkspaceState = state.copy(
        activeSessionId = session.id,
        sessions = listOf(session) + state.sessions,
    )

    /**
     * 活动会话指针的兜底：指向已不在表里的会话时返回 null。
     *
     * 索引文件是外部可改的（用户可能在两个界面间被旧版本写过、也可能手工动过），
     * 所以「指针还在、会话没了」必须当作没有活动会话处理，而不是照着悬空 id 继续用。
     */
    fun activeSession(state: CodingWorkspaces.WorkspaceState): String? =
        state.activeSessionId?.takeIf { id -> state.sessions.any { it.id == id } }

    /**
     * 落盘一轮消息并同步索引：标题取第一条用户消息（最多 24 字），时间与条数按本次刷新。
     *
     * - 会话已存在 → 就地更新（**保留**用户已经改过的标题），并按最近活动时间重排；
     * - 会话不存在 → 当作新会话插到表头（列表顺序与「刚建」一致，不重排）。
     */
    fun upsert(
        state: CodingWorkspaces.WorkspaceState,
        sessionId: String,
        messages: List<CodingMessageView>,
        now: Long,
    ): CodingWorkspaces.WorkspaceState {
        val title = messages.firstOrNull { it.role == "user" }?.text
            ?.trim()?.take(TITLE_MAX_LENGTH)?.takeIf { it.isNotBlank() } ?: "新会话"
        val existing = state.sessions.firstOrNull { it.id == sessionId }
        val base = existing ?: CodingWorkspaces.SessionMeta(sessionId, title, now, now, 0)
        val meta = base.copy(
            title = if (existing == null || existing.title == "新会话") title else existing.title,
            updatedAt = now,
            count = messages.size,
        )
        val sessions = if (existing == null) {
            listOf(meta) + state.sessions
        } else {
            state.sessions.map { if (it.id == sessionId) meta else it }
                .sortedByDescending { it.updatedAt }
        }
        return CodingWorkspaces.WorkspaceState(state.activeSessionId, sessions)
    }

    /**
     * 删掉一个会话：从表里移除；删掉的正是活动会话时**清空指针**，
     * 不留一个指向已删会话的悬空 id（调用方据此新建一个会话）。
     */
    fun remove(
        state: CodingWorkspaces.WorkspaceState,
        sessionId: String,
    ): CodingWorkspaces.WorkspaceState = CodingWorkspaces.WorkspaceState(
        activeSessionId = state.activeSessionId.takeIf { it != sessionId },
        sessions = state.sessions.filterNot { it.id == sessionId },
    )
}
