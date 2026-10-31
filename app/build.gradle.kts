import java.io.File
import java.security.MessageDigest
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
    // 项目级规则微调（Compose 命名、文件末尾换行等），见文件内注释
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
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

// ---------------------------------------------------------------------------
// detekt 基线只减不增：baseline 是存量问题的豁免名单，随修复变小是正常的，
// 但靠往基线里追加条目来"绕过闸门"会让闸门彻底失效（新问题一律能被一键豁免）。
// 记录条目数在 config/detekt/baseline-count.txt：超出即失败；变小则回写更小的值，
// 防止之后又悄悄长回去。
// ---------------------------------------------------------------------------
val detektBaselineFile = rootProject.file("config/detekt/baseline.xml")
val detektBaselineCountFile = rootProject.file("config/detekt/baseline-count.txt")

fun countDetektBaselineEntries(file: File): Int =
    if (!file.exists()) 0 else file.readLines().count { it.trimStart().startsWith("<ID>") }

val checkDetektBaseline by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线只减不增（新增豁免条目会让构建失败）"
    doLast {
        val actual = countDetektBaselineEntries(detektBaselineFile)
        val recorded = detektBaselineCountFile.takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull()
            ?: throw GradleException(
                "缺少 ${detektBaselineCountFile.relativeTo(rootProject.projectDir)}；" +
                    "请写入当前基线条目数（$actual）。",
            )
        if (actual > recorded) {
            throw GradleException(
                "detekt 基线条目从 $recorded 增加到 $actual：请修复新问题而不是把新条目写进基线。",
            )
        }
        // 修掉存量问题后条目只会变少：把新的下限写回，避免下次重新长回去
        if (actual < recorded) detektBaselineCountFile.writeText("$actual\n")
    }
}

tasks.named("check") { dependsOn(checkDetektBaseline) }

/**
 * 版本号由根 build.gradle.kts 统一计算（每次产出 APK 的构建自增一次并回写
 * version.properties），这里只读取同一个值，保证 :app 与 :provider 版本一致。
 */
val buildNumber: Int = rootProject.extra["buildNumber"] as Int

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
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"

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

// 版本号已由根 build.gradle.kts 统一计算并回写 version.properties

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
    // 同步完成即记下"这份 APK 对应哪份源码"（见下方 verifyProviderAssets）
    doLast {
        providerStampFiles.getValue("debug").apply {
            parentFile.mkdirs()
            writeText(providerSourceDigest())
        }
    }
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
    // 同 debug：记下这份 APK 对应的源码指纹
    doLast {
        providerStampFiles.getValue("release").apply {
            parentFile.mkdirs()
            writeText(providerSourceDigest())
        }
    }
}

tasks.register("syncProviderAssets") {
    group = "provider"
    description = "同步两个变体的 Provider APK 到主应用 assets（Provider 改动后手动执行）"
    dependsOn(syncProviderDebug, syncProviderRelease)
}

// ---------------------------------------------------------------------------
// Provider 资产新鲜度闸门
//
// assets/provider 里的 APK 是"构建产物直接提交进仓库"，因此它天然会落后于源码：
// 改了 provider / provider-api 却忘了同步时，主应用会静默地把旧 Provider 打进包，
// 表现为"线上行为还是老版本"，且没有任何编译期信号。
//
// 这里用源码指纹兜住：syncProviderAssets 时把当前指纹写进 config/provider-assets/<variant>.sha256，
// 每次合并 assets 前比对一次，不一致即构建失败并提示执行同步。
// 指纹覆盖 provider / provider-api 的 src/main 与各自的构建脚本（依赖/开关变化同样需要重新打包）。
// ---------------------------------------------------------------------------
val providerStampDir = rootProject.file("config/provider-assets")

fun providerSourceDigest(): String {
    // 注意：不要写 java.security.MessageDigest——脚本里的 `java` 指向 JavaPluginExtension，会解析失败
    val digest = MessageDigest.getInstance("SHA-256")
    val sourceRoots = listOf(
        project(":provider").file("src/main"),
        project(":provider-api").file("src/main"),
    )
    sourceRoots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".apk") }
            .sortedBy { it.absolutePath }.toList()
    }.forEach { f ->
        digest.update(f.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(f.readBytes())
    }
    listOf("provider", "provider-api").forEach { name ->
        project(":$name").file("build.gradle.kts").takeIf { it.exists() }?.let {
            digest.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
            digest.update(it.readBytes())
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// 两个变体的资产各自独立（签名/版本不同），因此各存一份指纹
val providerStampFiles = mapOf(
    "debug" to providerStampDir.resolve("debug.sha256"),
    "release" to providerStampDir.resolve("release.sha256"),
)

val verifyProviderAssets by tasks.registering {
    group = "provider"
    description = "校验 assets 内 Provider APK 是否与当前 Provider 源码一致（不一致请执行 :app:syncProviderAssets）"
    doLast {
        val expected = providerSourceDigest()
        val problems = providerStampFiles.mapNotNull { (variant, stampFile) ->
            when {
                !stampFile.exists() -> "$variant：缺少源码指纹记录（从未同步过 Provider 资产）"
                stampFile.readText().trim() != expected -> "$variant：Provider 源码已变更，assets 内的 APK 是旧版本"
                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Provider 资产与源码不一致：")
                    problems.forEach { appendLine("  - $it") }
                    appendLine("当前源码指纹：$expected")
                    appendLine("请执行 .\\gradlew.bat :app:syncProviderAssets 重新打包后再构建。")
                },
            )
        }
    }
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(verifyProviderAssets) }
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(verifyProviderAssets) }
// 同一次调用里先同步再校验：syncProviderAssets 会刷新指纹，不应被校验当成旧版本
verifyProviderAssets.configure { mustRunAfter(syncProviderDebug, syncProviderRelease) }

// 同一次调用里跑 syncProviderAssets + assemble* 时，assets 合并必须等同步落盘完成
// （两个任务共同读写 src/{debug,release}/assets/provider，Gradle 无法自动推断顺序）
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { mustRunAfter(syncProviderDebug) }
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { mustRunAfter(syncProviderRelease) }
