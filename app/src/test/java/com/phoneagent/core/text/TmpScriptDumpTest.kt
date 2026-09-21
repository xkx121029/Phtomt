package com.phoneagent.core.text

import com.phoneagent.feature.browser.BrowserScripts
import java.io.File
import org.junit.Test

/** 临时：把插值生成后的浏览器脚本导出到磁盘，供 node 做语法检查（检查完即删） */
class TmpScriptDumpTest {
    @Test
    fun dump() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hpa_scripts")
        dir.mkdirs()
        File(dir, "read.js").writeText(BrowserScripts.READ)
    }
}