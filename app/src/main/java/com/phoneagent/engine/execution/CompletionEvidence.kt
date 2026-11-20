package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot

/**
 * 完成证据分层（纯逻辑，可单测）。
 *
 * 对应真实失败：看到 toast「已加入播放队列」和绿勾就宣判完成，实际没有核对状态。
 * 提示语是**过渡态**：出现一两秒就消失；真正的完成状态是**持久态**（列表里出现该条目、
 * 播放控制显示该内容、页面上留下记录）。
 *
 * 这里的判据只做一件事：把"宣称完成时依据的那段文字"再观察一次，看它是不是已经消失。
 * 消失 → 那只是提示语，不能作为完成依据；仍在 → 至少是可核验的持久状态，放行。
 */
internal object CompletionEvidence {

    /** 完成前最多因"依据是瞬时提示"拒绝几次（防死循环） */
    const val MAX_RECHECK_REJECTIONS = 1

    /** 从完成说明里最多取几条被引用的证据（说明里引号内容过多时只取前几条，提示文案才不至于失控） */
    private const val MAX_CITED_FRAGMENTS = 3

    /**
     * 典型的瞬时提示措辞。只用于判断"刚刚出现又消失的这段文字是不是提示语"，
     * 不参与"任务是否完成"的判定——判定口径仍以页面持久状态与判分器为准。
     */
    private val HINT_MARKERS = listOf(
        "已加入", "已添加", "已保存", "已收藏", "已点赞", "已关注", "已发送",
        "已提交", "已开启", "已关闭", "已复制", "操作成功", "设置成功",
    )

    /**
     * 完成说明里被引号引用的文字片段：AI 引用的往往就是它"看到的那条提示/那条记录"。
     * 支持「」『』“”"" 四种引号，长度上限 40 字，避免把整段说明当成证据。
     */
    private val CITED_PATTERN = Regex("「([^」]{1,40})」|『([^』]{1,40})』|“([^”]{1,40})”|\"([^\"]{1,40})\"")

    /** 抽出完成说明里被引用的证据文字（去重，最多 [MAX_CITED_FRAGMENTS] 条） */
    fun citedEvidence(summary: String): List<String> =
        CITED_PATTERN.findAll(summary)
            .mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CITED_FRAGMENTS)
            .toList()

    /**
     * 再观察一次就消失的提示语。
     *
     * 只看 [HINT_MARKERS]：一段普通正文（歌名、题目、列表条目）不会因为再读一次就消失，
     * 只有 toast/snackbar 这类过渡态会。返回空 = 页面状态是持久的，不拦截完成。
     */
    fun vanishedHints(current: ScreenSnapshot, fresh: ScreenSnapshot): List<String> {
        val before = current.toAiText()
        val after = fresh.toAiText()
        return HINT_MARKERS.filter { before.contains(it) && !after.contains(it) }.take(2)
    }

    /** 完成说明里引用的证据文字，在被引用时还在页面上、再观察一次已消失 */
    fun vanishedCitedEvidence(summary: String, current: ScreenSnapshot, fresh: ScreenSnapshot): List<String> {
        val before = current.toAiText()
        val after = fresh.toAiText()
        return citedEvidence(summary).filter { before.contains(it) && !after.contains(it) }.take(2)
    }

    /**
     * 引用了"提示语措辞"、但当前页面上根本没有这段文字的证据。
     *
     * 覆盖"提示早就消失、AI 凭记忆里那条 toast 报完成"的情况：文案像提示语（已加入/操作成功…）
     * 且当前树里找不到，就不能当作完成依据。只对**提示语措辞**生效，普通正文（歌名/订单号）
     * 找不到时不算问题——无障碍树本来就可能漏掉部分文本。
     */
    fun unverifiableHintCitations(summary: String, current: ScreenSnapshot): List<String> {
        val text = current.toAiText()
        return citedEvidence(summary)
            .filter { frag -> HINT_MARKERS.any { frag.contains(it) } && !text.contains(frag) }
            .take(2)
    }
}
