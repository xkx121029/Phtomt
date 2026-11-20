package com.phoneagent.ui.model

/** 权限雷达条目类型 */
enum class PermissionKind {
    ACCESSIBILITY,   // 无障碍服务：AI 接管手机执行操作
    OVERLAY,         // 悬浮窗：实时进度跑马灯
    WRITE_SETTINGS,  // 修改系统设置：亮度/息屏时间/自动旋转等设备设置
    AUTOSTART,       // 自启动/省电白名单：后台稳定性
    NOTIFICATIONS,   // 通知：任务进度与完成提醒（Android 13+ 需运行时授权）
    USAGE_ACCESS,    // 使用情况访问权：前台应用交叉验证（兜底感知，AppOps 特殊权限）
    INSTALL_PACKAGES, // 安装未知应用：自更新与服务组件安装
    ALL_FILES,       // 所有文件访问：编码工作区读写用户选的任意目录
    DND_ACCESS,      // 勿扰访问权限：device_control 的 dnd 直连路径
    MICROPHONE,      // 麦克风：语音输入
}

/** 权限雷达条目 */
data class PermissionItem(
    val kind: PermissionKind,
    val title: String,
    val description: String,
    val granted: Boolean,
    /**
     * 可选权限：未授权也不影响基本任务，不计入首页「还缺 N 项授权」。
     * 用于那些装完即常态未开启、且开了只增强感知的项（如使用情况访问权）。
     */
    val optional: Boolean = false,
)