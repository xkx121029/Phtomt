package com.phoneagent.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备与环境事实的读取层（对应拆分目标里的 ContextBuilder 取数部分）。
 *
 * 职责边界：这里只回答"这台机器现在是什么样"——装了哪些应用、屏幕多大、有没有网、多少电、剩多少空间。
 * **不负责**把事实拼成给 AI 的提示词（那是 [AgentPrompts.environment] 与 AgentEngine 的组装活）；
 * 也不做任何决策。取数与组装分开之后，"取错了"和"写错了"是两类问题，各自能单独验证。
 *
 * 唯一的内部状态是应用数量缓存：决策每步都要用到它，不能每步全量扫一次 PackageManager。
 * 缓存按任务失效——调用方在任务开始时调 [resetAppCache]（应用可能在两次任务之间被装卸）。
 */
internal class DeviceFacts(private val context: Context) {

    /** 已安装应用数量缓存；-1 表示本任务还没查过 */
    private var appCountCache = -1

    /** 任务开始时清缓存：应用可能在两次任务之间被装上或卸掉 */
    fun resetAppCache() {
        appCountCache = -1
    }

    /** 已安装可启动应用：`应用名(包名)` 列表，按名称排序（查询一次，供清单与计数复用） */
    fun launcherApps(): List<String> = runCatching {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        pm.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(pm).toString().trim()
                if (label.isBlank()) null else "$label(${info.activityInfo.packageName})"
            }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    /** 已安装应用数量：每任务只查一次 PackageManager（决策每步都要用，不能每步全量查询） */
    fun launcherAppCount(): Int {
        if (appCountCache >= 0) return appCountCache
        return launcherApps().size.also { appCountCache = it }
    }

    /**
     * 已装应用名清单：供经验规则推断"任务文本里点名的那个应用"当关键词作用域。
     * 只在任务结束提炼时调用一次，不做缓存（复用同一次包管理器查询的产物，代价可忽略）。
     */
    fun launcherAppNames(): List<String> =
        launcherApps().mapNotNull { it.substringBefore('(').trim().ifBlank { null } }.distinct()

    /** 应用显示名；取不到时返回空串（调用方按"名字不可用"处理，不要拿包名冒充名字） */
    fun appLabelOf(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
    }.getOrDefault("")

    /** 当前时间（`2026-09-29 周二 20:00`）：元素树里读不到，"明天""下周"这类说法全靠它 */
    fun dateTimeText(): String = runCatching {
        SimpleDateFormat("yyyy-MM-dd E HH:mm", Locale.CHINA).format(Date())
    }.getOrDefault("")

    /**
     * 从系统 WindowManager 获取真实屏幕尺寸（不依赖无障碍服务）。
     * 读不到时给兜底分辨率：ShellCommands 要靠它换算比例坐标，给 0 会让整套坐标换算失效。
     */
    fun realScreenSize(): Pair<Int, Int> {
        val p = Point()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay?.getRealSize(p)
        val w = if (p.x > 0) p.x else DEFAULT_SCREEN_WIDTH
        val h = if (p.y > 0) p.y else DEFAULT_SCREEN_HEIGHT
        return w to h
    }

    /** 当前网络类型（Wi-Fi / 移动数据 / 以太网 / VPN / 无网络） */
    fun networkLabel(): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
        if (caps == null) {
            "无网络"
        } else {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "已连接"
            }
            // 连着 Wi-Fi 不代表出得了网：没有 INTERNET 能力时要说清楚，否则 AI 会照着"有网"安排联网动作
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) type else "$type（无外网）"
        }
    }.getOrDefault("")

    /** 当前电量（含是否充电） */
    fun batteryLabel(): String = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level < 0 || scale <= 0) {
            ""
        } else {
            val pct = level * PERCENT_SCALE / scale
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (charging) "$pct%（充电中）" else "$pct%"
        }
    }.getOrDefault("")

    /** 数据分区容量（可用 / 总量） */
    fun storageLabel(): String = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / BYTES_PER_GB
        val totalGb = stat.totalBytes / BYTES_PER_GB
        "可用 %.1f GB / 共 %.1f GB".format(freeGb, totalGb)
    }.getOrDefault("")

    companion object {
        /**
         * 一次最多列出的应用条数。
         * 上限只用来兜住"装了 500 个应用"的极端设备：够不上的部分必须显式告知 AI 去用 filter 缩小范围，
         * 绝不能静默截断——被截掉的应用在 AI 眼里等同于"没装"，会直接导致误判为需要澄清或放弃任务。
         */
        const val MAX_APP_LIST = 300

        /** WindowManager 读不到尺寸时的兜底分辨率（够 ShellCommands 换算比例坐标用） */
        private const val DEFAULT_SCREEN_WIDTH = 1080
        private const val DEFAULT_SCREEN_HEIGHT = 2400

        /** 电量分母：系统给的是 `level/scale`，换算成百分比要乘的分子 */
        private const val PERCENT_SCALE = 100

        /** 字节 → GB（除数是 2 的幂，除以它只改指数、不丢精度） */
        private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

        /**
         * 规划提示词里的已安装应用清单（纯逻辑，可单测）。
         *
         * 真被截断时必须写明并给出补救用法：提示词里写着「目标应用未安装 → 澄清或 give_up」，
         * 清单一旦漏项，AI 就会把已装的应用判成"没装"，进而反问用户或直接放弃。
         */
        fun appListPromptText(apps: List<String>, max: Int = MAX_APP_LIST): String = buildString {
            val shown = apps.take(max)
            append(shown.joinToString("、"))
            if (shown.size < apps.size) {
                append(
                    "\n（此处仅列出前 ${shown.size} 个，本机共 ${apps.size} 个；" +
                        "确认某个应用是否安装可用 device_query kind=apps 配合 filter 查）",
                )
            }
        }

        /**
         * `device_query kind=apps` 的查询结果文本（纯逻辑，可单测）。
         * 三种口径分开说清楚：读不到清单 / 清单里没有匹配 / 有匹配但列不全。
         */
        fun appQueryAnswerText(
            apps: List<String>,
            filter: String,
            max: Int = MAX_APP_LIST,
        ): String {
            val hit = if (filter.isBlank()) apps else apps.filter { it.contains(filter, ignoreCase = true) }
            return when {
                apps.isEmpty() -> "未能读取到已安装应用清单"
                hit.isEmpty() -> "已安装应用里没有匹配「$filter」的（共 ${apps.size} 个可启动应用）"
                else -> {
                    val shown = hit.take(max)
                    buildString {
                        append("已安装可启动应用共 ${apps.size} 个，匹配「${filter.ifBlank { "全部" }}」的 ${hit.size} 个：")
                        append("\n")
                        append(shown.joinToString("、"))
                        // 真的列不下时把话说清楚：漏掉的部分要靠 AI 自己用 filter 再查，而不是当作不存在
                        if (shown.size < hit.size) {
                            append(
                                "\n（仅列出前 ${shown.size} 个，剩下 ${hit.size - shown.size} 个" +
                                    "请用 filter 按关键词缩小范围后再查）",
                            )
                        }
                    }
                }
            }
        }
    }
}
