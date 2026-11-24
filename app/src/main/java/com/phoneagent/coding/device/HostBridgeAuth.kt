package com.phoneagent.coding.device

/**
 * 宿主桥的鉴权判据（纯函数，JVM 可测）。
 *
 * 保护面：Node 侧 agent 进程经管道发来的 `type:request` 能驱动设备侧能力
 * （工作区读写、编辑器、编码环境），所以每一条都必须带对密钥。
 *
 * 空密钥一律拒绝——它不是"本地模式免鉴权"，而是**没有任何鉴权**：部署时忘了配
 * 密钥，管道就对所有写入方开放，这条判断不能退回放行。
 *
 * 作废常量同样恒拒（XKX-46）：[HostBridgeToken.LEGACY_COMPROMISED] 曾硬编码在
 * 桥里，能读源码或反编译安装包的一方都拿得到，它已经不是密钥。即便某个老配置
 * 把期望值写回成它，这里也不认——升级后旧密钥必须立刻失效。
 */
internal object HostBridgeAuth {

    /**
     * @param expectedToken 配置里的共享密钥（[com.phoneagent.coding.api.CodingAgentConfig.token]）
     * @param providedToken 入站请求 `params.token`；缺省时调用方传空串或 null
     */
    fun allows(expectedToken: String, providedToken: String?): Boolean =
        expectedToken.isNotEmpty() &&
            expectedToken != HostBridgeToken.LEGACY_COMPROMISED &&
            providedToken == expectedToken
}
