package com.phoneagent

import android.app.Application
import com.phoneagent.di.initKoin

class PhoneAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this)
        // 内置浏览器桥：AI 执行 browse_* 时在后台静默宿主里加载网页（不切前台；无悬浮窗权限才回退到可见浏览器页）
        com.phoneagent.feature.browser.BrowserBridge.init(this)
    }
}