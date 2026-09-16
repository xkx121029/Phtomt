package com.phoneagent.execution

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 应用名 → 包名解析器（对应 HPA动作执行逻辑优化文档 v2.1 六、6.3）。
 *
 * AI 输出 open_app 时只需给应用名（如"美团"），端侧负责搜索已安装应用来解析为包名：
 * 1. 入参本身是合法包名（含 '.'，非中文）则原样返回
 * 2. 查询系统已安装应用列表，精确匹配应用名（忽略大小写）
 * 3. 精确匹配失败时，模糊匹配（去掉空格/特殊字符后做子串匹配）
 * 4. 均失败时，列出已安装应用供 AI 参考
 */
class AppNameResolver(private val context: Context) {

    /** 解析应用名 → 包名。传入参数为合法包名时原样返回；找不到返回 null */
    fun resolve(appName: String?): String? {
        if (appName.isNullOrBlank()) return null
        val name = appName.trim()
        Log.d(TAG, "resolve app: '$name'")

        // 本身是包名（含 '.'，非中文）
        if (name.contains('.') && !name.any { it.code in 0x4E00..0x9FFF }) {
            Log.d(TAG, "  -> 视为包名直接返回: $name")
            return name
        }

        // 查询已安装应用列表
        val installed = queryInstalledApps()

        // 精确匹配（忽略大小写）
        installed.firstOrNull { it.appName.equals(name, ignoreCase = true) }?.let {
            Log.d(TAG, "  -> 精确匹配: '$name' => ${it.packageName}")
            return it.packageName
        }

        // 模糊匹配：去掉空格和特殊字符后做子串匹配
        val cleanName = name.replace("\\s+".toRegex(), "").lowercase()
        installed.firstOrNull { app ->
            val cleanLabel = app.appName.replace("\\s+".toRegex(), "").lowercase()
            cleanLabel.contains(cleanName) || cleanName.contains(cleanLabel)
        }?.let {
            Log.d(TAG, "  -> 模糊匹配: '$name' => ${it.packageName}")
            return it.packageName
        }

        // 提示：列出所有已安装应用的名称和包名，供 AI 参考
        val allApps = installed.take(20).joinToString(", ") { "${it.appName} (${it.packageName})" }
        Log.w(TAG, "无法解析应用「$name」，已安装应用: $allApps")
        return null
    }

    /** 查询已安装的桌面启动器应用 */
    private fun queryInstalledApps(): List<InstalledApp> {
        return runCatching {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.queryIntentActivities(launcher, 0)
                .mapNotNull {
                    val label = it.loadLabel(pm).toString().trim().ifBlank { null } ?: return@mapNotNull null
                    InstalledApp(label, it.activityInfo.packageName)
                }
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    private data class InstalledApp(val appName: String, val packageName: String)
}

private const val TAG = "AppNameResolver"
