package com.phoneagent

import android.app.Application
import android.content.Context
import com.phoneagent.di.initKoin

class PhoneAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        initKoin(this)
        // 远程无障碍会话：注入 appContext，观察 Provider 绑定与无障碍连接状态
        com.phoneagent.device.a11y.AgentAccessibilityService.install(this)
        // 内置浏览器桥：AI 执行 browse_* 时在后台静默宿主里加载网页（不切前台；无悬浮窗权限才回退到可见浏览器页）
        com.phoneagent.feature.browser.BrowserBridge.init(this)
        // 编码工作区登记表：恢复活动工作区指针，保证 agent 进程 cwd / state 目录与界面上选中的一致
        runCatching { com.phoneagent.coding.agent.CodingWorkspaces.ensureInitialized(this) }
    }

    companion object {
        /** 全局 Application Context：无 Activity 的场景（Widget / 状态总线）取用 */
        @Volatile
        lateinit var appContext: Context
            private set
    }
}