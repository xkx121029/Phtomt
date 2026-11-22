package com.phoneagent.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 启动窗口底色 ↔ 页面底色的一致性。
 *
 * ## 为什么要钉这条
 * 冷启动时**第一块被画出来的画面是系统窗口，不是 Compose**：进程还没起来，系统就照 manifest 主题的
 * `windowBackground` 铺了一张图（Android 12+ 的 splash、更早版本的 windowPreview）。那一刻读不到任何
 * 设置，所以这张图的颜色只能来自资源，而页面底色来自 `AppColors.surfaceBase`。
 * 两边一旦不一致，用户看到的就是「先亮一下再进界面」——这正是性能审计里记的「白闪」。
 *
 * 所以这里钉的不是某个十六进制值，而是**资源与色板必须相等**这层关系：
 * 改色板底色而忘了改 `colors.xml`，或者把主题退回 `@android:color/white`，这两条都会红。
 */
class WindowBackgroundTest {

    /** 仓库根：从工作目录向上找到含 `app/src/main/java/com/phoneagent` 的目录 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/phoneagent").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未找到仓库根目录（工作目录=${File("").absolutePath}）")
    }

    private fun resource(file: String): String {
        val f = File(repoRoot(), file)
        assertTrue("资源文件不存在：$file", f.isFile)
        return f.readText()
    }

    /** 取某个 colors.xml 里某个颜色的字面值（`#RRGGBB` → 0xFFRRGGBB） */
    private fun colorResource(file: String, name: String): Int {
        val match = Regex("<color name=\"$name\">#([0-9A-Fa-f]{6})</color>")
            .find(resource(file))
        assertTrue("$file 里没有 $name 的颜色字面值", match != null)
        val rgb = match!!.groupValues[1].toLong(16)
        return (0xFF000000L or rgb).toInt()
    }

    private fun windowBackgroundItem(file: String): String {
        val match = Regex("<item name=\"android:windowBackground\">([^<]+)</item>")
            .find(resource(file))
        assertTrue("$file 里没有 windowBackground", match != null)
        return match!!.groupValues[1].trim()
    }

    @Test
    fun `启动窗口底色等于默认皮肤在浅色系统下的页面底色`() {
        assertEquals(
            "values/colors.xml 的 app_window_background 必须等于 LightAppColors.surfaceBase",
            LightAppColors.surfaceBase.toArgb(),
            colorResource("app/src/main/res/values/colors.xml", "app_window_background"),
        )
    }

    @Test
    fun `启动窗口底色等于默认皮肤在深色系统下的页面底色`() {
        assertEquals(
            "values-night/colors.xml 的 app_window_background 必须等于 DarkAppColors.surfaceBase",
            DarkAppColors.surfaceBase.toArgb(),
            colorResource("app/src/main/res/values-night/colors.xml", "app_window_background"),
        )
    }

    @Test
    fun `两套主题都引用同一个窗口底色资源而不是系统纯白纯黑`() {
        listOf("app/src/main/res/values/themes.xml", "app/src/main/res/values-night/themes.xml")
            .forEach { file ->
                assertEquals(
                    "$file 的 windowBackground 应引用 @color/app_window_background",
                    "@color/app_window_background",
                    windowBackgroundItem(file),
                )
            }
    }

    @Test
    fun `状态栏图标按底色亮度取反`() {
        // 每一套浅色色板都必须判成「浅底 → 深色图标」
        listOf(
            LightAppColors,
            LightContrastAppColors,
            LightOchreAppColors,
            LightInkBlueAppColors,
            LightPlumAppColors,
            GraphiteLightAppColors,
        ).forEach {
            assertTrue("浅色色板 ${it.surfaceBase} 应判为浅底", isLightSurface(it.surfaceBase))
        }
        // 深色同理。石墨单独列在这里，是因为它同时有浅色与深色两套：
        // 皮肤不是「深浅轴」，界面深浅永远跟着系统走，图标也必须跟着**底色**走
        listOf(
            DarkAppColors,
            DarkContrastAppColors,
            DarkOchreAppColors,
            DarkInkBlueAppColors,
            DarkPlumAppColors,
            GraphiteDarkAppColors,
        ).forEach {
            assertFalse("深色色板 ${it.surfaceBase} 应判为深底", isLightSurface(it.surfaceBase))
        }
    }
}
