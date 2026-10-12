package com.phoneagent.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.phoneagent.BuildConfig
import com.phoneagent.device.shell.AdbWirelessTransport
import com.phoneagent.device.shell.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 更新管理器：查询版本 → 下载安装包 → 校验 → 安装。
 *
 * 三条设计取舍：
 * 1. 下载跑在应用级协程（不绑视图生命周期），退到后台也能下完；
 * 2. 安装优先走 shell 静默装（无线 ADB / Shizuku 任一可用），装不了才回落系统安装器；
 * 3. 蓝奏云镜像的解析与解包全在服务端完成，这里只面对一个普通的 APK 下载地址，
 *    不必理解镜像页面与提取码，也就不会因为蓝奏云改版而连带失效。
 */
class UpdateManager(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val adb: AdbWirelessTransport,
) {
    private companion object {
        /** 下载缓冲：64KB 是 OkHttp/文件 IO 的常用档位，再大只是多占内存 */
        const val WRITE_BUFFER = 64 * 1024
        /** 未知总长时，每下满 1MB 才回显一次进度，避免每块都写一次 StateFlow */
        const val UNKNOWN_TOTAL_STEP = 1024 * 1024
        /** 未配置更新地址时的兜底站点 */
        const val DEFAULT_BASE_URL = "https://hpa.linshuxu.us.kg"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 读超时放宽到 3 分钟：安装包十几 MB，慢网下按默认 10 秒会被判超时
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 查询更新。返回是否发现新版本（启动时静默检查据此决定要不要提示） */
    suspend fun check(baseUrl: String, channel: UpdateChannel): Boolean {
        _state.value = UpdateState.Checking
        return try {
            val root = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
            val url = "$root/api/update/check?channel=${channel.id}" +
                "&version=${BuildConfig.VERSION_NAME}&build=${BuildConfig.VERSION_CODE}"
            val resp = json.decodeFromString(UpdateCheckResponse.serializer(), get(url))
            val release = resp.latest
            if (resp.hasUpdate && release != null) {
                _state.value = UpdateState.Available(release, resp.download)
                true
            } else {
                _state.value = UpdateState.UpToDate
                false
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(readable(e))
            false
        }
    }

    /** 下载并安装。整个过程在应用级协程里跑，离开设置页也不会中断 */
    fun downloadAndInstall(release: ReleaseInfo, download: DownloadInfo) {
        scope.launch { runInstall(release, download) }
    }

    private suspend fun runInstall(release: ReleaseInfo, download: DownloadInfo) {
        try {
            val dir = downloadDir()
            val name = apkName(release, download)
            val tmp = File(dir, "$name.part")
            val apk = File(dir, name)
            tmp.delete()
            apk.delete()

            fetchToFile(download, tmp)
            if (download.sha256.isNotBlank() && !verify(tmp, download.sha256)) {
                tmp.delete()
                throw IOException("安装包校验失败，已丢弃")
            }
            if (!tmp.renameTo(apk)) throw IOException("安装包落盘失败")
            install(apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(readable(e), release, download)
        }
    }

    /** 边下边回显进度；返回实际写入的字节数 */
    private suspend fun fetchToFile(download: DownloadInfo, tmp: File): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(download.url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("下载响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: download.size
            var read = 0L
            var lastPercent = -1
            var lastMark = 0L
            _state.value = UpdateState.Downloading(if (total > 0) 0 else -1, 0L, total)
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(WRITE_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = UpdateState.Downloading(percent, read, total)
                            }
                        } else if (read - lastMark >= UNKNOWN_TOTAL_STEP) {
                            lastMark = read
                            _state.value = UpdateState.Downloading(-1, read, 0L)
                        }
                    }
                }
            }
            read
        }
    }

    /** 装：先试 shell 静默装，失败再交系统安装器 */
    private suspend fun install(apk: File) {
        _state.value = UpdateState.Installing(true)
        if (silentInstall("pm install -r -d \"${apk.absolutePath}\"")) {
            _state.value = UpdateState.Done(silent = true)
            return
        }
        _state.value = UpdateState.Installing(false)
        launchSystemInstaller(apk)
        _state.value = UpdateState.Done(silent = false)
    }

    /** 依次尝试无线 ADB 与 Shizuku；两者都不可用或都失败时返回 false */
    private suspend fun silentInstall(command: String): Boolean {
        val adbOutput = runCatching { adb.executeShell(command) }.getOrNull()
        if (adbOutput?.contains("Success", ignoreCase = true) == true) return true
        if (!shizuku.isAvailable()) return false
        val result = runCatching { shizuku.executeShell(command) }.getOrNull()
        return result is ShizukuManager.ShellResult.Success &&
            result.output.contains("Success", ignoreCase = true)
    }

    /**
     * 系统安装器兜底。走 FileProvider 授权：安装包在私有外部目录，
     * 直接给 file:// 会触发 FileUriExposedException（targetSdk 24 起）。
     */
    private fun launchSystemInstaller(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("服务返回 ${resp.code}：${text.take(160)}")
            text
        }
    }

    /**
     * 下载目录。优先用应用外部私有目录：shell（uid 2000）能读到该路径，
     * `pm install` 才有得装；拿不到外部目录时才退回内部缓存（此时只能走系统安装器）。
     */
    private fun downloadDir(): File {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
        return File(base, "updates").apply { mkdirs() }
    }

    /** 落盘文件名：优先用服务端给出的 APK 名，兜底用版本号拼一个 */
    private fun apkName(release: ReleaseInfo, download: DownloadInfo): String {
        val fromServer = download.file.trim()
        if (fromServer.endsWith(".apk", ignoreCase = true)) return fromServer
        val version = release.version.trim().ifBlank { "update" }
        return "HappyAgent-$version.apk"
    }

    private fun verify(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(WRITE_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return actual.equals(expected.trim(), ignoreCase = true)
    }

    /** 把异常转成给用户看的中文提示 */
    private fun readable(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: "更新失败，请稍后再试"
}
