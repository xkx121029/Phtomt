import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:perf —— 启动/耗时分段打点（`StartupTrace.span`）。
//
// 为什么是独立模块：`:data` 的 DebugRecordsStore 在读存档时要打三段耗时点
// （读文件 / 解析 JSON / 解码缩略图），`StartupTrace` 却在 `:app` 的 `core/perf` 下——
// `:data` 看不见它。它是 HAPP-66 分期表里 P1 本来就列出的 `:core:perf`，
// 47 行、只依赖 android.os / android.util，单独成模块比塞进持久化层诚实。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.phoneagent.core.perf"
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
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源，豁免名单本模块自有（匹配键 `RuleId:文件名$签名` 不含路径）。
// 本模块文件在 :app 基线里零豁免，所以没有 baseline.xml——闸门会打印「跳过」并通过。
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

val checkDetektBaseline by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线只减不增（新增豁免条目会让构建失败）"
    doLast {
        val baseline = file("baseline.xml")
        if (!baseline.exists()) {
            logger.lifecycle("detekt 基线：本模块无 baseline.xml（零存量豁免），跳过。")
            return@doLast
        }
        val actual = baseline.readLines().count { it.trimStart().startsWith("<ID>") }
        val countFile = file("baseline-count.txt")
        val recorded = countFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
            ?: throw GradleException("缺少 baseline-count.txt；请写入当前基线条目数（$actual）。")
        if (actual > recorded) {
            throw GradleException("detekt 基线条目从 $recorded 增加到 $actual：请修复新问题而不是把新条目写进基线。")
        }
        if (actual < recorded) countFile.writeText("$actual\n")
    }
}

tasks.named("check") { dependsOn(checkDetektBaseline) }
