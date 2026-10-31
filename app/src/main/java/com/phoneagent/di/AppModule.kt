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
import com.phoneagent.device.shell.ShizukuBootstrap
import com.phoneagent.device.shell.WirelessAdbPairingFlow
import com.phoneagent.feature.update.UpdateManager
import com.phoneagent.feature.skill.SkillCatalog
import com.phoneagent.feature.skill.SkillExecutionGateway
import com.phoneagent.feature.skill.SkillRegistry
import com.phoneagent.feature.test.TestEngine
import com.phoneagent.ui.MainViewModel
import com.phoneagent.feature.document.DocumentEngine
import com.phoneagent.device.shell.TermuxBridge
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val appModule = module {
    single { AppSettings(androidContext()) }
    single { AiClient.create() }
    // shell 通道实现已迁入 Provider 应用：以下三者都是经 AIDL 远程调用的门面
    single { ShizukuManager(androidContext()) }
    single { MemoryStore(androidContext()) }
    single { DocumentEngine(androidContext()) }
    single { TermuxBridge(androidContext()) }
    // 末位注入 MemoryStore 单例：引擎与 UI 记忆页共用同一实例（消除双实例与各自缓存不一致）。
    // 全部用具名参数：11 个 get() 的位置参数一旦有人调整构造顺序就会静默错配，
    // 具名后编译器会直接报错（这是 AgentEngine 这种长参数列表唯一的静态保障）。
    single {
        AgentEngine(
            settings = get(),
            aiClient = get(),
            appContext = androidContext(),
            shizukuManager = get(),
            adbTransport = get(),
            termuxBridge = get(),
            documentEngine = get(),
            mcpManager = get(),
            skillGateway = get(),
            codingBridge = get(),
            memoryStore = get(),
        )
    }
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
    // 执行通路启动器门面：无线 ADB 引导与状态机在 Provider 内，主应用只轮询 adbStatus
    single { ShizukuBootstrap(androidContext()) }
    // 无线 ADB「开始配对」通知栏向导
    single { WirelessAdbPairingFlow(androidContext(), get<ShizukuBootstrap>()) }

    // 自动更新：查询官网版本、下载安装包，优先走 shell 静默装（经 Provider 的内置 Shizuku）
    single { UpdateManager(androidContext(), get<ShizukuManager>()) }

    // 编码运行时管理器：Node 宿主随主 APK 内置（app/src/main/jniLibs），只做就绪探测
    single { com.phoneagent.coding.CodingRuntimeManager(androidContext()) }

    // 编码 agent 委托桥（Superagent 融合 Phase 4）：引擎路由与 Phase 5 编码页共享同一实例
    single { com.phoneagent.engine.coding.CodingAgentBridge(androidContext(), get(), get()) }

    viewModel {
        MainViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
}

fun initKoin(context: Context) {
    startKoin {
        androidContext(context)
        modules(appModule)
    }
}
