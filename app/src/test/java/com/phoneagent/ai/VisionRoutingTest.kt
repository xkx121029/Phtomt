package com.phoneagent.ai

import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.core.ai.MainVisionMode
import com.phoneagent.core.ai.VisionRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主模型识图三态判定的回归用例（唯一定义点 VisionRouting）。
 *
 * 覆盖三件事：
 * 1. AUTO/ON/OFF × 探测 true/false/null 的九宫格里，AUTO 全听探测、ON/OFF 全听用户；
 * 2. 脏 key（空/历史值/大小写/空白）一律回落 AUTO，不让设置读坏把发图关死；
 * 3. probedVisionOf 按归一化地址 + 模型名命中，尾斜杠不误伤，查不到返回 null。
 */
class VisionRoutingTest {

    // ---- resolve：三态 × 探测结果 ----

    @Test
    fun resolve_AUTO_全听探测() {
        assertTrue(VisionRouting.resolve(MainVisionMode.AUTO, true))
        assertFalse(VisionRouting.resolve(MainVisionMode.AUTO, false))
        // 没测过/没测出来 → 等同不发图：宁可少发，不对着不支持图片的模型发图报错
        assertFalse(VisionRouting.resolve(MainVisionMode.AUTO, null))
    }

    @Test
    fun resolve_ON_用户强制开_探测不参与() {
        assertTrue(VisionRouting.resolve(MainVisionMode.ON, true))
        assertTrue(VisionRouting.resolve(MainVisionMode.ON, false))
        assertTrue(VisionRouting.resolve(MainVisionMode.ON, null))
    }

    @Test
    fun resolve_OFF_用户强制关_探测不参与() {
        assertFalse(VisionRouting.resolve(MainVisionMode.OFF, true))
        assertFalse(VisionRouting.resolve(MainVisionMode.OFF, false))
        assertFalse(VisionRouting.resolve(MainVisionMode.OFF, null))
    }

    // ---- modeFromKey：脏数据回落 ----

    @Test
    fun modeFromKey_合法值与宽容解析() {
        assertEquals(MainVisionMode.ON, VisionRouting.modeFromKey("ON"))
        assertEquals(MainVisionMode.OFF, VisionRouting.modeFromKey("off"))
        assertEquals(MainVisionMode.AUTO, VisionRouting.modeFromKey(" AUTO "))
    }

    @Test
    fun modeFromKey_脏数据一律回落AUTO() {
        assertEquals(MainVisionMode.AUTO, VisionRouting.modeFromKey(null))
        assertEquals(MainVisionMode.AUTO, VisionRouting.modeFromKey(""))
        assertEquals(MainVisionMode.AUTO, VisionRouting.modeFromKey("true"))
        assertEquals(MainVisionMode.AUTO, VisionRouting.modeFromKey("garbage"))
    }

    // ---- probedVisionOf：模型库查找 ----

    private val catalog = listOf(
        CatalogModel(endpointId = "https://api.example.com/v1", name = "glm-4.6", vision = true),
        CatalogModel(endpointId = "https://api.example.com/v1", name = "glm-4-flash", vision = false),
        CatalogModel(endpointId = "https://api.other.com/v1", name = "glm-4.6", vision = null),
    )

    @Test
    fun probedVisionOf_按归一化地址与模型名命中() {
        assertTrue(VisionRouting.probedVisionOf(catalog, "https://api.example.com/v1", "glm-4.6") == true)
        assertFalse(VisionRouting.probedVisionOf(catalog, "https://api.example.com/v1", "glm-4-flash") == true)
        assertNull(VisionRouting.probedVisionOf(catalog, "https://api.other.com/v1", "glm-4.6"))
    }

    @Test
    fun probedVisionOf_地址尾斜杠不影响命中() {
        assertTrue(VisionRouting.probedVisionOf(catalog, "https://api.example.com/v1/", "glm-4.6") == true)
    }

    @Test
    fun probedVisionOf_查不到或参数为空返回null() {
        assertNull(VisionRouting.probedVisionOf(catalog, "https://api.example.com/v1", "not-in-catalog"))
        assertNull(VisionRouting.probedVisionOf(catalog, "https://api.example.com/v1", " "))
        assertNull(VisionRouting.probedVisionOf(emptyList(), "https://api.example.com/v1", "glm-4.6"))
    }
}
