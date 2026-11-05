package com.phoneagent

import android.app.Application
import android.content.Context
import com.phoneagent.core.perf.StartupTrace
import com.phoneagent.di.initKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("Application.onCreate 开始")
        appContext = this
        initKoin(this)
        StartupTrace.mark("Koin 容器注册完成")
        // 远程无障碍会话：注入 appContext，观察 Provider 绑定与无障碍连接状态
        com.phoneagent.device.a11y.AgentAccessibilityService.install(this)
        // 内置浏览器桥：AI 执行 browse_* 时在后台静默宿主里加载网页（不切前台；无悬浮窗权限才回退到可见浏览器页）
        com.phoneagent.feature.browser.BrowserBridge.init(this)
        // 编码工作区登记表：恢复活动工作区指针，保证 agent 进程 cwd / state 目录与界面上选中的一致。
        // 这里要读注册表 JSON 并建目录（磁盘 IO），放到 IO 线程，不占首帧；
        // ensureInitialized 自带 @Synchronized，编码页自己再调一次也不会与这里冲突。
        ioScope.launch {
            StartupTrace.span("编码工作区恢复") {
                runCatching { com.phoneagent.coding.agent.CodingWorkspaces.ensureInitialized(this@PhoneAgentApp) }
            }
        }
        StartupTrace.mark("Application.onCreate 结束")
    }

    companion object {
        /** 进程级 IO 协程域：只用于启动期非首帧必需的磁盘准备 */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** 全局 Application Context：无 Activity 的场景（Widget / 状态总线）取用 */
        @Volatile
        lateinit var appContext: Context
            private set
    }
}