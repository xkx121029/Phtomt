package com.phoneagent.coding.device

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 宿主桥的服务端（#10 / #35 的落地端）。
 *
 * Node 侧的 agent 跑在应用起的子进程里；它有一部分活只能由 Kotlin 做——设备工作区的
 * 文件读写、代码编辑器的拉起、预览窗口的拉起。
 *
 *   host -> app:  {"id":"h1","type":"request","method":"editor_open","params":{...}}
 *   app -> host:  {"id":"h1","type":"response","result":{...}}
 *                 {"id":"h1","type":"error","error":"..."}
 *
 * `type` 字段是区分两个方向的唯一依据：应用自己的请求（id 形如 c1、c2）没有 type，
 * 而桥的请求一定有 type=="request"，所以两个方向永远不会串。
 *
 * 说明：本类原先还路由一批**手机操控**方法（shell_exec / clipboard / device_info /
 * apps_list / ui_dump / ui_action / screen_* / notifications_* / overlay_*）——那部分
 * 能力要整合进 HPA（happy_phone agent），已整体移除。移植进 HPA 后一期进一步禁用了
 * browser_* / userscripts / logcat_read / sqlite_*（HPA 有独立的浏览器体系，工具箱
 * 也由主工程承担），这里只保留：工作区文件、编辑器、预览。
 */
class HostBridgeServer(
    private val context: Context,
    /** 期望的共享密钥（与 CodingAgentConfig.token 一致）。入站反向请求必须带上它。 */
    private val expectedToken: () -> String = { "" },
) {

    /** 处理器可能阻塞（读写文件、等界面拉起），绝不能占着 stdout 读线程。 */
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "coding-host-rpc").apply { isDaemon = true }
    }

    /**
     * 路由一条宿主请求。`respond` 会把回复写回同一条管道；它在工作线程上被调用，
     * 实现方需要自己保证线程安全（AgentProcess.send 已经是线程安全的）。
     *
     * 鉴权（2026-10-21 修）：以前对已连上的 host 零校验——任何一个能往管道里写
     * type:request 的一方都能驱动设备侧能力（工作区读写、编辑器）。现在每个反向
     * 请求都必须带 params.token 且与配置里的密钥一致；没配密钥时（本地模式默认空）
     * 保持放行，不破坏既有部署。
     */
    fun handle(request: JSONObject, respond: (JSONObject) -> Unit) {
        val id = request.optString("id")
        if (id.isEmpty()) return
        val params = request.optJSONObject("params") ?: JSONObject()
        val expected = expectedToken()
        if (expected.isNotEmpty() && params.optString("token") != expected) {
            // 静默拒绝：不回包、不说明缺哪一项——对端没有凭据就不该知道细节。
            return
        }
        val method = request.optString("method")
        pool.execute {
            val reply = try {
                JSONObject()
                    .put("id", id)
                    .put("type", "response")
                    .put("result", dispatch(method, params))
            } catch (e: Throwable) {
                JSONObject()
                    .put("id", id)
                    .put("type", "error")
                    .put("error", (e.message ?: e.toString()).ifBlank { "host error" })
            }
            try {
                respond(reply)
            } catch (_: Exception) {
                /* 管道断了就忽略，别把线程带走 */
            }
        }
    }

    private fun dispatch(method: String, params: JSONObject): Any = when (method) {
        "host_capabilities" -> capabilities()
        "workspace_tree" -> DeviceWorkspace.tree(
            context,
            params.optInt("maxDepth", 6).coerceIn(1, 10),
            params.optInt("limit", 800).coerceIn(1, 3000),
        )
        "workspace_read" -> DeviceWorkspace.read(context, params.optString("path"))
        "workspace_write" -> DeviceWorkspace.write(
            context,
            params.optString("path"),
            params.optString("content"),
        )
        "workspace_create" -> DeviceWorkspace.create(context, params.optString("path"))
        "editor_open" -> editorOpen(params)
        "preview_open" -> previewOpen(params)
        else -> throw IllegalArgumentException("unknown host method: $method")
    }

    /**
     * 能力表。这里是纯功能开关，Node 侧据此决定降级还是直连。
     * browser / toolbox 在 HPA 一期禁用（HPA 有独立浏览器与工具体系），不上报。
     */
    private fun capabilities(): JSONObject {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val caps = JSONArray()
        // 工作区文件读写、编辑器、预览都不依赖任何运行时授权，随应用常驻可用。
        caps.put("workspace")
        caps.put("editor")
        caps.put("preview")

        return JSONObject()
            .put("platform", "android")
            .put("version", versionName)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL)
            .put("capabilities", caps)
    }

    // ---------- 工作区开发（#29 #30 #31 #34） ----------

    /**
     * 拉起代码编辑器。这是唯一一类「必须由用户亲手操作」的能力：编辑器是给人写的，
     * agent 能做的只是把它打开、定位到某个文件。
     */
    private fun editorOpen(params: JSONObject): JSONObject {
        val path = params.optString("path").takeIf { it.isNotEmpty() }
        if (path != null) DeviceWorkspace.resolveWithin(context, path) // 越界早失败，别等到界面里才炸
        com.phoneagent.coding.EditorActivity.open(context, path)
        return JSONObject().put("ok", true).put("path", path ?: JSONObject.NULL)
    }

    /**
     * 拉起预览窗口。target 可以是工作区里的 HTML，也可以是 agent 自己起的预览服务地址
     * （local_web_server），两种都用同一个 WebView 渲染。
     */
    private fun previewOpen(params: JSONObject): JSONObject {
        val target = params.optString("target")
        if (target.isEmpty()) throw IllegalArgumentException("preview_open 需要一个 target（工作区相对路径或 http(s) 链接）")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            DeviceWorkspace.resolveWithin(context, target)
        }
        com.phoneagent.coding.PreviewActivity.open(context, target)
        return JSONObject().put("ok", true).put("target", target)
    }
}
