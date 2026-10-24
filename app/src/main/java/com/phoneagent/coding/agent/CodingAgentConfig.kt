package com.phoneagent.coding.agent

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
    val token: String = "",
    /** 可选的离线演示剧本（assets/agent-host/scenarios/<name>.json），无 API key 也能回放真实工具循环。 */
    val mockScript: String = "",
)
