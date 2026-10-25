import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 与主应用共享版本号（只读，不自增：自增闸门只挂在主应用的 assemble/bundle 上）
val versionPropsFile = rootProject.file("version.properties")
fun loadBuildNumber(): Int {
    if (!versionPropsFile.exists()) return 1
    val props = Properties().apply {
        runCatching { versionPropsFile.inputStream().use { load(it) } }
    }
    return props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 1
}

android {
    namespace = "com.phoneagent.provider"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneagent.provider"
        minSdk = 26
        targetSdk = 36
        versionCode = loadBuildNumber()
        versionName = "0.2.${loadBuildNumber()}"
    }

    // 无 UI：无 launcher activity，只有无障碍服务 + AIDL 服务 + 授权页
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
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
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 与主应用共用 vendored Shizuku 客户端 + 内嵌 server（third_party/shizuku）
    implementation(project(":shizuku-api"))
    implementation(project(":shizuku-provider"))
    implementation(project(":shizuku-server"))
}

// 版本文件：主应用嵌入 assets 后，用于「已安装版本 vs 内置版本」的更新判断
val writeProviderVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("provider")
    outputs.file(outDir.map { it.file("version.txt") })
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "version.txt").writeText(android.defaultConfig.versionName ?: "0.0")
    }
}
