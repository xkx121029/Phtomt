package com.phoneagent.engine.docs

import com.phoneagent.data.prefs.AppSettings
import kotlinx.coroutines.flow.first

/**
 * 离线优先的方法文档检索实现：检索永远走本地包，网络只用于同步。
 *
 * 设计取舍：
 * - 本地已有包：立即返回，过期同步丢后台——网络快慢不影响决策延迟，离线也能用；
 * - 本地没有包：最多等一次整包拉取（[TaskDocRepository] 内部有 3.5s 上限），
 *   失败就直接返回空，调用方按"没有方法文档"走通用探索，不阻断任务。
 */
class OfflineMethodDocRetriever(
    private val repository: TaskDocRepository,
    private val settings: AppSettings,
) : MethodDocRetriever {

    override suspend fun retrieve(task: String, foregroundPackage: String?, limit: Int): List<MethodDoc> {
        if (task.isBlank() || limit <= 0) return emptyList()
        val s = runCatching { settings.settings.first() }.getOrNull() ?: return emptyList()
        // 与云端执行文档共用「同步执行文档」开关与服务端根地址（同一台训练器，见 AppSettings）
        if (!s.docSyncEnabled || s.docBaseUrl.isBlank()) return emptyList()
        repository.ensureReady(s, blockForFirstFetch = true)
        return repository.search(task, foregroundPackage, limit)
    }
}
