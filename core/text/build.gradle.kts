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
    // HumanTranslator（P1 从 :app 归位到本模块）要用 domain.model.CapabilityRegistry，
    // 于是本模块第一次有了内部依赖。方向仍是 core:text → domain，没有反向边。
    implementation(project(":domain"))
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
// 覆盖率闸门：百分比 + **可执行行数下限**。
//
// 只测百分比会漏掉「包还在、类被搬走」这种静默窄化（HAPP-77 在 core/text 上实测过），
// 所以每条规则都同时判两件事：可执行行数不得低于下限；行覆盖率不得低于阈值。
// 规则必须和它守护的类待在同一个编译单元里，否则迟早会拿别人的覆盖率交差。
// ---------------------------------------------------------------------------
// 两组分开量，因为它们的历史基线不同：
// - 转换器七类（HtmlConverter / HtmlEntities / HtmlMdBuilder / HtmlScanner / HtmlToMarkdown /
//   TimeFormats / AgentBudgets）在 P0 就有 87% 红线、713 行下限，原样保留；
// - HumanTranslator 是 P1 从 :app 归位进来的，**在 :app 时它没有任何规则**——它和转换器
//   同包，被转换器的 91.7% 顺带盖住了。合并成一条会悄悄拉低转换器的红线，
//   所以它单列，阈值取归位时实测值向下取整（74%），行数下限取实测 181。
val converterThreshold = 0.87
val converterMinLines = 713
val humanTranslatorThreshold = 0.74
val humanTranslatorMinLines = 181

val jacocoTestReport by tasks.existing(JacocoReport::class) {
    dependsOn(tasks.named("test"))
    reports { xml.required.set(true) }
}

data class LineCoverage(val covered: Int, val total: Int)

/** 按类名前缀从 jacoco XML 里挑类，累加它们的 LINE 计数。 */
fun coverageOf(text: String, predicate: (String) -> Boolean): LineCoverage {
    var covered = 0
    var total = 0
    Regex("""<class name="([^"]+)"[^>]*>([\s\S]*?)</class>""").findAll(text).forEach { cls ->
        if (!predicate(cls.groupValues[1])) return@forEach
        val counter = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
            .findAll(cls.groupValues[2]).lastOrNull() ?: return@forEach
        val missed = counter.groupValues[1].toInt()
        val cov = counter.groupValues[2].toInt()
        covered += cov
        total += missed + cov
    }
    return LineCoverage(covered, total)
}

fun requireCoverage(label: String, c: LineCoverage, threshold: Double, minLines: Int) {
    if (c.total == 0) {
        throw GradleException("覆盖率闸门：$label 的可执行行为 0 —— 测试没跑到，或类被搬走了。")
    }
    if (c.total < minLines) {
        throw GradleException(
            "$label 只剩 ${c.total} 行可执行代码（下限 $minLines）：包里的类被搬走了？" +
                "规则要跟着类走，而不是留在这里改测剩下的类。",
        )
    }
    val ratio = c.covered.toDouble() / c.total
    val pct = "%.1f%%".format(ratio * 100)
    if (ratio + 1e-9 < threshold) {
        throw GradleException("$label 行覆盖率 $pct < 阈值 ${"%.0f%%".format(threshold * 100)}（${c.covered}/${c.total}）。")
    }
    logger.lifecycle("覆盖率闸门通过：$label $pct ≥ ${"%.0f%%".format(threshold * 100)}（${c.covered}/${c.total}，下限 $minLines 行）")
}

val checkCoreTextCoverage by tasks.registering {
    group = "verification"
    description = "校验 com/phoneagent/core/text 的转换器与 HumanTranslator 各自的覆盖率与行数下限"
    dependsOn(jacocoTestReport)
    doLast {
        val reportXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :core:text:test。")
        }
        val text = reportXml.readText()
        val human = coverageOf(text) { it.endsWith("/HumanTranslator") }
        val rest = coverageOf(text) { !it.endsWith("/HumanTranslator") }
        requireCoverage("com/phoneagent/core/text（转换器等）", rest, converterThreshold, converterMinLines)
        requireCoverage("com/phoneagent/core/text（HumanTranslator）", human, humanTranslatorThreshold, humanTranslatorMinLines)
    }
}

tasks.named("check") { dependsOn(checkCoreTextCoverage) }
