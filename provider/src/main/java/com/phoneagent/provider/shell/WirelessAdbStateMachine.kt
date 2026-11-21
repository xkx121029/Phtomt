package com.phoneagent.provider.shell

/** 驱动 [AdbStatus] 流转的纯状态机。事件驱动，无 IO，便于单元测试。 */
class WirelessAdbStateMachine {

    private var status = AdbStatus()

    fun current(): AdbStatus = status

    /** Shizuku 直连可用，无线 ADB 通路无需介入 */
    fun onShizukuDirectReady() {
        // 直接通路接管，无线通路复位为非必需
        status = AdbStatus(AdbPhase.READY, "Shizuku 直连可用")
    }

    /** 需要走无线 ADB 通路：无线调试未开启 */
    fun onWirelessDebugDisabled() {
        fail(AdbFailure(AdbError.WIRELESS_DEBUG_DISABLED), userActionRequired = true)
    }

    fun onDiscoverNeedsCode() {
        status = AdbStatus(AdbPhase.WAITING_CODE, "已发现无线调试设备，请在下方输入 6 位配对码", isUserActionRequired = true)
    }

    fun onPairingStart() {
        status = AdbStatus(AdbPhase.PAIRING, "正在配对无线调试设备…")
    }

    fun onPairingSuccess() {
        status = AdbStatus(AdbPhase.PAIRED, "引导会话已配对，准备连接")
    }

    /**
     * 无线调试会话已连上。
     *
     * 刻意**不**升到 [AdbPhase.READY]：READY 的语义是"主应用真能拿到可执行的 shell"。
     * 会话本身在 Provider 内确实能执行命令（`AdbWirelessTransport.executeShell`，目前只用于把
     * 内置 Shizuku server 拉起来），但 AIDL 只暴露 `execShizuku` / `execTermux`——主应用没有
     * 走这条会话的入口。所以会话连上 ≠ 主应用能执行命令。
     */
    fun onAdbConnected() {
        status = AdbStatus(AdbPhase.PAIRED, "引导会话已连接，正在尝试拉起内置 Shizuku")
    }

    fun onBootStart() {
        status = AdbStatus(AdbPhase.BOOTING, "正在启动内置 Shizuku 服务…")
    }

    fun onBootSuccess() {
        status = AdbStatus(AdbPhase.READY, "内置 Shizuku 已启动就绪，可执行 shell")
    }

    fun onAdbDisconnected() {
        fail(AdbFailure(AdbError.ADB_DISCONNECTED), userActionRequired = true)
    }

    fun onPairingFailed(detail: String = "") {
        fail(AdbFailure(AdbError.PAIRING_FAILED, detail), userActionRequired = true)
    }

    /**
     * Shizuku 拉起失败：会话可能还连着，但**没有可执行的 shell**。
     *
     * 以前这里回到 READY 并写"引导会话仍可用"，主应用据此把能力报成 AVAILABLE、
     * 把命令派给 execShizuku 再失败——用户看到的是"已就绪"外加一串执行失败。
     * 会话只在能拉起 server 时有用，拉不起来就是这条通路不可用，如实报失败。
     */
    fun onBootFailed(detail: String = "") {
        status = AdbStatus(
            AdbPhase.FAILED,
            "Shizuku 服务未能启动，shell 命令不可用" +
                if (detail.isBlank()) "（可重试一键启动）" else "（$detail）",
            isUserActionRequired = false,
        )
    }

    fun onPairingPortOff() {
        fail(AdbFailure(AdbError.PAIRING_PORT_OFF), userActionRequired = true)
    }

    fun reset() {
        status = AdbStatus()
    }

    private fun fail(f: AdbFailure, userActionRequired: Boolean) {
        status = AdbStatus(
            phase = AdbPhase.FAILED,
            message = AdbFailure.message(f.error),
            isUserActionRequired = userActionRequired,
        )
    }

    fun isReady(): Boolean = status.phase == AdbPhase.READY
    fun isFailed(): Boolean = status.phase == AdbPhase.FAILED
    fun inProgress(): Boolean = when (status.phase) {
        AdbPhase.PAIRING, AdbPhase.BOOTING, AdbPhase.PAIRED -> true
        else -> false
    }
}
