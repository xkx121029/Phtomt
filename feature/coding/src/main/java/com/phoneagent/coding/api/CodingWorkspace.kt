package com.phoneagent.coding.api

import android.content.Context

/**
 * 编码工作区的落盘位置。
 *
 * 宿主设置页要展示「编码文件落在哪」，但不需要知道工作区登记表与指针的实现
 * （那些在 `coding.workspaces.CodingWorkspaces` / `coding.workspaces.Workspace` 里）。这里只留一个取值入口。
 */
object CodingWorkspace {

    /** 当前活动工作区的绝对路径（首次启动时兜底默认私有工作区）。 */
    fun path(context: Context): String =
        com.phoneagent.coding.workspaces.Workspace.resolve(context).absolutePath
}
