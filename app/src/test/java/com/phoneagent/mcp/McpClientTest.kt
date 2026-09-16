package com.phoneagent.mcp

import com.phoneagent.feature.mcp.McpClient
import com.phoneagent.feature.mcp.McpException
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.feature.mcp.McpServerConfig
import com.phoneagent.feature.mcp.McpTransport
import com.phoneagent.feature.skill.SkillRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {

    private val json = Json

    /** 假传输：按方法返回预置 JSON-RPC result */
    private fun fakeTransport(
        vararg handlers: Pair<String, JsonElement>,
        fallbackName: String = "fake-server",
    ): McpTransport {
        val map = handlers.toMap()
        return object : McpTransport {
            override suspend fun call(method: String, params: JsonObject?): JsonElement {
                return map[method] ?: buildJsonObject { put("ok", JsonPrimitive(true)) }
            }
        }
    }

    @Test
    fun 握手初始化() = runBlocking {
        val t = fakeTransport("initialize" to buildJsonObject { put("protocolVersion", JsonPrimitive("2025-03-26")) })
        val client = McpClient(t, "s")
        val result = client.initialize()
        assertEquals("2025-03-26", result.jsonObject["protocolVersion"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun 枚举工具() = runBlocking {
        val tools = buildJsonObject {
            put("tools", JsonArray(listOf(
                buildJsonObject {
                    put("name", JsonPrimitive("read_file"))
                    put("description", JsonPrimitive("读取文件"))
                    put("inputSchema", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject { put("path", buildJsonObject { put("type", JsonPrimitive("string")) }) })
                        put("required", JsonArray(listOf(JsonPrimitive("path"))))
                    })
                },
            )))
        }
        val t = fakeTransport("tools/list" to tools)
        val client = McpClient(t, "s")
        val listed = client.listTools()
        assertEquals(1, listed.size)
        assertEquals("read_file", listed[0].name)
    }

    @Test
    fun 调用工具并解析文本() = runBlocking {
        val textItem = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("文件内容：你好"))
        }
        val callResult = buildJsonObject {
            put("content", JsonArray(listOf(textItem)))
            put("isError", JsonPrimitive(false))
        }
        val t = fakeTransport("tools/call" to callResult)
        val client = McpClient(t, "s")
        val res = client.callTool("read_file", buildJsonObject { put("path", JsonPrimitive("/a.txt")) })
        assertFalse(res.isError)
        assertTrue(res.content.contains("文件内容"))
    }

    @Test
    fun 连接校验ok() = runBlocking {
        val t = fakeTransport(
            "initialize" to buildJsonObject { },
            "tools/list" to buildJsonObject { put("tools", JsonArray(emptyList())) },
        )
        val check = McpClient(t, "s").check()
        assertTrue(check.ok)
    }

    @Test
    fun 服务器报错转为连接失败() = runBlocking {
        val t = object : McpTransport {
            override suspend fun call(method: String, params: JsonObject?): JsonElement {
                throw McpException("MCP 服务器「s」返回了非 JSON 响应", -32603)
            }
        }
        val check = McpClient(t, "s").check()
        assertFalse(check.ok)
    }

    @Test
    fun 管理器绑定工具为Skill并分流调用() = runBlocking {
        val tools = buildJsonObject {
            put("tools", JsonArray(listOf(
                buildJsonObject {
                    put("name", JsonPrimitive("query_balance"))
                    put("description", JsonPrimitive("查询余额"))
                    put("inputSchema", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject { put("account", buildJsonObject { put("type", JsonPrimitive("string")) }) })
                        put("required", JsonArray(listOf(JsonPrimitive("account"))))
                    })
                },
            )))
        }
        val manager = McpManager(
            listOf(McpServerConfig("bank", "http://127.0.0.1:3000/mcp")),
        ) { cfg -> fakeTransport("tools/list" to tools, "tools/call" to buildJsonObject {
            put("content", JsonArray(listOf(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("余额123")) })))
            put("isError", JsonPrimitive(false))
        }) }

        val registry = SkillRegistry()
        val added = manager.bindToolsToSkills("bank", "mcp_bank_", registry)
        assertEquals(1, added)
        val skill = registry.byId("mcp_bank_query_balance")
        assertEquals("query_balance", skill?.mcp?.tool)
        assertEquals("bank", skill?.mcp?.server)
        // 参数来自 schema：account 必填
        assertTrue(skill?.params?.any { it.name == "account" && it.required } == true)

        // 分流调用 MCP
        val res = manager.callTarget(skill!!.mcp!!, mapOf())
        assertTrue(res.content.contains("余额"))
    }
}
