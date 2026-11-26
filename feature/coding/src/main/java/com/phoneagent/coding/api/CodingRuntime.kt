package com.phoneagent.coding.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 内置运行时（随主 APK 打包的 Node 宿主）的就绪状态。
 *
 * 与切片内部的 `CodingRuntimeState` 是同一件事的两套名字：对外只暴露这一套，
 * 调用方（引擎桥、设置页）因此不需要知道运行时是怎么探测出来的。
 */
sealed interface CodingRuntimeStatus {

    /** 已就绪；版本取主包版本（versionName / versionCode） */
    data class Ready(val versionName: String, val versionCode: Long) : CodingRuntimeStatus

    /** 未就绪（libnode.so 没随包打进来，或版本信息读不到）；[reason] 可直接展示给用户 */
    data class Unavailable(val reason: String) : CodingRuntimeStatus
}

/**
 * 编码运行时的对外门禁：调用方只需要「现在能不能跑」「重新探测一次」「把宿主停掉」。
 */
interface CodingRuntime {

    val status: StateFlow<CodingRuntimeStatus>

    /** 重新探测内置运行时 */
    fun refresh()

    /**
     * 停掉正在跑的 agent 宿主进程。
     *
     * 宿主进程的 cwd 与 state 目录在启动时确定，换工作区或改模型配置后不重启它，
     * 界面会「看起来」切了、实际还在用旧目录/旧配置。
     */
    fun stopHost()
}
