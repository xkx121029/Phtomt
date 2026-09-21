package com.phoneagent

import android.app.Application
import com.phoneagent.di.initKoin

class PhoneAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this)
        // 内置浏览器桥：AI 执行 browse_* 时即使界面还没起来，也要能从后台把浏览器页拉起来
        com.phoneagent.feature.browser.BrowserBridge.init(this)
    }
}