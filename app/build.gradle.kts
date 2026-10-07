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

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

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
