package com.phoneagent.env

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环境组件的 AIDL 入口。
 *
 * 一次只跑一个任务（安装或构建都是重活，并发只会让两个下载互抢带宽），
 * 后来的请求直接回"忙"而不排队——用户看到的是明确状态，而不是点了没反应。
 */
class EnvService : Service() {

    private companion object {
        const val TAG = "EnvService"
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "env-installer")
    }
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    private val env by lazy { LinuxEnv(this) }
    private val installer by lazy { ToolchainInstaller(this, env) }

    private val binder = object : IEnvProvider.Stub() {
        override fun apiVersion(): Int = EnvApi.API_VERSION

        override fun linuxReady(): Boolean = env.isReady()

        override fun stateJson(): String = runCatching { installer.state().toString() }
            .getOrElse { "{\"error\":\"${it.message}\"}" }

        override fun run(requestJson: String, callback: IEnvCallback?) {
            if (!running.compareAndSet(false, true)) {
                replyTo(callback, "上一个任务还在执行，请等它结束或先取消")
                return
            }
            cancelled.set(false)
            executor.execute {
                val result = try {
                    val request = ToolchainInstaller.Request.fromJson(requestJson)
                    installer.execute(request, CallbackListener(callback), cancelled::get)
                } catch (t: Throwable) {
                    Log.w(TAG, "环境任务失败", t)
                    ToolchainInstaller.Result(false, "请求解析失败：${t.javaClass.simpleName} ${t.message}")
                } finally {
                    running.set(false)
                }
                runCatching { callback?.onFinished(result.ok, result.message) }
            }
        }

        override fun cancel() {
            cancelled.set(true)
            Log.i(TAG, "已请求取消环境任务")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        cancelled.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    /** 回调可能抛出 binder 死亡异常；一律吞掉，不影响安装流程本身 */
    private class CallbackListener(private val callback: IEnvCallback?) : ToolchainInstaller.Listener {
        override fun onStep(index: Int, total: Int, title: String, state: String) {
            runCatching { callback?.onStep(index, total, title, state) }
        }

        override fun onLog(line: String) {
            runCatching { callback?.onLog(line) }
        }
    }

    private fun replyTo(callback: IEnvCallback?, message: String) {
        runCatching { callback?.onFinished(false, message) }
    }
}
