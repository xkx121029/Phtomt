package com.phoneagent.coding.api

/**
 * 编码工具协议名 → 中文名。
 *
 * 口径唯一定义在 `agent.CodingToolNames`（协议名与设备端宿主一一对应，不能改）；
 * 这里只是它对外的那一个入口。宿主 UI（工具卡）据此渲染，不再直接引用切片内部对象——
 * 下期把词表整体移进 `coding/api` 时，调用方零改动。
 */
object CodingToolLabels {

    /** 协议名 → 中文名；未知工具是「其他工具」，空名是「工具」 */
    fun label(raw: String?): String = com.phoneagent.coding.agent.CodingToolNames.label(raw)
}
