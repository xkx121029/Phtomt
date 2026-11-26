package com.phoneagent.coding.api

/**
 * 编码 agent 的运行配置（纯数据类，无持久化）。
 *
 * HPA 不在这里读写 SharedPreferences：provider/baseUrl/apiKey/model 由主工程的
 * AppSettings（主模型配置）注入，approval/token/mockScript 由编码设置或调用方提供。
 */
data class CodingAgentConfig(
    val provider: String = "openai",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val approval: String = "risky",
    /**
     * 宿主桥的共享密钥：Node 侧发往 Kotlin 的反向请求必须带上它（见 HostBridgeServer）。
     *
     * 没有默认值：空密钥等于没有鉴权（HostBridgeServer 会拒绝一切入站请求），
     * 所以这里强制每个调用方显式传一个非空值，漏传在编译期就暴露。
     */
    val token: String,
    /** 可选的离线演示剧本（assets/agent-host/scenarios/<name>.json），无 API key 也能回放真实工具循环。 */
    val mockScript: String = "",
)
