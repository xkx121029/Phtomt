package com.phoneagent.device.shell.adb

/**
 * 无线 ADB 传输层接口。真实设备实现承接 mDNS 发现、配对握手、ADB 连接与
 * 启动 Shizuku 服务；测试用假实现驱动 [ShizukuBootstrap] 决策逻辑。
 *
 * 所有实现应把可读错误通过 [shizuku.adb.AdbFailure] / 返回结果暴露，禁止吞异常不报。
 */
interface AdbBootstrapTransport {
    /** 判定无线 ADB 是否已连接可用 */
    suspend fun isConnected(): Boolean

    /** 同步判定无线 ADB 是否已连接（返回缓存的连接状态，供无协程的同步调用方使用） */
    fun isConnectedNow(): Boolean

    /** 发起配对：code 为 6 位配对码；返回是否配对成功，失败抛出或返回含错误 */ 
    suspend fun pair(code: String): AdbPairOutcome

    /** 用已建立的 ADB 连接拉起目标应用内的 Shizuku 服务；返回启动输出 */
    suspend fun startShizukuService(): AdbStartOutcome

    /**
     * 通过已建立的 ADB 连接执行真实 shell 命令并回读输出（复用原 Shizuku 命令通道）。
     * 未连接或执行失败返回 null；成功返回输出文本。
     */
    suspend fun executeShell(command: String): String?

    /** 关闭底层连接，释放资源 */
    fun shutdown()
}

/** 配对结果 */
sealed class AdbPairOutcome {
    data class Success(val productId: String = "") : AdbPairOutcome()
    data class Failure(val error: AdbError, val detail: String = "") : AdbPairOutcome()
}

/** 启动 Shizuku 服务结果 */
sealed class AdbStartOutcome {
    data class Success(val output: String) : AdbStartOutcome()
    data class Failure(val detail: String = "") : AdbStartOutcome()
}