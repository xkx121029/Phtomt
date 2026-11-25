import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :data —— 持久化层：SharedPreferences / DataStore 偏好（prefs）、任务/记忆/MCP 存储（store）、
// 日志导出（export），以及**它自己持久化的那些记录类型**（StepTrace / TaskSession / MemoryEvent /
// McpServerConfig / TemplateMatcher / ModelCatalog / TabPrefsCodec）。
//
// 为什么是 com.android.library：DataStore、`Context.getSharedPreferences`、`Bitmap`（调试截图）、
// `MediaStore`（导出到系统下载目录）都在这一层，实测 11 个文件里 10 个有 android.* 引用。
//
// 为什么把 7 个"别处的类型"搬进来（重要，别当垃圾清理掉）：
// 这几个类型在 `:app` 的包名下，但 `:data` 必须看见它们（例如 DebugRecordsStore 要序列化
// TaskSession / MemoryEvent，McpStore 要序列化 McpServerConfig）。`:app → :data` 是既有方向，
// 所以 `:data` 不能反向依赖 `:app`，这些类型只能下沉。搬的时候**包名一律不动**，
// 于是 :app 里 60 多处 import 一行都不用改；代价是这些包被两个模块各持一半（split package），
// 清单见报告「split package」一节。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // 不带版本：序列化插件已在根 buildscript classpath 上
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.phoneagent.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            // 覆盖率闸门要读 createDebugUnitTestCoverageReport 的报告
            enableUnitTestCoverage = true
        }
    }
    testOptions {
        unitTests {
            // 与 :app 口径一致：android.util.Log / Bitmap 等在 JVM 单测里返回默认值
            isReturnDefaultValues = true
            all { it.testLogging { events("passed", "skipped", "failed") } }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:log"))
    implementation(project(":core:text"))
    implementation(project(":core:security"))
    implementation(project(":core:perf"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // 搬进来的 SettingsCacheTest 直接构造 Preferences（DataStore 的纯数据面，不含 IO）
    testImplementation(libs.androidx.datastore.preferences)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源，豁免名单本模块自有（匹配键 `RuleId:文件名$签名` 不含路径）。
// 本模块的 49 条存量豁免是从 :app 基线里按文件名筛出来的（含搬进来的测试文件）。
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

val detektBaselineFile = file("baseline.xml")
val detektBaselineCountFile = file("baseline-count.txt")

fun countDetektBaselineEntries(file: File): Int =
    if (!file.exists()) 0 else file.readLines().count { it.trimStart().startsWith("<ID>") }

val checkDetektBaseline by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线只减不增（新增豁免条目会让构建失败）"
    doLast {
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

// ---------------------------------------------------------------------------
// 覆盖率闸门：百分比 + 可执行行数下限。
//
// 行数下限这条不是摆设：`com/phoneagent/data` 这个包在 :app 里可能还有别的类（例如
// `data/store` 这类包名共用的情形），只测百分比的话类被搬走也照样绿——HAPP-77 在
// `core/text` 上实测过这种静默窄化。规则必须和它守护的类待在同一个编译单元。
// ---------------------------------------------------------------------------
// 覆盖率闸门：百分比 + **可执行行数下限**。
//
// 只测百分比会漏掉「包还在、类被搬走」这种静默窄化（HAPP-77 在 core/text 上实测过），
// 所以每条规则都同时判两件事：可执行行数不得低于下限；行覆盖率不得低于阈值。
// 规则必须和它守护的类待在同一个编译单元里，否则迟早会拿别人的覆盖率交差。
// ---------------------------------------------------------------------------
// 本模块的名字面代码是 `com/phoneagent/data` 下三个子包（prefs / store / export），
// 它们各自是独立 <package> 块——**没有 `com/phoneagent/data` 这一层**，
// 所以闸门按前缀聚合三个子包，而不是去找一个不存在的包名。
// 三个子包缺任何一个都算失败：那意味着某一层被搬走或改名了。
val dataPackages = listOf(
    "com/phoneagent/data/prefs",
    "com/phoneagent/data/store",
    "com/phoneagent/data/export",
)
val coverageThreshold = 0.28
// 1351 → 1350：`AgentSurface` 下沉到 `:domain` 带走 1 行可执行代码，2026-11-25 CI 第 9 步因此红
val coverageMinLines = 1350

data class LineCoverage(val covered: Int, val total: Int)

fun lineCounterOf(block: String): Pair<Int, Int>? {
    val counter = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
        .findAll(block).lastOrNull() ?: return null
    return counter.groupValues[1].toInt() to counter.groupValues[2].toInt()
}

// 覆盖率闸门失败时的逐类诊断（实现在 root 的 gradle/coverage-shame.gradle.kts，六个模块共用一份）。
val coverageShame = rootProject.extra["coverageShame"] as (String, (String) -> Boolean, Int, Int?, String) -> String

val checkDataCoverage by tasks.registering {
    group = "verification"
    description = "校验 com/phoneagent/data 三个子包的覆盖率与可执行行数下限"
    dependsOn("createDebugUnitTestCoverageReport")
    doLast {
        val reportXml = layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :data:testDebugUnitTest。")
        }
        val text = reportXml.readText()
        var covered = 0
        var total = 0
        dataPackages.forEach { pkg ->
            val block = Regex("""<package name="${Regex.escape(pkg)}">([\s\S]*?)</package>""")
                .find(text)?.groupValues?.get(1)
                ?: throw GradleException(
                    "覆盖率闸门：报告里没有 $pkg —— 该子包被搬走或改名了。" +
                        "请把这条规则一起搬到新位置，而不是让它在这里静默失效。",
                )
            val (missed, cov) = lineCounterOf(block)
                ?: throw GradleException("覆盖率闸门：$pkg 没有 LINE 计数器（报告格式变了？）")
            if (missed + cov == 0) {
                throw GradleException("覆盖率闸门：$pkg 的可执行行为 0 —— 测试没跑到这个子包。")
            }
            covered += cov
            total += missed + cov
        }
        if (total < coverageMinLines) {
            throw GradleException(
                "com/phoneagent/data 只剩 $total 行可执行代码（下限 $coverageMinLines）：子包里的类被搬走了？\n" +
                    coverageShame(
                        text,
                        { name: String -> dataPackages.any { pkg -> name.startsWith("$pkg/") } },
                        total,
                        coverageMinLines,
                        "data/build.gradle.kts 的 coverageMinLines",
                    ),
            )
        }
        val ratio = covered.toDouble() / total
        val pct = "%.1f%%".format(ratio * 100)
        if (ratio + 1e-9 < coverageThreshold) {
            throw GradleException(
                "com/phoneagent/data 行覆盖率 $pct < 阈值 ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total）。\n" +
                    coverageShame(
                        text,
                        { name: String -> dataPackages.any { pkg -> name.startsWith("$pkg/") } },
                        total,
                        null,
                        "data/build.gradle.kts 的 coverageThreshold",
                    ),
            )
        }
        logger.lifecycle(
            "覆盖率闸门通过：com/phoneagent/data $pct ≥ ${"%.0f%%".format(coverageThreshold * 100)}" +
                "（$covered/$total，下限 $coverageMinLines 行，子包 ${dataPackages.size} 个）",
        )
    }
}

tasks.named("check") { dependsOn(checkDataCoverage) }
