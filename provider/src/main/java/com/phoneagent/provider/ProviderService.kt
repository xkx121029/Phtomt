package com.phoneagent.provider

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Provider 的 AIDL 入口：主应用绑定后远程调用三条通道（无障碍 / Shizuku / Termux）。
 *
 * 职责只有"接线"：把 AIDL 线程模型翻译成 Bridge 的挂起调用（Binder 线程阻塞等待，
 * 是双向 AIDL 的标准形态；Bridge 内部禁止依赖主线程，避免死锁）。
 * 具体能力分别由 [A11yBridge]（无障碍）与 [ShellBridge]（Shizuku / Termux / 无线 ADB 引导）实现。
 *
 * 绑定侧要求：调用方须持有 com.phoneagent.permission.BIND_PROVIDER（signature 级，
 * 只有同签名的主应用能拿到），故无需再做调用方校验。
 */
class ProviderService : Service() {

    private val binder = object : IDeviceProvider.Stub() {

        override fun apiVersion(): Int = API_VERSION

        override fun isA11yEnabled(): Boolean = A11yBridge.isEnabled(applicationContext)

        override fun isA11yConnected(): Boolean = A11yBridge.isConnected()

        override fun shizukuState(): Int = ShellBridge.shizukuState()

        override fun termuxInstalled(): Boolean = ShellBridge.termuxInstalled()

        override fun termuxPermissionGranted(): Boolean = ShellBridge.termuxPermissionGranted()

        override fun captureScreen(): String? =
            runBlockingBridge { A11yBridge.captureScreenJson() }

        override fun takeScreenshot(): ByteArray? =
            runBlockingBridge { A11yBridge.takeScreenshotPng() }

        override fun hitTest(x: Int, y: Int): String? =
            runBlockingBridge { A11yBridge.hitTestJson(x, y) }

        override fun liveCenter(selector: A11ySelector): IntArray? =
            runBlockingBridge { A11yBridge.liveCenter(selector) }

        override fun click(x: Int, y: Int): ActionResult =
            runBlockingBridge { A11yBridge.click(x, y) }

        override fun longClick(x: Int, y: Int, durationMs: Int): ActionResult =
            runBlockingBridge { A11yBridge.longClick(x, y, durationMs) }

        override fun clickNode(selector: A11ySelector, longClick: Boolean, durationMs: Int): ActionResult =
            runBlockingBridge { A11yBridge.clickNode(selector, longClick, durationMs) }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ActionResult =
            runBlockingBridge { A11yBridge.swipe(x1, y1, x2, y2, durationMs) }

        override fun scrollContainer(direction: String): ActionResult =
            runBlockingBridge { A11yBridge.scrollContainer(direction) }

        override fun globalAction(action: Int): Boolean =
            runBlockingBridge { A11yBridge.globalAction(action) }

        override fun typeText(text: String, x: Int, y: Int): ActionResult =
            runBlockingBridge { A11yBridge.typeText(text, x, y) }

        override fun launchApp(packageName: String): ActionResult =
            runBlockingBridge { A11yBridge.launchApp(packageName) }

        override fun openUri(uri: String, pkg: String?): ActionResult =
            runBlockingBridge { A11yBridge.openUri(uri, pkg) }

        override fun openSettingsAction(action: String): ActionResult =
            runBlockingBridge { A11yBridge.openSettingsAction(action) }

        override fun launchAssistant(text: String?): ActionResult =
            runBlockingBridge { A11yBridge.launchAssistant(text) }

        override fun handoffToAssistant(text: String?): String =
            runBlockingBridge { A11yBridge.handoffToAssistant(text) }

        override fun execShizuku(command: String): ShellResultParcel =
            runBlockingBridge { ShellBridge.execShizuku(command) }

        override fun execTermux(command: String, timeoutMs: Int): ShellResultParcel =
            runBlockingBridge { ShellBridge.execTermux(command, timeoutMs) }

        override fun termuxProbe(): ShellResultParcel =
            runBlockingBridge { ShellBridge.termuxProbe() }

        override fun adbStatus(): String = ShellBridge.adbStatusJson()

        override fun adbEnsureReady(): ShellResultParcel =
            runBlockingBridge { ShellBridge.adbEnsureReady() }

        override fun adbPairAndEnsureReady(code: String): ShellResultParcel =
            runBlockingBridge { ShellBridge.adbPairAndEnsureReady(code) }
    }

    /** Binder 线程阻塞等待 Bridge 的挂起实现；异常一律收口成失败回执，不允许炸穿 Binder 线程。
     *  block 声明为 suspend：Bridge 方法全是挂起函数；crossinline：调用点嵌在 withContext 的
     *  协程 lambda 里，禁止非局部返回。 */
    private inline fun <T> runBlockingBridge(crossinline block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() } }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        A11yBridge.init(applicationContext)
        ShellBridge.init(applicationContext)
    }

    override fun onDestroy() {
        ShellBridge.destroy()
        super.onDestroy()
    }

    companion object {
        /** 契约版本：增删 IDeviceProvider 方法时递增 */
        const val API_VERSION = 1
    }
}
