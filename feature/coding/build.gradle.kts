import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :feature:coding —— Coding 模式整片切片（agent 宿主 / 工作区 / 设备桥 / 编码环境 / 预览）。
//
// ## 为什么独立成模块
//
// 这一期是把 Coding 模式整体摘走的最后一步：搬进独立编译单元之后，
// `:feature:coding` 的依赖边就是它真实的依赖边，`:app` 里剩下的每一处对它的引用
// 都必须是「组合根/适配器」，不能再有「顺手 import 一下」的口子。
//
// 依赖方向（硬约束）：
//
//     :app → :feature:coding → { :core:log, :env-api, androidx.* }
//
// **不能出现 `:feature:coding → :app`**：一旦有这条边，整片切片就又钉死在宿主上了。
// 证明方式见本次搬移提交的报告：`./gradlew :feature:coding:dependencies
// --configuration debugCompileClasspath` 的树里没有 `project :app`。
//
// ## 为什么包名一个都没改
//
// 类仍留在 `com.phoneagent.coding.**`（含命名空间 `com.phoneagent.coding`）：
//
//   - `app/src/main/AndroidManifest.xml` 里 `.coding.CodingAgentService` /
//     `.coding.EditorActivity` / `.coding.PreviewActivity` 三条相对类名仍然解析得到，不用动；
//   - `:app` 里 9 个引用点（组合根 `di/AppModule`、`PhoneAgentApp`、四个设置页与
//     `SkillDelegate` / `MainViewModel`）一行 import 都不用改。
//
// 这与模块化 P0/P1/P2 是同一个取舍：`ui:design` 那次已经证明 typealias 替代不了包名，
// 改包名会牵动大量引用却换不来任何隔离收益——模块边界由 Gradle 边决定，不由包名决定。
//
// 代价（与 P1/P2 相同）：跨模块的 `internal` 不再可见。原本给 `:app` 组合根用的
// 三个符号因此被放开为 public（见 agent/AgentClientSessions.kt 与
// device/HostBridgeTokenProvider.kt 的类注释）。
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // api/CodingPreviewRenderer.kt 声明了一个 @Composable 端口方法（实现由宿主注入），
    // 需要 Compose 编译器插件改签名——与 :ui:design 同一套版本（取自 libs.versions.toml）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.phoneagent.coding"
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
            // 与 :app 口径一致：android.* 平台桩函数返回默认值
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
    // 统一日志门面（agent / 环境 / 设备桥全都在用 AppLog）
    implementation(project(":core:log"))

    // 编码环境跨进程契约（IEnvProvider AIDL）：env/CodingEnvClient 与 CodingEnvBootstrap 的唯一外部门
    implementation(project(":env-api"))

    // FileProvider（SharedFiles 分享）/ ContextCompat / NotificationCompat（CodingAgentService 前台服务）
    implementation(libs.androidx.core.ktx)
    // activity-compose：EditorActivity / PreviewActivity 的 setContent 与 enableEdgeToEdge
    implementation(libs.androidx.activity.compose)
    // @Composable 注解本身（api/CodingPreviewRenderer.kt）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// 通知小图标的**双份副本**：为什么不是一个 git mv 就能解决。
//
// `CodingAgentService` 建前台通知要用 `R.drawable.ic_stat_agent`，而这个图标同时被
// `:app` 的 `TaskProgressNotifier` / `OverlayNotification` 用着。本工程开了
// `android.nonTransitiveRClass=true`（gradle.properties:14），因此 **`:app` 的 R 里
// 看不到库模块的资源**，`:feature:coding` 的 R 里也看不到 `:app` 的资源——两边各要一份。
//
// 打包时同名的两份会在资源合并阶段归一（`app` 的优先级更高、内容逐字节相同），
// 所以 APK 里仍然只有一个 drawable，产物体积与像素输出都没变。
//
// 代价是「两处内容、没有任何机械联系」——这正是本仓库反复吃亏的失效模式，
// 所以加下面这道逐字节比对：谁改了一份忘了另一份，构建立刻红。
// ---------------------------------------------------------------------------
val notificationIconCopies = listOf(
    rootProject.file("app/src/main/res/drawable/ic_stat_agent.xml"),
    file("src/main/res/drawable/ic_stat_agent.xml"),
)

