package com.phoneagent.PhoneAgentApp

import android.app.Application
import com.phoneagent.di.initKoin

class PhoneAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(this)
    }
}