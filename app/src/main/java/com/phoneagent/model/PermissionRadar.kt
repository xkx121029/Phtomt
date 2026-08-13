package com.phoneagent.model

/** 权限雷达条目类型 */
enum class PermissionKind {
    ACCESSIBILITY,   // 无障碍服务：AI 接管手机执行操作
    OVERLAY,         // 悬浮窗：实时进度跑马灯
    AUTOSTART,       // 自启动/省电白名单：后台稳定性
    QUERY_ALL_PACKAGES, // 获取已安装程序：识别并启动目标应用
}

/** 权限雷达条目 */
data class PermissionItem(
    val kind: PermissionKind,
    val title: String,
    val description: String,
    val granted: Boolean,
)