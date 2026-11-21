package com.phoneagent.coding.device

import com.phoneagent.core.log.AppLog
import kotlinx.coroutines.CancellationException

/**
 * 宿主桥密钥的存放面。真实实现是 AppSettings 的加密存储（SecretCodec + DataStore），
 * 单测用内存假实现——判据本身不碰 Android。
 */
internal interface HostBridgeTokenStore {
    suspend fun read(): String
    suspend fun write(token: String)
}

/**
 * 每装机密钥的解析：读存储 → 可用就复用，不可用就生成并落盘。
 *
 * 进程内缓存一次结果：同一次进程运行期间始终返回同一个值。否则存储一旦抖动，
 * 正在跑的宿主手里的密钥会忽然失效，而它没有重读的机会。
 *
 * 失败语义（全部 fail-closed，绝不回退到历史常量）：
 * - 读失败 → 按"没有密钥"重新生成；
 * - 生成不出可用密钥 → 交空串，[HostBridgeAuth] 对空密钥一律拒绝；
 * - 写失败 → 本次进程照常使用新密钥（宿主已按它启动），重启后重新生成一套。
 */
internal class HostBridgeTokenProvider(
    private val store: HostBridgeTokenStore,
    private val generate: () -> String = { HostBridgeToken.generate() },
) {

    /** [fresh] 表示这次解析**新生成**了密钥，调用方据此处置已经在跑的宿主进程。 */
    data class Resolved(val token: String, val fresh: Boolean)

    @Volatile
    private var cached: String? = null

    suspend fun ensure(): Resolved {
        cached?.let { return Resolved(it, fresh = false) }
        val usable = HostBridgeToken.usable(readStored())
        if (usable.isNotEmpty()) {
            cached = usable
            return Resolved(usable, fresh = false)
        }
        val token = HostBridgeToken.usable(generate())
        if (token.isEmpty()) {
            AppLog.w(TAG, "宿主桥密钥生成失败，入站请求将被全部拒绝")
            return Resolved("", fresh = false)
        }
        try {
            store.write(token)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLog.w(TAG, "宿主桥密钥落盘失败，本次进程仍用新密钥：${e.javaClass.simpleName}")
        }
        cached = token
        return Resolved(token, fresh = true)
    }

    private suspend fun readStored(): String = try {
        store.read()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLog.w(TAG, "读取宿主桥密钥失败，按未配置处理：${e.javaClass.simpleName}")
        ""
    }

    private companion object {
        const val TAG = "HostBridgeToken"
    }
}
