import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        targetSdk = 35
        versionCode = loadBuildNumber()
        versionName = "0.1.${loadBuildNumber()}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.mlkit.text.chinese)

    debugImplementation(libs.androidx.ui.tooling)
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