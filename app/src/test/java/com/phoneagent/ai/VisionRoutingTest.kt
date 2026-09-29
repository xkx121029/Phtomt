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

    // ---- route：三条视觉链路各走不走 ----

    /** 默认取"最保守"的一档：非复杂页、云端就绪、未开混合、外挂开启、主模型读不到图 */
    private fun routeOf(
        complexPage: Boolean = false,
        cloudReady: Boolean = true,
        smartRoute: Boolean = false,
        externalEnabled: Boolean = true,
        mainGetsImage: Boolean = false,
    ) = VisionRouting.route(
        VisionRouting.RouteInput(
            complexPage = complexPage,
            cloudReady = cloudReady,
            smartRoute = smartRoute,
            externalEnabled = externalEnabled,
            mainGetsImage = mainGetsImage,
            // 跳过描述这一路的差异由 wantVisionDesc 单独覆盖，这里固定取默认（跳过）
            skipDescWhenMainSees = true,
        ),
    )

    @Test
    fun route_未开混合路由_端侧与云端都走() {
        val r = routeOf(smartRoute = false)

        assertTrue(r.useOnDevice3b)
        assertTrue(r.cloudVision)
    }

    @Test
    fun route_混合路由_简单页交给端侧3B_把云端额度留给复杂页() {
        val r = routeOf(complexPage = false, smartRoute = true)

        assertTrue(r.useOnDevice3b)
        assertFalse(r.cloudVision)
    }

    @Test
    fun route_混合路由_复杂页交给云端_跳过端侧3B() {
        val r = routeOf(complexPage = true, smartRoute = true)

        assertFalse(r.useOnDevice3b)
        assertTrue(r.cloudVision)
    }

    @Test
    fun route_混合路由_复杂页但云端不可用_必须回落到端侧3B() {
        // 两条路都断会让 AI 面对一个完全不可见的页面，所以"云端不可用"必须能把 3B 拉回来
        val r = routeOf(complexPage = true, cloudReady = false, smartRoute = true)

        assertTrue(r.useOnDevice3b)
        assertFalse(r.cloudVision)
    }

    @Test
    fun route_混合路由_未启用外挂且简单页_只能靠云端() {
        val r = routeOf(complexPage = false, smartRoute = true, externalEnabled = false)

        assertFalse(r.useOnDevice3b)
        assertTrue(r.cloudVision)
    }

    @Test
    fun route_未启用外挂_端侧3B始终不参与() {
        assertFalse(routeOf(externalEnabled = false).useOnDevice3b)
        assertFalse(routeOf(complexPage = true, externalEnabled = false).useOnDevice3b)
    }

    /** 只有"是否跳过文字描述"不同，单独构造 */
    private fun wantVisionDesc(mainGetsImage: Boolean, skipWhenMainSees: Boolean): Boolean {
        val input = VisionRouting.RouteInput(
            complexPage = false,
            cloudReady = true,
            smartRoute = false,
            externalEnabled = true,
            mainGetsImage = mainGetsImage,
            skipDescWhenMainSees = skipWhenMainSees,
        )
        return VisionRouting.route(input).wantVisionDesc
    }

    @Test
    fun route_主模型能直读且开了跳过描述_不再把同一张图转成文字() {
        assertFalse(wantVisionDesc(mainGetsImage = true, skipWhenMainSees = true))
    }

    @Test
    fun route_主模型能直读但没开跳过描述_仍要文字描述() {
        assertTrue(wantVisionDesc(mainGetsImage = true, skipWhenMainSees = false))
    }

    @Test
    fun route_主模型读不到图_一律需要文字描述() {
        assertTrue(wantVisionDesc(mainGetsImage = false, skipWhenMainSees = true))
    }

    // ---- sourceOf：本轮视觉结果的来源归属 ----

    private fun source(
        externalUsed: Boolean = false,
        mainGetsImage: Boolean = false,
        hasDescription: Boolean = false,
        cloudVision: Boolean = false,
    ) = VisionRouting.sourceOf(externalUsed, mainGetsImage, hasDescription, cloudVision)

    @Test
    fun sourceOf_外挂3B优先于主模型直读() {
        // 执行链路里外挂 3B 先跑，它的框选坐标是 hint 定位的第一优先来源
        assertEquals(
            VisionRouting.Source.EXTERNAL,
            source(externalUsed = true, mainGetsImage = true, hasDescription = true, cloudVision = true),
        )
    }

    @Test
    fun sourceOf_主模型直读优先于文字描述来源() {
        assertEquals(
            VisionRouting.Source.MAIN_DIRECT,
            source(mainGetsImage = true, cloudVision = true),
        )
    }

    @Test
    fun sourceOf_有描述时按走的是云端还是端侧归属() {
        assertEquals(VisionRouting.Source.CLOUD, source(hasDescription = true, cloudVision = true))
        assertEquals(VisionRouting.Source.LOCAL, source(hasDescription = true))
    }

    @Test
    fun sourceOf_没有任何视觉产出_归为无() {
        assertEquals(VisionRouting.Source.NONE, source(cloudVision = true))
    }

    // ---- modelOf：来源到模型名 ----

    @Test
    fun modelOf_各来源给出对应模型名() {
        assertEquals("Qwen2.5-VL-3B (端侧)", VisionRouting.modelOf(VisionRouting.Source.EXTERNAL, "视觉模型", "主模型"))
        assertEquals("视觉模型", VisionRouting.modelOf(VisionRouting.Source.CLOUD, "视觉模型", "主模型"))
        assertEquals("ML Kit 中文OCR", VisionRouting.modelOf(VisionRouting.Source.LOCAL, "视觉模型", "主模型"))
        assertEquals("主模型", VisionRouting.modelOf(VisionRouting.Source.MAIN_DIRECT, "视觉模型", "主模型"))
        assertEquals("", VisionRouting.modelOf(VisionRouting.Source.NONE, "视觉模型", "主模型"))
    }
}