val checkNotificationIconInSync by tasks.registering {
    group = "verification"
    description = "校验 :app 与 :feature:coding 的通知图标副本逐字节一致"
    doLast {
        val contents = notificationIconCopies.map { it.readBytes().toList() }
        if (contents.distinct().size != 1) {
            val listed = notificationIconCopies.joinToString(", ") { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
            throw GradleException(
                "通知图标两份副本不一致：$listed。" +
                    "两份必须逐字节相同（nonTransitiveRClass 让两边无法共用一份资源），改完一处请同步另一处。",
            )
        }
    }
}

tasks.named("check") { dependsOn(checkNotificationIconInSync) }

// ---------------------------------------------------------------------------
// detekt：规则与 :app 同源（同一份 config/detekt/detekt.yml），豁免名单本模块自有。
//
// baseline 是搬移时按文件名从 :app 的 config/detekt/baseline.xml 里筛出来的 55 条
// （匹配键 `RuleId:文件名$签名`、**不含路径**，所以搬走之后这批存量问题会跟着文件名走、
// 但只有在有 baseline.xml 的模块里才会被豁免）。:app 的基线同步从 1356 减到 1301，
// 合计不变——这是「搬走多少条就从基线里减掉多少」的机械证据。
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
// 覆盖率闸门：整片 `com/phoneagent/coding` 的行覆盖率 + **可执行行数下限**。
//
// 口径与 :data 的那道闸门一致：按前缀聚合**所有子包**（agent / api / device / env /
// skill / workspaces 与根包本身），而不是去找一个不存在的包名。
//
// 为什么必须带行数下限：包级规则只看「包里还剩什么」，类被搬走时它不会报错，
// 只会悄悄改测剩下的类（`core/text` 就是这么绿过一次的）。行数一旦掉下来就是红的。
//
// 为什么阈值定得低：这里是**整片切片**，其中 EditorActivity / PreviewActivity /
// CodingAgentService / SharedFiles 是 Android 宿主件（WebView、前台服务、FileProvider），
// JVM 单测调不到它们的函数体。按仓库既有口径（`device/ops` 17.2%）「当前实测值 = 基线，
// 只防回落」，不为了把数字做好看而排除这些类——排除出去就等于让它们彻底不受监控。
// ---------------------------------------------------------------------------
val coveragePackagePrefix = "com/phoneagent/coding"
/** 实测 20.8%（543/2612，2026-11-26 搬移后首次实测，7 个子包）——只防回落 */
val coverageThreshold = 0.20
/** 实测可执行行数 2612；下限取同一个数，类被搬走时它会掉下来 */
val coverageMinLines = 2612

// 覆盖率闸门失败时的逐类诊断（实现在 root 的 gradle/coverage-shame.gradle.kts，各模块共用一份）。
val coverageShame = rootProject.extra["coverageShame"] as (String, (String) -> Boolean, Int, Int?, String) -> String

val checkFeatureCodingCoverage by tasks.registering {
    group = "verification"
    description = "校验 $coveragePackagePrefix 的行覆盖率不低于 ${"%.0f%%".format(coverageThreshold * 100)}"
    dependsOn("createDebugUnitTestCoverageReport")
    doLast {
        val reportXml = layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile
        if (!reportXml.exists()) {
            throw GradleException("找不到覆盖率报告 ${reportXml.path}；该闸门需要先跑 :feature:coding:testDebugUnitTest。")
        }
        val text = reportXml.readText()
        val blocks = Regex("""<package name="([^"]+)">([\s\S]*?)</package>""")
            .findAll(text)
            .filter { it.groupValues[1] == coveragePackagePrefix || it.groupValues[1].startsWith("$coveragePackagePrefix/") }
            .toList()
        if (blocks.isEmpty()) {
            throw GradleException(
                "覆盖率闸门：报告里没有 $coveragePackagePrefix 及其子包 —— 包可能被搬走或改名了。" +
                    "请把这条规则一起搬到新位置，而不是让它在这里静默失效。",
            )
        }
        var covered = 0
        var total = 0
        blocks.forEach { block ->
            val counter = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
                .findAll(block.groupValues[2]).lastOrNull()
                ?: throw GradleException("覆盖率闸门：${block.groupValues[1]} 没有 LINE 计数器（报告格式变了？）")
            covered += counter.groupValues[2].toInt()
            total += counter.groupValues[1].toInt() + counter.groupValues[2].toInt()
        }
        if (total == 0) {
            throw GradleException("覆盖率闸门：$coveragePackagePrefix 的可执行行为 0 —— 测试没有跑到这个包，不能算通过。")
        }
        if (total < coverageMinLines) {
            throw GradleException(
                "$coveragePackagePrefix 只剩 $total 行可执行代码（下限 $coverageMinLines）：包里的类被搬走了？\n" +
                    coverageShame(
                        text,
                        { name: String -> name == coveragePackagePrefix || name.startsWith("$coveragePackagePrefix/") },
                        total,
                        coverageMinLines,
                        "feature/coding/build.gradle.kts 的 coverageMinLines",
                    ),
            )
        }
        val ratio = covered.toDouble() / total
        val pct = "%.1f%%".format(ratio * 100)
        if (ratio + 1e-9 < coverageThreshold) {
            throw GradleException(
                "$coveragePackagePrefix 行覆盖率 $pct < 阈值 ${"%.0f%%".format(coverageThreshold * 100)}（$covered/$total）。\n" +
                    coverageShame(
                        text,
                        { name: String -> name == coveragePackagePrefix || name.startsWith("$coveragePackagePrefix/") },
                        total,
                        null,
                        "feature/coding/build.gradle.kts 的 coverageThreshold",
                    ),
            )
        }
        logger.lifecycle(
            "覆盖率闸门通过：$coveragePackagePrefix $pct ≥ ${"%.0f%%".format(coverageThreshold * 100)}" +
                "（$covered/$total，下限 $coverageMinLines 行，子包 ${blocks.size} 个）",
        )
    }
}

tasks.named("check") { dependsOn(checkFeatureCodingCoverage) }
