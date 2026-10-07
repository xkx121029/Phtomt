package com.phoneagent.device.vision

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Qwen2.5-VL-3B 模型运行时下载（进程内版本，供设置页调用）。
 *
 * 模型（主权重约 1.8GB + 视觉投影约 1.3GB）不打包进 APK（避免超 Android
 * 安装上限），改为首次使用时从 ModelScope 直链下载到应用私有目录。
 * 支持断点续传（Range）与进度回调。
 */
object VisionModelDownloader {

    /** 下载中断的标记，置 true 请求停止 */
    @Volatile
    var cancelled: Boolean = false

    data class DownloadFile(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
    )

    /** 模型文件清单。本地存盘文件名固定，远程指向 ModelScope unsloth 仓库内链。 */
    private val defaultFiles = listOf(
        DownloadFile(
            fileName = NativeVisionEngine.TEXT_MODEL_FILE,
            url = "https://www.modelscope.cn/models/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/master/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 1_929_901_408L,
        ),
        DownloadFile(
            fileName = NativeVisionEngine.MMPROJ_FILE,
            url = "https://www.modelscope.cn/models/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/master/mmproj-F16.gguf",
            sizeBytes = 1_338_428_256L,
        ),
    )

    @Volatile
    var files: List<DownloadFile> = defaultFiles

    /** 覆盖文件直链（可选） */
    @Volatile
    var overrideUrls: Map<String, String> = emptyMap()

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /**
     * 下载全部模型文件到 context.filesDir/vision_models/。
     * 返回实际下载的字节数。过程中通过 [onProgress] 上报。
     */
    suspend fun downloadAll(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long, currentFile: String) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        cancelled = false
        val dir = NativeVisionEngine.modelDir(context)
        dir.mkdirs()
        var done: Long = 0
        for (f in files) {
            if (cancelled) break
            val url = overrideUrls[f.fileName] ?: f.url
            done += downloadFile(dir, f.fileName, url, f.sizeBytes) { cur, total ->
                val cumulative = done + cur
                onProgress(cumulative, totalBytes, f.fileName)
            }
            done = done.coerceAtMost(totalBytes)
        }
        done
    }

    private fun downloadFile(
        dir: File,
        fileName: String,
        urlStr: String,
        expectedSize: Long,
        onPartial: (Long, Long) -> Unit,
    ): Long {
        val out = File(dir, fileName)
        if (out.exists() && expectedSize > 0 && out.length() >= expectedSize) {
            return out.length()
        }
        RandomAccessFile(out, "rw").use { raf ->
            val existing = raf.length()
            raf.seek(existing)

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=$existing-")
                }
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code in 200..399) {
                    val input: InputStream =
                        if (code == 206) conn.inputStream
                        else {
                            // 服务器不支持断点：重头下载
                            raf.setLength(0); raf.seek(0)
                            // 关闭旧连接，重新以非 Range 打开
                            conn.disconnect()
                            val c2 = URL(urlStr).openConnection() as HttpURLConnection
                            c2.connectTimeout = 20_000
                            c2.readTimeout = 30_000
                            c2.inputStream
                        }
                    var downloaded = existing
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled) break
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        onPartial(downloaded, expectedSize)
                    }
                    input.close()
                } else {
                    // 网络不可达等：保持已有部分，等待下次续传
                }
            } finally {
                conn.disconnect()
            }
        }
        return out.length()
    }

    /** 返回各文件本地大小（用于状态展示） */
    fun localStatus(context: Context): Map<String, Long> =
        NativeVisionEngine.modelDir(context).listFiles()
            ?.associate { it.name to it.length() }
            ?: emptyMap()
}
