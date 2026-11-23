import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:log —— 应用统一日志门面（AppLog + runCatchingLogged）。
//
// 为什么这里用 com.android.library 而 :core:text 用 kotlin("jvm")：AppLog 直接调
// android.util.Log，而且 `sink` 的 level 契约就是 Log.DEBUG/INFO/WARN/ERROR 的 Int 常量
// （TelemetryRecorder 按它分档），这不是"换个 import 就能变纯 JVM"的东西。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.phoneagent.core.log"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            // android.util.Log 等返回默认值（0/null），与 :app 的口径一致（app/build.gradle.kts）；
            // AppLog 直接调 android.util.Log，不开这个开关，本模块的单测一跑就 "not mocked"。
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Catching.kt 用的是 kotlinx.coroutines.CancellationException（JVM 上等于
    // java.util.concurrent.CancellationException，但保留原 import 以免改动调用方语义）
    implementation(libs.kotlinx.coroutines.core)
    // runCatchingLogged 的契约单测（原在 :app 的 src/test，搬进本模块才能被
    // :core:log:testDebugUnitTest 跑到；留在 :app 时这条 CI 任务是 NO-SOURCE）
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源，豁免名单本模块自有（匹配键 `RuleId:文件名$签名` 不含路径，
// 包搬出 :app 后原豁免不再生效）。
// ---------------------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("baseline.xml")
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    setSource(files("src/main/java", "src/test/java"))
    reports {
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// 基线条目数只减不增，口径与 :app 的 checkDetektBaseline 一致。
val detektBaselineFile = file("baseline.xml")
val detektBaselineCountFile = file("baseline-count.txt")

fun countDetektBaselineEntries(file: File): Int =
    if (!file.exists()) 0 else file.readLines().count { it.trimStart().startsWith("<ID>") }

val checkDetektBaseline by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线只减不增（新增豁免条目会让构建失败）"
    doLast {
        // 没有 baseline.xml 不是错误：那说明本模块搬进来的文件本来零豁免（例如 :core:log）。
        // 硬要求 count 文件存在会把「没有存量问题」判成失败，那是把闸门装反了。
        if (!detektBaselineFile.exists()) {
            logger.lifecycle("detekt 基线：本模块无 baseline.xml（零存量豁免），跳过。")
            return@doLast
        }
        val actual = countDetektBaselineEntries(detektBaselineFile)
        val recorded = detektBaselineCountFile.takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull()
            ?: throw GradleException(
                "缺少 ${detektBaselineCountFile.name}；请写入当前基线条目数（$actual）。",
            )
        if (actual > recorded) {
            throw GradleException(
                "detekt 基线条目从 $recorded 增加到 $actual：请修复新问题而不是把新条目写进基线。",
            )
        }
        if (actual < recorded) detektBaselineCountFile.writeText("$actual\n")
    }
}

tasks.named("check") { dependsOn(checkDetektBaseline) }
