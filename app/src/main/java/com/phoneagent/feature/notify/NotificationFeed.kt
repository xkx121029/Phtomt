package com.phoneagent.feature.notify

/**
 * 通知感知缓存：[AgentNotificationListener] 把本机活动通知汇入这里，
 * 引擎决策上下文与 notif_read / notif_clear 技能都读这一份。
 *
 * 纯内存环形结构（最多 [MAX] 条，超过挤掉最旧的），不落盘——通知是易变信息，
 * 持久化只会让 AI 拿到过期的验证码。
 */
object NotificationFeed {

    data class Item(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val ongoing: Boolean,
    )

    /** 缓存上限：通知再多只留最近这些，防长任务内存膨胀 */
    const val MAX = 30

    /** 决策上下文里单条通知正文的截断（验证码/标题在前的场景足够） */
    private const val DECISION_BODY_LIMIT = 120

    /** notif_read 回传里单条通知正文的截断（给 AI 读，可稍长） */
    private const val READ_BODY_LIMIT = 200

    /** 决策上下文最多列出的通知条数：再多只会稀释上下文 */
    private const val DECISION_MAX_LINES = 10

    private val items = LinkedHashMap<String, Item>()
    private val lock = Any()

    /** 监听服务是否在线（授权并连接成功后置 true，断开置 false） */
    @Volatile
    var connected: Boolean = false
        private set

    /** 清理动作的实际执行者：由监听服务注册（cancelNotification 需要监听身份） */
    @Volatile
    var canceller: ((String) -> Boolean)? = null

    fun setConnected(value: Boolean) {
        connected = value
        if (!value) synchronized(lock) { items.clear() }
    }

    fun upsert(item: Item) {
        synchronized(lock) {
            items.remove(item.key)
            items[item.key] = item
            while (items.size > MAX) {
                val eldest = items.keys.firstOrNull() ?: break
                items.remove(eldest)
            }
        }
    }

    fun remove(key: String) {
        synchronized(lock) { items.remove(key) }
    }

    /** 当前缓存的全部通知（新→旧） */
    fun all(): List<Item> = synchronized(lock) { items.values.reversed() }

    /**
     * 决策上下文文本（供引擎注入）：过滤掉本 App 自己的通知（任务进度/更新提示），
     * 返回中文块；无可用通知时返回空串（不占上下文）。
     */
    fun decisionText(selfPkg: String?): String {
        val list = all().filter { it.packageName != selfPkg }
        if (list.isEmpty()) return ""
        return buildString {
            appendLine("\n## 最新通知（来自通知监听，可能含验证码/提醒）")
            list.take(DECISION_MAX_LINES).forEach { n ->
                val head = n.title.ifBlank { n.appName }
                val body = n.text.replace("\n", " ").take(DECISION_BODY_LIMIT)
                appendLine("- [${n.appName}] $head${if (body.isBlank()) "" else "：$body"}")
            }
        }
    }

    /**
     * notif_read 的回传文本：全部通知的紧凑清单（给 AI 读）。
     * 监听未开启时返回失败原因。
     */
    fun readText(): String? {
        val list = if (connected) all() else null
        return when {
            list == null -> null
            list.isEmpty() -> "当前没有可读的通知"
            else -> buildString {
                appendLine("共 ${list.size} 条通知：")
                list.forEach { n ->
                    val head = n.title.ifBlank { n.appName }
                    val body = n.text.replace("\n", " ").take(READ_BODY_LIMIT)
                    appendLine("- [${n.packageName}] $head${if (body.isBlank()) "" else "：$body"}")
                }
            }
        }
    }

    /** notif_clear：取消全部可取消的通知（ongoing 不动），返回成功条数 */
    fun cancelAll(): Int {
        if (!connected) return -1
        var count = 0
        all().forEach { n ->
            if (!n.ongoing && canceller?.invoke(n.key) == true) count++
        }
        return count
    }
}
