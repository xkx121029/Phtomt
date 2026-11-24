package com.phoneagent.data.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 调试记录截图清理规则测试。
 *
 * 回归目标：[DebugRecordsStore.save] 改成「先写新图 → 原子替换 records.json → 再清理旧图」，
 * 清理只能删掉当前存档不引用的截图；records.json、非截图文件与本次保留的截图都必须留下。
 * （完整 save 依赖 android.graphics.Bitmap，JVM 单测里跑不了，所以只测可独立验证的清理规则。）
 */
class DebugRecordsStorePruneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `清理时只删除不被当前存档引用的截图`() {
        val dir = tmp.newFolder("debug")
        val keep = "shot_2_0.jpg"
        val shots = listOf(
            "shot_1_0.jpg",   // 上一代留存 → 删
            "shot_0.png",     // 更早版本的 png 残留 → 删
            "shot_2_1.jpg",   // 本次未引用（截图被裁掉） → 删
        )
        (shots + keep).forEach { File(dir, it).writeText("img") }
        File(dir, "records.json").writeText("{}")
        File(dir, "records.json.tmp").writeText("{}")
        File(dir, "notes.txt").writeText("keep me")

        DebugRecordsStore.pruneShotFiles(dir, setOf(keep))

        assertTrue("本次引用的截图必须保留", File(dir, keep).exists())
        assertTrue("存档文件不能被当成截图清掉", File(dir, "records.json").exists())
        assertTrue("非截图文件不受影响", File(dir, "notes.txt").exists())
        shots.forEach { assertFalse("旧截图应被清理：$it", File(dir, it).exists()) }
    }
}
