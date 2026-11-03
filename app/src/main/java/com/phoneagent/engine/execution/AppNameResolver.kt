package com.phoneagent.engine.execution

import android.content.Context
import android.content.Intent
import com.phoneagent.core.log.AppLog
// 候选挑选与助手探测共用同一份实现（provider-api），保证两处"优先系统应用"的规则不会漂移
import com.phoneagent.provider.a11y.pickPreferredPackage

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
 *
 * 性能（先量后改）：`queryIntentActivities` 是一次跨进程全量扫描，改造前**每个 open_app 动作都要扫一遍**。
 * 现在清单按解析器实例缓存（引擎生命周期内有效），并把"名字 → 包名"的命中结果一并缓存：
 * 同一个应用反复打开时零 IPC。未命中时会刷新一次清单再试——覆盖"任务进行中刚装了应用"这种情况，
 * 所以缓存不会导致"明明装了却说没装"。
 */
class AppNameResolver(
    context: Context,
    /** 已安装应用查询（默认走 PackageManager；单测注入假数据） */
    private val queryInstalled: () -> List<InstalledApp> = { queryLauncherApps(context) },
) {

    private companion object {
        /** 模糊匹配前的空白剔除；已装应用可达数百个，逐条编译正则代价明显 */
        val WS_RE = Regex("\\s+")
    }

    /** 已安装应用清单缓存（null = 还没查过） */
    private var installedCache: List<InstalledApp>? = null

    /** 命中缓存：规范化名字 → 包名（避免每次都重新做一遍精确/模糊比对） */
    private val resolvedCache = HashMap<String, String>()

    /** 本实例真实发生的 PackageManager 查询次数（单测与排查用，不参与业务判断） */
    var queryCount: Int = 0
        private set

    /** 解析应用名 → 包名。传入参数为合法包名时原样返回；找不到返回 null */
    fun resolve(appName: String?): String? {
        if (appName.isNullOrBlank()) return null
        val name = appName.trim()
        AppLog.d(TAG, "resolve app: '$name'")

        // 本身是包名（含 '.'，非中文）
        if (name.contains('.') && !name.any { it.code in 0x4E00..0x9FFF }) {
            AppLog.d(TAG, "  -> 视为包名直接返回: $name")
            return name
        }

        val key = name.lowercase()
        resolvedCache[key]?.let {
            AppLog.d(TAG, "  -> 命中解析缓存: '$name' => $it")
            return it
        }

        // 本次解析开始前是否已经有缓存清单：没有说明下面这次就是新鲜查询，未命中时不必再刷一次
        val hadCache = installedCache != null
        var installed = installed(refresh = false)
        pickPkg(installed, name)?.let { pkg ->
            val hit = remember(key, pkg)
            AppLog.d(TAG, "  -> 匹配: '$name' => $hit")
            return hit
        }

        // 可能是本任务期间刚装上应用：刷新清单再试一次（首次查询本身是新的，不重复刷）
        if (hadCache) {
            installed = installed(refresh = true)
            pickPkg(installed, name)?.let { pkg ->
                val hit = remember(key, pkg)
                AppLog.d(TAG, "  -> 刷新清单后匹配: '$name' => $hit")
                return hit
            }
        }

        // 提示：列出所有已安装应用的名称和包名，供 AI 参考
        val allApps = installed.take(20).joinToString(", ") { "${it.appName} (${it.packageName})" }
        AppLog.w(TAG, "无法解析应用「$name」，已安装应用: $allApps")
        return null
    }

    /** 精确匹配 → 模糊匹配（两步都优先系统应用）；找不到返回 null */
    private fun pickPkg(installed: List<InstalledApp>, name: String): String? {
        pickPreferredPackage(
            installed.filter { it.appName.equals(name, ignoreCase = true) }.map { it.packageName to it.isSystem },
        )?.let { return it }

        val cleanName = WS_RE.replace(name, "").lowercase()
        return pickPreferredPackage(
            installed.filter { app ->
                val cleanLabel = WS_RE.replace(app.appName, "").lowercase()
                cleanLabel.contains(cleanName) || cleanName.contains(cleanLabel)
            }.map { it.packageName to it.isSystem },
        )
    }

    private fun remember(key: String, pkg: String): String {
        resolvedCache[key] = pkg
        return pkg
    }

    /** 取已安装清单：命中缓存直接返回；[refresh] 为真时强制重查（清单变了，旧命中结果一并作废） */
    private fun installed(refresh: Boolean): List<InstalledApp> {
        val cached = installedCache
        if (cached != null && !refresh) return cached
        val fresh = queryInstalled()
        queryCount++
        installedCache = fresh
        if (refresh) resolvedCache.clear()
        return fresh
    }
}

/** 解析用的最小应用条目（名字、包名、是否系统应用） */
data class InstalledApp(val appName: String, val packageName: String, val isSystem: Boolean)

/** 查询已安装的桌面启动器应用（PackageManager 实现；失败时返回空清单） */
private fun queryLauncherApps(context: Context): List<InstalledApp> = runCatching {
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

private const val TAG = "AppNameResolver"
