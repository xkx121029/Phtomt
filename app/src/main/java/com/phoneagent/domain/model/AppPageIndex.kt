package com.phoneagent.domain.model.AppPageIndex

import android.provider.Settings

/**
 * 软件页面直达索引库：常用软件可直达/稳定到达的页面清单。
 *
 * AI 用 open + app（软件名或包名）+ page（页面索引序号）定位页面，
 * 执行层按 [resolve] 取直达方式，避免 AI 胡编 scheme。
 * 表内页面：优先走系统 Intent Action / 深链；仅能启动的给包名直达。
 */
object AppPageIndex {

    /** 一条直达方式：uri 深链 > intentAction 系统设置页 > packageName 仅启动 */
    data class Entry(
        val uri: String? = null,
        val intentAction: String? = null,
        val packageName: String? = null,
    )

    data class Page(val index: Int, val name: String, val entry: Entry)

    data class App(
        val name: String,
        val packageName: String?,
        val pages: List<Page>,
    )

    private val apps: List<App> = listOf(
        App(
            name = "系统设置", packageName = "com.android.settings",
            pages = listOf(
                Page(1, "Wi-Fi", Entry(intentAction = Settings.ACTION_WIFI_SETTINGS, packageName = "com.android.settings")),
                Page(2, "蓝牙", Entry(intentAction = Settings.ACTION_BLUETOOTH_SETTINGS, packageName = "com.android.settings")),
                Page(3, "显示", Entry(intentAction = Settings.ACTION_DISPLAY_SETTINGS, packageName = "com.android.settings")),
                Page(4, "应用管理", Entry(intentAction = Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, packageName = "com.android.settings")),
                Page(5, "存储", Entry(intentAction = Settings.ACTION_INTERNAL_STORAGE_SETTINGS, packageName = "com.android.settings")),
                Page(6, "声音", Entry(intentAction = Settings.ACTION_SOUND_SETTINGS, packageName = "com.android.settings")),
                Page(7, "位置", Entry(intentAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS, packageName = "com.android.settings")),
            ),
        ),
        App(
            name = "高德地图", packageName = "com.autonavi.minimap",
            pages = listOf(
                Page(1, "地图首页", Entry(uri = "androidamap://main", packageName = "com.autonavi.minimap")),
                Page(2, "路线规划", Entry(uri = "androidamap://route", packageName = "com.autonavi.minimap")),
                Page(3, "附近", Entry(uri = "androidamap://nearby", packageName = "com.autonavi.minimap")),
            ),
        ),
        App(
            name = "抖音", packageName = "com.ss.android.ugc.aweme",
            pages = listOf(
                Page(1, "首页", Entry(packageName = "com.ss.android.ugc.aweme")),
                Page(2, "搜索", Entry(packageName = "com.ss.android.ugc.aweme")),
            ),
        ),
        App(
            name = "浏览器", packageName = null,
            pages = listOf(
                Page(1, "网页", Entry()),
            ),
        ),
    )

    /** 按软件名或包名、页面索引查直达方式；查不到返回 null */
    fun resolve(appQuery: String?, page: Int?): Entry? {
        if (appQuery.isNullOrBlank() || page == null) return null
        val app = apps.firstOrNull {
            it.name.equals(appQuery, ignoreCase = true) || (it.packageName != null && it.packageName == appQuery)
        } ?: return null
        return app.pages.firstOrNull { it.index == page }?.entry
    }

    /** 供系统提示词注入的索引文本（编号用于 open 的 page 字段） */
    fun indexText(): String = apps.joinToString("\n") { app ->
        "${app.name}：${app.pages.joinToString(" ", prefix = "", postfix = "") { "${it.index}=${it.name}" }}"
    }
}