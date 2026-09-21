package com.phoneagent.engine.execution

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
 *
 * **同名候选一律优先系统自带应用**：用户偏好「打开软件优先用系统软件」，而 AI 说"打开浏览器"
 * 这类**泛指类目**时，同一台机器上往往同时装着系统浏览器与第三方浏览器（如 Chrome/夸克），
 * 按安装顺序取第一个会让结果随机。因此候选命中多个时先挑系统应用，没有系统应用才用第三方
 * （如"微信"这类无系统版的第三方应用不受影响）。
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

        // 精确匹配（忽略大小写）；同名多个（如"浏览器"）优先系统自带
        pickPreferredPackage(
            installed.filter { it.appName.equals(name, ignoreCase = true) }.map { it.packageName to it.isSystem },
        )?.let {
            Log.d(TAG, "  -> 精确匹配: '$name' => $it")
            return it
        }

        // 模糊匹配：去掉空格和特殊字符后做子串匹配；同样优先系统自带
        val cleanName = name.replace("\\s+".toRegex(), "").lowercase()
        pickPreferredPackage(
            installed.filter { app ->
                val cleanLabel = app.appName.replace("\\s+".toRegex(), "").lowercase()
                cleanLabel.contains(cleanName) || cleanName.contains(cleanLabel)
            }.map { it.packageName to it.isSystem },
        )?.let {
            Log.d(TAG, "  -> 模糊匹配: '$name' => $it")
            return it
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
                    val info = it.activityInfo?.applicationInfo
                    val system = info != null &&
                        info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                    InstalledApp(label, it.activityInfo.packageName, system)
                }
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())
    }

    private data class InstalledApp(val appName: String, val packageName: String, val isSystem: Boolean)
}

/**
 * 候选挑选：优先系统自带应用，没有系统应用才取第一个候选。
 * 抽成纯函数是为了可单测——[AppNameResolver] 本身依赖 PackageManager 无法在纯 JVM 里跑。
 */
internal fun pickPreferredPackage(candidates: List<Pair<String, Boolean>>): String? =
    candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first

private const val TAG = "AppNameResolver"
