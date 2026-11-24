package com.phoneagent.data.prefs

/**
 * Tab 持久化的分号编解码（AppSettings.tab_order / tab_visible 存取共用）。
 * 抽成纯函数是为了 JVM 单测 round-trip（Superagent 融合 Phase 7）；
 * 解码语义与抽取前 AppSettings 内联的 split/trim/filter 逐字符一致，未改行为。
 *
 * 从 `ui/MainTabs.kt` 里拆出来是因为 `AppSettings`（`:data`）要用它，而 `MainTabs`
 * 本身依赖 Compose 的 `ImageVector` / `AppIcons`，搬不动；`MainTabs` 一行都没引用本对象，
 * 所以拆开后 `:app` 侧只剩 `MainTabsTest` 一处 import 要补。
 */
object TabPrefsCodec {
    /** 落库缺省串（DataStore 无记录时用），与 MainTabs.all 的默认顺序一致 */
    const val DEFAULT = "home;chat;coding;agent;memory;settings"

    /** Tab id 列表 → 分号串（AppSettings.update 落库） */
    fun encode(ids: List<String>): String = ids.joinToString(";")

    /** 分号串 → Tab id 列表（AppSettings 读取）：null 回退默认，空白与空段剔除 */
    fun decode(raw: String?): List<String> =
        (raw ?: DEFAULT).split(";").map { it.trim() }.filter { it.isNotEmpty() }
}
