import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :ui:design —— UI 设计层：主题（色板 / 字体 / 形状 / 间距 / 动效 / 皮肤）、
// 共用组件（按钮 / 卡片 / 状态胶囊 / 空态 / 玻璃面 / 页眉骨架）与图标集。
//
// ## 为什么独立成模块
//
// `:app` 与下一期的 `:feature:coding` 都要用同一套视觉语言，但两者不该互相依赖。
// 把设计件下沉到本模块后，三条边各自成立且无环：
//
//     :app → :ui:design
//     :feature:coding → :ui:design
//     :ui:design → （只有 Compose / Lucide / Haze，没有本仓库任何模块）
//
// 最后一条是硬约束：设计层不能依赖 `:data` / `:app` / `:feature:coding`。
// 它需要的那一个「UI 形态枚举」`AgentSurface` 因此先被下沉到 `:domain`
// （见 domain/src/main/kotlin/com/phoneagent/data/prefs/AgentSurface.kt 顶部注释）。
//
// ## 为什么包名一个都没改
//
// 主题留在 `com.phoneagent.ui.theme`、共用组件留在 `com.phoneagent.ui.components`、
// 图标留在 `com.phoneagent.ui.icons`：`:app` 里数百处 import 与所有调用点零改动，
// 这一期是"只搬不改"。代价是 `com.phoneagent.ui.components` 被本模块与 `:app` 各持一半
// （`BrandMark` / `ComposerPanel` / `DialCard` / `Markdown` / `SkillCard` / `ToolAtoms` /
// `StartupSkeleton` / `CapabilityVisuals` / `Formatters` / `Haptic` 仍在 `:app`），
// 与模块化 P1 下沉记录类型时同一个取舍。**跨模块的 `internal` 不再可见**，
// 因此原本给 `:app` 用的三个符号被显式放开（见搬移提交的说明）：
// `glassTintPreview` / `isLightSurface` / `UiStyle.toneScale`。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // Compose 编译器插件：本模块的 @Composable 与 :app 同一套（版本取自 libs.versions.toml）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.phoneagent.ui.design"
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
            // 与 :app 口径一致：Compose 的 Android 侧桩函数返回默认值
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
    // 版本全部来自 gradle/libs.versions.toml 的 BOM 与 alias，不引入任何新版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    // Theme.kt 里 PhoneAgentTheme 要按浅/深底色切换系统栏图标（WindowCompat）
    implementation(libs.androidx.core.ktx)
    // 图标体系（ui/icons/AppIcons.kt 唯一的图标来源）
    implementation(libs.lucide.icons)
    // 毛玻璃（Glass.kt）
    implementation(libs.haze)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源，豁免名单本模块自有。
// baseline 的匹配键是 `RuleId:文件名$签名`、不含路径，所以 47 条存量豁免是
// 按文件名从 :app 的 config/detekt/baseline.xml 里筛出来带过来的。
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
// 覆盖率闸门：本模块设计令牌包的行覆盖率 + **可执行行数下限**。
//
// 为什么本模块也需要闸门，而不是只靠 tool/check-test-results.sh 的 NO-SOURCE 兜底：
// 兜底脚本只回答「测试跑了没有、跑了多少条」，它不管「跑的是不是被搬走的那些类」。
// 行数下限补的正是这个洞：包里的类被再搬到别处时，行数会掉下来，闸门当场红。
//
// 阈值口径（与 :app 的 coverageRules 同一套：「当前实测值 = 基线，只防回落」）见下方常量。
// 只覆盖 `com/phoneagent/ui/theme`：它是本模块唯一的**纯逻辑**包（令牌映射、脏数据归一、
// 皮肤→几何量映射），JVM 单测能真跑到。`ui/components` / `ui/icons` 全是 @Composable 声明与
// 图标常量，JVM 上拿不到测量、也调不到它们的函数体，给它设百分比阈值只会逼出「永远绿的假测试」。
// ---------------------------------------------------------------------------
val coveragePackage = "com/phoneagent/ui/theme"
/** 实测值 70.1%（1065/1520，2026-11-25，`DesignTokensTest` 的 14 条用例）——只防回落 */
val coverageThreshold = 0.70
/** 实测可执行行数 1520；下限取同一个数，类被搬走时它会掉下来 */
val coverageMinLines = 1520

// 覆盖率闸门失败时的逐类诊断（实现在 root 的 gradle/coverage-shame.gradle.kts，六个模块共用一份）。
val coverageShame = rootProject.extra["coverageShame"] as (String, (String) -> Boolean, Int, Int?, String) -> String

val checkUiDesignCoverage by tasks.registering {
    group = "verification"
    description = "校验 $coveragePackage 的行覆盖率不低于 ${"%.0f%%".format(coverageThreshold * 100)}"
    dependsOn("createDebugUnitTestCoverageReport")
    doLast {
        val reportXml = layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :ui:design:testDebugUnitTest。")
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
                "$coveragePackage 只剩 $total 行可执行代码（下限 $coverageMinLines）：包里的类被搬走了？\n" +
                    coverageShame(
                        reportXml.readText(),
                        { name: String -> name.startsWith("$coveragePackage/") },
                        total,
                        coverageMinLines,
                        "ui/design/build.gradle.kts 的 coverageMinLines",
                    ),
            )
        }
        val ratio = covered.toDouble() / total
        val pct = "%.1f%%".format(ratio * 100)
        if (ratio + 1e-9 < coverageThreshold) {
            throw GradleException(
                "$coveragePackage 行覆盖率 $pct < 阈值 ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total）。\n" +
                    coverageShame(
                        reportXml.readText(),
                        { name: String -> name.startsWith("$coveragePackage/") },
                        total,
                        null,
                        "ui/design/build.gradle.kts 的 coverageThreshold",
                    ),
            )
        }
        logger.lifecycle(
            "覆盖率闸门通过：$coveragePackage $pct ≥ ${"%.0f%%".format(coverageThreshold * 100)}" +
                "（$covered/$total，下限 $coverageMinLines 行）",
        )
    }
}

tasks.named("check") { dependsOn(checkUiDesignCoverage) }
