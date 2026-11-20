package com.phoneagent.device.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
// 候选挑选与助手探测共用同一份实现（provider-api），保证两处「优先系统应用」的规则不会漂移
import com.phoneagent.provider.a11y.pickPreferredPackage

/** 可启动应用的最小条目（显示名 / 包名 / 是否系统应用） */
data class LauncherApp(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)

/**
 * 可启动应用枚举（**唯一定义点**）：走系统 API 的 `MAIN + LAUNCHER` intent 查询。
 *
 * 为什么不靠 `QUERY_ALL_PACKAGES` 授权：
 * - 个别 ROM / 应用市场策略下该权限声明了也拿不全，实测甚至会整份清单为空；
 * - 它是 Google Play 的政策敏感权限，能不用就不用；
 * - 业务口径本来就是「用户能打开的应用」——`<queries>` 里声明 `MAIN + LAUNCHER` 后，
 *   Android 11+ 的包可见性规则会把所有带启动入口的应用放行，`queryIntentActivities`
 *   因此能拿全，全程不需要任何授权，也不需要用户去设置页点一次。
 *
 * 无缓存：调用方（DeviceFacts / AppNameResolver / DeviceOpsChannel）各自持有任务级缓存，
 * 这里只保证「一次查询拿到干净结果」。查询失败返回空清单，由调用方决定怎么解释。
 */
object LauncherCatalog {

    /** 枚举所有带启动入口的应用，按显示名排序；同一个包有多个入口时只保留一条 */
    fun query(context: Context): List<LauncherApp> = runCatching {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .mapNotNull { resolve ->
                val activity = resolve.activityInfo ?: return@mapNotNull null
                val pkg = activity.packageName ?: return@mapNotNull null
                val label = runCatching { resolve.loadLabel(pm).toString().trim() }
                    .getOrDefault("")
                    .ifBlank { return@mapNotNull null }
                LauncherApp(label, pkg, isSystemApp(activity.applicationInfo))
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }.getOrDefault(emptyList())

    /** 按显示名查包名（精确忽略大小写 → 去空白模糊）；同分时优先系统应用。找不到返回 null */
    fun resolveByName(context: Context, name: String): String? = resolveByNameIn(query(context), name)

    /**
     * 纯逻辑的解析段（[query] 结果 + 名字 → 包名）：Android 框架无关，JVM 单测直接验证这里。
     * 规则：精确匹配（忽略大小写）优先；都不中时去掉空白做子串双向匹配；同分优先系统应用。
     */
    internal fun resolveByNameIn(apps: List<LauncherApp>, name: String): String? {
        val wantedText = name.trim()
        if (wantedText.isEmpty()) return null
        val exact = apps.filter { it.label.equals(wantedText, ignoreCase = true) }
        if (exact.isNotEmpty()) return pickPreferred(exact)
        val cleanWanted = wantedText.filterNot { it.isWhitespace() }.lowercase()
        val fuzzy = apps.filter {
            val clean = it.label.filterNot { c -> c.isWhitespace() }.lowercase()
            clean.contains(cleanWanted) || cleanWanted.contains(clean)
        }
        return pickPreferred(fuzzy)
    }

    /** 同分候选优先系统应用（用户偏好「打开软件优先用系统软件」），规则复用 provider-api */
    private fun pickPreferred(candidates: List<LauncherApp>): String? =
        pickPreferredPackage(candidates.map { it.packageName to it.isSystem })

    /** `FLAG_SYSTEM` 判定：系统应用（含预装） */
    private fun isSystemApp(info: ApplicationInfo?): Boolean =
        info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
