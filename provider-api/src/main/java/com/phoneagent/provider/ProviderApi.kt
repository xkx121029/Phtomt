package com.phoneagent.provider

/**
 * [IDeviceProvider] 契约常量：主应用与 Provider 共用同一份，避免两边各写一套导致漂移。
 */
object ProviderApi {

    /** 契约版本：增删 [IDeviceProvider] 方法时递增（v5：新增按 display 读元素树，副屏模式用） */
    const val API_VERSION = 5

    /** 分片传输的数据种类：屏幕元素树 JSON（UTF-8） */
    const val KIND_TREE = 0

    /** 分片传输的数据种类：无障碍截图 PNG */
    const val KIND_SCREENSHOT = 1
}
