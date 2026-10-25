import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// 静态检查：默认规则集当"下限"，再用基线豁免存量问题。
// 基线只固化"今天已经存在的"问题，因此这次接入不会让构建先爆红，
// 而新增/改动的代码再犯同类问题（长函数、超长行、未用导入…）会立刻失败——
// 这是把 4000 行级文件继续变胖的唯一自动闸门。
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // 只扫手写源码；build/ 下的生成物（BuildConfig、资源 id）不参与
    setSource(files("src/main/java", "src/test/java"))
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// 版本号文件：读取在配置阶段（只读），自增推迟到构建执行阶段，
// 避免 IDE 同步/任意 Gradle 配置触发版本号无意义上涨
val versionPropsFile = rootProject.file("version.properties")

fun loadBuildNumber(): Int {
    if (!versionPropsFile.exists()) return 1
    val props = Properties().apply {
        runCatching { versionPropsFile.inputStream().use { load(it) } }
    }
    return props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 1
}

android {
    namespace = "com.phoneagent"
    compileSdk = 36

    // 签名配置：从 upload-signing.properties 读取（本地文件，不提交到仓库）
    val signingPropsFile = file("upload-signing.properties")
    if (signingPropsFile.exists()) {
        val props = Properties().apply { signingPropsFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(props["storeFile"] as? String ?: "upload-keystore.jks")
                storePassword = props["storePassword"] as? String ?: ""
                keyAlias = props["keyAlias"] as? String ?: ""
                keyPassword = props["keyPassword"] as? String ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        targetSdk = 36
        versionCode = loadBuildNumber()
        versionName = "0.2.${loadBuildNumber()}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // 端侧 3B 视觉引擎的预编译 .so 仅有 arm64-v8a（libllama/libmtmd/libggml*），
        // 统一限定 ABI：debug 与 release 一致，避免模拟器 ABI 装上后 dlopen 失败
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // ABI 精简在 defaultConfig 统一限定 arm64-v8a（端侧视觉 .so 仅有该 ABI）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            // 覆盖率：AGP 内置 JaCoCo 支持，产出的报告由
            // `createDebugUnitTestCoverageReport` 生成（CI 会上传为构建产物）。
            // 覆盖率数字只用于"看见盲区"——不对全量设阈值：本工程主体是
            // 无障碍/Shizuku/悬浮窗这类只能在真机上跑的逻辑，JVM 单测天然覆盖不到。
            enableUnitTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    // 端侧视觉 JNI 垫片：仅编译 visionbridge（dlopen 方式加载 jniLibs 里的 llama/mtmd）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 编码运行时（libnode.so + 依赖闭包）随主 APK 打包：so 必须是磁盘上的真实文件
        // （Android 10+ 只允许从 nativeLibraryDir 执行），不能 zip 内存映射
        jniLibs.useLegacyPackaging = true
    }
    testOptions {
        unitTests {
            // android.util.Log 等返回默认值（0/null），使纯逻辑单测可在 JVM 运行
            isReturnDefaultValues = true
            all { it.testLogging { events("passed", "skipped", "failed") } }
        }
    }
}

// Kotlin 编译目标：Kotlin 2.4 起 android 块内的 `kotlinOptions.jvmTarget = "17"` 已移除
// （字符串形式直接报错），统一改用 compilerOptions DSL。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 图标体系已整体迁移至 Lucide（见 ui/icons/AppIcons.kt），material-icons-extended 不再被引用
    implementation(libs.lucide.icons)
    // 毛玻璃（背景模糊）：仅用于真正浮在滚动内容之上的固定 chrome
    implementation(libs.haze)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Shizuku 客户端库与内嵌 server 已随 shell 通道迁入 Provider 应用（:provider 模块），
    // 主应用不再直接引用 rikka.shizuku API，:shizuku-api / :shizuku-provider / :shizuku-server
    // 三个依赖一并移除（减小 APK 体积）；shell 能力改经 :provider-api 的 AIDL 远程调用

    // 权限 Provider 跨进程契约（IDeviceProvider AIDL + Parcelable）：
    // 与 :provider 共享同一份定义，序列化两端严格一致
    implementation(project(":provider-api"))

    // 端侧视觉离线兜底：ML Kit 中文 OCR（3B 模型未下载/推理失败时框选控件）
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.common)

    debugImplementation(libs.androidx.ui.tooling)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 版本号自增仅在真正执行构建（assemble/bundle）时发生，避免配置阶段误增
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }
    .configureEach {
        doFirst {
            val props = Properties().apply {
                if (versionPropsFile.exists()) runCatching { versionPropsFile.inputStream().use { load(it) } }
            }
            val next = (props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 0) + 1
            props.setProperty("BUILD_NUMBER", next.toString())
            runCatching { versionPropsFile.outputStream().use { props.store(it, "Auto-incremented by Gradle") } }
        }
    }

