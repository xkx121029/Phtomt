package com.phoneagent.coding.api

import kotlinx.coroutines.flow.StateFlow

/** 「环境」页观察的状态（实现仍在 `env.CodingEnvClient.UiState`，下期整体移入 api 时调用方零改动） */
typealias CodingEnvUiState = com.phoneagent.coding.env.CodingEnvClient.UiState

/** 一条安装 / 构建步骤 */
typealias CodingEnvStep = com.phoneagent.coding.env.CodingEnvClient.Step

/**
 * 「编码 → 环境」页需要的窄门户：安装引导、一键安装、构建与进度。
 *
 * 设置页只依赖这一份，不再认识 `CodingEnvClient` 这个具体类——AIDL 绑定、
 * 共享存储镜像、组件版本比对都留在切片内部。
 */
interface CodingEnvPortal {

    val state: StateFlow<CodingEnvUiState>

    /** 重新探测安装状态、存储权限、工程版本与组件侧进度快照 */
    suspend fun refresh()

    /** 拉起环境组件安装器；null 表示已拉起或已是最新，非空是失败原因 */
    suspend fun installComponent(): String?

    /** 打开组件自带的存储授权页；null 表示已拉起 */
    fun openStorageSetup(): String?

    /** 一键安装 Linux 根文件系统 + JDK + Gradle + Android SDK */
    suspend fun installEnvironment(): String?

    /** 在容器内跑一次 Gradle 构建 */
    suspend fun buildApk(task: String = "assembleDebug"): String?

    /** 等当前任务结束；超时返回 null，调用方据此报「仍在执行」而不是判失败 */
    suspend fun awaitFinished(timeoutMs: Long): CodingEnvUiState?

    /** 清理下载缓存与解包中间产物；null 表示已发起 */
    suspend fun cleanCache(): String?

    /** 重置编码环境（删 rootfs / JDK / Gradle / SDK）；null 表示已发起 */
    suspend fun resetEnvironment(): String?

    /** 请求取消当前任务 */
    fun cancel()
}
