package com.phoneagent.engine.execution

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完成证据分层：提示语（toast/绿勾）是过渡态，不能作为完成依据。
 *
 * 对应真机失败：看到「已加入播放队列」的 toast 和绿勾就收尾，实际状态从未核对。
 */
class CompletionEvidenceTest {

    private fun snap(vararg texts: String): ScreenSnapshot = ScreenSnapshot(
        packageName = "com.example",
        elements = texts.mapIndexed { i, t ->
            UiElement(
                index = i,
                className = "android.widget.TextView",
                type = "Text",
                text = t,
                x = 100,
                y = 100 + i * 50,
                left = 0,
                top = 100 + i * 50,
                right = 500,
                bottom = 140 + i * 50,
            )
        },
    )

    @Test
    fun 抽出完成说明里被引用的证据() {
        val cited = CompletionEvidence.citedEvidence("已把「晴天」加入队列，看到“已加入播放队列”")
        assertEquals(listOf("晴天", "已加入播放队列"), cited)
    }

    @Test
    fun 没有引号时没有证据文字() {
        assertTrue(CompletionEvidence.citedEvidence("任务已完成").isEmpty())
    }

    @Test
    fun 再观察就消失的提示语被判为瞬时证据() {
        val current = snap("已加入播放队列", "晴天", "下一首")
        val fresh = snap("晴天", "下一首")
        val vanished = CompletionEvidence.vanishedHints(current, fresh)
        assertEquals(listOf("已加入"), vanished)
    }

    @Test
    fun 持久状态不算瞬时证据() {
        val current = snap("播放队列：晴天", "下一首")
        val fresh = snap("播放队列：晴天", "下一首")
        assertTrue(CompletionEvidence.vanishedHints(current, fresh).isEmpty())
    }

    @Test
    fun 引用文字在再观察时消失同样判为瞬时证据() {
        val current = snap("已加入播放队列")
        val fresh = snap("我的音乐")
        val vanished = CompletionEvidence.vanishedCitedEvidence("看到「已加入播放队列」", current, fresh)
        assertEquals(listOf("已加入播放队列"), vanished)
    }

    @Test
    fun 提示语措辞在当前页面根本不存在时不可核验() {
        val current = snap("我的音乐", "下一首")
        val bad = CompletionEvidence.unverifiableHintCitations("看到「已加入播放队列」", current)
        assertEquals(listOf("已加入播放队列"), bad)
    }

    @Test
    fun 普通正文不存在时不算问题() {
        // 歌名这类正文可能被无障碍树漏掉，不能因此拦截完成
        val current = snap("我的音乐")
        assertTrue(CompletionEvidence.unverifiableHintCitations("播放了「晴天」", current).isEmpty())
    }
}
