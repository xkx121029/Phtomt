package com.phoneagent.debug.logtransfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * debug 专有的启动钩子。
 *
 * 为什么用 ContentProvider：`Application.onCreate()` 在 main 源集里，改动它意味着
 * release 包也要背一份「if (debug)」和对应代码；而 Manifest 里声明的 ContentProvider
 * 由系统在进程启动时（Application.onCreate 之前）实例化并调用 [onCreate]，因此把
 * debug 功能的启动完全收在这一侧，main 源码零改动、release 包零代码。
 *
 * 该 Provider 声明在 `src/debug/AndroidManifest.xml`，release 变体不参与合并。
 */
class DebugLogBridgeInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { DebugLogBridge.start(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
