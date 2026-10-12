package com.phoneagent.feature.update

import kotlinx.serialization.Serializable

/**
 * 客户端接收更新的频道。
 *
 * 与服务端 `website/server/lib/update.js` 的 CHANNEL_ORDER 一一对应：
 * stable < beta < dev < nightly。选定频道后服务端只会推送「同档与更稳定档」的版本——
 * 稳定版只收稳定版，测试版还能收到稳定版，开发版能收到稳定版+测试版，每日构建全收。
 * 这条矩阵是产品约定，改动必须两端同时改，否则会出现「客户端收到了被自己屏蔽的渠道」。
 */
enum class UpdateChannel(val id: String, val label: String, val summary: String) {
    STABLE("stable", "稳定版", "只接收稳定版更新"),
    BETA("beta", "测试版", "接收测试版与稳定版更新"),
    DEV("dev", "开发版", "接收开发版、测试版与稳定版更新"),
    NIGHTLY("nightly", "每日构建", "接收全部渠道更新（含每日构建）");

    companion object {
        fun of(id: String?): UpdateChannel = entries.firstOrNull { it.id == id } ?: STABLE
    }
}

/** 服务端给出的一个版本记录 */
@Serializable
data class ReleaseInfo(
    val version: String = "",
    val build: Int = 0,
    val channel: String = "stable",
    val date: String = "",
    val title: String = "",
    val summary: String = "",
    val notes: List<String> = emptyList(),
)

/**
 * 下载信息。
 *
 * [url] 永远是一个「拿得到就能装」的地址：服务端已经替客户端把蓝奏云镜像解析、解包好，
 * 失败时静默回落到官网直链，所以客户端不必理解镜像与提取码。
 * [mirror] / [shareUrl] / [password] 只用于在界面上说明这一版的来源。
 */
@Serializable
data class DownloadInfo(
    val url: String = "",
    val file: String = "",
    val size: Long = 0L,
    val sizeText: String = "",
    val sha256: String = "",
    val mirror: String = "origin",
    val shareUrl: String? = null,
    val password: String = "",
)

/** /api/update/check 的响应 */
@Serializable
data class UpdateCheckResponse(
    val channel: String = "stable",
    val clientVersion: String = "",
    val clientBuild: Int = 0,
    val hasUpdate: Boolean = false,
    val latest: ReleaseInfo? = null,
    val download: DownloadInfo? = null,
)

/** 更新流程的用户可见状态（设置页据此渲染） */
sealed interface UpdateState {
    /** 尚未检查 */
    data object Idle : UpdateState

    /** 正在查询服务端 */
    data object Checking : UpdateState

    /** 已是最新 */
    data object UpToDate : UpdateState

    /** 有新版本可升级 */
    data class Available(val release: ReleaseInfo, val download: DownloadInfo?) : UpdateState

    /** 正在下载（percent<0 表示总长未知） */
    data class Downloading(val percent: Int, val bytesRead: Long, val totalBytes: Long) : UpdateState

    /** 正在安装：silent=true 走 shell 静默装 */
    data class Installing(val silent: Boolean) : UpdateState

    /** 收尾：silent=true 表示已静默装完，false 表示已交给系统安装器 */
    data class Done(val silent: Boolean) : UpdateState

    /** 失败；release/download 非空时界面仍可重试 */
    data class Failed(val message: String, val release: ReleaseInfo? = null, val download: DownloadInfo? = null) : UpdateState
}
