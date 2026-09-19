package com.phoneagent.engine

import com.phoneagent.data.store.AiMemoryEntry
import com.phoneagent.data.store.ProfileEntry

/**
 * 把记忆拼成一段可注入提示词的简报。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测。
 * 逐步骤决策每轮都要带上记忆，必须严格控长：单条截断 + 总量截断，
 * 否则长线任务里记忆会挤掉页面元素树，反而让 AI 看不到当前页面。
 */
object MemoryBrief {

    /** 注入提示词的总长度上限（字符） */
    const val MAX_CHARS = 600

    /** 单条记忆的展示长度上限 */
    private const val PER_ENTRY_CHARS = 60

    /** 用户画像段的长度上限 */
    private const val PROFILE_CHARS = 200

    /** 记忆列表标题（长度参与总量核算） */
    private const val MEMORY_HEADER = "已知记忆：\n"

    /**
     * 拼装记忆简报（只含画像与 AI 记忆）。
     *
     * 异常经验刻意不在这里：它随当前页面变化，而本函数的产物是按任务缓存的，
     * 混进来会导致换页后仍注入旧页面的经验。异常经验由调用方每步单独拼接。
     *
     * @param profile 用户画像（沿用 "key:value" 串）
     * @param memories AI 记忆条目
     * @return 空串表示没有任何记忆可注入，调用方据此完全不加这段（不给 AI 增加负担）
     */
    fun build(
        profile: List<ProfileEntry> = emptyList(),
        memories: List<AiMemoryEntry> = emptyList(),
    ): String {
        val sb = StringBuilder()

        val profileText = profile
            .filter { it.value.isNotBlank() }
            .joinToString("，") { "${it.key}:${it.value}" }
            .take(PROFILE_CHARS)
        if (profileText.isNotBlank()) {
            sb.append("用户画像：").append(profileText)
        }

        // 越常被用到、越新的记忆排前面，超出总量时优先丢弃尾部
        val ordered = memories
            .filter { it.content.isNotBlank() }
            .sortedWith(compareByDescending<AiMemoryEntry> { it.useCount }.thenByDescending { it.updatedAt })
        val lines = ArrayList<String>()
        var used = sb.length
        for (entry in ordered) {
            val line = "- ${entry.content.take(PER_ENTRY_CHARS)}"
            // 首行还要额外容纳列表标题（以及与前文之间的换行）
            val extra = if (lines.isEmpty()) {
                (if (sb.isNotEmpty()) 1 else 0) + MEMORY_HEADER.length + line.length
            } else {
                1 + line.length
            }
            // 放不下就整体停止（而不是跳过这条继续塞后面的），保证总量不超上限
            if (used + extra > MAX_CHARS) break
            lines += line
            used += extra
        }
        if (lines.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(MEMORY_HEADER).append(lines.joinToString("\n"))
        }

        return sb.toString()
    }
}
