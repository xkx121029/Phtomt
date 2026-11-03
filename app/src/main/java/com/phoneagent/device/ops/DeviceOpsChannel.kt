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
import kotlinx.coroutines.withContext

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
class DeviceOpsChannel(
    private val context: Context,
    /** 注入命令通道：返回 stdout；null = 没有可用通道或执行失败 */
    private val shell: suspend (String) -> String?,
    private val memory: MemoryStore? = null,
    /** 副屏代理：引擎按任务是否启用副屏提供；未启用时为 null */
    private val virtualDisplay: () -> VirtualDisplayAgent? = { null },
) {

    sealed interface OpsResult {
        data class Ok(val text: String) : OpsResult
        data class Fail(val reason: String) : OpsResult
    }

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
        else -> OpsResult.Fail("未知的系统直连能力族：$family")
    }

    // ---------------------------------------------------------------- device_info

    private fun deviceInfo(op: String, args: Map<String, String>): OpsResult = when (op) {
        "status" -> OpsResult.Ok(buildString {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
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
        else -> OpsResult.Fail("device_info 不支持 op=$op")
    }

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
    @Suppress("DEPRECATION")
    private fun inspectApp(input: String): OpsResult {
        if (input.isBlank()) return OpsResult.Fail("inspect_app 需要 app（包名或应用名）")
        val pm = context.packageManager
        val pkg = resolvePackage(input) ?: return OpsResult.Fail("没有找到应用「$input」")
        val info = runCatching { pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS) }.getOrNull()
            ?: return OpsResult.Fail("读取应用信息失败：$pkg")
        val appInfo = info.applicationInfo ?: return OpsResult.Fail("读取应用信息失败：$pkg（applicationInfo 为空）")
        val requested = info.requestedPermissions.orEmpty()
        val granted = requested.filter { runCatching { pm.checkPermission(it, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false) }
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
        "wifi", "bluetooth", "airplane", "location", "nfc" -> systemToggle(op, boolArg(args, "enable"))
        else -> OpsResult.Fail("device_control 不支持 op=$op")
    }

    private fun torch(on: Boolean): OpsResult {
        val cm = context.getSystemService(CameraManager::class.java)
            ?: return OpsResult.Fail("手电筒：相机服务不可用")
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return OpsResult.Fail("手电筒：本机没有可用的闪光灯")
        return runCatching {
            cm.setTorchMode(id, on)
            OpsResult.Ok("手电筒已${if (on) "打开" else "关闭"}")
        }.getOrElse { OpsResult.Fail("手电筒操作失败：${it.message ?: "系统拒绝"}（部分机型需要相机权限）") }
    }

    private fun writeSettingsReason(): String =
        "写系统设置需要授权：请到「设置 → 应用 → 本应用 → 修改系统设置」允许后再试；" +
            "或用 Shizuku/无线 ADB 通道执行（无需该授权）。"

    private fun brightness(level: Int?): OpsResult {
        if (level == null) return OpsResult.Fail("brightness 需要 level（0~100）")
        val value = (level.coerceIn(0, 100) * 255 / 100)
        if (!Settings.System.canWrite(context)) return OpsResult.Fail("亮度：${writeSettingsReason()}")
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            OpsResult.Ok("亮度已设为 $level%（系统值 $value/255）")
        }.getOrElse { OpsResult.Fail("亮度写入失败：${it.message ?: "系统拒绝"}") }
    }

    private fun volume(args: Map<String, String>): OpsResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return OpsResult.Fail("音量：音频服务不可用")
        val stream = when ((args["stream"] ?: "music").lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "notification", "notify" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val level = intArg(args, "level") ?: return OpsResult.Fail("volume 需要 level（0~100）")
        val max = am.getStreamMaxVolume(stream)
        val target = (level.coerceIn(0, 100) * max / 100)
        return runCatching {
            am.setStreamVolume(stream, target, 0)
            OpsResult.Ok("音量已设为 $level%（$target/$max）")
        }.getOrElse { OpsResult.Fail("音量调整失败：${it.message ?: "系统拒绝"}") }
    }

    private fun mediaKey(action: String): OpsResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return OpsResult.Fail("媒体控制：音频服务不可用")
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
        }.getOrElse { OpsResult.Fail("媒体键发送失败：${it.message ?: "系统拒绝"}") }
    }

    private fun soundMode(mode: String?): OpsResult {
        val am = context.getSystemService(AudioManager::class.java)
            ?: return OpsResult.Fail("声音模式：音频服务不可用")
        val target = when (mode?.lowercase()) {
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "ring", null -> AudioManager.RINGER_MODE_NORMAL
            else -> return OpsResult.Fail("sound_mode 的 mode 只能是 ring/vibrate/silent")
        }
        return runCatching {
            am.ringerMode = target
            OpsResult.Ok("已切换到${when (target) { AudioManager.RINGER_MODE_VIBRATE -> "震动"; AudioManager.RINGER_MODE_SILENT -> "静音"; else -> "响铃" }}")
        }.getOrElse { OpsResult.Fail("切换声音模式失败：${it.message ?: "系统拒绝"}（勿扰模式可能限制了铃声）") }
    }

    private fun dnd(enable: Boolean?): OpsResult {
        if (enable == null) return OpsResult.Fail("dnd 需要 enable=true/false")
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return OpsResult.Fail("勿扰：通知服务不可用")
        if (!nm.isNotificationPolicyAccessGranted) {
            return OpsResult.Fail("勿扰：未授予「勿扰访问权限」，请到系统设置里给本应用开启后重试")
        }
        return runCatching {
            nm.setInterruptionFilter(if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            OpsResult.Ok("勿扰已${if (enable) "开启" else "关闭"}")
        }.getOrElse { OpsResult.Fail("勿扰切换失败：${it.message ?: "系统拒绝"}") }
    }

    private fun screenTimeout(seconds: Int?): OpsResult {
        if (seconds == null) return OpsResult.Fail("screen_timeout 需要 seconds（5~1800）")
        if (!Settings.System.canWrite(context)) return OpsResult.Fail("息屏时间：${writeSettingsReason()}")
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds.coerceIn(5, 1800) * 1000)
            OpsResult.Ok("息屏时间已设为 ${seconds.coerceIn(5, 1800)} 秒")
        }.getOrElse { OpsResult.Fail("息屏时间写入失败：${it.message ?: "系统拒绝"}") }
    }

    private fun rotation(enable: Boolean?): OpsResult {
        if (enable == null) return OpsResult.Fail("rotation 需要 enable=true/false")
        if (!Settings.System.canWrite(context)) return OpsResult.Fail("旋转：${writeSettingsReason()}")
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enable) 1 else 0)
            OpsResult.Ok("自动旋转已${if (enable) "开启" else "关闭"}")
        }.getOrElse { OpsResult.Fail("旋转设置失败：${it.message ?: "系统拒绝"}") }
    }

    private suspend fun systemToggle(op: String, enable: Boolean?): OpsResult {
        if (enable == null) return OpsResult.Fail("$op 需要 enable=true/false")
        val on = if (enable) "enable" else "disable"
        val cmd = when (op) {
            "wifi" -> "svc wifi $on"
            "bluetooth" -> "cmd bluetooth_manager $on"
            "airplane" -> "cmd connectivity airplane-mode $on"
            "location" -> "cmd location set-location-enabled ${if (enable) "true" else "false"}"
            "nfc" -> "svc nfc $on"
            else -> return OpsResult.Fail("不支持的系统开关：$op")
        }
        val out = shell(cmd)
            ?: return OpsResult.Fail("$op：需要 Shizuku/无线 ADB 通道（或 Root）；当前没有可用注入通道")
        return OpsResult.Ok("$op 已提交${if (enable) "开启" else "关闭"}（系统回执：${out.ifBlank { "无输出" }.take(300)}）")
    }

    // --------------------------------------------------------------- settings

    private suspend fun settingsOp(op: String, args: Map<String, String>): OpsResult {
        val ns = (args["namespace"] ?: "system").lowercase()
        val key = args["key"]?.trim().orEmpty()
        if (key.isBlank()) return OpsResult.Fail("settings_op 需要 key")
        if (!KEY_RE.matches(key)) return OpsResult.Fail("设置项 key 含非法字符：$key")
        if (ns !in setOf("system", "secure", "global")) return OpsResult.Fail("namespace 只能是 system/secure/global")
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
        if (op != "set") return OpsResult.Fail("settings_op 只支持 get/set")
        val value = args["value"] ?: return OpsResult.Fail("settings_op 的 set 需要 value")
        // 优先用注入命令（能写 secure/global，不改系统权限）；没有注入通道时退回 system 的可写授权
        val out = shell("settings put $ns $key ${quote(value)}")
        if (out != null) return OpsResult.Ok("$ns/$key 已写入 $value")
        if (ns == "system" && Settings.System.canWrite(context)) {
            val ok = runCatching { Settings.System.putString(context.contentResolver, key, value) }.isSuccess
            if (ok) return OpsResult.Ok("system/$key 已写入 $value")
        }
        return OpsResult.Fail("写入 $ns/$key 失败：需要 Shizuku/无线 ADB 通道；" +
            (if (ns == "system") "或先授予「修改系统设置」权限。" else "secure/global 只能走注入通道。"))
    }

    // -------------------------------------------------------------- clipboard

    private fun clipboardOp(op: String, args: Map<String, String>): OpsResult {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return OpsResult.Fail("剪贴板服务不可用")
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
                val text = args["text"] ?: return OpsResult.Fail("clipboard set 需要 text")
                cm.setPrimaryClip(ClipData.newPlainText("phantom", text))
                OpsResult.Ok("已写入剪贴板（${text.length} 字）")
            }
            "clear" -> {
                cm.clearPrimaryClip()
                OpsResult.Ok("剪贴板已清空")
            }
            else -> OpsResult.Fail("clipboard 只支持 get/set/clear")
        }
    }

    // ------------------------------------------------------------ alarm_manage

    private suspend fun alarmManage(op: String, args: Map<String, String>): OpsResult {
        val uriOut = queryAlarms()
            ?: return OpsResult.Fail("闹钟管理：读不到时钟应用的闹钟数据。系统没有开放闹钟数据库访问，" +
                "需要 Root/Shizuku 的时钟 Provider；没有时可改走 open_app 打开时钟应用用手势操作，或重新 set_alarm 新增。")
        if (op == "list") return OpsResult.Ok("现有闹钟：\n$uriOut")
        val hour = intArg(args, "hour")
        val minute = intArg(args, "minute")
        if (hour == null || minute == null) return OpsResult.Fail("alarm_manage 的 $op 需要 hour 与 minute 定位已有闹钟")
        val where = "hour=$hour AND minutes=$minute"
        return when (op) {
            "enable" -> {
                val enable = boolArg(args, "enable") ?: return OpsResult.Fail("alarm_manage enable 需要 enable=true/false")
                updateAlarm(where, listOf(Triple("enabled", "i", if (enable) "1" else "0")))
                    .fold(
                        { OpsResult.Ok("已${if (enable) "启用" else "关闭"} $hour:${"%02d".format(minute)} 的闹钟（${it}）") },
                        { OpsResult.Fail("改闹钟开关失败：${it.message ?: "未知原因"}（需要 Root/Shizuku 且厂商时钟开放 Provider）") },
                    )
            }
            "delete" -> {
                val out = shell("content delete --uri ${ALARM_URI} --where ${quote(where)}")
                if (out.isNullOrBlank()) OpsResult.Fail("删除闹钟失败：注入通道不可用或时钟 Provider 拒绝")
                else OpsResult.Ok("已删除 $hour:${"%02d".format(minute)} 的闹钟（${out.take(200)}）")
            }
            "update" -> {
                val nh = intArg(args, "new_hour") ?: return OpsResult.Fail("alarm_manage update 需要 new_hour")
                val nm = intArg(args, "new_minute") ?: return OpsResult.Fail("alarm_manage update 需要 new_minute")
                updateAlarm(where, listOf(Triple("hour", "i", nh.toString()), Triple("minutes", "i", nm.toString())))
                    .fold(
                        { OpsResult.Ok("闹钟已改为 $nh:${"%02d".format(nm)}（${it}）") },
                        { OpsResult.Fail("改闹钟时间失败：${it.message ?: "未知原因"}（需要 Root/Shizuku 且厂商时钟开放 Provider）") },
                    )
            }
            else -> OpsResult.Fail("alarm_manage 只支持 list/enable/delete/update")
        }
    }

    private suspend fun updateAlarm(where: String, binds: List<Triple<String, String, String>>): kotlin.Result<String> {
        val bind = binds.joinToString(" ") { (k, type, v) -> "--bind $k:$type:${quote(v)}" }
        val out = shell("content update --uri ${ALARM_URI} --where ${quote(where)} $bind")
            ?: return kotlin.Result.failure(IllegalStateException("注入通道不可用"))
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return kotlin.Result.failure(IllegalStateException("Provider 未匹配到该闹钟或拒绝写入"))
        }
        return kotlin.Result.success(out.take(200))
    }

    /** 依次尝试常见时钟 Provider，返回第一条可用输出 */
    private suspend fun queryAlarms(): String? {
        for (uri in ALARM_URIS) {
            val out = shell("content query --uri $uri --projection _id:hour:minutes:enabled:label") ?: continue
            if (out.isNotBlank() && !out.contains("Error", ignoreCase = true) && !out.contains("No result", ignoreCase = true)) {
                return out.take(2000)
            }
        }
        return null
    }

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

    private suspend fun calendarList(args: Map<String, String>): OpsResult {
        val days = (args["days"]?.toLongOrNull() ?: 30L).coerceIn(1L, 365L)
        val limit = (args["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val query = args["query"]?.trim().orEmpty()
        val now = System.currentTimeMillis()
        val until = now + days * 24 * 3600_000L
        val out = shell("content query --uri $CALENDAR_URI --projection _id:title:dtstart:dtend --where ${quote("dtstart>=$now AND dtstart<=$until")} --sort ${quote("dtstart ASC")}")
            ?: return OpsResult.Fail(
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
            ?: return OpsResult.Fail("写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。可改走 open_app 打开日历应用手动新建。")
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception", ignoreCase = true)) {
            return OpsResult.Fail("日历 Provider 拒绝写入：${out.take(200)}。可改走 open_app 打开日历应用手动新建。")
        }
        return OpsResult.Ok("已新建日程「$title」：${formatCalendarTime(startMs)} ~ ${formatCalendarTime(endMs)}（calendar_id=$calendarId）")
    }

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
        val out = shell("content update --uri $CALENDAR_URI --where ${quote("_id=$id")} --bind ${binds.joinToString(" --bind ")}")
            ?: return OpsResult.Fail("写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。")
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return OpsResult.Fail("没有找到 id=$id 的日程，或日历 Provider 拒绝写入。先用 calendar_op list 确认 id。")
        }
        return OpsResult.Ok("已更新日程 id=$id（${binds.size} 个字段）")
    }

    private suspend fun calendarDelete(args: Map<String, String>): OpsResult {
        val id = args["event_id"]?.trim().orEmpty()
        if (id.isEmpty()) return OpsResult.Fail("calendar_op delete 需要 event_id（从 calendar_op list 结果里取）")
        val out = shell("content delete --uri $CALENDAR_URI --where ${quote("_id=$id")}")
            ?: return OpsResult.Fail("写日历需要 Shizuku 或无线 ADB 注入通道；当前不可用。")
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return OpsResult.Fail("没有找到 id=$id 的日程，或日历 Provider 拒绝删除。先用 calendar_op list 确认 id。")
        }
        return OpsResult.Ok("已删除日程 id=$id")
    }

    /**
     * 时间解析：纯数字按毫秒时间戳；否则依次尝试几种常见写法。
     * 只到分钟精度——日历事件的秒级误差没有意义，少给模型一种"猜格式"的负担。
     */
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
        runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).apply { isLenient = false }.parse(text) }.getOrNull()
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

    private suspend fun virtualDisplayOp(op: String, args: Map<String, String>): OpsResult {
        val agent = virtualDisplay()
            ?: return OpsResult.Fail("副屏：本任务未启用副屏模式（需自由模式且 Shizuku/无线 ADB 可用）")
        return when (op) {
            "status" -> OpsResult.Ok("副屏：${if (agent.isReady) "已创建（display=${agent.displayId}）" else "未创建"}")
            "create" -> outcome(agent.ensure(), "创建副屏")
            "destroy" -> run { agent.destroy(); OpsResult.Ok("副屏已销毁") }
            "launch" -> {
                val app = args["app"] ?: return OpsResult.Fail("virtual_display launch 需要 app")
                outcome(agent.launch(resolvePackage(app) ?: app), "副屏启动应用")
            }
            "tap" -> {
                val x = intArg(args, "x"); val y = intArg(args, "y")
                if (x == null || y == null) OpsResult.Fail("virtual_display tap 需要 x/y") else outcome(agent.tap(x, y), "副屏点击")
            }
            "swipe" -> {
                val x1 = intArg(args, "x1"); val y1 = intArg(args, "y1")
                val x2 = intArg(args, "x2"); val y2 = intArg(args, "y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) OpsResult.Fail("virtual_display swipe 需要 x1/y1/x2/y2")
                else outcome(agent.swipe(x1, y1, x2, y2), "副屏滑动")
            }
            "type" -> {
                val text = args["text"] ?: return OpsResult.Fail("virtual_display type 需要 text")
                outcome(agent.typeText(text), "副屏输入")
            }
            "key" -> {
                val code = when ((args["key"] ?: "").uppercase()) {
                    "BACK" -> 4; "HOME" -> 3; "ENTER" -> 66; "RECENT" -> 187; else -> -1
                }
                if (code < 0) OpsResult.Fail("virtual_display key 只支持 BACK/HOME/ENTER/RECENT")
                else outcome(agent.keyevent(code), "副屏按键")
            }
            "screenshot" -> {
                val bytes = agent.screenshot()
                if (bytes == null) OpsResult.Fail("副屏截图失败：当前系统不支持 screencap -d 或副屏未就绪")
                else OpsResult.Ok("副屏截图成功：${bytes.size} 字节 PNG（内容已交给端侧，不在对话里展开）")
            }
            else -> OpsResult.Fail("virtual_display 不支持 op=$op")
        }
    }

    private fun outcome(o: VirtualDisplayAgent.Outcome, what: String): OpsResult = when (o) {
        is VirtualDisplayAgent.Outcome.Ok -> OpsResult.Ok("$what 成功${o.output.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}")
        is VirtualDisplayAgent.Outcome.Fail -> OpsResult.Fail("$what 失败：${o.reason}")
    }

    // -------------------------------------------------------------- memory_get

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
    private suspend fun memoryWrite(op: String, args: Map<String, String>): OpsResult {
        val store = memory ?: return OpsResult.Fail("记忆编辑：记忆库不可用")
        return when (op) {
            "write" -> {
                val text = args["text"]?.trim().orEmpty()
                if (text.isEmpty()) return OpsResult.Fail("memory_write 的 write 需要 text（要记住的内容）")
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
                val text = args["text"]?.trim().orEmpty()
                if (text.isEmpty()) return OpsResult.Fail("memory_write 的 update 需要 text（新的内容）")
                when (val r = store.updateAiMemory(id, text, args["category"], args["revision"]?.trim()?.toLongOrNull())) {
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
    private suspend fun fileRead(op: String, args: Map<String, String>): OpsResult = withContext(Dispatchers.IO) {
        when (op) {
            "read" -> {
                val raw = args["path"]?.trim().orEmpty()
                if (raw.isEmpty()) return@withContext OpsResult.Fail("file_read 的 read 需要 path（绝对路径，如 /sdcard/Download/a.txt）")
                val f = resolvePath(raw) ?: return@withContext OpsResult.Fail("路径不可访问：$raw（仅支持 /sdcard、/storage 与应用私有目录）")
                if (!f.exists()) return@withContext OpsResult.Fail("文件不存在：${f.path}")
                if (f.isDirectory) return@withContext OpsResult.Fail("${f.path} 是目录；列目录请用 op=list")
                val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@withContext OpsResult.Fail("读取失败（二进制文件或非 UTF-8 编码）：${f.path}")
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
                }.getOrElse { return@withContext OpsResult.Fail("写入失败：${it.message ?: "系统拒绝"}") }
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
    private suspend fun appManage(op: String, args: Map<String, String>): OpsResult {
        val input = args["app"]?.trim().orEmpty()
        if (input.isEmpty()) return OpsResult.Fail("app_manage 需要 app（包名或应用名）")
        val pkg = resolvePackage(input) ?: return OpsResult.Fail("没有找到应用「$input」")
        val command = when (op) {
            "force_stop" -> "am force-stop $pkg"
            "background" -> "am kill $pkg"
            else -> return OpsResult.Fail("app_manage 只支持 op=force_stop/background")
        }
        val out = shell(command)
            ?: return OpsResult.Fail("$op 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道，请到设置里开启后重试")
        val tail = out.trim().take(120)
        return OpsResult.Ok("已${if (op == "force_stop") "强制停止" else "清理后台"}：$pkg" + if (tail.isEmpty()) "" else "（输出：$tail）")
    }

    // ---------------------------------------------------------------- terminal_op

    /** 命令行族：exec / check_root；命令由 AI 给出，属于自由模式专属能力。 */
    private suspend fun terminalOp(op: String, args: Map<String, String>): OpsResult = when (op) {
        "exec" -> {
            val cmd = args["command"]?.trim().orEmpty()
            if (cmd.isEmpty()) return OpsResult.Fail("terminal_op 的 exec 需要 command（要执行的命令）")
            val out = shell(cmd)
                ?: return OpsResult.Fail("terminal 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道，请到设置里开启后重试")
            OpsResult.Ok("$ $cmd\n" + out.trim().take(4000))
        }
        "check_root" -> {
            val out = shell("id -u")
                ?: return OpsResult.Fail("check_root 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道")
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
    private suspend fun personalRead(op: String, args: Map<String, String>): OpsResult {
        val limit = (args["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 200)
        val query = args["query"]?.trim().orEmpty()
        val mediaType = args["type"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "images"
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
            else -> return OpsResult.Fail("personal_read 只支持 op=contacts/calls/sms/calendar/media/logs")
        }
        val out = shell(command) ?: return OpsResult.Fail(
            "$op 需要 Shizuku 或无线 ADB 通道；当前没有可用注入通道（读个人数据要以 shell 身份查询）",
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
