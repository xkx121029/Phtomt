package com.phoneagent.coding.agent

import android.content.Context
import java.io.File

/**
 * Materialises the bundled agent host and locates the embedded Node runtime.
 *
 * Layout:
 *   filesDir/agent-host/...   JS agent host, extracted from assets on first run
 *   nativeLibraryDir/libnode.so + libs   Node runtime, bundled in this APK's
 *                             jniLibs by tool/bundle_runtime.py and extracted
 *                             there by PackageManager.
 *                             Executing from nativeLibraryDir is the only
 *                             exec-allowed location on Android 10+; the runtime
 *                             libs are renamed to lib*.so by tool/bundle_runtime.py.
 */
object RuntimeBootstrap {
    private const val HOST_ASSET = "agent-host"

    /**
     * host 资产的内容指纹，由 tool/bundle_host.mjs 在拷贝时算好写进 assets/VERSION。
     * 以前这里是一个手工 bump 的常量（"3"），忘了改就会「装了新 APK，设备还在跑旧
     * host」——没有任何报错，只有行为对不上。现在指纹自动跟着内容走。
     */
    private fun assetFingerprint(context: Context): String = try {
        context.assets.open("$HOST_ASSET/VERSION").bufferedReader().use { it.readText().trim() }
    } catch (_: Exception) {
        "" // 老资产没带指纹：退化为每次解压一次，安全但慢
    }

    class Runtime(val nodePath: String, val libDir: String)

    fun ensureHost(context: Context): File {
        val hostDir = File(context.filesDir, HOST_ASSET)
        val entry = File(hostDir, "host-stdio.js")
        val versionFile = File(hostDir, ".version")
        val want = assetFingerprint(context)
        if (!entry.exists() || want.isEmpty() || versionFile.readTextSafe() != want) {
            hostDir.deleteRecursively()
            copyAssets(context, HOST_ASSET, hostDir)
            versionFile.writeText(want)
        }
        return entry
    }

    /**
     * 定位 Node 运行时。libnode.so 与依赖闭包随主 APK 打包（tool/bundle_runtime.py
     * 生成进 app/src/main/jniLibs），PackageManager 会把 lib*.so 解压到本包的
     * nativeLibraryDir——Android 10+ 唯一允许 exec 的位置，与 HACA 已验证的自包
     * exec 模式一致。
     */
    fun runtime(context: Context): Runtime {
        val libDir = context.applicationInfo.nativeLibraryDir
        val node = File(libDir, "libnode.so")
        check(node.exists()) { "内置编码运行时缺失：libnode.so 不在 $libDir（请安装完整打包的 APK）" }
        return Runtime(node.absolutePath, libDir)
    }

    /**
     * Materialise a bundled mock scenario (assets/agent-host/scenarios/<name>.json)
     * into the files dir and return its path, or "" when none is selected.
     * The host reads it via HAPPY_ACA_MOCK_SCRIPT to replay a real tool loop
     * offline — this is how the device-side "read -> edit -> run" demo is verified.
     */
    fun scenarioPath(context: Context, name: String): String {
        if (name.isBlank()) return ""
        val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "")
        if (safe.isEmpty()) return ""
        val asset = "$HOST_ASSET/scenarios/$safe.json"
        val dest = File(File(context.filesDir, "$HOST_ASSET/scenarios"), "$safe.json")
        return try {
            context.assets.open(asset).use { input ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { input.copyTo(it) }
            }
            dest.absolutePath
        } catch (_: Exception) {
            "" // no such scenario bundled; run without a script
        }
    }

    private fun copyAssets(context: Context, assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            // leaf file
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                input.copyTo(dest.outputStream())
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssets(context, "$assetPath/$child", File(dest, child))
        }
    }
}

private fun File.readTextSafe(): String? =
    try {
        readText()
    } catch (_: Exception) {
        null
    }
