package com.phoneagent.engine

/**
 * 已安装应用清单与显示名的**任务级缓存**（性能改造：先量后改）。
 *
 * 为什么需要它：`PackageManager.queryIntentActivities` 在装了一二百个应用的设备上是几十毫秒级的
 * 跨进程查询，`getApplicationInfo` 同样是 IPC。改造前这两处都在热路径上反复调用：
 * 规划要清单、每步决策要"前台应用显示名"、任务收尾要应用名清单，同一次任务里被重复查询多次。
 *
 * 缓存口径：
 * - 清单与显示名都按任务失效（应用可能在两次任务之间被装卸，见 [DeviceFacts.resetAppCache]）；
 * - 显示名连"取不到"（空串）一起缓存：取不到的结果再查一次也还是取不到，重复 IPC 没有意义。
 *
 * 查询计数 [appQueryCount] / [labelQueryCount] 只用于单测与埋点核对"到底少扫了几次"，
 * 不参与任何业务判断。
 */
internal class AppInventory(
    private val fetchApps: () -> List<String>,
    private val fetchLabel: (String) -> String,
) {
    /** 本任务已缓存的启动器应用清单；null 表示还没查过 */
    private var appsCache: List<String>? = null

    /** 显示名缓存（包名 → 显示名，取不到时为空串） */
    private val labelCache = HashMap<String, String>(8)

    /** 本任务内真实发生的清单查询次数（测试/埋点用） */
    var appQueryCount: Int = 0
        private set

    /** 本任务内真实发生的显示名查询次数（测试/埋点用） */
    var labelQueryCount: Int = 0
        private set

    /** 已安装可启动应用：`应用名(包名)` 列表；本任务内只查一次 PackageManager */
    fun apps(): List<String> = appsCache ?: fetchApps().also {
        appQueryCount++
        appsCache = it
    }

    fun count(): Int = apps().size

    /** 应用显示名；取不到时返回空串（调用方按"名字不可用"处理） */
    fun labelOf(pkg: String): String {
        if (pkg.isBlank()) return ""
        labelCache[pkg]?.let { return it }
        val label = fetchLabel(pkg)
        labelQueryCount++
        labelCache[pkg] = label
        return label
    }

    /** 任务开始时清空：应用可能在两次任务之间被装上或卸掉 */
    fun reset() {
        appsCache = null
        labelCache.clear()
        appQueryCount = 0
        labelQueryCount = 0
    }
}
