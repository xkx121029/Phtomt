package com.phoneagent.ui.vm

import com.phoneagent.feature.document.DocumentEngine
import com.phoneagent.feature.document.DocResult
import com.phoneagent.feature.update.DownloadInfo
import com.phoneagent.feature.update.ReleaseInfo
import com.phoneagent.feature.update.UpdateChannel
import com.phoneagent.feature.update.UpdateManager
import com.phoneagent.feature.update.UpdateState
import kotlinx.coroutines.flow.StateFlow

/**
 * 文档预览域：AI 生成的文档在 Agent 页任务流里就地预览，状态完全由 [DocumentEngine] 持有。
 */
class DocumentDelegate(private val documentEngine: DocumentEngine) {

    val docResult: StateFlow<DocResult?> = documentEngine.result

    fun dismissDoc() = documentEngine.dismiss()
}

/**
 * 自动更新域：查询、下载、安装都跑在 [UpdateManager] 自己的作用域里，
 * 因此这里不需要协程作用域——退出设置页不会中断下载，这条语义沿用原实现。
 */
class UpdateDelegate(private val updateManager: UpdateManager) {

    val updateState: StateFlow<UpdateState> = updateManager.state

    /**
     * 查询更新。返回是否发现新版本——启动时的静默检查据此决定要不要打扰用户。
     * @param baseUrl 官网地址，留空由更新模块回落到官方站点
     */
    suspend fun checkUpdate(baseUrl: String, channel: UpdateChannel): Boolean =
        updateManager.check(baseUrl, channel)

    /** 下载并安装。下载跑在更新模块自己的作用域里，退出设置页也不会中断 */
    fun downloadAndInstallUpdate(release: ReleaseInfo, download: DownloadInfo) {
        updateManager.downloadAndInstall(release, download)
    }
}
