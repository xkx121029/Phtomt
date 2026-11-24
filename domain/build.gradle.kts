import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :domain —— 纯领域模型（`com.phoneagent.domain.model`：动作 / 意图 / 能力规格 / 任务与屏幕模型）。
//
// 为什么是 kotlin("jvm")：这 13 个文件实测零 `android.*` 引用、零 `com.phoneagent.*` 依赖，
// 唯一外部依赖是 kotlinx.serialization（`@Serializable` 与自定义 serializer）。
// 纯 JVM 让 `:data` / `:core:text` / `:core:security` 都能依赖它而不碰 AGP 变体解析。
//
// 注意：`domain/rules` **不在**本模块——它还有 536 行覆盖率和自己的闸门在 :app 里量，
// 搬它是另一期的事。本模块只管 model。
plugins {
    id("org.jetbrains.kotlin.jvm")
    jacoco
    // 不带版本：KGP 与序列化插件都已在根 buildscript classpath 上
    // （根 build.gradle.kts 里 `alias(...) apply false`），再写 `alias(...)` 会因
    // "plugin is already on the classpath with an unknown version" 直接失败。
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.detekt)
}

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
    // 模型自身的 @Serializable / 自定义 serializer 契约
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // AlarmTimeParserTest 直接断言序列化结果
    testImplementation(libs.kotlinx.serialization.json)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源，豁免名单本模块自有——baseline 的匹配键是
// `RuleId:文件名$签名`、不含路径，包搬出 :app 后原豁免不再生效。
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

// 基线条目数只减不增，口径与 :app 的 checkDetektBaseline 一致。
val detektBaselineFile = file("baseline.xml")
val detektBaselineCountFile = file("baseline-count.txt")

fun countDetektBaselineEntries(file: File): Int =
    if (!file.exists()) 0 else file.readLines().count { it.trimStart().startsWith("<ID>") }

val checkDetektBaseline by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线只减不增（新增豁免条目会让构建失败）"
    doLast {
        // 没有 baseline.xml 不是错误：那说明搬进来的文件本来零豁免。
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
// 覆盖率闸门：本模块的行覆盖率与**可执行行数下限**。
//
// 只看百分比会漏掉「包还在、类被搬走」这种静默窄化（HAPP-77 实测），所以两条一起判：
// 包/计数器找不到算失败；可执行行数少于下限也算失败。
// ---------------------------------------------------------------------------
// 覆盖率闸门：百分比 + **可执行行数下限**。
//
// 只测百分比会漏掉「包还在、类被搬走」这种静默窄化（HAPP-77 在 core/text 上实测过），
// 所以每条规则都同时判两件事：可执行行数不得低于下限；行覆盖率不得低于阈值。
// 规则必须和它守护的类待在同一个编译单元里，否则迟早会拿别人的覆盖率交差。
// ---------------------------------------------------------------------------
val coveragePackage = "com/phoneagent/domain/model"
val coverageThreshold = 0.21
val coverageMinLines = 1252

val jacocoTestReport by tasks.existing(JacocoReport::class) {
    dependsOn(tasks.named("test"))
    reports { xml.required.set(true) }
}

val checkDomainCoverage by tasks.registering {
    group = "verification"
    description = "校验 $coveragePackage 的行覆盖率不低于 ${"%.0f%%".format(coverageThreshold * 100)}"
    dependsOn(jacocoTestReport)
    doLast {
        val reportXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :domain:test。")
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

tasks.named("check") { dependsOn(checkDomainCoverage) }
