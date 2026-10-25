package com.phoneagent.provider.shell

/**
 * Shizuku 系统操作门面：把常用的系统级 shell 操作包成类型化方法，
 * 免去调用方各自拼命令、各自解析输出的漂移。
 *
 * 与 [ShizukuManager.executeShell] 的关系：那是"能执行任意命令的底层通道"，
 * 这里是"系统管理常用动作的命名入口"——pm 多用户、appops、dumpsys、运行时权限授予。
 * 自由模式下 AI 的 shell 意图仍走原始命令；这组方法供端侧主动调用（如助理槽位兜底检测）。
 */
class ShizukuSystemOps(private val manager: ShizukuManager) {

    /** 列出系统全部用户（含分身/工作资料），输出一行一个 `用户 id:名称` */
    fun listUsers(): ShizukuManager.ShellResult =
        manager.executeShell("pm list users")

    /** 查询某应用的 appops 策略（如后台弹窗、悬浮窗授权态） */
    fun appopsGet(packageName: String): ShizukuManager.ShellResult =
        manager.executeShell("appops get $packageName")

    /** 按小节读取 dumpsys（如 `window`、`battery`、`activity top`），避免整包输出超限 */
    fun dumpsysSection(section: String): ShizukuManager.ShellResult =
        manager.executeShell("dumpsys $section")

    /**
     * 授予运行时权限（等价 `pm grant`；写 secure settings 类特殊权限也走这里）。
     * 注意：这是改设备状态的动作，调用方自行确认授权边界（AI 侧只能经自由模式的 shell 意图触达）。
     */
    fun grantPermission(packageName: String, permission: String): ShizukuManager.ShellResult =
        manager.executeShell("pm grant $packageName $permission")

    /**
     * 读取一条 secure 设置（如 `assistant`、`voice_interaction_service`）。
     * 与 Context 直接读 Settings.Secure 等价，但走 shell uid 在任何机型上口径一致；
     * 作为 Context 读取失败的兜底通道。
     */
    fun readSecureSetting(key: String): ShizukuManager.ShellResult =
        manager.executeShell("settings get secure $key")
}
