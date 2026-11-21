package com.phoneagent.ui.vm

import com.phoneagent.data.store.AnomalyMemoryEntry
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.data.store.PagePathEdge
import com.phoneagent.data.store.ProfileEntry
import com.phoneagent.data.store.TaskMemoryEntry
import com.phoneagent.engine.AgentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 记忆域：四类落库记忆（异常 / 画像 / 任务 / 页面）+ 引擎内存态的实时记忆事件。
 *
 * 引擎仍通过 [engineProvider] 惰性取（`by lazy`）：构造本对象不会拉起引擎对象图，
 * 只有真正读取 [memoryEvents] 这类引擎派生状态时才会构建——与拆分前一致。
 */
class MemoryDelegate(
    private val memoryStore: MemoryStore,
    engineProvider: () -> AgentEngine,
    private val scope: CoroutineScope,
) {

    private val engine: AgentEngine by lazy { engineProvider() }

    // ---- 本次任务内的引擎内存态事件（Agent 页据此实时插卡/出气泡）----
    /** 本次任务内 AI 写入的记忆事件（引擎内存态），Agent 页据此实时插卡 */
    val memoryEvents: StateFlow<List<com.phoneagent.engine.MemoryEvent>> by lazy { engine.memoryEvents }

    /** 本次任务内 AI "对用户说话"的事件（引擎内存态），Agent 页据此实时出气泡 */
    val sayEvents: StateFlow<List<com.phoneagent.engine.SayEvent>> by lazy { engine.sayEvents }

    /** 本对话内 AI 澄清 → 用户选择 的往来（引擎内存态），Agent 页据此保留 问题 + 选择 */
    val clarifyEvents: StateFlow<List<com.phoneagent.engine.ClarifyAnswered>> by lazy { engine.clarifyEvents }

    /**
     * 合并形态下的纯对话问答（引擎内存态）：Agent 页把「对话」Tab 的产出也画在同一条时间线上。
     * 与 `conversation` 不同，这里只存用户看得懂的 问题 + 回答，不含内部决策提示词。
     */
    val chatTurns: StateFlow<List<com.phoneagent.engine.runtime.ChatTurnEvent>> by lazy { engine.chatTurns }

    /** 撤销一条刚写入的记忆：删库 + 从任务流移除卡片 */
    fun undoMemory(id: Long) {
        scope.launch {
            engine.dismissMemoryEvent(id)
            refreshMemory()
        }
    }

    private val _memoryAnomalies = MutableStateFlow<List<AnomalyMemoryEntry>>(emptyList())
    val memoryAnomalies: StateFlow<List<AnomalyMemoryEntry>> get() = _memoryAnomalies.asStateFlow()

    private val _memoryProfile = MutableStateFlow<List<ProfileEntry>>(emptyList())
    val memoryProfile: StateFlow<List<ProfileEntry>> get() = _memoryProfile.asStateFlow()

    private val _memoryTaskMemories = MutableStateFlow<List<TaskMemoryEntry>>(emptyList())
    val memoryTaskMemories: StateFlow<List<TaskMemoryEntry>> get() = _memoryTaskMemories.asStateFlow()

    /** 页面记忆：任务执行自动沉淀的页面热点 */
    private val _pageMemories = MutableStateFlow<List<PageMemoryEntry>>(emptyList())
    val pageMemories: StateFlow<List<PageMemoryEntry>> get() = _pageMemories.asStateFlow()

    /** 页面路径边：页面间的跳转记录 */
    private val _pagePathEdges = MutableStateFlow<List<PagePathEdge>>(emptyList())
    val pagePathEdges: StateFlow<List<PagePathEdge>> get() = _pagePathEdges.asStateFlow()

    private val _memoryLoading = MutableStateFlow(false)
    val memoryLoading: StateFlow<Boolean> get() = _memoryLoading.asStateFlow()

    /** 加载记忆数据（重新编排图谱） */
    fun refreshMemory() {
        scope.launch {
            _memoryLoading.value = true
            _memoryAnomalies.value = memoryStore.loadAnomalies()
            _memoryProfile.value = memoryStore.loadProfile()
            _memoryTaskMemories.value = memoryStore.loadTaskMemories()
            _pageMemories.value = memoryStore.loadPageMemories()
            _pagePathEdges.value = memoryStore.loadPageEdges()
            _memoryLoading.value = false
        }
    }

    /** 清空异常记忆 */
    fun clearAnomalyMemory() {
        scope.launch {
            memoryStore.saveAnomalies(emptyList())
            refreshMemory()
        }
    }

    /** 清空用户画像 */
    fun clearProfileMemory() {
        scope.launch {
            memoryStore.saveProfile(emptyList())
            refreshMemory()
        }
    }

    // ---- 任务记忆（任务执行中的工作记忆）----
    /** 删除单条任务记忆 */
    fun deleteTaskMemory(id: Long) {
        scope.launch {
            memoryStore.deleteTaskMemory(id)
            refreshMemory()
        }
    }

    /** 清空任务记忆 */
    fun clearTaskMemories() {
        scope.launch {
            memoryStore.clearTaskMemories()
            refreshMemory()
        }
    }

    // ---- 页面记忆（任务执行自动沉淀的页面热点与路径）----
    /** 删除单条页面记忆（连带删除引用它的路径边） */
    fun deletePageMemory(id: Long) {
        scope.launch {
            memoryStore.deletePageMemory(id)
            refreshMemory()
        }
    }

    /** 清空页面记忆与路径 */
    fun clearPageMemories() {
        scope.launch {
            memoryStore.clearPageMemories()
            refreshMemory()
        }
    }
}
