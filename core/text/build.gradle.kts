import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:text —— HTML → Markdown 转换、实体解码、时间格式化。
//
// 为什么是 kotlin("jvm") 而不是 com.android.library：本模块一个 android.* 引用都没有，
// 纯 JVM 模块编译更快（不必等 AGP 的变体解析），而且**编译期就挡住**了"顺手 import 一个
// android.util.Log / Context"——那是包搬出 :app 之后最容易发生的退化。
//
// 用不了这个模板的包：只要有一个 android.* 引用（例如 :core:log 的 AppLog 直接用
// android.util.Log），就必须用 com.android.library，见 core/log/build.gradle.kts。
plugins {
    // 这里不许写版本：KGP 已经在根 buildscript classpath 上（根 build.gradle.kts 的
    // `alias(libs.plugins.kotlin.android) apply false` 把它带进来了），再写
    // `alias(libs.plugins.kotlin.jvm)` 会直接失败：
    //   "The request for this plugin could not be satisfied because the plugin is already
    //    on the classpath with an unknown version, so compatibility cannot be checked."
    // 版本仍由 libs.versions.toml 的 `kotlin` 单一来源决定。
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.detekt)
}

// 与 :app 保持同一个 JVM 目标（app/build.gradle.kts 的 jvmTarget 是 17），
// 否则 :app 引用本模块时会报 inline class / 元数据版本不兼容。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 刻意不加实现依赖：本模块只依赖 Kotlin 标准库。往这里加依赖前先问一句"这一层还配叫 core 吗"。
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 完全同源（同一份 config/detekt/detekt.yml），
// 但**豁免名单是本模块自己的**——baseline 的匹配键是 `RuleId:文件名$签名`、不含路径，
// 所以包搬出 :app 之后，:app 的 baseline 兜不住这里的存量问题，必须有自己的一份。
// ---------------------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("baseline.xml")
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    setSource(files("src/main/kotlin", "src/test/kotlin"))
    reports {
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// 基线条目数只减不增，口径与 :app 的 checkDetektBaseline 一致：
// 超出即失败（不许往基线里加新条目绕过闸门），变小则回写更小的值。
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
