package com.phoneagent.device.shell.adb

/**
 * 无线调试（ADB over Wi-Fi）拉取 Shizuku 相关的数据模型。
 *
 * 通路优先级：
 * 1. SHIZUKU_READY：Shizuku 已在本进程可用（直接通路）
 * 2. ADB_BOOTSTRAP ：
 *    - WIRELESS_ADB_READY：已完成无线调试 ADB 连接（已配对）
 *    - BOOTSTRAPPING：已用 ADB 拉起 Shizuku 服务，等待就绪
 *    - READY：Shizuku 经无线 ADB 拉起成功，归为可用
 * 上层业务（执行层）无需关心底层走哪条通路，调用 ensureReady() 即可。
 */

/** 无线 ADB 的离散状态 */
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

/** 配对/启动过程中对用户可见的人话状态与错误 */
data class AdbStatus(
    val phase: AdbPhase = AdbPhase.UNPAIRED,
    val message: String = "",
    val isUserActionRequired: Boolean = false,
)

/** 用户可读的错误类型（对应需求异常处理清单） */
enum class AdbError {
    WIRELESS_DEBUG_DISABLED, // 无线调试未开启
    PAIRING_PORT_OFF,        // 无线调试已开但未开放配对端口
    PAIRING_FAILED,          // 配对失败（码错误/超时/被拒）
    ADB_DISCONNECTED,        // ADB 连接断开
    SHIZUKU_START_FAILED,    // Shizuku 服务启动失败
    UNKNOWN,
}

data class AdbFailure(
    val error: AdbError,
    val detail: String = "",
) {
    companion object {
        fun message(e: AdbError): String = when (e) {
            AdbError.WIRELESS_DEBUG_DISABLED -> "无线调试未开启，请在系统设置中开启「无线调试」，并点击「使用配对码配对设备」"
            AdbError.PAIRING_PORT_OFF -> "无线调试已开启，但未开放配对端口，请保持在「使用配对码配对设备」界面"
            AdbError.PAIRING_FAILED -> "配对失败：请检查配对码是否正确、设备与电脑是否在同一局域网、并保持在配对界面"
            AdbError.ADB_DISCONNECTED -> "ADB 连接已断开，请重新连接无线调试"
            AdbError.SHIZUKU_START_FAILED -> "Shizuku 服务启动失败，请确认设备本机已安装合法的 Shizuku 服务"
            AdbError.UNKNOWN -> "发生未知错误，请重试"
        }
    }
}

/** mDNS 发现的无线调试配对服务（host + 配对端口 + 派生密钥用 salt） */
data class AdbPairingService(
    val host: String,
    val port: Int,
    val salt: ByteArray? = null,
)

/**
 * 无线 ADB 各阶段可配置超时（健壮性优化）。
 * 构造参数均带默认值，便于测试注入短超时。
 */
data class AdbTimeouts(
    val discoverMs: Long = 20_000L,   // mDNS 发现超时
    val connectMs: Long = 6_000L,     // TCP 连接超时
    val authMs: Long = 8_000L,        // AUTH/握手超时
    val shellReadMs: Long = 15_000L,  // shell 输出等待超时
)