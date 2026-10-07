package com.phoneagent.core.ai

/**
 * 主模型「能不能直接读图」的三态判定 —— 全项目唯一定义点。
 *
 * 背景：这件事此前只看设置页的一个手填布尔（`has_vision`）。模型库里明明有**真实请求探测**出来的
 * [CatalogModel.vision]，却在运行时完全没参与，于是"模型能识图但用户没勾开关"就永远不发图。
 *
 * 三态把两件事分开：
 * - [MainVisionMode.AUTO]：探测结果说了算（`true` 才发图）；
 * - [MainVisionMode.ON] / [MainVisionMode.OFF]：用户的明确覆盖，探测不参与。
 */
enum class MainVisionMode(val key: String) {
    /** 按模型库的探测结果决定 */
    AUTO("AUTO"),

    /** 强制把截图交给主模型 */
    ON("ON"),

    /** 强制不发图（只给文字） */
    OFF("OFF"),
}

object VisionRouting {

    /** 脏数据（空串/历史值/手工改坏）一律回落 [MainVisionMode.AUTO]，不让设置读坏了就把发图能力关死 */
    fun modeFromKey(key: String?): MainVisionMode =
        MainVisionMode.entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
            ?: MainVisionMode.AUTO

    /**
     * 三态合流：[mode] 为 [MainVisionMode.AUTO] 时听探测结果；`ON`/`OFF` 时探测不参与。
     *
     * [probedVision] 为 `null` 表示"没测过 / 没测出来"（服务端 400/5xx、网关改写、网络故障），
     * AUTO 下等同"不发图"：宁可少发，也不要对着不支持图片输入的模型发图换来一次 400。
     * 想强发就在设置里切「强制开」。
     */
    fun resolve(mode: MainVisionMode, probedVision: Boolean?): Boolean = when (mode) {
        MainVisionMode.AUTO -> probedVision == true
        MainVisionMode.ON -> true
        MainVisionMode.OFF -> false
    }

    /**
     * 从模型库里找出"当前主模型（地址 + 模型名）"的探测能力。
     *
     * 地址先经 [ModelCatalogCodec.endpointId] 归一化（补协议、去尾斜杠）再比对，
     * 与设置页职责行、端点 id 的口径一致；查不到（模型不在库里 / 用户手填了名字）返回 `null`。
     */
    fun probedVisionOf(catalog: List<CatalogModel>, baseUrl: String, model: String): Boolean? {
        val name = model.trim()
        if (name.isBlank()) return null
        val id = ModelCatalogCodec.endpointId(baseUrl)
        return catalog.firstOrNull { it.endpointId == id && it.name == name }?.vision
    }

    // ---- 一步决策的视觉路由 ----

    /**
     * 路由输入（全部是"已经算好的事实"，不在这里读设置、不碰设备，因此可单测）。
     *
     * [complexPage]：元素树是否稀疏到不足以支撑决策。
     * [cloudReady]：云端视觉配置是否就绪（有视觉模型且没有明确指定只走 LOCAL）。
     * [mainGetsImage]：主模型能否识图 **且** 本轮确实拍到了图。
     */
    data class RouteInput(
        val complexPage: Boolean,
        val cloudReady: Boolean,
        val smartRoute: Boolean,
        val externalEnabled: Boolean,
        val mainGetsImage: Boolean,
        val skipDescWhenMainSees: Boolean,
    )

    /** 路由输出：这一轮视觉怎么走 */
    data class Route(
        /** 端侧 3B 用于本步 */
        val useOnDevice3b: Boolean,
        /** 云端视觉用于本步 */
        val cloudVision: Boolean,
        /** 是否还需要"把截图转成文字描述"这一步 */
        val wantVisionDesc: Boolean,
    )

    /**
     * 视觉路由：决定端侧 3B、云端视觉、文字描述三者这一步各走不走。
     *
     * 这里承载的是原引擎里最难读的一段条件——三条链路互相补位、任何一条被"优化"掉都可能
     * 让 AI 面对一个完全不可见的页面：
     * - 混合路由（[RouteInput.smartRoute]）下，端侧 3B 只接简单任务（框选快、省额度），
     *   复杂任务（元素树稀疏、需强语义理解）交给云端；
     * - 但云端不可用时**必须**回落到 3B，不能两条路都断；
     * - 主模型自己能看图时，把同一张图再转成文字没有收益，可跳过描述
     *   （端侧 3B 框选出的坐标照常保留，那是 hint 定位的第一优先来源）。
     */
    fun route(input: RouteInput): Route = Route(
        // 开启端侧 3B，且（未开混合 或 简单任务 或 复杂任务但云端不可用）
        useOnDevice3b = input.externalEnabled &&
            (!input.smartRoute || !input.complexPage || !input.cloudReady),
        // 云端视觉可用，且（未开混合 或 复杂任务 或 未启用外挂只能靠云端）
        cloudVision = input.cloudReady &&
            (!input.smartRoute || input.complexPage || !input.externalEnabled),
        wantVisionDesc = !(input.mainGetsImage && input.skipDescWhenMainSees),
    )

    // ---- 决策后处理：本轮视觉结果的来源归属（写进决策轨迹，供调试页回看） ----

    /** 本轮决策的视觉来源。用枚举而不是字符串，避免下游靠 `source == "云端"` 这种字面量比对 */
    enum class Source(val label: String) {
        /** 端侧 3B 控件识别 */
        EXTERNAL("端侧3B"),

        /** 主模型直接读图（未生成文字描述） */
        MAIN_DIRECT("主模型直读"),

        /** 云端视觉模型给出的文字描述 */
        CLOUD("云端"),

        /** 端侧识别的文字描述 */
        LOCAL("本地OCR"),

        /** 本步没有视觉产出 */
        NONE("无"),
    }

    /**
     * 判定本步视觉来源。顺序即优先级，与执行链路一致：
     * 端侧 3B 先跑 → 主模型直读（跳过描述）→ 云端描述 → 端侧描述 → 无。
     */
    fun sourceOf(
        externalUsed: Boolean,
        mainGetsImage: Boolean,
        hasDescription: Boolean,
        cloudVision: Boolean,
    ): Source = when {
        externalUsed -> Source.EXTERNAL
        mainGetsImage -> Source.MAIN_DIRECT
        hasDescription && cloudVision -> Source.CLOUD
        hasDescription -> Source.LOCAL
        else -> Source.NONE
    }

    /** 端侧 3B 的名称（探测链路固定，不随设置变化） */
    const val EXTERNAL_VISION_MODEL_LABEL = "Qwen2.5-VL-3B (端侧)"

    /**
     * 端侧文字描述通道的名称。
     *
     * 本地读图已统一由进程内端侧视觉承担（OCR 由 ML Kit 兜底），
     * 这个标签写的是旧实现。保留原文以免改动调试页既有显示口径（差异已记录在 CHANGELOG）。
     */
    const val LOCAL_VISION_MODEL_LABEL = "ML Kit 中文OCR"

    /** 视觉来源 → 展示用的模型名（[cloudModel]/[mainModel] 由调用方从当前设置取出） */
    fun modelOf(source: Source, cloudModel: String, mainModel: String): String = when (source) {
        Source.EXTERNAL -> EXTERNAL_VISION_MODEL_LABEL
        Source.CLOUD -> cloudModel
        Source.LOCAL -> LOCAL_VISION_MODEL_LABEL
        Source.MAIN_DIRECT -> mainModel
        Source.NONE -> ""
    }
}
