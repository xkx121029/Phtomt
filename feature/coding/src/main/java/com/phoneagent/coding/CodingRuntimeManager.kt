package com.phoneagent.coding

import android.content.Context
import android.os.Build
import com.phoneagent.coding.agent.LocalAgent
import com.phoneagent.coding.api.CodingRuntime
import com.phoneagent.coding.api.CodingRuntimeStatus
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
 *
 * 对外只经 [CodingRuntime]（`coding/api`）暴露：探测结果以 [CodingRuntimeStatus] 投影出去，
 * [state] 这一份留给编码页自己渲染（它要的是切片内部那套类型）。
 */
class CodingRuntimeManager(private val context: Context) : CodingRuntime {

    private val _state = MutableStateFlow<CodingRuntimeState>(probe())
    val state: StateFlow<CodingRuntimeState> = _state.asStateFlow()

    /** 对外投影：与 [state] 同一次探测结果，只是换成了 api 那套类型 */
    private val _status = MutableStateFlow<CodingRuntimeStatus>(CodingRuntimeStatus.Unavailable("尚未探测"))
    override val status: StateFlow<CodingRuntimeStatus> = _status.asStateFlow()

    init {
        refresh()
    }

    /** 重新探测内置运行时：libnode.so 存在 → Installed（主包版本），缺失 → NotReady。 */
    override fun refresh() {
        val next = probe()
        _state.value = next
        _status.value = next.toStatus()
    }

    /** 运行时是否已就绪（供编码入口做门禁判断）。 */
    fun isInstalled(): Boolean = _state.value is CodingRuntimeState.Installed

    override fun stopHost() {
        LocalAgent.stop()
    }

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

    private fun CodingRuntimeState.toStatus(): CodingRuntimeStatus = when (this) {
        is CodingRuntimeState.Installed -> CodingRuntimeStatus.Ready(versionName, versionCode)
        is CodingRuntimeState.NotReady -> CodingRuntimeStatus.Unavailable(reason)
    }
}