// ---------------------------------------------------------------------------
// 编码 agent host 同步：从 happy_ACA 上游仓库单向同步 agent-core 源码到
// assets/agent-host（tool/bundle_host.mjs 生成 VERSION 内容指纹，RuntimeBootstrap
// 据指纹决定是否重新解压）。只经脚本单向流动，assets/agent-host 不手改、不提交。
// HACA 检出不存在时跳过（CI/纯 HPA 开发者无需克隆上游仓库），路径可用
// HACA_ROOT 环境变量覆盖，默认 ../happy_ACA。
// ---------------------------------------------------------------------------
val hacaRoot = System.getenv("HACA_ROOT") ?: rootProject.file("../happy_ACA").absolutePath
val bundleHost by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    environment("HACA_ROOT", hacaRoot)
    commandLine("node", "tool/bundle_host.mjs")
    onlyIf { file("$hacaRoot/packages/agent-core/src/index.js").exists() }
}

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(bundleHost)
}

// ---------------------------------------------------------------------------
// 权限 Provider APK（assets 内静态资产）：Provider 构建产物直接提交在
// src/{debug,release}/assets/provider/ 下，普通主应用构建【不再】先编译
// :provider——Provider 变更频率低，避免每次迭代都为它买单。
// 按变体分目录保证签名一致：debug 主应用 ↔ debug 证书 Provider，release ↔
// upload 证书；签名不一致会导致 signature 级 BIND_PROVIDER 绑定被拒。
// Provider 模块有改动后手动执行一次：
//   .\gradlew.bat :app:syncProviderAssets
// ---------------------------------------------------------------------------
val providerBuildDir = project(":provider").layout.buildDirectory

val syncProviderDebug by tasks.registering(Copy::class) {
    group = "provider"
    description = "把最新 provider-debug.apk 同步进主应用 debug assets（提交进仓库）"
    dependsOn(":provider:assembleDebug", ":provider:writeProviderVersion")
    from(providerBuildDir.file("outputs/apk/debug/provider-debug.apk")) { rename { "provider.apk" } }
    from(providerBuildDir.file("provider/version.txt"))
    into(file("src/debug/assets/provider"))
}

val syncProviderRelease by tasks.registering(Copy::class) {
    group = "provider"
    description = "把最新 provider-release.apk 同步进主应用 release assets（提交进仓库）"
    if (rootProject.file("app/upload-signing.properties").exists()) {
        dependsOn(":provider:assembleRelease", ":provider:writeProviderVersion")
        from(providerBuildDir.file("outputs/apk/release/provider-release.apk")) { rename { "provider.apk" } }
    } else {
        // 未配置上传密钥时回落 debug 包（本地 release 构建同样用 debug 证书）
        dependsOn(":provider:assembleDebug", ":provider:writeProviderVersion")
        from(providerBuildDir.file("outputs/apk/debug/provider-debug.apk")) { rename { "provider.apk" } }
    }
    from(providerBuildDir.file("provider/version.txt"))
    into(file("src/release/assets/provider"))
}

tasks.register("syncProviderAssets") {
    group = "provider"
    description = "同步两个变体的 Provider APK 到主应用 assets（Provider 改动后手动执行）"
    dependsOn(syncProviderDebug, syncProviderRelease)
}
