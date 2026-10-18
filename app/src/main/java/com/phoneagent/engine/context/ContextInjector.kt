package com.phoneagent.engine.context

import com.phoneagent.core.security.DataSanitizer
import com.phoneagent.data.store.AiMemoryDedupe
import com.phoneagent.data.store.AnomalyMemoryEngine
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.engine.AgentPrompts
import com.phoneagent.engine.DeviceFacts
import com.phoneagent.engine.EnvFacts
import com.phoneagent.engine.MemoryBrief
import com.phoneagent.engine.PreviousTask
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.RuleScoper
import com.phoneagent.engine.TaskSession
import com.phoneagent.engine.execution.AppNameResolver
import com.phoneagent.engine.perception.effectiveLabel
import com.phoneagent.engine.runtime.EngineLimits.MAX_PREVIOUS_TASKS
import com.phoneagent.engine.runtime.EngineStore
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.rules.SessionContext

/**
 * 引擎上下文注入器（重构批次 6 前半）：为规划与每步决策组装"记忆 / 经验规则 / 异常经验 /
 * 页面记忆 / 会话承接 / 环境事实 / 设备几何"等上下文素材。
 * 从 AgentEngine 原样迁入，行为不变；缓存态一律存 [EngineStore]。
 */
internal class ContextInjector(
    private val appContext: android.content.Context,
    private val memory: MemoryStore,
    private val anomalyEngine: AnomalyMemoryEngine,
    private val appNameResolver: AppNameResolver,
    private val deviceFacts: DeviceFacts,
    private val store: EngineStore,
) {
    fun screenWidth(): Int = store.lastSnapshot.screenWidth.takeIf { it > 0 } ?: realScreenWidth()
    fun screenHeight(): Int = store.lastSnapshot.screenHeight.takeIf { it > 0 } ?: realScreenHeight()

    // 真实屏幕尺寸（不依赖无障碍服务）：直接问 WindowManager，取数细节见 DeviceFacts
    fun realScreenWidth(): Int = deviceFacts.realScreenSize().first

    fun realScreenHeight(): Int = deviceFacts.realScreenSize().second

    /**
     * 规划提示词用的已安装应用清单，让计划贴近真实环境。
     * 文案与"截断必须写明"的规则在 DeviceFacts.appListPromptText（纯逻辑，可单测）。
     */
    fun installedAppList(): String = DeviceFacts.appListPromptText(deviceFacts.launcherApps())

    /** 累计前台应用出现次数（同一任务内），供经验规则推断作用域；本 App 自己不计入 */
    fun noteForeground(pkg: String) {
        if (pkg.isBlank() || pkg == appContext.packageName) return
        store.foregroundPkgCounts[pkg] = (store.foregroundPkgCounts[pkg] ?: 0) + 1
    }

    // ==================== 环境上下文 ====================

    /**
     * 采集环境事实注入 AI：时间 / 网络 / 电量 / 前台应用 / 已安装应用数。
     * 这些是元素树里读不到的事实（日期决定"明天"是哪天，前台应用决定面前这页属于谁）。
     */
    fun envFacts(snapshot: ScreenSnapshot? = null): EnvFacts {
        val pkg = snapshot?.packageName.orEmpty()
        // 顺手记一次前台应用分布：任务结束提炼经验时据此推断作用域（不额外采集，零成本）
        noteForeground(pkg)
        // 取不到显示名时退回包名：宁可给 AI 一个能搜的串，也不要留空让它以为"没有前台应用"
        val label = if (pkg.isBlank()) "" else deviceFacts.appLabelOf(pkg)
        val foreground = when {
            pkg.isBlank() -> ""
            label.isBlank() || label == pkg -> pkg
            else -> "$label($pkg)"
        }
        return EnvFacts(
            dateTime = deviceFacts.dateTimeText(),
            network = deviceFacts.networkLabel(),
            battery = deviceFacts.batteryLabel(),
            foreground = foreground,
            installedCount = deviceFacts.launcherAppCount(),
        )
    }

    /**
     * device_query 的查询结果文本（中文，供 AI 直接读）。
     * kind 已由转译层白名单校验，这里只负责把本地事实取出来并组装；
     * 应用清单那一段的文案在 DeviceFacts.appQueryAnswerText（纯逻辑，可单测）。
     */
    fun deviceQueryText(kind: String, filter: String): String = when (kind) {
        "apps" -> DeviceFacts.appQueryAnswerText(deviceFacts.launcherApps(), filter)
        "time" -> "当前时间：${deviceFacts.dateTimeText()}"
        "battery" -> "电量：${deviceFacts.batteryLabel().ifBlank { "未知" }}"
        "network" -> "网络：${deviceFacts.networkLabel().ifBlank { "未知" }}"
        "storage" -> "存储：${deviceFacts.storageLabel().ifBlank { "未知" }}"
        else -> listOf(
            "当前时间：${deviceFacts.dateTimeText()}",
            "网络：${deviceFacts.networkLabel().ifBlank { "未知" }}",
            "电量：${deviceFacts.batteryLabel().ifBlank { "未知" }}",
            "存储：${deviceFacts.storageLabel().ifBlank { "未知" }}",
            "已安装可启动应用：${deviceFacts.launcherAppCount()} 个（需要清单请查 kind=apps，可用 filter 过滤）",
        ).joinToString("\n")
    }

    // ==================== 会话承接（连续对话） ====================

    /**
     * 会话承接块：取**本对话内**更早的往来（已结束任务的目标/状态/结论，以及纯对话问答），
     * 与本轮输入是否为追问一起交给 AI。
     *
     * 取材严格限定在当前对话里（起点见会话起点流）：上一段对话的聊天记录既不发出去，
     * 也不会被拿来讲"上一轮"。每任务只构建一次（规划与所有决策步共用），避免两处口径不一致。
     */
    fun sessionContextText(task: String, lang: PromptLang): String {
        store.sessionContextCache?.let { return it }
        val text = runCatching {
            // 素材带时间戳，任务与问答按同一时间轴排，才读得出"上一轮"到底是谁
            val turns = ArrayList<Pair<Long, PreviousTask>>()
            val start = store.conversationStart.value
            store.taskSessions.value
                .filter { it.taskId >= start }
                // 只承接已结束的任务：进行中的那条就是本任务自己，不能拿它当"上一轮"
                .filter { it.status != TaskSession.Status.RUNNING }
                .forEach { s ->
                    turns += s.startedAt to PreviousTask(
                        goal = s.title,
                        statusLabel = taskStatusLabel(s.status),
                        conclusion = s.summary,
                    )
                }
            synchronized(store.chatLock) {
                store.conversationChats.forEach { c ->
                    turns += c.at to PreviousTask(goal = c.question, statusLabel = "对话", conclusion = c.answer)
                }
            }
            val previous = turns.sortedBy { it.first }.map { it.second }.takeLast(MAX_PREVIOUS_TASKS)
            AgentPrompts.sessionContext(lang, previous, SessionContext.isFollowUp(task))
        }.getOrDefault("")
        store.sessionContextCache = text
        return text
    }

    /** 承接块里的任务状态标签（与侧边栏一致） */
    private fun taskStatusLabel(status: TaskSession.Status): String = when (status) {
        TaskSession.Status.DONE -> "已完成"
        TaskSession.Status.ABORTED -> "已停止"
        TaskSession.Status.FAILED -> "失败"
        TaskSession.Status.RUNNING -> "进行中"
    }

    // ==================== 记忆 / 经验规则 / 异常经验 / 页面记忆 ====================

    /**
     * 本步要注入的（记忆简报, 经验规则简报）。
     *
     * 两段必须一起算，因为**同一条内容只允许注入一遍**：命中的经验规则会先从记忆候选里剔除，
     * 否则"在美团要先关掉首页弹窗"会既出现在记忆段又出现在规则段，白占上下文还显得自相矛盾。
     * 反过来，没命中的规则（作用域不符 / 置信度不够）不会被剔除，它的内容仍由记忆段兜住，信息不丢。
     *
     * 画像 + 记忆 + 规则每任务只读一次库并缓存（记忆被写入后缓存作废）；
     * 异常经验随页面变化，按页面指纹单独缓存，换页才重新查。
     */
    suspend fun memoryAndRules(task: String, snapshot: ScreenSnapshot): Pair<String, String> {
        val raw = store.memoryRawCache ?: runCatching {
            EngineStore.MemoryRaw(memory.loadProfile(), memory.loadAiMemories(), memory.loadEvolvedRules())
        }.getOrDefault(EngineStore.MemoryRaw(emptyList(), emptyList(), emptyList())).also { store.memoryRawCache = it }

        val hit = RuleScoper.select(raw.rules, snapshot.packageName.orEmpty(), task)
        val memories = if (hit.isEmpty()) {
            raw.memories
        } else {
            raw.memories.filterNot { m -> hit.any { AiMemoryDedupe.isSame(it.content, m.content) } }
        }
        val base = MemoryBrief.build(profile = raw.profile, memories = memories)
        val hint = anomalyHint(snapshot)
        val memoryText = when {
            hint.isBlank() -> base
            base.isBlank() -> "异常经验：${hint.take(60)}"
            else -> "$base\n异常经验：${hint.take(60)}"
        }
        val fresh = hit.filter { it.id !in store.touchedRuleIds }
        if (fresh.isNotEmpty()) {
            // 同一任务内命中多次只计一次：否则一个 40 步的任务会把计数刷到 40，淘汰排序彻底失真
            store.touchedRuleIds += fresh.map { it.id }
            runCatching { memory.touchEvolvedRules(fresh.map { it.id }) }
        }
        return memoryText to RuleScoper.brief(hit)
    }

    /**
     * 当前页面命中的异常经验（一句话）。同一页面指纹只查一次库；
     * 命中的条目暂存到 currentAnomalyEntry，供该步结束时回写使用效果。
     */
    suspend fun anomalyHint(snapshot: ScreenSnapshot): String {
        val fp = runCatching { com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot) }
            .getOrDefault("")
        if (fp.isBlank()) return ""
        if (fp == store.anomalyHintFingerprint) return store.currentAnomalyEntry?.userSolution.orEmpty()
        store.anomalyHintFingerprint = fp
        val labels = snapshot.elements.mapNotNull { it.effectiveLabel() }.take(30)
        val hit = runCatching { anomalyEngine.findSolution(fp, labels) }.getOrNull()
        store.currentAnomalyEntry = hit
        return hit?.userSolution.orEmpty()
    }

    /**
     * 页面记忆提示块：命中已记忆页面时返回注入文本（已脱敏，≤400 字），未命中返回空串。
     * 按页面指纹缓存，换页才重查（与 anomalyHint 同款），DataStore 读取不进每步热路径。
     * @param hit 本步 Recorder 命中的已记忆页面（可能为 null：新页面或未记忆）
     */
    suspend fun pageMemoryHintFor(snapshot: ScreenSnapshot, hit: com.phoneagent.data.store.PageMemoryEntry?): String {
        val fp = runCatching { com.phoneagent.engine.perception.PageFingerprint.computeMeaningful(snapshot) }.getOrDefault("")
        if (fp.isBlank()) return ""
        if (fp == store.pageMemoryHintFingerprint) return store.pageMemoryHintText
        store.pageMemoryHintFingerprint = fp
        store.pageMemoryHintText = ""
        val entry = hit ?: runCatching {
            memory.loadPageMemories().firstOrNull {
                it.appPackage == snapshot.packageName.orEmpty() && it.fingerprint == fp
            }
        }.getOrNull() ?: return ""
        val pkg = entry.appPackage
        val edges = runCatching { memory.loadPageEdges().filter { it.appPackage == pkg && it.fromFp == fp } }
            .getOrDefault(emptyList())
        // 路径边的「下一页」标题从页面记忆里反查，AI 才知道走这条边能到哪
        val pages = runCatching { memory.loadPageMemories() }.getOrDefault(emptyList())
        val paths = edges.mapNotNull { e ->
            val to = pages.firstOrNull { it.appPackage == pkg && it.fingerprint == e.toFp }
            val toTitle = to?.title?.ifBlank { null } ?: to?.pageType?.ifBlank { null } ?: return@mapNotNull null
            AgentPrompts.PagePathHint(e.actionLabel, e.fromControl, toTitle)
        }
        val hotspots = entry.hotspots.map { it.label to it.semanticId }
        val text = runCatching {
            DataSanitizer.sanitize(AgentPrompts.pageMemoryBlock(store.currentLang, entry, hotspots, paths))
        }.getOrDefault("")
        store.pageMemoryHintText = text.take(400)
        return store.pageMemoryHintText
    }

    /**
     * 规划用页面记忆行：按最近使用取前 6 条路径，组装「应用名：出发页 —动作→ 目标页（走过N次）」。
     * 只挑与任务提到的应用相关的记忆（应用名出现在任务文本里才注入），避免无关路径污染规划。
     */
    suspend fun pageMemoryPlanningLines(task: String, lang: PromptLang): List<String> {
        val edges = runCatching { memory.loadPageEdges() }.getOrDefault(emptyList())
        if (edges.isEmpty()) return emptyList()
        val pages = runCatching { memory.loadPageMemories() }.getOrDefault(emptyList())
        val lines = edges.sortedByDescending { it.updatedAt }.mapNotNull { e ->
            // 应用名解析失败（应用已卸载等）退回包名尾段，仍可读
            val appName = runCatching { appNameResolver.resolve(e.appPackage) }.getOrNull()
                ?: e.appPackage.substringAfterLast('.')
            // 任务没提到这个应用就不注入：规划上下文要贴题，不撒网
            if (task.none { it.isLetterOrDigit() } || !(task.contains(appName, ignoreCase = true) || task.contains(e.appPackage, true))) {
                return@mapNotNull null
            }
            val from = pages.firstOrNull { it.appPackage == e.appPackage && it.fingerprint == e.fromFp }
            val to = pages.firstOrNull { it.appPackage == e.appPackage && it.fingerprint == e.toFp }
            val fromTitle = from?.title?.ifBlank { null } ?: from?.pageType ?: ""
            val toTitle = to?.title?.ifBlank { null } ?: to?.pageType ?: ""
            val countNote = if (lang == PromptLang.CN) "（走过${e.count}次）" else " (used ${e.count}x)"
            "$appName：$fromTitle ${e.actionLabel} → $toTitle$countNote"
        }
        return lines.take(6).map { it.take(80) }
    }
}
