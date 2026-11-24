import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:security —— 密钥编解码（Keystore AES/GCM）与脱敏判据（DataSanitizer + SensitivePageDetector）。
//
// 为什么是 com.android.library：`SecretCodec` 直接用 `android.security.keystore.*` 与
// `android.util.Base64`，这不是"换个 import 就能变纯 JVM"的东西——换掉 Keystore 就是换掉密钥
// 存放语义。`SensitivePageDetector` / `DataSanitizer` 本身是纯逻辑，但和密钥放一起才叫
// 「安全层」，拆成两个模块没有收益。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.phoneagent.core.security"
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
            // 与 :app 口径一致：android.util.Base64 / Keystore 在 JVM 单测里返回默认值
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
    implementation(project(":core:log"))
    implementation(project(":domain"))
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源，豁免名单本模块自有（匹配键 `RuleId:文件名$签名` 不含路径）。
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
// 覆盖率闸门：百分比 + 可执行行数下限（后者防「包还在、类被搬走」的静默窄化）。
// ---------------------------------------------------------------------------
// 覆盖率闸门：百分比 + **可执行行数下限**。
//
// 只测百分比会漏掉「包还在、类被搬走」这种静默窄化（HAPP-77 在 core/text 上实测过），
// 所以每条规则都同时判两件事：可执行行数不得低于下限；行覆盖率不得低于阈值。
// 规则必须和它守护的类待在同一个编译单元里，否则迟早会拿别人的覆盖率交差。
// ---------------------------------------------------------------------------
val coveragePackage = "com/phoneagent/core/security"
val coverageThreshold = 0.66
val coverageMinLines = 100

val checkCoreSecurityCoverage by tasks.registering {
    group = "verification"
    description = "校验 $coveragePackage 的行覆盖率不低于 ${"%.0f%%".format(coverageThreshold * 100)}"
    dependsOn("createDebugUnitTestCoverageReport")
    doLast {
        val reportXml = layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :core:security:testDebugUnitTest。")
        }
        val pkgBlock = Regex("""<package name="${Regex.escape(coveragePackage)}">([\s\S]*?)</package>""")
            .find(reportXml.readText())?.groupValues?.get(1)
            ?: throw GradleException(
                "覆盖率闸门：报告里没有 $coveragePackage —— 包可能被搬走或改名了。" +
                    "请把这条规则一起搬到新位置，而不是让它在这里静默失效。",
            )
        val lineCounter = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
            .findAll(pkgBlock).lastOrNull()
            ?: throw GradleException("覆盖率闸门：$coveragePackage 没有 LINE 计数器（报告格式变了？）")
        val missed = lineCounter.groupValues[1].toInt()
        val covered = lineCounter.groupValues[2].toInt()
        val total = missed + covered
        if (total == 0) {
            throw GradleException("覆盖率闸门：$coveragePackage 的可执行行为 0 —— 测试没有跑到这个包，不能算通过。")
        }
        if (total < coverageMinLines) {
            throw GradleException("$coveragePackage 只剩 $total 行可执行代码（下限 $coverageMinLines）：包里的类被搬走了？")
        }
        val ratio = covered.toDouble() / total
        val pct = "%.1f%%".format(ratio * 100)
        if (ratio + 1e-9 < coverageThreshold) {
            throw GradleException("$coveragePackage 行覆盖率 $pct < 阈值 ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total）。")
        }
        logger.lifecycle("覆盖率闸门通过：$coveragePackage $pct ≥ ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total，下限 $coverageMinLines 行）")
    }
}

tasks.named("check") { dependsOn(checkCoreSecurityCoverage) }
