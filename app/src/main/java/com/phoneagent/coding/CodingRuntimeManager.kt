package com.phoneagent.coding

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 编码运行时（内置 Node 宿主）的状态。 */
sealed class CodingRuntimeState {
    /** 内置运行时已就绪（版本随主包） */
    data class Installed(val versionName: String, val versionCode: Long) : CodingRuntimeState()

    /** 内置 libnode.so 缺失（APK 未打包进运行时，属于构建/分发问题，无下载安装入口） */
    data class NotReady(val reason: String) : CodingRuntimeState()
}

/**
 * 编码运行时管理器：探测随主 APK 打包的 Node 运行时（libnode.so + 依赖闭包，
 * 由 tool/bundle_runtime.py 生成进 app/src/main/jniLibs，PackageManager 解压到
 * 本包 nativeLibraryDir）。
 *
 * 运行时不再单独下载安装：libnode.so 存在即恒为就绪（版本取主包 versionName/
 * versionCode）；只有构建产物缺 libnode.so 时才落 NotReady（交给上层做只读错误展示）。
 */
class CodingRuntimeManager(private val context: Context) {

    private val _state = MutableStateFlow<CodingRuntimeState>(probe())
    val state: StateFlow<CodingRuntimeState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新探测内置运行时：libnode.so 存在 → Installed（主包版本），缺失 → NotReady。 */
    fun refresh() {
        _state.value = probe()
    }

    /** 运行时是否已就绪（供编码入口做门禁判断）。 */
    fun isInstalled(): Boolean = _state.value is CodingRuntimeState.Installed

    private fun probe(): CodingRuntimeState {
        val libDir = context.applicationInfo.nativeLibraryDir
        if (!File(libDir, "libnode.so").isFile) {
            return CodingRuntimeState.NotReady(
                "内置编码运行时缺失（libnode.so 未随 APK 打包），请安装完整打包的 APK",
            )
        }
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            CodingRuntimeState.Installed(info.versionName ?: "", code)
        } catch (e: Exception) {
            CodingRuntimeState.NotReady("无法读取主包版本信息：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
