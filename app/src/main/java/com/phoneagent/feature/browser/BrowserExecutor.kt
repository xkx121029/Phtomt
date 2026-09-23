package com.phoneagent.feature.browser

/**
 * 浏览器通道的执行缝。
 *
 * [BrowserBridge] 直接碰 `android.webkit.WebView`、`android.util.Log` 和 Activity 启动，
 * 在 JVM 单测（`testDebugUnitTest`）里一调用就抛 "not mocked"，于是通道的判定逻辑
 * （参数校验 / 只读护栏 / 措辞）根本测不了。这里把"决策"与"具体操作"切开：
 * 生产用 [BridgeExecutor] 转调 Bridge，单测注入假实现，还能断言"被拒时压根没落到执行"。
 */
internal interface BrowserExecutor {
    suspend fun open(url: String): BrowseResult
    suspend fun read(): BrowseResult
    suspend fun click(by: String, value: String, guard: Boolean): BrowseResult
    suspend fun input(by: String, value: String, text: String): BrowseResult
    suspend fun scroll(direction: String): BrowseResult
    suspend fun back(): BrowseResult
}

/** 生产实现：一律转调 [BrowserBridge]（切主线程、等加载完成等细节都在 Bridge 里）。 */
internal class BridgeExecutor : BrowserExecutor {
    override suspend fun open(url: String): BrowseResult = BrowserBridge.open(url)
    override suspend fun read(): BrowseResult = BrowserBridge.read()
    override suspend fun click(by: String, value: String, guard: Boolean): BrowseResult =
        BrowserBridge.click(by, value, guard)
    override suspend fun input(by: String, value: String, text: String): BrowseResult =
        BrowserBridge.input(by, value, text)
    override suspend fun scroll(direction: String): BrowseResult = BrowserBridge.scroll(direction)
    override suspend fun back(): BrowseResult = BrowserBridge.back()
}