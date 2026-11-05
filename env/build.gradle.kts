import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 与主应用共享版本号：由根 build.gradle.kts 统一计算
val buildNumber: Int = rootProject.extra["buildNumber"] as Int

/**
 * 发布签名：与主应用共用同一份 upload-signing.properties / keystore。
 * 必须同签名——EnvService 挂 signature 级权限 BIND_ENV，签名不同则主应用绑不上。
 */
val uploadSigningPropsFile = rootProject.file("app/upload-signing.properties")

android {
    namespace = "com.phoneagent.env"
    compileSdk = 36

    if (uploadSigningPropsFile.exists()) {
        val props = Properties().apply { uploadSigningPropsFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file("app/${props["storeFile"] as? String ?: "upload-keystore.jks"}")
                storePassword = props["storePassword"] as? String ?: ""
                keyAlias = props["keyAlias"] as? String ?: ""
                keyPassword = props["keyPassword"] as? String ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.phoneagent.env"
        minSdk = 26
        // targetSdk 28 是硬约束而不是偷懒：Android 10+ 的 W^X 只允许 targetSdk < 29 的应用
        // execve 私有目录（filesDir）里的文件。proot 引导集必须落在 filesDir 并被 execve，
        // 一旦把 targetSdk 提到 29+，SELinux 会在启动瞬间拒绝执行，整个 Linux 环境直接不可用。
        // 这是环境组件独立成 APK 的唯一原因（主应用 targetSdk 36，做不到）。
        targetSdk = 28
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 固定 28 是本模块的设计前提，按项关掉这条政策告警
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 跨进程契约与主应用共享同一份 AIDL 定义
    implementation(project(":env-api"))
}

// 版本文件：主应用嵌入 assets 后，用于「已安装版本 vs 内置版本」的更新判断
val writeEnvVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("env")
    outputs.file(outDir.map { it.file("version.txt") })
    // 版本号在配置阶段读取，任务没有可追踪的输入：强制每次重写，
    // 否则被判 UP-TO-DATE 后主应用会拿旧版本号误判「已是最新」
    outputs.upToDateWhen { false }
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "version.txt").writeText(android.defaultConfig.versionName ?: "0.0")
    }
}
