// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.device.ops

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import com.phoneagent.data.store.AiMemoryEdit
import com.phoneagent.data.store.AiMemoryUpsert
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.device.screen.VirtualDisplayAgent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 系统直连执行器（对齐 Eta 的 device / phone / file 工具族里"不经过 GUI"的那批能力）。
 *
 * 设计约束：
 * - AI 只给「能力族 + op + args」，命令与 API 调用全部由端侧拼装，AI 不写 shell、不碰底层；
 * - 缺权限/缺通道时返回**中文原因 + 可执行替代**，不静默失败、不谎称成功；
 * - 读操作尽量走系统 API；改系统级设置/闹钟数据这类系统不开放的能力走注入命令（Shizuku/无线 ADB），
 *   没有注入通道时明确说明需要 Root/Shizuku，而不是假装改成功。
 *
 * 与 `shell` 自由模式的区别：这里不接收 AI 自写命令，RiskTier 也更低（除副屏/闹钟管理外）。
 */
// 该类是设备能力的集中登记表，拆分风险高，此处按规则豁免
@Suppress("LargeClass", "TooManyFunctions")
class DeviceOpsChannel(
    private val context: Context,
    /** 注入命令通道：返回 stdout；null = 没有可用通道或执行失败 */
    private val shell: suspend (String) -> String?,
    private val memory: MemoryStore? = null,
    /** 副屏代理：引擎按任务是否启用副屏提供；未启用时为 null */
    private val virtualDisplay: () -> VirtualDisplayAgent? = { null },
    /**
     * 注入通道不可用时的人话诊断（服务组件是否安装 / Shizuku 是否就绪 / 无线 ADB 是否连接）。
     *
     * 之前所有失败都只报「需要 Shizuku 或无线 ADB 注入通道」，用户与 AI 都无从知道
     * 卡的到底是哪一环（没装服务组件？没启动 Shizuku？没授权？），只能靠猜。
     */
    private val channelAdvice: () -> String = { "" },
) {

    /** 最近一次在副屏里拉起的包名：`present` 不带 app 时靠它把画面投回主屏 */
    private var lastVirtualApp: String? = null

    sealed interface OpsResult {
        data class Ok(val text: String) : OpsResult
        data class Fail(val reason: String) : OpsResult
    }

    /**
     * 注入通道缺口的失败文案：保留原句（含替代路线），再追加端侧实测的通道诊断。
     * 诊断为空时不加括号，保持与旧文案完全一致（不改变无引擎装配场景下的表现）。
     */
    private fun noChannel(prefix: String): OpsResult.Fail {
        val advice = runCatching { channelAdvice() }.getOrDefault("").trim()
        return OpsResult.Fail(prefix + if (advice.isNotEmpty()) "（$advice）" else "")
    }

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    suspend fun run(family: String, op: String?, args: Map<String, String>): OpsResult = when (family) {
        "device_info" -> deviceInfo(op ?: "status", args)
        "device_control" -> deviceControl(op ?: "status", args)
        "settings_op" -> settingsOp(op ?: "get", args)
        "clipboard" -> clipboardOp(op ?: "get", args)
        "alarm_manage" -> alarmManage(op ?: "list", args)
        "calendar_op" -> calendarOp(op ?: "list", args)
        "virtual_display" -> virtualDisplayOp(op ?: "status", args)
        "memory_get" -> memoryGet(args)
        "memory_write" -> memoryWrite(op ?: "write", args)
        "file_read" -> fileRead(op ?: "read", args)
        "file_write" -> fileWrite(op ?: "write", args)
        "app_manage" -> appManage(op ?: "force_stop", args)
        "terminal_op" -> terminalOp(op ?: "exec", args)
        "personal_read" -> personalRead(op ?: "contacts", args)
        else -> OpsResult.Fail(
            "未知的系统直连能力族：$family。可用的有 device_info、device_control、settings_op、clipboard、" +
                "alarm_manage、calendar_op、virtual_display、memory_get、memory_write、file_read、file_write、" +
                "app_manage、terminal_op、personal_read",
        )
    }

    // ---------------------------------------------------------------- device_info

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "MaxLineLength")
    private fun deviceInfo(op: String, args: Map<String, String>): OpsResult = when (op) {
        "status" -> OpsResult.Ok(buildString {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
            @Suppress("MaxLineLength")
            val battery = context.getSystemService(BatteryManager::class.java)
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("电量：${battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1}%" +
                "（${if (battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING) "充电中" else "未充电"}）")
            appendLine("内存：可用 ${mb(mem?.availMem ?: 0)} / 总计 ${mb(mem?.totalMem ?: 0)}")
            append("存储：可用 ${gb(freeBytes(Environment.getDataDirectory().path))} / 总计 ${gb(totalBytes(Environment.getDataDirectory().path))}")
        })
        "network" -> OpsResult.Ok(networkText())
        "storage" -> OpsResult.Ok(buildString {
            appendLine("内部存储：可用 ${gb(freeBytes(Environment.getDataDirectory().path))} / 总计 ${gb(totalBytes(Environment.getDataDirectory().path))}")
            append("外部存储：可用 ${gb(freeBytes(Environment.getExternalStorageDirectory().path))} / 总计 ${gb(totalBytes(Environment.getExternalStorageDirectory().path))}")
        })
        "memory" -> OpsResult.Ok(run {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            "内存：可用 ${mb(info.availMem)} / 总计 ${mb(info.totalMem)}；低内存=${info.lowMemory}"
        })
        "battery" -> OpsResult.Ok(run {
            val bm = context.getSystemService(BatteryManager::class.java)
            val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            "电量：$percent%；充电状态=${bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1}"
        })
        "display" -> OpsResult.Ok(run {
            val dm = context.resources.displayMetrics
            val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            "屏幕：${dm.widthPixels}x${dm.heightPixels}，密度 ${dm.densityDpi}，亮度 $brightness/255"
        })
        "sound" -> OpsResult.Ok(soundText())
        "apps" -> OpsResult.Ok(appsText())
        "inspect_app" -> inspectApp(args["app"] ?: args["package"] ?: "")
        else -> OpsResult.Fail(
            "device_info 不支持 op=$op；可用：status（概览）、network、storage、memory、battery、display、" +
                "sound、apps（已安装应用）、inspect_app（单个应用详情）",
        )
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun networkText(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return "网络：无法读取（ConnectivityManager 不可用）"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return "网络：当前无活动网络"
        val types = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("移动数据")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("蓝牙")
        }
        return "网络：${if (types.isEmpty()) "已连接（类型未知）" else types.joinToString("+")}；" +
            "已验证上网=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun soundText(): String {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return "声音：无法读取"
        val mode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "震动"
            else -> "响铃"
        }
        fun vol(stream: Int) = "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}"
        return "声音：$mode；媒体 ${vol(AudioManager.STREAM_MUSIC)}，铃声 ${vol(AudioManager.STREAM_RING)}，" +
            "通知 ${vol(AudioManager.STREAM_NOTIFICATION)}，闹钟 ${vol(AudioManager.STREAM_ALARM)}"
    }

    private fun appsText(): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val userApps = apps.filter { !it.systemFlag() }
        val shown = userApps.take(40).joinToString("\n") { "${pm.getApplicationLabel(it)}（${it.packageName}）" }
        return "已安装应用 ${apps.size} 个（第三方 ${userApps.size} 个）：\n$shown" +
            if (userApps.size > 40) "\n…（仅列出前 40 个）" else ""
    }

    /** 应用详情：版本 / 安装时间 / 来源 / 权限（对齐 Eta inspect_app） */
    @Suppress("DEPRECATION", "ReturnCount", "MaxLineLength")
    private fun inspectApp(input: String): OpsResult {
        if (input.isBlank()) {
            return OpsResult.Fail("inspect_app 需要 app：给应用名（如「微信」）或包名（如 com.tencent.mm）")
        }
        val pm = context.packageManager
        val pkg = resolvePackage(input) ?: return OpsResult.Fail(
            "没有找到应用「$input」。请确认应用名或包名是否正确；不确定时先调 device_info（op=apps）" +
                "看一遍已安装应用列表，再把准确名称给我",
        )
        val info = runCatching { pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS) }.getOrNull()
            ?: return OpsResult.Fail(
                "读取应用信息失败：$pkg。它可能刚被卸载或受系统限制；请用 device_info（op=apps）确认它还在列表里",
            )
        val appInfo = info.applicationInfo ?: return OpsResult.Fail(
            "读取应用信息失败：$pkg（系统没返回应用详情）。请用 device_info（op=apps）确认它还在已安装列表里",
        )
        val requested = info.requestedPermissions.orEmpty()
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val granted = requested.filter { runCatching { pm.checkPermission(it,
            pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false) }
        return OpsResult.Ok(buildString {
            appendLine("应用：${runCatching { pm.getApplicationLabel(appInfo) }.getOrDefault(pkg)}（$pkg）")
            appendLine("版本：${info.versionName ?: "未知"}（code ${longVersion(info)}）")
            appendLine("UID：${appInfo.uid}；系统应用=${appInfo.systemFlag()}；targetSdk=${appInfo.targetSdkVersion}")
            appendLine("安装时间：${formatTime(info.firstInstallTime)}；更新时间：${formatTime(info.lastUpdateTime)}")
            appendLine("来源：${runCatching { pm.getInstallerPackageName(pkg) }.getOrNull() ?: "未知"}")
            append("权限：申请 ${requested.size} 项，已授予 ${granted.size} 项" +
                if (granted.isNotEmpty()) "（${granted.take(12).joinToString("、")}${if (granted.size > 12) "…" else ""}）" else "")
        })
    }

    private fun longVersion(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun formatTime(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    // ------------------------------------------------------------- device_control

    private suspend fun deviceControl(op: String, args: Map<String, String>): OpsResult = when (op) {
        "flashlight_on", "flashlight_off" -> torch(op == "flashlight_on")
        "brightness" -> brightness(intArg(args, "level"))
        "volume" -> volume(args)
        "media" -> mediaKey(args["action"] ?: "play")
        "sound_mode" -> soundMode(args["mode"])
        "dnd" -> dnd(boolArg(args, "enable"))
        "screen_timeout" -> screenTimeout(intArg(args, "seconds"))
        "rotation" -> rotation(boolArg(args, "enable"))
        "dark_mode" -> darkMode(boolArg(args, "enable"))
        "wifi", "bluetooth", "airplane", "location", "nfc" -> systemToggle(op, boolArg(args, "enable"))
        else -> OpsResult.Fail(
            "device_control 不支持 op=$op；可用：flashlight_on、flashlight_off、brightness、volume、media、" +
                "sound_mode、dnd、screen_timeout、rotation、dark_mode、wifi、bluetooth、airplane、location、nfc",
        )
    }

    /**
     * 手电筒：下发后等系统 TorchCallback 回报「已到达目标状态」才算成功。
     *
     * 之前的实现 `setTorchMode` 没抛异常就报「已打开」，但相机被占用/闪光灯过热时
     * 系统会静默拒绝，用户看到的是「提示说开了、实际没亮」。这里以回调为准。
     */
    private suspend fun torch(on: Boolean): OpsResult {
        val cm = context.getSystemService(CameraManager::class.java)
            ?: return OpsResult.Fail(
                "手电筒：相机服务不可用。请下拉控制中心手动打开手电筒；若控制中心也没有这个图标，" +
                    "说明系统禁用了相机服务，需要到 设置 → 应用 → 相机 里启用",
            )
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return OpsResult.Fail(
            "手电筒：本机没有可用的闪光灯（平板或部分机型属正常）。请下拉控制中心确认有没有手电筒图标",
        )

        val confirmed = runCatching {
            withTimeoutOrNull(1_500L) {
                suspendCancellableCoroutine { cont ->
                    lateinit var callback: CameraManager.TorchCallback
                    callback = object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                            if (cameraId != id || !cont.isActive) return
                            if (enabled == on) {
                                runCatching { cm.unregisterTorchCallback(this) }
                                cont.resume(true)
                            }
                        }

                        override fun onTorchModeUnavailable(cameraId: String) {
                            if (cameraId != id || !cont.isActive) return
                            runCatching { cm.unregisterTorchCallback(this) }
                            cont.resume(false)
                        }
                    }
                    cm.registerTorchCallback(callback, null)
                    cm.setTorchMode(id, on)
                    cont.invokeOnCancellation { runCatching { cm.unregisterTorchCallback(callback) } }
                }
            }
        }.getOrElse {
            return OpsResult.Fail(
                "手电筒操作失败：${it.message ?: "系统拒绝"}。部分机型需要相机权限：" +
                    "请到 设置 → 应用 → 本应用 → 权限 里允许「相机」后重试，或直接下拉控制中心手动打开",
            )
        }
        return when (confirmed) {
            true -> OpsResult.Ok("手电筒已${if (on) "打开" else "关闭"}（回读闪光灯状态=${if (on) "on" else "off"}）")
            false -> OpsResult.Fail(
                "手电筒不可用：系统回报闪光灯状态不可用，通常是相机正被占用或闪光灯过热保护。" +
                    "请先关掉正在用相机的应用（相机、扫码、视频通话），等半分钟散热后重试；" +
                    "也可以下拉控制中心手动打开",
            )
            else -> OpsResult.Fail(
                "手电筒命令已下发，但 1.5 秒内没有收到「${if (on) "开" else "关"}」的状态回调，本机无法确认是否生效。" +
                    "请下拉控制中心看一眼手电筒图标${if (on) "是否已亮起" else "是否已熄灭"}；没变就手动点一下",
            )
        }
    }

    // ------------------------------------------------------ 回读验证（真的生效）

    private fun writeSettingsReason(): String =
        "写系统设置需要授权：请到「设置 → 应用 → 本应用 → 修改系统设置」允许后再试；" +
            "或用 Shizuku/无线 ADB 通道执行（无需该授权）。"

    private fun systemInt(key: String): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()

    private fun globalInt(key: String): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, key) }.getOrNull()

    private fun secureInt(key: String): Int? =
        runCatching { Settings.Secure.getInt(context.contentResolver, key) }.getOrNull()

    /** 注入通道读设置（本应用身份读不到时兜底） */
    private suspend fun shellSetting(ns: String, key: String): Int? =
        shell("settings get $ns $key")?.trim()?.toIntOrNull()

    /**
     * 回读开关值：**优先走注入通道**。
     *
     * 写是用 shell 写的，读也用同一条链路，才不会出现「本进程 Settings 缓存还是旧值」的假失败；
     * 没有通道时再退回本进程读。
     */
    private suspend fun readGlobalInt(key: String): Int? =
        shellSetting("global", key) ?: globalInt(key)

    private suspend fun readSecureInt(key: String): Int? =
        shellSetting("secure", key) ?: secureInt(key)

    /** 读字符串设置值（用于 settings_op set 的回读）：同样优先走注入通道 */
    private suspend fun settingString(ns: String, key: String): String? {
        shell("settings get $ns $key")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { return it }
        val local = runCatching {
            when (ns) {
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                "global" -> Settings.Global.getString(context.contentResolver, key)
                else -> Settings.System.getString(context.contentResolver, key)
            }
        }.getOrNull()
        return local
    }

    /**
     * 等系统真的把值落下去：每 400ms 回读一次、最多 5 次，返回最后一次读到的值。
     * 设置类写入不是同步生效（亮度/开关都有过渡态），只读一次容易把「过渡中」误判成失败。
     */
    private suspend fun awaitValue(probe: suspend () -> Int?, expect: (Int) -> Boolean): Int? {
        var last: Int? = null
        repeat(5) {
            last = probe()
            val current = last
            if (current != null && expect(current)) return current
            delay(400)
        }
        return last ?: probe()
    }

    /**
     * 系统设置写入：先走本应用身份（需「修改系统设置」授权），没有授权再改走注入通道。
     *
     * 写入成功不再等于「改成功」：写完必须回读，值与期望不一致就如实失败，
     * 常见原因是自动亮度/设备策略/其它应用在覆盖同一个 key。
     */
    private suspend fun writeSystemSetting(key: String, value: Int, label: String): OpsResult {
        val appWriteAllowed = Settings.System.canWrite(context)
        val written = appWriteAllowed &&
            runCatching { Settings.System.putInt(context.contentResolver, key, value) }.isSuccess
        if (!written && shell("settings put system $key $value") == null) {
            return noChannel("${label}失败：${writeSettingsReason()}")
        }
        val actual = awaitValue(
            probe = { systemInt(key) ?: shellSetting("system", key) },
            expect = { it == value },
        )
        return if (actual == value) {
            OpsResult.Ok("$label（回读 system/$key=$actual）")
        } else {
            OpsResult.Fail(
                "$label 未生效：回读 system/$key=${actual ?: "读不到"}，期望 $value。" +
                    "可能被自动亮度、省电模式或设备策略覆盖：请先关掉自动亮度/省电模式再试，" +
                    "也可以到 设置里手动改一次",
            )
        }
    }

    private suspend fun brightness(level: Int?): OpsResult {
        if (level == null) {
            return OpsResult.Fail("brightness 需要 level：0~100 的整数，例如 50 表示一半亮度")
        }
        val percent = level.coerceIn(0, 100)
        // 与 Eta 一致：有注入通道时走 `cmd display set-brightness --unit percentage`，
        // 它直接改显示亮度，不受「自动亮度」开关影响；没有通道再退回写 Settings。
        val viaDisplay = shell("cmd display set-brightness $percent --unit percentage")
        if (viaDisplay != null) {
            var actual: Double? = null
            repeat(5) {
                delay(400)
                actual = shell("cmd display get-brightness --unit percentage")?.trim()?.toDoubleOrNull()
                val current = actual
                if (current != null && kotlin.math.abs(current - percent) <= 1.0) {
                    return OpsResult.Ok("亮度已设为 $percent%（回读 ${current}%）")
                }
            }
            return OpsResult.Fail(
                "亮度未达到目标：回读 ${actual?.let { "$it%" } ?: "读不到"}，期望 $percent%。" +
                    "多半是「自动亮度」或省电策略在覆盖，请先关闭自动亮度再试，" +
                    "也可以到 设置 → 显示 → 亮度 手动拖到目标位置",
            )
        }
        return writeSystemSetting(
            Settings.System.SCREEN_BRIGHTNESS,
            percent * 255 / 100,
            "亮度已设为 $percent%（系统值 ${percent * 255 / 100}/255）",
        )
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun volume(args: Map<String, String>): OpsResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return OpsResult.Fail("音量：音频服务不可用。请按手机侧边的音量键手动调，或重启手机后再试")
        val stream = when ((args["stream"] ?: "music").lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "notification", "notify" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val level = intArg(args, "level")
            ?: return OpsResult.Fail("volume 需要 level：0~100 的整数，例如 50 表示一半音量")
        val max = am.getStreamMaxVolume(stream)
        val target = (level.coerceIn(0, 100) * max / 100)
        return runCatching {
            am.setStreamVolume(stream, target, 0)
            val actual = am.getStreamVolume(stream)
            if (actual == target) {
                OpsResult.Ok("音量已设为 $level%（回读 $actual/$max）")
            } else {
                OpsResult.Fail(
                    "音量未生效：期望 $target/$max，回读 $actual/$max。" +
                        "勿扰模式或「固定音量」策略可能拦住了铃声/通知音量：" +
                        "请先关掉勿扰、或在 设置 → 声音 里检查「固定音量」，也可以直接按侧边音量键手动调",
                )
            }
        }.getOrElse {
            OpsResult.Fail(
                "音量调整失败：${it.message ?: "系统拒绝"}。请按手机侧边的音量键手动调一次，" +
                    "若仍不行，检查是否开启了勿扰模式或音量被家长控制/设备策略锁定",
            )
        }
    }

    private fun mediaKey(action: String): OpsResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return OpsResult.Fail("媒体控制：音频服务不可用。请先打开音乐/视频应用，再在应用里手动播放或暂停")
        val code = when (action.lowercase()) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY
        }
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val up = KeyEvent(KeyEvent.ACTION_UP, code)
        return runCatching {
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            OpsResult.Ok("已发送媒体键：$action")
        }.getOrElse {
            OpsResult.Fail(
                "媒体键发送失败：${it.message ?: "系统拒绝"}。请先打开一个正在播放的音乐/视频应用再试，" +
                    "或直接在该应用里手动操作",
            )
        }
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "MaxLineLength")
    private fun soundMode(mode: String?): OpsResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return OpsResult.Fail("声音模式：音频服务不可用。请直接按侧边音量键或拨动静音开关手动切换")
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val target = when (mode?.lowercase()) {
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "ring", null -> AudioManager.RINGER_MODE_NORMAL
            else -> return OpsResult.Fail(
                "sound_mode 的 mode 只能是 ring（响铃）、vibrate（震动）、silent（静音），" +
                    "例如 mode=silent；也可以直接拨动手机侧边的静音开关",
            )
        }
        return runCatching {
            am.ringerMode = target
            val actual = am.ringerMode
            if (actual == target) {
                OpsResult.Ok("已切换到${when (target) { AudioManager.RINGER_MODE_VIBRATE -> "震动"; AudioManager.RINGER_MODE_SILENT -> "静音"; else -> "响铃" }}（回读 ringerMode=$actual）")
            } else {
                OpsResult.Fail(
                    "声音模式未生效：期望 $target，回读 $actual（勿扰模式可能限制铃声）。" +
                        "请先关闭勿扰模式，或直接拨动手机侧边的静音/响铃开关",
                )
            }
        }.getOrElse {
            OpsResult.Fail(
                "切换声音模式失败：${it.message ?: "系统拒绝"}（勿扰模式可能限制了铃声）。" +
                    "请先关闭勿扰，再拨动侧边静音开关手动切换",
            )
        }
    }

    /**
     * 勿扰：优先用注入通道 `cmd notification set_dnd`（与 Eta 一致）。
     *
     * 系统 API `setInterruptionFilter` 需要用户在设置里手动授予「勿扰访问权限」，
     * 大量机型上这一步不可达；注入通道直接改 zen_mode，不需要该授权。
     * 两种路径都必须回读 `zen_mode` 才算成功。
     */
    private suspend fun dnd(enable: Boolean?): OpsResult {
        if (enable == null) {
            return OpsResult.Fail("dnd 需要 enable：true 表示开启勿扰，false 表示关闭，例如 enable=true")
        }
        val expectedOpen = enable
        val expect: (Int) -> Boolean = { if (expectedOpen) it != 0 else it == 0 }

        val viaShell = shell("cmd notification set_dnd ${if (enable) "priority" else "off"}")
        if (viaShell != null) {
            val actual = awaitValue(
                probe = { readGlobalInt("zen_mode") },
                expect = expect,
            )
            return if (actual != null && expect(actual)) {
                OpsResult.Ok("勿扰已${if (enable) "开启" else "关闭"}（回读 zen_mode=$actual）")
            } else {
                OpsResult.Fail("勿扰命令已执行，但回读 zen_mode=${actual ?: "读不到"}，与期望不一致。" +
                    "请下拉控制中心看一眼勿扰图标；部分 ROM 的「专注模式」会覆盖 zen_mode")
            }
        }

        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return OpsResult.Fail("勿扰：通知服务不可用。请下拉控制中心点「勿扰」图标手动切换")
        if (!nm.isNotificationPolicyAccessGranted) {
            return OpsResult.Fail(
                "勿扰：没有 Shizuku/无线 ADB 通道，且未授予「勿扰访问权限」。" +
                    "请到 设置 → 通知 → 勿扰访问权限 里给本应用开启，或配置注入通道",
            )
        }
        return runCatching {
            nm.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            val actual = awaitValue(
                probe = { readGlobalInt("zen_mode") },
                expect = expect,
            )
            if (actual != null && expect(actual)) {
                OpsResult.Ok("勿扰已${if (enable) "开启" else "关闭"}（回读 zen_mode=$actual）")
            } else {
                OpsResult.Fail("勿扰切换未生效：回读 zen_mode=${actual ?: "读不到"}。" +
                    "请到 设置 → 通知 → 勿扰 手动确认一下")
            }
        }.getOrElse { OpsResult.Fail("勿扰切换失败：${it.message ?: "系统拒绝"}；请到 设置 → 通知 → 勿扰访问权限 检查授权") }
    }

    private suspend fun screenTimeout(seconds: Int?): OpsResult {
        if (seconds == null) {
            return OpsResult.Fail("screen_timeout 需要 seconds：5~1800 的秒数，例如 30 秒填 seconds=30")
        }
        val effective = seconds.coerceIn(5, 1800)
        return writeSystemSetting(
            Settings.System.SCREEN_OFF_TIMEOUT,
            effective * 1000,
            "息屏时间已设为 $effective 秒",
        )
    }

    private suspend fun rotation(enable: Boolean?): OpsResult {
        if (enable == null) {
            return OpsResult.Fail("rotation 需要 enable：true 表示开启自动旋转，false 表示锁定方向，例如 enable=false")
        }
        return writeSystemSetting(
            Settings.System.ACCELEROMETER_ROTATION,
            if (enable) 1 else 0,
            "自动旋转已${if (enable) "开启" else "关闭"}",
        )
    }

    /**
     * 深色模式：`cmd uimode night yes|no` 改的是 Configuration 的夜间位。
     *
     * 与其它开关同一标准：改完必须回读当前夜间模式，读不到或读到「跟随时间自动切换」
     * 都不能算成功——「自动」档位下用户会在日落后被系统改回去。
     */
    private suspend fun darkMode(enable: Boolean?): OpsResult {
        if (enable == null) {
            return OpsResult.Fail("dark_mode 需要 enable：true 表示开启深色模式，false 表示关闭，例如 enable=true")
        }
        val want = if (enable) "yes" else "no"
        val out = shell("cmd uimode night $want")
            ?: return noChannel(
                "切换深色模式需要 Shizuku/无线 ADB 通道（系统不开放第三方应用改夜间模式）。" +
                    "可改走 open_app 打开「设置 → 显示 → 深色模式」手动切换。",
            )
        val actual = awaitValue(
            probe = { shell("cmd uimode night")?.let(::parseNightMode) },
            expect = { it == if (enable) 1 else 0 },
        )
        return when (actual) {
            if (enable) 1 else 0 ->
                OpsResult.Ok("深色模式已${if (enable) "开启" else "关闭"}（回读 uimode night=$want）")
            -1 -> OpsResult.Fail(
                "深色模式回读为「跟随时间自动切换」，命令没有定死档位。请到 设置 → 显示 → 深色模式 手动选「始终」",
            )
            else -> OpsResult.Fail(
                "深色模式命令已执行，但回读=${if (actual == null) "读不到" else if (actual == 1) "yes" else "no"}，期望 $want，" +
                    "无法确认是否生效。请到 设置 → 显示 看一眼；系统回执：${out.trim().take(120)}",
            )
        }
    }

    /** `cmd uimode night` 的输出：`Night mode: yes|no|auto`，auto 返回 -1 表示档位未定死 */
    private fun parseNightMode(output: String): Int? {
        val text = output.lowercase()
        return when {
            text.contains("night_no") -> 0
            text.contains("night_yes") -> 1
            text.contains("night_auto") -> -1
            Regex("\\byes\\b").containsMatchIn(text) -> 1
            Regex("\\bno\\b").containsMatchIn(text) -> 0
            else -> null
        }
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun systemToggle(op: String, enable: Boolean?): OpsResult {
        if (enable == null) {
            return OpsResult.Fail("$op 需要 enable：true 表示开启，false 表示关闭，例如 enable=true")
        }
        val on = if (enable) "enable" else "disable"
        val cmd = when (op) {
            "wifi" -> "svc wifi $on"
            "bluetooth" -> "cmd bluetooth_manager $on"
            "airplane" -> "cmd connectivity airplane-mode $on"
            "location" -> "cmd location set-location-enabled ${if (enable) "true" else "false"}"
            "nfc" -> "svc nfc $on"
            else -> return OpsResult.Fail(
                "不支持的系统开关：$op；可用的有 wifi、bluetooth、airplane、location、nfc",
            )
        }
        val out = shell(cmd)
            ?: return noChannel(
                "$op：需要 Shizuku/无线 ADB 通道（或 Root），当前没有可用注入通道。" +
                    "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权；" +
                    "也可以下拉控制中心手动切换$op",
            )

        // 命令有回执 ≠ 开关真的变了：逐项回读系统设置里的真实状态
        val probe: suspend () -> Int? = when (op) {
            "wifi" -> { { readGlobalInt("wifi_on") } }
            "bluetooth" -> { { readGlobalInt("bluetooth_on") } }
            "airplane" -> { { readGlobalInt("airplane_mode_on") } }
            "location" -> { { readSecureInt("location_mode") } }
            else -> { { null } }
        }
        if (op == "nfc") {
            // NFC 没有可回读的状态值：只能报「无法确认」，不能报成功
            val reply = out.trim()
            val noService = reply.contains("Can't find service", ignoreCase = true) ||
                reply.contains("null NfcAdapter", ignoreCase = true) ||
                reply.contains("Could not find provider", ignoreCase = true)
            return OpsResult.Fail(
                if (noService) {
                    "本机没有可用的 NFC 服务（系统回执：${reply.take(120).ifBlank { "无输出" }}）。" +
                        "如果这台手机确实有 NFC，请到 设置 → 连接 → NFC 手动打开；没有 NFC 硬件就忽略这条指令"
                } else {
                    "NFC 命令已执行，但本机读不到 NFC 开关状态，无法确认是否${if (enable) "开启" else "关闭"}。" +
                        "请下拉控制中心看一眼 NFC 图标；系统回执：${reply.ifBlank { "无输出" }.take(200)}"
                },
            )
        }
        val expect: (Int) -> Boolean = when (op) {
            "location" -> { v -> if (enable) v != 0 else v == 0 }
            else -> { v -> if (enable) v == 1 else v == 0 }
        }
        val actual = awaitValue(probe, expect)
        return when {
            actual != null && expect(actual) ->
                OpsResult.Ok("$op 已${if (enable) "开启" else "关闭"}（回读开关值=$actual）")
            actual != null ->
                OpsResult.Fail("$op 命令已执行，但回读开关值=$actual，与期望${if (enable) "开启" else "关闭"}不一致。" +
                    "可能被飞行模式或设备策略阻止：请先关闭飞行模式/省电模式后重试，或下拉控制中心手动切换 $op")
            else ->
                // 命令有回执但读不到真实开关值：不能当成设置成功，只能如实报「无法确认」
                OpsResult.Fail("$op 命令已执行，但本机读不到开关状态，无法确认是否${if (enable) "开启" else "关闭"}。" +
                    "请下拉控制中心看一眼 $op 图标；系统回执：${out.ifBlank { "无输出" }.take(200)}")
        }
    }

    // --------------------------------------------------------------- settings

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun settingsOp(op: String, args: Map<String, String>): OpsResult {
        val ns = (args["namespace"] ?: "system").lowercase()
        val key = args["key"]?.trim().orEmpty()
        if (key.isBlank()) {
            return OpsResult.Fail(
                "settings_op 需要 key：要读写的设置项名称，例如 key=screen_brightness（system 命名空间）",
            )
        }
        if (!KEY_RE.matches(key)) {
            return OpsResult.Fail("设置项 key 含非法字符：$key。key 只能用小写字母、数字、下划线和点，请改正后重试")
        }
        if (ns !in setOf("system", "secure", "global")) {
            return OpsResult.Fail("namespace 只能是 system、secure、global 三者之一，例如 namespace=system")
        }
        if (op == "get") {
            val value = runCatching {
                when (ns) {
                    "secure" -> Settings.Secure.getString(context.contentResolver, key)
                    "global" -> Settings.Global.getString(context.contentResolver, key)
                    else -> Settings.System.getString(context.contentResolver, key)
                }
            }.getOrNull()
            return OpsResult.Ok(if (value == null) "$ns/$key = 未设置（或读取被系统限制）" else "$ns/$key = $value")
        }
        if (op != "set") return OpsResult.Fail("settings_op 只支持 op=get（读）和 op=set（写）")
        val value = args["value"] ?: return OpsResult.Fail("settings_op 的 set 需要 value：要写入的值，例如 value=1")
        // 优先用注入命令（能写 secure/global，不改系统权限）；没有注入通道时退回 system 的可写授权
        val out = shell("settings put $ns $key ${quote(value)}")
        val appWritten = if (out == null && ns == "system" && Settings.System.canWrite(context)) {
            runCatching { Settings.System.putString(context.contentResolver, key, value) }.isSuccess
        } else {
            false
        }
        if (out == null && !appWritten) {
            return noChannel(
                "写入 $ns/$key 失败：需要 Shizuku/无线 ADB 通道；" +
                    (if (ns == "system") "或先授予「修改系统设置」权限。" else "secure/global 只能走注入通道。") +
                    "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权后重试",
            )
        }
        // 写完回读：值没落下去就如实失败，不报「已写入」
        var actual: String? = null
        repeat(3) {
            actual = settingString(ns, key)
            if (actual == value) return OpsResult.Ok("$ns/$key 已写入 $value（回读一致）")
            delay(300)
        }
        return OpsResult.Fail(
            "$ns/$key 写入未生效：回读=${actual ?: "读不到"}，期望 $value。" +
                "请确认 key 名称拼写正确；有些项被系统锁定改不了，就不要继续硬改",
        )
    }

    // -------------------------------------------------------------- clipboard

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun clipboardOp(op: String, args: Map<String, String>): OpsResult {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return OpsResult.Fail("剪贴板服务不可用。请重启手机后再试，或手动长按文本复制/粘贴")
        return when (op) {
            "get" -> {
                val clip = runCatching { cm.primaryClip }.getOrNull()
                    ?: return OpsResult.Fail("剪贴板：读不到内容（Android 10+ 限制后台读取，请先让本应用在前台）")
                val text = (0 until clip.itemCount).joinToString("\n") { i ->
                    clip.getItemAt(i).coerceToText(context).toString()
                }.trim()
                OpsResult.Ok(if (text.isEmpty()) "剪贴板为空" else "剪贴板内容：\n${text.take(2000)}")
            }
            "set" -> {
                val text = args["text"] ?: return OpsResult.Fail("clipboard set 需要 text：要复制到剪贴板的内容")
                runCatching { cm.setPrimaryClip(ClipData.newPlainText("phantom", text)) }
                    .getOrElse {
                        return OpsResult.Fail(
                            "写剪贴板失败：${it.message ?: "系统拒绝"}。" +
                                "请把本应用切到前台后重试，或手动长按文本复制",
                        )
                    }
                // 写入后回读：拿到的内容一致才算真的写进去了
                val actual = runCatching {
                    cm.primaryClip?.let { clip ->
                        (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context).toString() }
                    }
                }.getOrNull()
                if (actual == text) {
                    OpsResult.Ok("已写入剪贴板（回读一致，${text.length} 字）")
                } else {
                    OpsResult.Fail(
                        "剪贴板写入未确认：回读为「${actual?.take(40) ?: "读不到"}」。" +
                            "Android 10+ 限制后台读剪贴板，请把本应用切到前台后再让我写一次",
                    )
                }
            }
            "clear" -> {
                cm.clearPrimaryClip()
                OpsResult.Ok("剪贴板已清空")
            }
            else -> OpsResult.Fail("clipboard 只支持 get/set/clear")
        }
    }

    // ------------------------------------------------------------ alarm_manage

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun alarmManage(op: String, args: Map<String, String>): OpsResult {
        val uriOut = queryAlarms()
            ?: return OpsResult.Fail("闹钟管理：读不到时钟应用的闹钟数据。系统没有开放闹钟数据库访问，" +
                "需要 Root/Shizuku 的时钟 Provider；没有时可改走 open_app 打开时钟应用用手势操作，或重新 set_alarm 新增。")
        if (op == "list") return OpsResult.Ok("现有闹钟：\n$uriOut")
        val hour = intArg(args, "hour")
        val minute = intArg(args, "minute")
        if (hour == null || minute == null) {
            return OpsResult.Fail(
                "alarm_manage 的 $op 需要 hour 与 minute 定位已有闹钟，例如 hour=7、minute=30；" +
                    "不确定现有闹钟时间时先调 alarm_manage（op=list）看一眼",
            )
        }
        val where = "hour=$hour AND minutes=$minute"
        return when (op) {
            "enable" -> {
                val enable = boolArg(args,
                    "enable") ?: return OpsResult.Fail(
                    "alarm_manage enable 需要 enable：true 表示启用这条闹钟，false 表示关闭，例如 enable=false",
                )
                val flag = if (enable) "1" else "0"
                updateAlarm(where, listOf(Triple("enabled", "i", flag)))
                    .fold(
                        {
                            // 命令有回执不等于开关变了：回查同一时分的闹钟行
                            val row = alarmRow(queryAlarms().orEmpty(), hour, minute)
                            when {
                                row == null -> OpsResult.Fail(
                                    "已执行${if (enable) "启用" else "关闭"}，但回查不到 $hour:${"%02d".format(minute)} 的闹钟，" +
                                        "无法确认是否生效。请打开时钟应用看一眼这条闹钟的开关",
                                )
                                row.contains("enabled=$flag") -> OpsResult.Ok(
                                    "已${if (enable) "启用" else "关闭"} $hour:${"%02d".format(minute)} 的闹钟（回查 enabled=$flag）",
                                )
                                else -> OpsResult.Fail(
                                    "闹钟开关未改动：回查 $hour:${"%02d".format(minute)} 仍是 ${Regex("enabled=(\\d)")
                                        .find(row)?.groupValues?.get(1) ?: "未知"}。" +
                                        "部分厂商时钟不开放该字段，请打开时钟应用手动切换",
                                )
                            }
                        },
                        { OpsResult.Fail(
                            "改闹钟开关失败：${it.message ?: "未知原因"}（需要 Root/Shizuku 且厂商时钟开放 Provider）。" +
                                "请到应用设置里确认注入通道已连接并授权，或打开时钟应用手动切换这条闹钟",
                        ) },
                    )
            }
            "delete" -> {
                val out = shell("content delete --uri ${alarmUri()} --where ${quote(where)}")
                if (out.isNullOrBlank()) {
                    OpsResult.Fail(
                        "删除闹钟失败：注入通道不可用或时钟 Provider 拒绝。" +
                            "请到应用设置里确认 Shizuku/无线 ADB 已连接并授权后重试，" +
                            "或打开时钟应用手动删掉这条闹钟",
                    )
                } else {
                    // 删除命令有回执不等于行没了：回查一遍，还在就如实报失败
                    val after = queryAlarms().orEmpty()
                    if (after.contains("hour=$hour") && after.contains("minutes=$minute")) {
                        OpsResult.Fail(
                            "已执行删除，但回查时钟 Provider 仍能看到 $hour:${"%02d".format(minute)} 的闹钟。" +
                                "请打开时钟应用手动删除这条",
                        )
                    } else {
                        OpsResult.Ok("已删除 $hour:${"%02d".format(minute)} 的闹钟（回查已不存在；${out.take(200)}）")
                    }
                }
            }
            "update" -> {
                val nh = intArg(args, "new_hour")
                    ?: return OpsResult.Fail("alarm_manage update 需要 new_hour：改后的小时（0~23），例如 new_hour=8")
                val nm = intArg(args, "new_minute")
                    ?: return OpsResult.Fail("alarm_manage update 需要 new_minute：改后的分钟（0~59），例如 new_minute=15")
                updateAlarm(where, listOf(Triple("hour", "i", nh.toString()), Triple("minutes", "i", nm.toString())))
                    .fold(
                        {
                            if (alarmRow(queryAlarms().orEmpty(), nh, nm) != null) {
                                OpsResult.Ok("闹钟已改为 $nh:${"%02d".format(nm)}（回查新时间已存在）")
                            } else {
                                OpsResult.Fail(
                                    "改闹钟时间的命令已执行，但回查不到 $nh:${"%02d".format(nm)} 的闹钟，" +
                                        "无法确认是否生效。请打开时钟应用看一眼列表",
                                )
                            }
                        },
                        { OpsResult.Fail(
                            "改闹钟时间失败：${it.message ?: "未知原因"}（需要 Root/Shizuku 且厂商时钟开放 Provider）。" +
                                "请到应用设置里确认注入通道已连接并授权，或打开时钟应用手动改时间",
                        ) },
                    )
            }
            else -> OpsResult.Fail("alarm_manage 只支持 list/enable/delete/update")
        }
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun updateAlarm(where: String, binds: List<Triple<String, String, String>>): kotlin.Result<String> {
        val bind = binds.joinToString(" ") { (k, type, v) -> "--bind $k:$type:${quote(v)}" }
        val out = shell("content update --uri ${alarmUri()} --where ${quote(where)} $bind")
            ?: return kotlin.Result.failure(IllegalStateException("注入通道不可用"))
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return kotlin.Result.failure(IllegalStateException("Provider 未匹配到该闹钟或拒绝写入"))
        }
        return kotlin.Result.success(out.take(200))
    }

    /** 从闹钟 Provider 的输出里挑出「某时某分」那一行（写后回查用） */
    private fun alarmRow(query: String, hour: Int, minute: Int): String? =
        query.lineSequence()
            .firstOrNull { it.contains("hour=$hour") && it.contains("minutes=$minute") }
            ?.trim()

    /** 最近一次查询成功的闹钟 Provider URI：写/删沿用同一条，避免厂商机上 AOSP URI 不生效 */
    private var alarmUriInUse: String? = null

    /** 依次尝试常见时钟 Provider，返回第一条可用输出 */
    private suspend fun queryAlarms(): String? {
        for (uri in ALARM_URIS) {
            val out = shell("content query --uri $uri --projection _id:hour:minutes:enabled:label") ?: continue
            if (out.isNotBlank() && !out.contains("Error", ignoreCase = true) && !out.contains("No result",
                ignoreCase = true)) {
                alarmUriInUse = uri
                return out.take(2000)
            }
        }
        alarmUriInUse = null
        return null
    }

    /** 写/删闹钟用哪条 URI：优先用刚才查询成功的那条 */
    private fun alarmUri(): String = alarmUriInUse ?: ALARM_URI

    // ---------------------------------------------------------- calendar_op

    /**
     * 日历事件读写（对齐 Eta 的 list_calendars / create|update|delete_calendar_event）。
     *
     * 走注入通道的 `content` 命令而不是 ContentResolver：日历 Provider 的读写权限只有系统/日历应用持有，
     * 以本应用身份直接调用会被拒；以 shell 身份执行才拿得到（与闹钟管理同一个思路）。
     * 没有通道时不假装成功，直接给中文原因 + 替代路线。
     */
    private suspend fun calendarOp(op: String, args: Map<String, String>): OpsResult = when (op) {
        "list" -> calendarList(args)
        "create" -> calendarCreate(args)
        "update" -> calendarUpdate(args)
        "delete" -> calendarDelete(args)
        else -> OpsResult.Fail("calendar_op 只支持 list/create/update/delete")
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun calendarList(args: Map<String, String>): OpsResult {
        val days = (args["days"]?.toLongOrNull() ?: 30L).coerceIn(1L, 365L)
        val limit = (args["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val query = args["query"]?.trim().orEmpty()
        val now = System.currentTimeMillis()
        val until = now + days * 24 * 3600_000L
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val out = shell("content query --uri $CALENDAR_URI --projection _id:title:dtstart:dtend --where ${quote("dtstart>=$now AND dtstart<=$until")} --sort ${quote("dtstart ASC")}")
            ?: return noChannel(
                "读日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动查看。",
            )
        val rows = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("No result") && !it.startsWith("Error") }
            .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .take(limit)
            .map { line -> formatCalendarRow(line) }
            .toList()
        if (rows.isEmpty()) {
            return OpsResult.Ok("未来 $days 天没有${if (query.isEmpty()) "" else "标题含「$query」的"}日程。")
        }
        return OpsResult.Ok("未来 $days 天的日程（${rows.size} 条）：\n" + rows.joinToString("\n") { "- $it" }.take(4000))
    }

    /** 把 `Row: 0 _id=.., title=..` 这类原始行压成「时间 · 标题 · id」 */
    private fun formatCalendarRow(line: String): String {
        fun field(key: String): String? =
            Regex("$key=([^,]+)").find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val id = field("_id") ?: "?"
        val title = field("title") ?: "（无标题）"
        val start = field("dtstart")?.toLongOrNull()?.let { formatCalendarTime(it) } ?: "时间未知"
        val end = field("dtend")?.toLongOrNull()?.let { formatCalendarTime(it) }
        return "$start${if (end != null) " ~ $end" else ""} · $title（id=$id）"
    }

    private fun formatCalendarTime(ms: Long): String = CALENDAR_TIME.format(Date(ms))

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "MaxLineLength")
    private suspend fun calendarCreate(args: Map<String, String>): OpsResult {
        val title = args["title"]?.trim().orEmpty()
        if (title.isEmpty()) return OpsResult.Fail("calendar_op create 需要 title（事件标题）")
        val startText = args["start"]?.trim().orEmpty()
        if (startText.isEmpty()) return OpsResult.Fail("calendar_op create 需要 start（如「2026-11-05 09:00」）")
        val startMs = parseCalendarTime(startText)
            ?: return OpsResult.Fail("读不懂 start=$startText；请写成「YYYY-MM-DD HH:mm」或毫秒时间戳")
        val endMs = args["end"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            parseCalendarTime(text) ?: return OpsResult.Fail("读不懂 end=$text；请写成「YYYY-MM-DD HH:mm」或毫秒时间戳")
        } ?: (startMs + (args["duration_min"]?.toLongOrNull() ?: 60L) * 60_000L)
        val calendarId = args["calendar_id"]?.toLongOrNull() ?: 1L
        val allDay = boolArg(args, "all_day") ?: false
        val binds = buildList {
            add("calendar_id:i:$calendarId")
            add("title:s:${quote(title)}")
            add("dtstart:l:$startMs")
            add("dtend:l:$endMs")
            add("allDay:i:${if (allDay) 1 else 0}")
            add("eventTimezone:s:${quote(java.util.TimeZone.getDefault().id)}")
        }.joinToString(" --bind ")
        val out = shell("content insert --uri $CALENDAR_URI --bind $binds")
            ?: return noChannel("写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动新建。")
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return OpsResult.Fail("日历 Provider 拒绝写入：${out.take(200)}。可改走 open_app 打开日历应用手动新建。")
        }
        // 写入命令有回执不等于事件真的在库里：回查同名同开始时间的那条
        val row = findCalendarEvent("title=${quote(title)} AND dtstart=$startMs")
            ?: return OpsResult.Fail(
                "日历写入命令已执行，但回查不到「$title」这条日程，无法确认是否真的写入。" +
                    "请打开日历应用看一眼当天有没有这条；也可以先用 calendar_op list 确认。",
            )
        val newId = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1)
        return OpsResult.Ok(
            "已新建日程「$title」：${formatCalendarTime(startMs)} ~ ${formatCalendarTime(endMs)}" +
                "（calendar_id=$calendarId，回查 id=${newId ?: "?"}）",
        )
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun calendarUpdate(args: Map<String, String>): OpsResult {
        val id = args["event_id"]?.trim().orEmpty()
        if (id.isEmpty()) return OpsResult.Fail("calendar_op update 需要 event_id（从 calendar_op list 结果里取）")
        val binds = buildList {
            args["title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add("title:s:${quote(it)}") }
            args["start"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val ms = parseCalendarTime(text) ?: return OpsResult.Fail("读不懂 start=$text；请写成「YYYY-MM-DD HH:mm」")
                add("dtstart:l:$ms")
            }
            args["end"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                val ms = parseCalendarTime(text) ?: return OpsResult.Fail("读不懂 end=$text；请写成「YYYY-MM-DD HH:mm」")
                add("dtend:l:$ms")
            }
            boolArg(args, "all_day")?.let { add("allDay:i:${if (it) 1 else 0}") }
        }
        if (binds.isEmpty()) return OpsResult.Fail("calendar_op update 至少要给一个要改的字段（title/start/end/all_day）")
        // 记录「改完之后应该读到什么」，用于写后回查
        val expects = buildList {
            args["title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { add("title=$it") }
            args["start"]?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                parseCalendarTime(text)?.let { add("dtstart=$it") }
            }
            boolArg(args, "all_day")?.let { add("allDay=${if (it) 1 else 0}") }
        }
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val out = shell("content update --uri $CALENDAR_URI --where ${quote("_id=$id")} --bind ${binds.joinToString(" --bind ")}")
            ?: return noChannel(
                "写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动改这条日程。",
            )
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return OpsResult.Fail("没有找到 id=$id 的日程，或日历 Provider 拒绝写入。先用 calendar_op list 确认 id。")
        }
        // 命令有回执不等于字段改了：按 _id 回查，逐个核对本次提交的值
        val row = findCalendarEvent("_id=$id")
            ?: return OpsResult.Fail(
                "更新命令已执行，但回查不到 id=$id 的日程，无法确认是否生效。" +
                    "若列表里也没有这条，说明它已被删除；否则请打开日历应用手动改一次。",
            )
        val missing = expects.filterNot { row.contains(it) }
        return if (missing.isEmpty()) {
            OpsResult.Ok("已更新日程 id=$id（回查 ${expects.joinToString("、")} 均已生效）")
        } else {
            OpsResult.Fail(
                "日程已改动，但回查 ${missing.joinToString("、")} 与提交值不一致，可能被日历应用拒绝。" +
                    "请打开日历应用核对该日程。",
            )
        }
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun calendarDelete(args: Map<String, String>): OpsResult {
        val id = args["event_id"]?.trim().orEmpty()
        if (id.isEmpty()) return OpsResult.Fail("calendar_op delete 需要 event_id（从 calendar_op list 结果里取）")
        val out = shell("content delete --uri $CALENDAR_URI --where ${quote("_id=$id")}")
            ?: return noChannel(
                "删日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动删除这条日程。",
            )
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return OpsResult.Fail("没有找到 id=$id 的日程，或日历 Provider 拒绝删除。先用 calendar_op list 确认 id。")
        }
        // 删除同样要看结果：按 _id 再查一次，还能查到就是没删掉
        if (findCalendarEvent("_id=$id") != null) {
            return OpsResult.Fail(
                "删除命令已执行，但回查 id=$id 的日程还在，删除没有生效。请打开日历应用手动删除这条。",
            )
        }
        return OpsResult.Ok("已删除日程 id=$id（回查已不存在）")
    }

    /** 回查单条日历事件：命中返回原始行；读不到或不存在返回 null */
    private suspend fun findCalendarEvent(where: String): String? {
        val out = shell(
            "content query --uri $CALENDAR_URI --projection _id:title:dtstart:dtend:allDay --where ${quote(where)}",
        ) ?: return null
        if (out.contains("Error", ignoreCase = true)) return null
        return out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("_id=") && !it.startsWith("No result") }
    }

    /**
     * 时间解析：纯数字按毫秒时间戳；否则依次尝试几种常见写法。
     * 只到分钟精度——日历事件的秒级误差没有意义，少给模型一种"猜格式"的负担。
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun parseCalendarTime(raw: String): Long? {
        val text = raw.trim()
        text.toLongOrNull()?.let { return it }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "MM-dd HH:mm", "M月d日 HH:mm", "M月d日",
        )
        for (p in patterns) {
            val fmt = SimpleDateFormat(p, Locale.getDefault()).apply { isLenient = false }
            val parsed = runCatching { fmt.parse(text) }.getOrNull() ?: continue
            if (parsed != null) return parsed.time
        }
        // 只给了「HH:mm」：按今天算，已过去则顺延到明天
        runCatching { SimpleDateFormat("HH:mm",
            Locale.getDefault()).apply { isLenient = false }.parse(text) }.getOrNull()
            ?.let { t ->
                val cal = java.util.Calendar.getInstance().apply {
                    time = t
                    val now = java.util.Calendar.getInstance()
                    set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
                    set(java.util.Calendar.DAY_OF_YEAR, now.get(java.util.Calendar.DAY_OF_YEAR))
                    if (timeInMillis <= now.timeInMillis) add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }
        return null
    }

    // ---------------------------------------------------------- virtual_display

    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition", "MaxLineLength")
    private suspend fun virtualDisplayOp(op: String, args: Map<String, String>): OpsResult {
        // 条件由多个协议字段组合而成，抽成具名布尔量会分散逻辑，此处按规则豁免
        @Suppress("ComplexCondition", "MaxLineLength")
        val agent = virtualDisplay()
            ?: return OpsResult.Fail(
                "副屏：本任务未启用副屏模式。请先切到自由模式，并在应用设置里确认 Shizuku/无线 ADB 已连接",
            )
        return when (op) {
            "status" -> OpsResult.Ok("副屏：${if (agent.isReady) "已创建（display=${agent.displayId}）" else "未创建"}")
            "create" -> outcome(agent.ensure(), "创建副屏")
            "destroy" -> run { agent.destroy(); OpsResult.Ok("副屏已销毁") }
            "launch" -> {
                val app = args["app"]
                    ?: return OpsResult.Fail("virtual_display launch 需要 app：写应用名或包名，例如 app=微信")
                val pkg = resolvePackage(app) ?: app
                // 记下最近一次在副屏里拉起的是谁：present 不带 app 时靠它把画面投回主屏
                lastVirtualApp = pkg
                outcome(agent.launch(pkg), "副屏启动应用")
            }
            "tap" -> {
                val x = intArg(args, "x"); val y = intArg(args, "y")
                if (x == null || y == null) {
                    OpsResult.Fail("virtual_display tap 需要 x/y 屏幕坐标；不确定位置时先调 op=screenshot 看一眼画面")
                } else {
                    outcome(agent.tap(x, y), "副屏点击")
                }
            }
            "swipe" -> {
                val x1 = intArg(args, "x1"); val y1 = intArg(args, "y1")
                val x2 = intArg(args, "x2"); val y2 = intArg(args, "y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    OpsResult.Fail("virtual_display swipe 需要 x1/y1/x2/y2 起点与终点坐标；不确定位置时先截图看一眼")
                } else {
                    outcome(agent.swipe(x1, y1, x2, y2), "副屏滑动")
                }
            }
            "type" -> {
                val text = args["text"]
                    ?: return OpsResult.Fail("virtual_display type 需要 text：要输入的文本内容")
                outcome(agent.typeText(text), "副屏输入")
            }
            "key" -> {
                val code = when ((args["key"] ?: "").uppercase()) {
                    "BACK" -> 4; "HOME" -> 3; "ENTER" -> 66; "RECENT" -> 187; else -> -1
                }
                if (code < 0) {
                    OpsResult.Fail("virtual_display key 只支持 BACK、HOME、ENTER、RECENT，例如 key=BACK")
                } else {
                    outcome(agent.keyevent(code), "副屏按键")
                }
            }
            "screenshot" -> {
                val bytes = agent.screenshot()
                if (bytes == null) {
                    OpsResult.Fail(
                        "副屏截图失败：当前系统不支持 screencap -d 或副屏尚未就绪。" +
                            "请先调 virtual_display（op=status）看副屏是否已创建，没创建就先 op=create",
                    )
                } else {
                    OpsResult.Ok("副屏截图成功：${bytes.size} 字节 PNG（内容已交给端侧，不在对话里展开）")
                }
            }
            "present" -> presentVirtual(agent, args)
            else -> OpsResult.Fail(
                "virtual_display 不支持 op=$op；可用：status、create、destroy、launch、tap、swipe、type、key、" +
                    "screenshot、present",
            )
        }
    }

    /**
     * 把副屏里操作的应用投到主屏前台（virtual_display present）。
     *
     * 副屏的价值是"不占用用户当前屏幕"，但任务收尾时用户往往想立刻看到结果。
     * 同一应用跨 display 共享进程状态，在 display 0 拉起它 = 把副屏结果呈现给用户，
     * 无需额外的投屏/镜像通路，也就不必去碰 MediaProjection 这类敏感权限。
     */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun presentVirtual(agent: VirtualDisplayAgent, args: Map<String, String>): OpsResult {
        val pkg = args["app"]?.trim()?.takeIf { it.isNotEmpty() }?.let { resolvePackage(it) ?: it }
            ?: lastVirtualApp
            ?: return OpsResult.Fail("present 需要 app：先用 virtual_display launch 在副屏里打开应用，或直接给 app")
        val out = shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            ?: return noChannel(
                "把应用拉到主屏需要 Shizuku 或无线 ADB 注入通道；当前不可用。" +
                    "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权后重试",
            )
        if (out.contains("No activities found", ignoreCase = true) || out.contains("aborted", ignoreCase = true)) {
            return OpsResult.Fail("$pkg 没有可启动的入口（可能是系统应用或未安装）；可改用 open 深链或 show_agent")
        }
        return OpsResult.Ok("已把 $pkg 拉到主屏前台；副屏（display=${agent.displayId}）仍在后台，可继续操作")
    }

    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    private fun outcome(o: VirtualDisplayAgent.Outcome, what: String): OpsResult = when (o) {
        is VirtualDisplayAgent.Outcome.Ok -> OpsResult.Ok("$what 成功${o.output.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}")
        is VirtualDisplayAgent.Outcome.Fail -> OpsResult.Fail("$what 失败：${o.reason}")
    }

    // -------------------------------------------------------------- memory_get

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun memoryGet(args: Map<String, String>): OpsResult {
        val store = memory ?: return OpsResult.Fail("记忆检索：记忆库不可用")
        val limit = (args["limit"]?.toIntOrNull() ?: 8).coerceIn(1, 30)
        val query = args["query"]?.trim().orEmpty()
        val all = runCatching { store.loadAiMemories() }.getOrDefault(emptyList())
        if (all.isEmpty()) return OpsResult.Ok("长期记忆为空（还没有写入过）")
        val matched = (if (query.isEmpty()) all else all.filter {
            it.content.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true)
        }).sortedByDescending { it.createdAt }.take(limit)
        if (matched.isEmpty()) return OpsResult.Ok("没有匹配「$query」的记忆（共 ${all.size} 条）")
        // 带上 #id 与 rev（=updatedAt）：AI 要改/删时必须按 id 定位，rev 用于 update 的版本校验
        return OpsResult.Ok("匹配 ${matched.size} 条（共 ${all.size} 条）：\n" + matched.joinToString("\n") {
            "- #${it.id} rev=${it.updatedAt} [${it.category}] ${it.content.take(180)}"
        })
    }

    // ---------------------------------------------------------------- memory_write

    /**
     * 记忆编辑（不改屏幕）：
     * - write：新增一条，category 默认 general；
     * - update：按 id 改内容，revision 给了就做 CAS（与当前 updatedAt 不一致则拒绝，防覆盖）；
     * - delete：按 id 删除。
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "MaxLineLength")
    private suspend fun memoryWrite(op: String, args: Map<String, String>): OpsResult {
        val store = memory ?: return OpsResult.Fail("记忆编辑：记忆库不可用")
        return when (op) {
            "write" -> {
                val text = args["text"]?.trim().orEmpty()
                if (text.isEmpty()) return OpsResult.Fail("memory_write 的 write 需要 text（要记住的内容）")
                // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                @Suppress("MaxLineLength")
                val category = args["category"]?.trim()?.takeIf { it.isNotBlank() } ?: "general"
                when (val r = store.upsertAiMemory(text, category, "用户指令", "user")) {
                    null -> OpsResult.Fail("记忆写入失败：内容为空")
                    is AiMemoryUpsert.Added -> OpsResult.Ok("已记住 #${r.entry.id} [${r.entry.category}] ${r.entry.content.take(120)}")
                    is AiMemoryUpsert.Updated -> OpsResult.Ok("已更新相近记忆 #${r.entry.id} [${r.entry.category}] ${r.entry.content.take(120)}")
                }
            }
            "update" -> {
                val id = args["id"]?.trim()?.toLongOrNull()
                    ?: return OpsResult.Fail("memory_write 的 update 需要数字 id（先 memory_get 查 id）")
                // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                @Suppress("MaxLineLength")
                val text = args["text"]?.trim().orEmpty()
                if (text.isEmpty()) return OpsResult.Fail("memory_write 的 update 需要 text（新的内容）")
                when (val r = store.updateAiMemory(id, text, args["category"],
                    args["revision"]?.trim()?.toLongOrNull())) {
                    is AiMemoryEdit.Updated -> OpsResult.Ok("已修改记忆 #$id：${r.entry.content.take(120)}（rev=${r.entry.updatedAt}）")
                    AiMemoryEdit.NotFound -> OpsResult.Fail("没有 id=$id 的记忆；先 memory_get 查现有条目")
                    is AiMemoryEdit.Conflict -> OpsResult.Fail(
                        "记忆 #$id 已被改过（现内容：${r.current.content.take(80)}，rev=${r.current.updatedAt}），请按最新内容重试",
                    )
                }
            }
            "delete" -> {
                val id = args["id"]?.trim()?.toLongOrNull()
                    ?: return OpsResult.Fail("memory_write 的 delete 需要数字 id")
                if (store.deleteAiMemory(id)) OpsResult.Ok("已删除记忆 #$id") else OpsResult.Fail("没有 id=$id 的记忆")
            }
            else -> OpsResult.Fail("memory_write 只支持 write/update/delete")
        }
    }

    // ---------------------------------------------------------------- file_read

    /**
     * 只读文件族（对齐 Eta 的 read_file / stat_file / list_directory / glob_files / grep_files）。
     * 只允许 /sdcard、/storage 与应用私有目录，避免 AI 一把梭到 /data 或系统目录；
     * 扫描类操作有节点/条数/文件大小上限，防止把整棵树读进上下文。
     */
    // 结构完整、拆分风险高，强行拆分会牵动较多调用方，此处按规则豁免
    @Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength")
    private suspend fun fileRead(op: String, args: Map<String, String>): OpsResult = withContext(Dispatchers.IO) {
        when (op) {
            "read" -> {
                val raw = args["path"]?.trim().orEmpty()
                if (raw.isEmpty()) return@withContext OpsResult.Fail("file_read 的 read 需要 path（绝对路径，如 /sdcard/Download/a.txt）")
                // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                @Suppress("MaxLineLength")
                val f = resolvePath(raw) ?: return@withContext OpsResult.Fail("路径不可访问：$raw（仅支持 /sdcard、/storage 与应用私有目录）")
                if (!f.exists()) {
                    return@withContext OpsResult.Fail(
                        "文件不存在：${f.path}。请先用 file_read（op=list）看一眼所在目录里实际有哪些文件名",
                    )
                }
                if (f.isDirectory) return@withContext OpsResult.Fail("${f.path} 是目录；列目录请用 op=list")
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@withContext OpsResult.Fail(
                        "读取失败：${f.path} 是二进制文件或不是 UTF-8 文本，读出来也是乱码。" +
                            "若只是想确认它存在，用 op=stat 看大小和时间即可",
                    )
                val cap = 4000
                OpsResult.Ok(buildString {
                    appendLine("${f.path}（${f.length()} 字节）")
                    append(if (text.length > cap) text.take(cap) + "\n…（已截断，全文 ${text.length} 字符）" else text)
                })
            }
            "list" -> {
                val dir = resolvePath(args["path"]?.trim().orEmpty(), "/sdcard")
                    ?: return@withContext OpsResult.Fail("路径不可访问；list 支持 /sdcard、/storage 与应用私有目录")
                if (!dir.isDirectory) return@withContext OpsResult.Fail("不是目录：${dir.path}")
                val limit = (args["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 500)
                val all = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                OpsResult.Ok(buildString {
                    appendLine("${dir.path} 共 ${all.size} 项${if (all.size > limit) "（只列前 $limit 项）" else ""}")
                    all.take(limit).forEach { c ->
                        appendLine(if (c.isDirectory) "- [目录] ${c.name}/" else "- [文件] ${c.name}（${c.length()} 字节）")
                    }
                }.trimEnd())
            }
            "stat" -> {
                val f = resolvePath(args["path"]?.trim().orEmpty())
                    ?: return@withContext OpsResult.Fail("stat 需要可访问的 path")
                if (!f.exists()) return@withContext OpsResult.Fail("路径不存在：${f.path}")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(f.lastModified()))
                OpsResult.Ok("${f.path}\n类型：${if (f.isDirectory) "目录" else "文件"}\n" +
                    "大小：${f.length()} 字节\n修改时间：$time\n可读：${f.canRead()} 可写：${f.canWrite()}")
            }
            "glob" -> {
                val pattern = args["pattern"]?.trim().orEmpty()
                if (pattern.isEmpty()) return@withContext OpsResult.Fail("glob 需要 pattern（如 **/*.md）")
                val root = resolvePath(args["path"]?.trim().orEmpty(), "/sdcard")
                    ?: return@withContext OpsResult.Fail("glob 的搜索根不可访问")
                if (!root.isDirectory) return@withContext OpsResult.Fail("glob 的 path 必须是目录：${root.path}")
                val limit = (args["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                val regex = globToRegex(pattern)
                val basePath = root.path.trimEnd('/')
                val hits = ArrayList<String>()
                walkFiles(root) { f ->
                    val rel = f.path.trimEnd('/').removePrefix("$basePath/")
                    if (regex.matches(rel)) {
                        hits.add("${f.path}（${f.length()} 字节）")
                        hits.size >= limit
                    } else false
                }
                if (hits.isEmpty()) OpsResult.Ok("没有匹配「$pattern」的文件（根目录：${root.path}）")
                else OpsResult.Ok("匹配 ${hits.size} 个文件（根目录：${root.path}）：\n" + hits.joinToString("\n") { "- $it" })
            }
            "grep" -> {
                val pattern = args["pattern"]?.trim().orEmpty()
                if (pattern.isEmpty()) return@withContext OpsResult.Fail("grep 需要 pattern（正则表达式）")
                val regex = runCatching { Regex(pattern) }.getOrElse {
                    return@withContext OpsResult.Fail("grep 的正则不合法：${it.message ?: pattern}")
                }
                val root = resolvePath(args["path"]?.trim().orEmpty(), "/sdcard")
                    ?: return@withContext OpsResult.Fail("grep 的搜索根不可访问")
                if (!root.isDirectory) return@withContext OpsResult.Fail("grep 的 path 必须是目录：${root.path}")
                val limit = (args["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                val hits = ArrayList<String>()
                walkFiles(root) { f ->
                    if (f.length() > maxScanBytes) return@walkFiles false
                    val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@walkFiles false
                    var added = false
                    text.lineSequence().forEachIndexed { idx, line ->
                        if (hits.size < limit && regex.containsMatchIn(line)) {
                            hits.add("${f.path}:${idx + 1}: ${line.trim().take(160)}")
                            added = true
                        }
                    }
                    if (!added) false else hits.size >= limit
                }
                if (hits.isEmpty()) OpsResult.Ok("没有匹配「$pattern」的内容（根目录：${root.path}）")
                else OpsResult.Ok("匹配 ${hits.size} 处（根目录：${root.path}）：\n" + hits.joinToString("\n") { "- $it" })
            }
            else -> OpsResult.Fail("file_read 只支持 op=read/list/stat/glob/grep")
        }
    }

    // ---------------------------------------------------------------- file_write

    /** 改文件族（write/append/edit）；edit 要求原文在文件中唯一出现，避免误替换。 */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod")
    private suspend fun fileWrite(op: String, args: Map<String, String>): OpsResult = withContext(Dispatchers.IO) {
        val raw = args["path"]?.trim().orEmpty()
        if (raw.isEmpty()) return@withContext OpsResult.Fail("file_write 需要 path（目标文件绝对路径）")
        val f = resolvePath(raw) ?: return@withContext OpsResult.Fail("路径不可访问：$raw（仅支持 /sdcard、/storage 与应用私有目录）")
        when (op) {
            "write", "append" -> {
                val text = args["text"] ?: return@withContext OpsResult.Fail("file_write 的 $op 需要 text（写入内容）")
                runCatching {
                    if (op == "write") {
                        f.parentFile?.mkdirs()
                        f.writeText(text, Charsets.UTF_8)
                    } else {
                        f.parentFile?.mkdirs()
                        f.appendText(text, Charsets.UTF_8)
                    }
                }.getOrElse {
                    return@withContext OpsResult.Fail(
                        "写入失败：${it.message ?: "系统拒绝"}。请换到 /sdcard/Download 或 /sdcard/Documents 这类可写目录再试，" +
                            "并确认文件名没有被系统保留",
                    )
                }
                OpsResult.Ok("已${if (op == "write") "覆盖写入" else "追加"}：${f.path}（现 ${f.length()} 字节）")
            }
            "edit" -> {
                val old = args["old"].orEmpty()
                val new = args["new"].orEmpty()
                if (old.isEmpty()) return@withContext OpsResult.Fail("file_write 的 edit 需要 old（被替换的原文）")
                if (!f.isFile) return@withContext OpsResult.Fail("edit 的目标不是已存在的文件：${f.path}")
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@withContext OpsResult.Fail("读取失败（非 UTF-8 文本）：${f.path}")
                val count = countOccurrences(text, old)
                when {
                    count == 0 -> OpsResult.Fail("edit 失败：文件里没有这段原文（${old.take(40)}…）")
                    count > 1 -> OpsResult.Fail("edit 失败：原文出现 $count 次，请给一段更长的唯一片段")
                    else -> {
                        val idx = text.indexOf(old)
                        val updated = text.replaceRange(idx, idx + old.length, new)
                        runCatching { f.writeText(updated, Charsets.UTF_8) }
                            .getOrElse { return@withContext OpsResult.Fail("写入失败：${it.message ?: "系统拒绝"}") }
                        OpsResult.Ok("已替换 1 处：${f.path}（${text.length} → ${updated.length} 字符）")
                    }
                }
            }
            else -> OpsResult.Fail("file_write 只支持 op=write/append/edit")
        }
    }

    // ---------------------------------------------------------------- app_manage

    /** 应用管控：走注入通道的 am 命令（普通应用无法强停其他应用）。 */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount", "MaxLineLength")
    private suspend fun appManage(op: String, args: Map<String, String>): OpsResult {
        val input = args["app"]?.trim().orEmpty()
        if (input.isEmpty()) {
            return OpsResult.Fail("app_manage 需要 app：应用名（如「微信」）或包名（如 com.tencent.mm）")
        }
        val pkg = resolvePackage(input) ?: return OpsResult.Fail(
            "没有找到应用「$input」。请确认应用名或包名；不确定时先调 device_info（op=apps）看已安装列表",
        )
        val command = when (op) {
            "force_stop" -> "am force-stop $pkg"
            "background" -> "am kill $pkg"
            else -> return OpsResult.Fail("app_manage 只支持 op=force_stop（强制停止）和 op=background（清理后台）")
        }
        val out = shell(command)
            ?: return noChannel(
                "$op 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道。" +
                    "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权后重试",
            )
        // 命令有回执不等于进程没了：用 pidof 回读，还在就如实报「无法确认已停止」
        val pid = shell("pidof $pkg")?.trim().orEmpty()
        return if (pid.isEmpty()) {
            OpsResult.Ok("已${if (op == "force_stop") "强制停止" else "清理后台"}：$pkg（回读进程列表已无该应用）")
        } else {
            OpsResult.Fail(
                "$op 命令已执行，但 $pkg 仍在运行（pid=$pid），无法确认是否已停止。" +
                    "若它正在前台，请先切到别的应用再试；仍不行就手动在多任务里划掉。" +
                    "系统回执：${out.trim().take(120).ifEmpty { "无输出" }}",
            )
        }
    }

    // ---------------------------------------------------------------- terminal_op

    /** 命令行族：exec / check_root；命令由 AI 给出，属于自由模式专属能力。 */
    private suspend fun terminalOp(op: String, args: Map<String, String>): OpsResult = when (op) {
        "exec" -> {
            val cmd = args["command"]?.trim().orEmpty()
            if (cmd.isEmpty()) {
                return OpsResult.Fail("terminal_op 的 exec 需要 command：要执行的命令，例如 command=\"ls /sdcard\"")
            }
            val out = shell(cmd)
                ?: return noChannel(
                    "terminal 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道。" +
                        "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权后重试",
                )
            OpsResult.Ok("$ $cmd\n" + out.trim().take(4000))
        }
        "check_root" -> {
            val out = shell("id -u")
                ?: return noChannel(
                    "check_root 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道。" +
                        "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权后重试",
                )
            val uid = out.trim().lineSequence().firstOrNull().orEmpty()
            if (uid.contains("uid=0") || uid == "0") OpsResult.Ok("当前注入通道具备 Root（id -u → $uid）")
            else OpsResult.Ok("当前注入通道不是 Root（id -u → ${uid.ifBlank { "无输出" }}）")
        }
        else -> OpsResult.Fail("terminal_op 只支持 op=exec/check_root")
    }

    // ---------------------------------------------------------------- personal_read

    /**
     * 个人数据只读：用注入通道的 `content query` / `logcat` 直接查 ContentProvider，
     * 绕开 App 自身权限（以 shell 身份读）。输出在端侧按 query 过滤并截断，避免整表灌进上下文。
     */
    // 分派逻辑集中在一处，扁平化拆分会破坏原有可读性，此处按规则豁免
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun personalRead(op: String, args: Map<String, String>): OpsResult {
        val limit = (args["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 200)
        val query = args["query"]?.trim().orEmpty()
        val mediaType = args["type"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "images"
        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
        @Suppress("MaxLineLength")
        val command = when (op) {
            "contacts" -> "content query --uri content://contacts/phones --projection display_name:number"
            "calls" -> "content query --uri content://call_log/calls --projection number:type:date:duration --sort \"date DESC\""
            "sms" -> "content query --uri content://sms --projection address:body:date --sort \"date DESC\""
            "calendar" -> "content query --uri content://com.android.calendar/events --projection title:dtstart:dtend"
            "media" -> "content query --uri ${when (mediaType) {
                "videos" -> "content://media/external/video/media"
                "audio" -> "content://media/external/audio/media"
                else -> "content://media/external/images/media"
            }} --projection _display_name:relative_path"
            "logs" -> "logcat -d -t 300"
            else -> return OpsResult.Fail(
                "personal_read 只支持 op=contacts、calls、sms、calendar、media、logs，例如 op=contacts",
            )
        }
        val out = shell(command) ?: return noChannel(
            "$op 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道（读个人数据要以 shell 身份查询）。" +
                "请到应用设置里检查 Shizuku/无线 ADB 是否已连接并授权后重试",
        )
        val rows = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("No result") }
            .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .take(limit)
            .toList()
        if (rows.isEmpty()) {
            return OpsResult.Ok("没有查到${if (query.isEmpty()) "数据" else "含「$query」的数据"}（op=$op）。" +
                if (op == "logs") "" else "若返回为空也可能是通道权限不足。")
        }
        return OpsResult.Ok("$op${if (query.isEmpty()) "" else "（过滤「$query」）"} 前 ${rows.size} 条：\n" +
            rows.joinToString("\n") { "- ${it.take(220)}" }.take(4000))
    }

    // ------------------------------------------------------------------ helpers

    /** 单文件扫描上限：超过这个大小不读内容，避免 grep 拖垮内存 */
    private val maxScanBytes = 512 * 1024L

    /** 允许文件能力操作的根目录（其余路径一律拒绝，防止 AI 误伤系统目录） */
    private val fileRoots: List<String> by lazy {
        listOf("/sdcard", "/storage", context.filesDir.path, context.cacheDir.path) +
            listOfNotNull(context.externalCacheDir?.path, context.getExternalFilesDir(null)?.path)
    }

    /** 绝对路径直接用；相对路径按 /sdcard 解析；解析后必须落在 [fileRoots] 之内 */
    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun resolvePath(raw: String, default: String? = null): File? {
        val p = raw.trim().trim('"').ifEmpty { default ?: return null }
        val f = if (p.startsWith("/")) File(p) else File("/sdcard", p)
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return null
        val path = canonical.path
        return canonical.takeIf { fileRoots.any { root -> path == root || path.startsWith("$root/") } }
    }

    /** `*` 不跨目录、`**` 跨目录、`?` 单字符（覆盖 Eta glob 的常见写法） */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i++
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c in "\\.[]{}()+^$|" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return Regex(sb.toString())
    }

    /**
     * 广度优先遍历目录（跳过隐藏目录），onFile 返回 true 表示"够了，停"。
     * 节点数上限兜底，避免 /sdcard 上有超大目录时扫描停不下来。
     */
    // 嵌套结构是协议/匹配逻辑本身，扁平化风险较高，此处按规则豁免
    @Suppress("NestedBlockDepth", "ReturnCount")
    private fun walkFiles(root: File, maxNodes: Int = 20000, onFile: (File) -> Boolean): Boolean {
        val queue = ArrayDeque<File>().apply { add(root) }
        var nodes = 0
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                nodes++
                if (nodes > maxNodes) return false
                if (c.isDirectory) {
                    if (!c.name.startsWith(".")) queue.add(c)
                } else if (onFile(c)) {
                    return true
                }
            }
        }
        return false
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = text.indexOf(needle, idx + needle.length)
        }
        return count
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private fun resolvePackage(input: String): String? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        val pm = context.packageManager
        if (raw.contains('.')) {
            runCatching { pm.getPackageInfo(raw, 0) }.onSuccess { return raw }
        }
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        return apps.firstOrNull {
            runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault("").equals(raw, ignoreCase = true)
        }?.packageName
    }

    private fun intArg(args: Map<String, String>, key: String): Int? =
        args[key]?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun boolArg(args: Map<String, String>, key: String): Boolean? {
        val raw = args[key]?.trim()?.lowercase() ?: return null
        return when (raw) {
            "true", "1", "on", "yes", "开", "开启" -> true
            "false", "0", "off", "no", "关", "关闭" -> false
            else -> null
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun freeBytes(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(0L)
    private fun totalBytes(path: String): Long = runCatching { StatFs(path).totalBytes }.getOrDefault(0L)
    private fun mb(bytes: Long): String = String.format(Locale.US, "%.0fMB", bytes / 1024.0 / 1024.0)
    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0)

    @Suppress("DEPRECATION")
    private fun android.content.pm.ApplicationInfo.systemFlag(): Boolean =
        (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

    private companion object {
        private val KEY_RE = Regex("[A-Za-z0-9_.]+")

        /** 不同厂商时钟 Provider 的 authority（AOSP / OPPO / MIUI / 华为 / 荣耀 / Google） */
        private val ALARM_URIS = listOf(
            "content://com.android.deskclock/alarm",
            "content://com.oplus.alarmclock/alarm",
            "content://com.coloros.alarmclock/alarm",
            "content://com.miui.clock/alarm",
            "content://com.huawei.clock/alarm",
            "content://com.hihonor.clock/alarm",
            "content://com.google.android.deskclock/alarm",
        )

        /** 写入/删除时使用的 URI：先用 AOSP，失败再换厂商（由 content 命令报错暴露） */
        private const val ALARM_URI = "content://com.android.deskclock/alarm"

        /** 日历事件 Provider（AOSP / 绝大多数厂商共用这一份 authority） */
        private const val CALENDAR_URI = "content://com.android.calendar/events"

        /** 日历结果里的时间展示格式：到分钟即可，秒级没有意义 */
        private val CALENDAR_TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
