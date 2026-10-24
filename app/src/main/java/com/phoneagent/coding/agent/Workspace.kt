package com.phoneagent.coding.agent

import android.content.Context
import java.io.File

/**
 * Resolves the directory the agent treats as its workspace.
 *
 * HPA 用户约定：不用公共存储目录。优先级：
 *  1. 应用外部私有目录下的 happy_ACA（Android/data/com.phoneagent/files/happy_ACA）
 *     ——免存储权限，文件管理器里用户也能看到。
 *  2. App-private filesDir/workspace — always available, not user visible.
 */
object Workspace {
    const val DIR_NAME = "happy_ACA"

    fun resolve(context: Context): File {
        external(context)?.let { return it }
        return File(context.filesDir, "workspace").apply { mkdirs() }
    }

    private fun external(context: Context): File? = try {
        // Android 11+ scoped storage: the app-specific external dir needs no
        // permission at all and survives across the whole Documents tree.
        val candidate = runCatching { context.getExternalFilesDir(null) }
            .getOrNull()
            ?.let { File(it, DIR_NAME) }
        candidate?.takeIf { dir ->
            // Writability is the real test; existence alone is not enough.
            (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
        }?.apply { mkdirs() }
    } catch (_: Exception) {
        null
    }

    /** Human-readable location for the settings UI. */
    fun displayPath(context: Context): String = resolve(context).absolutePath
}
