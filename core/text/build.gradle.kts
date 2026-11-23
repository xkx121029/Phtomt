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
    jacoco
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

// ---------------------------------------------------------------------------
// 覆盖率闸门：本模块这七个转换类（HtmlConverter / HtmlEntities / HtmlMdBuilder /
// HtmlScanner / HtmlToMarkdown / TimeFormats）的 87% 红线由**本模块自己**守。
//
// 这条红线原来在 :app 的 coverageRules 里量。七个类搬进本模块之后，:app 的报告里
// `com/phoneagent/core/text` 这个包**仍然存在**（HumanTranslator 留在了 :app），
// 只是包里已经不含转换器 —— checkPureLogicCoverage 于是拿 HumanTranslator 的覆盖率
// 交了差，闸门照旧绿（91.7%），转换器的真实覆盖率没有任何人在测。
// 包级规则看不见「包还在、类被搬走」这件事，所以规则必须和它守护的类待在同一个编译单元。
//
// 两个 fail-closed 条件：包/计数器找不到算失败；可执行行数少于下限也算失败
// （后者防的正是上面那次静默窄化）。
// ---------------------------------------------------------------------------
val coveragePackage = "com/phoneagent/core/text"
val coverageThreshold = 0.87

/** 当前报告里本包的可执行行数；类被搬走或删掉会让它掉下来，闸门据此报警。 */
val coverageMinLines = 713

val jacocoTestReport by tasks.existing(JacocoReport::class) {
    dependsOn(tasks.named("test"))
    reports { xml.required.set(true) }
}

val checkCoreTextCoverage by tasks.registering {
    group = "verification"
    description = "校验 $coveragePackage 的行覆盖率不低于 ${"%.0f%%".format(coverageThreshold * 100)}"
    dependsOn(jacocoTestReport)
    doLast {
        val reportXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :core:text:test。")
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
            throw GradleException(
                "$coveragePackage 只剩 $total 行可执行代码（下限 $coverageMinLines）：包里的类被搬走了？" +
                    "规则要跟着类走，而不是留在这里改测剩下的类。",
            )
        }
        val ratio = covered.toDouble() / total
        val pct = "%.1f%%".format(ratio * 100)
        if (ratio + 1e-9 < coverageThreshold) {
            throw GradleException(
                "$coveragePackage 行覆盖率 $pct < 阈值 ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total）。" +
                    "请补测，或在本文件的 coverageThreshold 里调整阈值（需说明理由）。",
            )
        }
        logger.lifecycle("覆盖率闸门通过：$coveragePackage $pct ≥ ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total）")
    }
}

tasks.named("check") { dependsOn(checkCoreTextCoverage) }
