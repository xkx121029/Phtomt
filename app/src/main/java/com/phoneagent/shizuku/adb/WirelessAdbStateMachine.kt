package com.phoneagent.shizuku.adb

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
        status = AdbStatus(AdbPhase.PAIRED, "无线 ADB 已配对，准备连接")
    }

    fun onAdbConnected() {
        status = AdbStatus(AdbPhase.PAIRED, "无线 ADB 已连接，正在拉起 Shizuku…")
    }

    fun onBootStart() {
        status = AdbStatus(AdbPhase.BOOTING, "正在后台启动 Shizuku 服务…")
    }

    fun onBootSuccess() {
        status = AdbStatus(AdbPhase.READY, "Shizuku 已通过无线 ADB 启动成功")
    }

    fun onAdbDisconnected() {
        fail(AdbFailure(AdbError.ADB_DISCONNECTED), userActionRequired = true)
    }

    fun onPairingFailed(detail: String = "") {
        fail(AdbFailure(AdbError.PAIRING_FAILED, detail), userActionRequired = true)
    }

    fun onBootFailed(detail: String = "") {
        fail(AdbFailure(AdbError.SHIZUKU_START_FAILED, detail), userActionRequired = false)
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