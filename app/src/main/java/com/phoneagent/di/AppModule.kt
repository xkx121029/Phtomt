package com.phoneagent.di

import android.content.Context
import com.phoneagent.agent.AgentEngine
import com.phoneagent.ai.AiClient
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.test.TestEngine
import com.phoneagent.ui.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val appModule = module {
    single { AppSettings(androidContext()) }
    single { AiClient.create() }
    single { AgentEngine(get(), get(), androidContext()) }
    single { TestEngine(get()) }
    viewModel { MainViewModel(get(), get(), get()) }
}

fun initKoin(context: Context) {
    startKoin {
        androidContext(context)
        modules(appModule)
    }
}