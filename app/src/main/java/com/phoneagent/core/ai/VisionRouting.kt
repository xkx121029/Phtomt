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
}