package com.phoneagent.coding.agent

import android.content.Context
import java.io.File

/**
 * Resolves the directory the agent treats as its workspace.
 *
 * 活动工作区由 [CodingWorkspaces] 的登记表决定（HPA 私有目录或用户用系统选择器挑的目录），
 * 这里只做「进程内指针 + 首次启动兜底」：
 *  - [setActive] 在切换工作区时更新指针，编码 agent 重启后 workDir 随之变化；
 *  - 指针为空（冷启动、服务被杀后拉起）时回落到默认私有工作区。
 */
object Workspace {
    const val DIR_NAME = "happy_ACA"

    @Volatile
    private var active: File? = null

    fun setActive(dir: File) {
        val normalized = dir.absoluteFile
        normalized.mkdirs()
        active = normalized
    }

    fun resolve(context: Context): File {
        val current = active
        if (current != null && current.isDirectory) return current
        return File(File(context.filesDir, "workspaces"), "default").apply { mkdirs() }
            .also { active = it }
    }

    /** Human-readable location for the settings UI. */
    fun displayPath(context: Context): String = resolve(context).absolutePath
}
