package com.phoneagent.di

import android.content.Context
import com.phoneagent.agent.AgentEngine
import com.phoneagent.ai.AiClient
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.mcp.McpManager
import com.phoneagent.mcp.OkHttpMcpTransportFactory
import com.phoneagent.memory.MemoryStore
import com.phoneagent.prompt.PromptTemplateStore
import com.phoneagent.shizuku.ShizukuManager
import com.phoneagent.shizuku.adb.AdbWirelessTransport
import com.phoneagent.shizuku.adb.ShizukuBootstrap
import com.phoneagent.skill.SkillCatalog
import com.phoneagent.skill.SkillExecutionGateway
import com.phoneagent.skill.SkillRegistry
import com.phoneagent.test.TestEngine
import com.phoneagent.ui.MainViewModel
import com.phoneagent.workspace.WorkAreaEngine
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val appModule = module {
    single { AppSettings(androidContext()) }
    single { AiClient.create() }
    single { ShizukuManager() }
    single { MemoryStore(androidContext()) }
    single { WorkAreaEngine(androidContext(), get(), get()) }
    single { AgentEngine(get(), get(), androidContext(), get(), get()) }
    single { TestEngine(get()) }

    // ===== HPA 迭代：Skill / MCP / 提示词 / 双通路 =====
    // Skill 注册表：内置技能 + 用户自定义 + MCP 技能（初值仅内置）
    single { SkillRegistry(SkillCatalog.builtins()) }
    // MCP 管理器：服务器列表启动时为空，用户在设置页配置后 replaceFlag 由 UI 重建
    single { OkHttpMcpTransportFactory.managerOf(emptyList()) }
    // 提示词模板库：内置默认模板
    single { PromptTemplateStore(PromptTemplateStore.defaults()) }
    // 执行网关：统一 Skill/MCP 解析入口
    single { SkillExecutionGateway(get(), get()) }
    // Shizuku 双通路启动器：Shizuku 直连 / 无线 ADB 拉起
    single { ShizukuBootstrap(get(), AdbWirelessTransport(androidContext())) }

    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

fun initKoin(context: Context) {
    startKoin {
        androidContext(context)
        modules(appModule)
    }
}