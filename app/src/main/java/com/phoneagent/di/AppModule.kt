package com.phoneagent.di

import android.content.Context
import com.phoneagent.engine.AgentEngine
import com.phoneagent.core.ai.AiClient
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.feature.mcp.McpManager
import com.phoneagent.data.store.McpStore
import com.phoneagent.feature.mcp.OkHttpMcpTransportFactory
import com.phoneagent.data.store.MemoryStore
import com.phoneagent.device.shell.ShizukuManager
import com.phoneagent.device.shell.AdbWirelessTransport
import com.phoneagent.device.shell.ShizukuBootstrap
import com.phoneagent.device.shell.WirelessAdbPairingFlow
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillExecutionGateway
import com.phoneagent.feature.skill.SkillRegistry
import com.phoneagent.feature.test.TestEngine
import com.phoneagent.ui.MainViewModel
import com.phoneagent.feature.document.DocumentEngine
import com.phoneagent.device.shell.TermuxBridge
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import kotlinx.coroutines.flow.first
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val appModule = module {
    single { AppSettings(androidContext()) }
    single { AiClient.create() }
    single { ShizukuManager() }
    single { MemoryStore(androidContext()) }
    single { DocumentEngine(androidContext()) }
    single { TermuxBridge(androidContext()) }
    single { AgentEngine(get(), get(), androidContext(), get(), get<AdbWirelessTransport>(), get(), get(), get(), get()) }
    single { TestEngine(get()) }

    // ===== HPA 迭代：Skill / MCP / 提示词 / 双通路 =====
    // Skill 注册表：内置技能 + 用户自定义 + MCP 技能（初值仅内置）
    single { SkillRegistry(SkillCatalog.builtins()) }
    // MCP 管理器：服务器列表启动时为空，用户在设置页配置后持久化并由 UI 观察；可变单例（可被引擎/网关共享）
    single { OkHttpMcpTransportFactory.managerOf(emptyList()) }
    // MCP 服务器配置持久化（DataStore）
    single { McpStore(androidContext()) }
    // 执行网关：统一 Skill/MCP 解析入口
    single { SkillExecutionGateway(get(), get()) }
    // 无线 ADB 传输（共享单例：ShizukuBootstrap 直连 / WirelessAdbPairingFlow 发现共用）
    single { AdbWirelessTransport(androidContext()) }
    // 执行通路启动器：无线 ADB 为主、Shizuku 可选；通道偏好取自 AppSettings.executionChannel
    single {
        ShizukuBootstrap(
            get(),
            get<AdbWirelessTransport>(),
            channelProvider = { get<AppSettings>().settings.first().executionChannel },
        )
    }
    // 无线 ADB「开始配对」通知栏向导
    single { WirelessAdbPairingFlow(androidContext(), get<AdbWirelessTransport>(), get()) }

    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

fun initKoin(context: Context) {
    startKoin {
        androidContext(context)
        modules(appModule)
    }
}