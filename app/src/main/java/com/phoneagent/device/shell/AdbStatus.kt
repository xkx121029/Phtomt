package com.phoneagent.device.shell

/**
 * 无线 ADB 引导状态在主应用侧的展示模型。
 *
 * 状态机本体已随 shell 通道整体迁入 Provider（com.phoneagent.provider.shell.WirelessAdbStateMachine），
 * 这里只保留 UI 需要的最小投影：Provider 经 AIDL 返回同名字段 JSON，主应用反序列化后渲染。
 */

/** 无线 ADB 的离散状态（取值与 Provider 状态机保持一致） */
enum class AdbPhase {
    UNPAIRED,        // 未配对（需引导用户在设置开启无线调试并输入配对码）
    WAITING_CODE,    // 已发现设备，等待用户输入 6 位配对码
    PAIRING,         // 正在配对中
    PAIRED,          // 配对成功（ADB 可连接）
    BOOTING,         // 正在用 ADB 拉起 Shizuku 服务
    READY,           // 无线 ADB 通路就绪（可执行 shell / Shizuku 可用）
    DISCONNECTED,    // ADB 断开
    FAILED,          // 失败，携带错误消息
}

/** 配对/启动过程中对用户可见的人话状态；字段与 Provider 返回的 JSON 一一对应 */
data class AdbStatus(
    val phase: AdbPhase = AdbPhase.UNPAIRED,
    val message: String = "",
    val isUserActionRequired: Boolean = false,
)

/** 用户可读的错误类型（与 Provider 侧 AdbError 同名同义） */
enum class AdbError {
    WIRELESS_DEBUG_DISABLED, // 无线调试未开启
    PAIRING_PORT_OFF,        // 无线调试已开但未开放配对端口
    PAIRING_FAILED,          // 配对失败（码错误/超时/被拒）
    ADB_DISCONNECTED,        // ADB 连接断开
    SHIZUKU_START_FAILED,    // Shizuku 服务启动失败
    UNKNOWN,
}
