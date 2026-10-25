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

/**
 * 发布签名：与主应用共用同一份 upload-signing.properties / keystore。
 * 必须同签名——Provider 的 AIDL 服务挂 signature 级权限 BIND_PROVIDER，
 * 签名不同则 release 主应用绑定不上（表现为服务组件「已安装但永远连不上」）。
 */
val uploadSigningPropsFile = rootProject.file("app/upload-signing.properties")

android {
    namespace = "com.phoneagent.provider"
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
            // 有上传密钥时同主应用签名（signature 级 BIND_PROVIDER 要求两边同证书）
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

    // 跨进程契约与主应用共享同一份 AIDL/Parcelable 定义
    implementation(project(":provider-api"))

    // 与主应用共用 vendored Shizuku 客户端 + 内嵌 server（third_party/shizuku）
    implementation(project(":shizuku-api"))
    implementation(project(":shizuku-provider"))
    implementation(project(":shizuku-server"))

    // 分片传输的边界行为（越界 / 淘汰 / 逐片拼回）用纯 JVM 单测覆盖，不需要真机
    testImplementation(libs.junit)
}

// 版本文件：主应用嵌入 assets 后，用于「已安装版本 vs 内置版本」的更新判断
val writeProviderVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("provider")
    outputs.file(outDir.map { it.file("version.txt") })
    // 版本号在配置阶段读取，但任务本身没有可追踪的输入文件：
    // 一旦被 Gradle 判为 UP-TO-DATE，就会一直沿用旧的 version.txt，
    // 主应用据此误判「已是最新」而永远不提示更新服务组件。故强制每次重写。
    outputs.upToDateWhen { false }
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "version.txt").writeText(android.defaultConfig.versionName ?: "0.0")
    }
}
