plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

// 跨进程契约共享模块：主应用（调用方）与 Provider 应用（实现方）各自打包一份，
// 保证 AIDL 序列化两端完全一致，避免手写副本漂移。
android {
    namespace = "com.phoneagent.provider"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
