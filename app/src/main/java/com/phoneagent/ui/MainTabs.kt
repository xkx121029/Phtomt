package com.phoneagent.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.phoneagent.ui.icons.AppIcons

/**
 * 底部 Tab 的静态定义（Superagent 融合 Phase 5）。
 *
 * id 是跨进程持久化（AppSettings.tab_order / tab_visible）与页面路由的唯一键；
 * title 与图标只在这里决定，导航栏与设置页的选项卡管理共用这一份，改一处全站生效。
 * 图标一律取 [AppIcons] 间接层已有条目，不新引图标库。
 */
data class MainTabSpec(
    val id: String,
    val title: String,
    val icon: ImageVector,
)

object MainTabs {
    const val HOME = "home"
    const val CHAT = "chat"
    const val CODING = "coding"
    const val AGENT = "agent"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"

    /** 不可隐藏的 Tab：主页与设置是出口与入口，可见性开关在设置页禁用，这里再兜一层 */
    val pinned = setOf(HOME, SETTINGS)

    /** 全量 Tab 集（默认顺序，与 AppSettings.Settings 的默认值一致） */
    val all = listOf(
        MainTabSpec(HOME, "主页", AppIcons.Home),
        MainTabSpec(CHAT, "对话", AppIcons.Send),
        MainTabSpec(CODING, "编码", AppIcons.Code),
        MainTabSpec(AGENT, "操控", AppIcons.Bolt),
        MainTabSpec(MEMORY, "记忆", AppIcons.Memory),
        MainTabSpec(SETTINGS, "设置", AppIcons.Settings),
    )

    private val byId = all.associateBy { it.id }

    fun specOf(id: String): MainTabSpec? = byId[id]
    fun titleOf(id: String): String = byId[id]?.title ?: id

    /**
     * 由持久化配置合成「可见 Tab 列表」——界面渲染只认这一份。
     *
     * 兜底修正三件事：
     * 1. 配置里出现脏 id / 缺了新 Tab → 用默认顺序补齐尾部；
     * 2. 钉住的 Tab（home/settings）被写成了隐藏 → 强制在场；
     * 3. 顺序串里重复的 id → 去重。
     */
    fun visibleTabs(order: List<String>, visible: List<String>): List<MainTabSpec> {
        val ordered = (order + all.map { it.id }).distinct().filter { byId.containsKey(it) }
        val visibleSet = visible.toSet()
        return ordered.mapNotNull { id ->
            specOf(id)?.takeIf { id in pinned || id in visibleSet }
        }
    }
}

/**
 * Tab 持久化的分号编解码（AppSettings.tab_order / tab_visible 存取共用）。
 * 抽成纯函数是为了 JVM 单测 round-trip（Superagent 融合 Phase 7）；
 * 解码语义与抽取前 AppSettings 内联的 split/trim/filter 逐字符一致，未改行为。
 */
object TabPrefsCodec {
    /** 落库缺省串（DataStore 无记录时用），与 [MainTabs.all] 的默认顺序一致 */
    const val DEFAULT = "home;chat;coding;agent;memory;settings"

    /** Tab id 列表 → 分号串（AppSettings.update 落库） */
    fun encode(ids: List<String>): String = ids.joinToString(";")

    /** 分号串 → Tab id 列表（AppSettings 读取）：null 回退默认，空白与空段剔除 */
    fun decode(raw: String?): List<String> =
        (raw ?: DEFAULT).split(";").map { it.trim() }.filter { it.isNotEmpty() }
}
