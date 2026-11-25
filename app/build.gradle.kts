import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// 静态检查：默认规则集当"下限"，再用基线豁免存量问题。
// 基线只固化"今天已经存在的"问题，因此这次接入不会让构建先爆红，
// 而新增/改动的代码再犯同类问题（长函数、超长行、未用导入…）会立刻失败——
// 这是把 4000 行级文件继续变胖的唯一自动闸门。
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$rootDir/config/detekt/baseline.xml")
    // 项目级规则微调（Compose 命名、文件末尾换行等），见文件内注释
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // 只扫手写源码；build/ 下的生成物（BuildConfig、资源 id）不参与
    setSource(files("src/main/java", "src/test/java"))
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

// ---------------------------------------------------------------------------
// detekt 基线只减不增：baseline 是存量问题的豁免名单，随修复变小是正常的，
// 但靠往基线里追加条目来"绕过闸门"会让闸门彻底失效（新问题一律能被一键豁免）。
// 记录条目数在 config/detekt/baseline-count.txt：超出即失败；变小则回写更小的值，
// 防止之后又悄悄长回去。
// ---------------------------------------------------------------------------
val detektBaselineFile = rootProject.file("config/detekt/baseline.xml")
val detektBaselineCountFile = rootProject.file("config/detekt/baseline-count.txt")

fun countDetektBaselineEntries(file: File): Int =
    if (!file.exists()) 0 else file.readLines().count { it.trimStart().startsWith("<ID>") }

val checkDetektBaseline by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线只减不增（新增豁免条目会让构建失败）"
    doLast {
        val actual = countDetektBaselineEntries(detektBaselineFile)
        val recorded = detektBaselineCountFile.takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull()
            ?: throw GradleException(
                "缺少 ${detektBaselineCountFile.relativeTo(rootProject.projectDir)}；" +
                    "请写入当前基线条目数（$actual）。",
            )
        if (actual > recorded) {
            throw GradleException(
                "detekt 基线条目从 $recorded 增加到 $actual：请修复新问题而不是把新条目写进基线。",
            )
        }
        // 修掉存量问题后条目只会变少：把新的下限写回，避免下次重新长回去
        if (actual < recorded) detektBaselineCountFile.writeText("$actual\n")
    }
}

tasks.named("check") { dependsOn(checkDetektBaseline) }

// ---------------------------------------------------------------------------
// detekt 基线的**分类**计数。
//
// 只记总数（1543）有个盲区：清掉 100 条超长行和修掉 1 个巨型类，在总数上看起来一样。
// 实测基线里 MaxLineLength(482) + MagicNumber(433) 占了 59%，而真正反映结构的
// LargeClass / CyclomaticComplexMethod / LongMethod 合计不到 12%。所以额外记一份分类表，
// 让"基线降了"能看出是清了噪音还是清了结构问题。
//
// 校验规则：分类合计必须等于 baseline-count.txt 的总数，否则说明有人手改了基线却没同步分类表。
// ---------------------------------------------------------------------------
val detektBaselineBreakdownFile = rootProject.file("config/detekt/baseline-breakdown.txt")

fun detektRuleOf(idLine: String): String {
    // 基线条目形如 <ID>MaxLineLength:SomeFile.kt$...</ID>，取冒号前的规则名
    val raw = idLine.trim().removePrefix("<ID>").substringBefore("</ID>")
    return raw.substringBefore(':').trim()
}

val checkDetektBaselineBreakdown by tasks.registering {
    group = "verification"
    description = "校验 detekt 基线的分类计数与总数一致（防止只改总数不更新分类）"
    doLast {
        val lines = detektBaselineFile.takeIf { it.exists() }?.readLines().orEmpty()
        val byRule = lines.filter { it.trimStart().startsWith("<ID>") }
            .groupingBy { detektRuleOf(it) }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        val total = byRule.sumOf { it.second }
        val rendered = buildString {
            appendLine("# detekt 基线的规则分布（自动生成，check 会校验合计 = baseline-count.txt）")
            appendLine("# 规则名                                        条数")
            byRule.forEach { (rule, count) -> appendLine("%-44s %d".format(rule, count)) }
            appendLine("# 合计 $total")
        }
        val recorded = detektBaselineBreakdownFile.takeIf { it.exists() }?.readText()
        if (recorded == null) {
            detektBaselineBreakdownFile.writeText(rendered)
            logger.lifecycle("已生成 ${detektBaselineBreakdownFile.name}（合计 $total 条）")
        } else {
            val recordedTotal = Regex("""#\s*合计\s*(\d+)""").find(recorded)?.groupValues?.get(1)?.toIntOrNull()
            val countTotal = countDetektBaselineEntries(detektBaselineFile)
            if (recordedTotal != null && recordedTotal != countTotal) {
                throw GradleException(
                    "detekt 分类表记录的合计（$recordedTotal）与基线条目数（$countTotal）不一致：" +
                        "请在清理基线后重新生成 ${detektBaselineBreakdownFile.name}（删掉该文件再跑一次 check）。",
                )
            }
        }
    }
}

tasks.named("check") { dependsOn(checkDetektBaselineBreakdown) }

// ---------------------------------------------------------------------------
// 判据索引的锚点校验（docs/judgement-index.md）。
//
// 这张表唯一的失效方式不是写错描述，而是**锚点悄悄指向另一行**：函数改名、代码上移下移
// 之后，`文件:行号` 仍然"存在"，读者点开却是别的代码——比链接直接断开更难发现。
// 所以这里把可机械验证的部分全部钉住：文件存在、行号在范围内、该行确实是表里写的那个符号。
//
// 符号与锚点按「同一表格单元内的出现顺序」逐个配对；数量对不上时退化成
// 「该行至少包含本格提到的某个符号」——宁可宽一点，也不要因为表格排版调整就误报。
// 只校验 .kt 锚点的符号；.md 锚点（README / CHANGELOG / 其它 docs）只查存在与行号范围。
// ---------------------------------------------------------------------------
val judgementIndexFile = rootProject.file("docs/judgement-index.md")

/** 反引号片段形状：`path:line` / `path:line-end` / 承接上一个文件的 `:line` */
val anchorSpanRegex = Regex("""^(?:([A-Za-z0-9_./-]+\.(?:kt|md)):)?(\d+)(?:-(\d+))?$""")

/** 反引号片段里的符号形状（路径片段由 anchorSpanRegex 单独处理） */
val symbolSpanRegex = Regex("""^[A-Za-z_][A-Za-z0-9_.]*$""")

val checkDocAnchors by tasks.registering {
    group = "verification"
    description = "校验 docs/judgement-index.md 的 file:line 锚点：文件存在、行号在范围内、该行确含表里写的符号"
    doLast {
        if (!judgementIndexFile.exists()) {
            throw GradleException(
                "缺少 docs/judgement-index.md：判据索引是本仓库的维护约定（改了判据就要查文档），不能悄悄消失。",
            )
        }
        val problems = mutableListOf<String>()
        var anchorsChecked = 0

        judgementIndexFile.readLines().forEachIndexed { rowIndex, rawLine ->
            if (!rawLine.trimStart().startsWith("|")) return@forEachIndexed
            val row = rawLine.trim().removePrefix("|").removeSuffix("|")
            val cells = row.split("|")
            cells.forEachIndexed { cellIndex, cell ->
                val spans = Regex("`([^`]+)`").findAll(cell).map { it.groupValues[1] }.toList()
                if (spans.isEmpty()) return@forEachIndexed

                // 一、把本格里的锚点按出现顺序收集起来（裸 `:NNN` 承接本格前面最近的文件）
                val anchorSpans = mutableListOf<String>()
                val anchorRefs = mutableListOf<Triple<String, Int, Int>>()
                var currentFile: String? = null
                for (span in spans) {
                    val m = anchorSpanRegex.find(span.trim()) ?: continue
                    val path = m.groupValues[1].ifBlank { null }
                    if (path != null) currentFile = path
                    val file = currentFile ?: continue
                    val start = m.groupValues[2].toInt()
                    val end = m.groupValues[3].ifBlank { m.groupValues[2] }.toInt()
                    anchorSpans += span
                    anchorRefs += Triple(file, start, end)
                }
                if (anchorRefs.isEmpty()) return@forEachIndexed

                // 二、本格里提到的符号（去掉路径片段与文件后缀）
                val symbols = spans
                    .filter { symbolSpanRegex.matches(it) && !it.endsWith(".kt") && !it.endsWith(".md") }
                    .map { it.substringAfterLast('.') }
                val strictPairing = symbols.isNotEmpty() && symbols.size == anchorRefs.size

                anchorRefs.forEachIndexed { anchorIndex, ref ->
                    val file = ref.first
                    val start = ref.second
                    val end = ref.third
                    anchorsChecked++
                    val where = "docs/judgement-index.md:${rowIndex + 1} 第 ${cellIndex + 1} 列的 `${anchorSpans[anchorIndex]}`"
                    val target = rootProject.file(file)
                    if (!target.exists()) {
                        problems += "$where → 文件不存在：$file"
                        return@forEachIndexed
                    }
                    val lines = target.readLines()
                    if (start < 1 || end > lines.size || start > end) {
                        problems += "$where → 行号越界：$file 只有 ${lines.size} 行，却引用 $start-$end"
                        return@forEachIndexed
                    }
                    // 文档锚点只查存在与范围：那里没有"函数名"可对
                    if (!file.endsWith(".kt")) return@forEachIndexed

                    val cited = lines.subList(start - 1, end).joinToString("\n")
                    val expected = if (strictPairing) symbols[anchorIndex] else null
                    val hit = when {
                        expected != null -> cited.contains(expected)
                        symbols.isNotEmpty() -> symbols.any { cited.contains(it) }
                        else -> true // 本格只引了行号、没提符号名：无可对
                    }
                    if (!hit) {
                        val what = expected?.let { "符号 `$it`" } ?: "本格提到的任一符号（${symbols.joinToString(" / ")}）"
                        problems += "$where → $file:$start 里找不到 $what，实际是：${lines[start - 1].trim().take(100)}"
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("判据索引的锚点对不上（${problems.size} 处，共校验 $anchorsChecked 个）：")
                    problems.forEach { appendLine("  - $it") }
                    appendLine()
                    append("修法：改了判据函数名或挪动代码后，同步更新 docs/judgement-index.md 的行号；")
                    append("新增判据则加一行。确实要临时放过时不要改这个脚本，而是把那一行删掉——")
                    append("表的价值来自每条都对得上。")
                },
            )
        }
        logger.lifecycle("判据索引锚点校验通过：$anchorsChecked 个锚点全部命中（文件存在、行号在范围内、符号对得上）。")
    }
}

tasks.named("check") { dependsOn(checkDocAnchors) }

// ---------------------------------------------------------------------------
// 覆盖率闸门（只对**纯逻辑包**生效）。
//
// 不对全量设阈值：本工程主体是无障碍 / Shizuku / 悬浮窗这类只能在真机上跑的逻辑，
// 全局阈值只会逼人写无意义的测试。但下面这几个包是无副作用的纯逻辑（规则、转译、
// 文本转换、新拆出的设备能力工具），完全可以在 JVM 覆盖住，设阈值是有意义的。
//
// 阈值按"不低于当前实测值"起步，随补测逐步抬高，避免一上来就红。
// ---------------------------------------------------------------------------
// 阈值取「当前实测值」为基线，只用来防**回落**（覆盖率掉了就失败），
// 不是"应该达到"的目标：domain/rules 88.6%、core/text 91.7%、engine/execution 62.2%、
// device/ops 17.2%（后者多为 shell 命令拼装，JVM 测不到）。补测后请一并抬高这里的数字。
//
// 每条规则都配一个**可执行行数下限**（见下面的 coverageMinLines）。为什么必须有它：
// 包级规则只看得见「这个包里还剩什么」，包里的类被搬走时它不会报错，只会悄悄改测剩下的类。
// 实测事故：core/text 的七个转换类搬进 :core:text 之后，:app 里这个包只剩 HumanTranslator，
// 闸门照旧绿（91.7%），而转换器真正的覆盖率当时没有任何人在测。
// 行数下限把这个盲区变成红：类被搬走 → 可执行行数掉下来 → 提示把规则一起迁到新模块。
// 确实有意删类时，把下限一起调低，并在提交信息里说明。
val coverageRules = mapOf(
    "com/phoneagent/domain/rules" to 0.85,
    // `com/phoneagent/core/text` 这条规则已经搬进 core/text/build.gradle.kts：
    // 该包最后一个类（HumanTranslator）在模块化 P1 也搬进 :core:text 之后，:app 的报告里
    // 这个包整个消失，留在这里会让 fail-closed 直接红——规则必须跟着类走。
    "com/phoneagent/engine/execution" to 0.45,
    "com/phoneagent/device/ops" to 0.17,
)

/** 各包在上述报告里的可执行行数下限，用途见 coverageRules 上方的注释。 */
val coverageMinLines = mapOf(
    "com/phoneagent/domain/rules" to 536,
    "com/phoneagent/engine/execution" to 2887,
    "com/phoneagent/device/ops" to 1047,
)

// 覆盖率闸门失败时的逐类诊断（实现在 root 的 gradle/coverage-shame.gradle.kts，六个模块共用一份）。
val coverageShame = rootProject.extra["coverageShame"] as (String, (String) -> Boolean, Int, Int?, String) -> String

val checkPureLogicCoverage by tasks.registering {
    group = "verification"
    description = "校验纯逻辑包的单元测试覆盖率不低于阈值（不含真机依赖的包）"
    // 依赖 AGP 生成的覆盖率 XML 报告
    dependsOn("createDebugUnitTestCoverageReport")
    doLast {
        val xml = layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile
        if (!xml.exists()) {
            throw GradleException(
                "找不到覆盖率报告 ${xml.path}；该闸门需要先跑 createDebugUnitTestCoverageReport。",
            )
        }
        val text = xml.readText()
        // JaCoCo XML：<package name="com/phoneagent/domain/rules">...<counter type="LINE" missed=".." covered=".."/>
        val failures = mutableListOf<String>()
        coverageRules.forEach { (pkg, threshold) ->
            val pkgBlock = Regex("""<package name="${Regex.escape(pkg)}">([\s\S]*?)</package>""")
                .find(text)?.groupValues?.get(1)
            if (pkgBlock == null) {
                // 不能跳过：包从报告里消失只有两种原因 —— 包被搬走了（规则过期），
                // 或者它根本没被测试跑到。两种都必须红，否则闸门会「看着还在、其实不管了」。
                throw GradleException(
                    "覆盖率闸门：报告里没有 $pkg —— 该包已不在 :app 的编译集里（多半是搬进了独立模块）。" +
                        "请把这条规则迁到那个模块自己的闸门里，再从这里删掉；" +
                        "留在表里只会让人以为它还被守着。",
                )
            }
            val lineCounter = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
                .findAll(pkgBlock).lastOrNull()
            if (lineCounter == null) {
                throw GradleException("覆盖率闸门：$pkg 在报告里没有 LINE 计数器（报告格式变了？）")
            }
            val missed = lineCounter.groupValues[1].toInt()
            val covered = lineCounter.groupValues[2].toInt()
            val total = missed + covered
            if (total == 0) {
                throw GradleException("覆盖率闸门：$pkg 的可执行行为 0 —— 测试没有跑到这个包，不能算通过。")
            }
            coverageMinLines[pkg]?.let { minLines ->
                if (total < minLines) {
                    failures += "  $pkg 只剩 $total 行可执行代码（下限 $minLines）：包里的类被搬走了？" +
                        "规则要跟着迁到那个模块自己的闸门里，而不是留在这里改测剩下的类。\n" +
                        coverageShame(
                            text,
                            { name: String -> name.startsWith("$pkg/") },
                            total,
                            minLines,
                            "app/build.gradle.kts 的 coverageMinLines",
                        )
                }
            }
            val ratio = covered.toDouble() / total
            val pct = "%.1f%%".format(ratio * 100)
            if (ratio + 1e-9 < threshold) {
                failures += "  $pkg 行覆盖率 $pct < 阈值 ${"%.0f%%".format(threshold * 100)}（$covered/$total）\n" +
                    coverageShame(
                        text,
                        { name: String -> name.startsWith("$pkg/") },
                        total,
                        null,
                        "app/build.gradle.kts 的 coverageRules",
                    )
            } else {
                logger.lifecycle("覆盖率闸门通过：$pkg $pct ≥ ${"%.0f%%".format(threshold * 100)}")
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "纯逻辑包覆盖率低于阈值：\n${failures.joinToString("\n")}\n" +
                    "请补测，或在 app/build.gradle.kts 的 coverageRules 里调整阈值（需说明理由）。",
            )
        }
    }
}

/**
 * 版本号由根 build.gradle.kts 统一计算（每次产出 APK 的构建自增一次并回写
 * version.properties），这里只读取同一个值，保证 :app 与 :provider 版本一致。
 */
val buildNumber: Int = rootProject.extra["buildNumber"] as Int

// 渠道后缀：CI 用 `-Pchannel=$CHANNEL` 传入（beta / dev），本地构建不带该参数。
//
// 为什么放进 versionName：tag、Release 名、APK 文件名一直叫 `v0.2.9195-beta`，而包内
// versionName 只有 `0.2.9195`——同一个包两个名字，用户在「关于」里看到的号在 GitHub 上搜不到。
// 现在三处统一成同一个字符串。
//
// 本地默认 dev：本地构建从不发布，dev 是最诚实的标注，也避免 versionName 又变回「无后缀」的第三种形态。
// 注意：服务端升级检查会拿 versionName 做比较（website/server/lib/helpers.js 的 compareVersion），
// 那边的解析必须先剥掉 `-后缀` 再按 `.` 分段，否则 `9195-beta` 会被 Number() 变成 0。
val channel: String = (providers.gradleProperty("channel").orNull ?: "").trim().ifEmpty { "dev" }

android {
    namespace = "com.phoneagent"
    compileSdk = 36

    // 签名配置：从 upload-signing.properties 读取（本地文件，不提交到仓库）
    val signingPropsFile = file("upload-signing.properties")
    if (signingPropsFile.exists()) {
        val props = Properties().apply { signingPropsFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(props["storeFile"] as? String ?: "upload-keystore.jks")
                storePassword = props["storePassword"] as? String ?: ""
                keyAlias = props["keyAlias"] as? String ?: ""
                keyPassword = props["keyPassword"] as? String ?: ""
            }
        }
    }

    // CI 固定签名：HPA_DEBUG_KEYSTORE 指向还原好的 keystore 时，debug 变体显式用它签名。
    // 不依赖 AGP 对默认路径（$HOME/.android 或 $ANDROID_SDK_HOME/.android）的推断——
    // 推断失败时 AGP 会临时生成随机 keystore，导致 CI 包与本机包不能互相覆盖安装。
    val ciDebugKeystore = System.getenv("HPA_DEBUG_KEYSTORE")
    if (!ciDebugKeystore.isNullOrBlank() && file(ciDebugKeystore).exists()) {
        signingConfigs {
            // maybeCreate：AGP 通常已预建 "debug" 配置，这里只覆盖它的内容，避免重复创建报错
            maybeCreate("debug").apply {
                storeFile = file(ciDebugKeystore)
                storePassword = System.getenv("HPA_DEBUG_KEYSTORE_PASS") ?: "android"
                keyAlias = "androiddebugkey"
                keyPassword = System.getenv("HPA_DEBUG_KEYSTORE_PASS") ?: "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.2.$buildNumber-$channel"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // 端侧 3B 视觉引擎的预编译 .so 仅有 arm64-v8a（libllama/libmtmd/libggml*），
        // 统一限定 ABI：debug 与 release 一致，避免模拟器 ABI 装上后 dlopen 失败
        //
        // 视觉验收专用旁路：显式传 -PincludeX86_64=true 时追加 x86_64，让 APK 能装进 x86_64 模拟器。
        // 默认（不传该属性）产物仍是纯 arm64-v8a，供日常安装与分发使用，行为与此前一致。
        // 注意：x86_64 变体不含端侧 .so，端侧视觉引擎在此变体下不可用，仅用于 UI 观感核对。
        val includeX86_64 = (providers.gradleProperty("includeX86_64").orNull ?: "false").toBoolean()
        ndk {
            abiFilters += "arm64-v8a"
            if (includeX86_64) abiFilters += "x86_64"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // ABI 精简在 defaultConfig 统一限定 arm64-v8a（端侧视觉 .so 仅有该 ABI）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            // 覆盖率：AGP 内置 JaCoCo 支持，产出的报告由
            // `createDebugUnitTestCoverageReport` 生成（CI 会上传为构建产物）。
            // 覆盖率数字只用于"看见盲区"——不对全量设阈值：本工程主体是
            // 无障碍/Shizuku/悬浮窗这类只能在真机上跑的逻辑，JVM 单测天然覆盖不到。
            enableUnitTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    // 端侧视觉 JNI 垫片：仅编译 visionbridge（dlopen 方式加载 jniLibs 里的 llama/mtmd）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 编码运行时（libnode.so + 依赖闭包）随主 APK 打包：so 必须是磁盘上的真实文件
        // （Android 10+ 只允许从 nativeLibraryDir 执行），不能 zip 内存映射
        jniLibs.useLegacyPackaging = true
    }
    testOptions {
        unitTests {
            // android.util.Log 等返回默认值（0/null），使纯逻辑单测可在 JVM 运行
            isReturnDefaultValues = true
            all { it.testLogging { events("passed", "skipped", "failed") } }
        }
    }
}

// Kotlin 编译目标：Kotlin 2.4 起 android 块内的 `kotlinOptions.jvmTarget = "17"` 已移除
// （字符串形式直接报错），统一改用 compilerOptions DSL。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // Baseline Profile 运行时安装器（见 app/src/main/baseline-prof.txt 的说明）。
    // 必须在 APK 里存在，profile 才会在「装完第一次启动」时被交给 dex2oat；
    // 少了它，baseline-prof.txt 只会被当成一个没用的资源打进包里。
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 图标体系已整体迁移至 Lucide（见 ui/icons/AppIcons.kt），material-icons-extended 不再被引用
    implementation(libs.lucide.icons)
    // 毛玻璃（背景模糊）：仅用于真正浮在滚动内容之上的固定 chrome
    implementation(libs.haze)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Shizuku 客户端库与内嵌 server 已随 shell 通道迁入 Provider 应用（:provider 模块），
    // 主应用不再直接引用 rikka.shizuku API，:shizuku-api / :shizuku-provider / :shizuku-server
    // 三个依赖一并移除（减小 APK 体积）；shell 能力改经 :provider-api 的 AIDL 远程调用

    // 权限 Provider 跨进程契约（IDeviceProvider AIDL + Parcelable）：
    // 与 :provider 共享同一份定义，序列化两端严格一致
    implementation(project(":provider-api"))

    // 编码环境跨进程契约（IEnvProvider AIDL）：与 :env 组件共享同一份定义
    implementation(project(":env-api"))

    // 从 :app 抽出的纯逻辑模块（模块化 P0）：包名不变，调用方 import 不用改
    implementation(project(":core:text"))
    implementation(project(":core:log"))

    // 模块化 P1：纯领域模型、安全层（Keystore / 脱敏）、持久化层。
    // 依赖方向固定为 app → data → {core:security, core:text, core:log} → domain，
    // 反向一条都没有（data 不能看见 app，所以它持久化的记录类型搬进了 :data 自己）。
    implementation(project(":domain"))
    implementation(project(":core:security"))
    implementation(project(":core:perf"))
    implementation(project(":data"))

    // 模块化 P2：UI 设计层（主题令牌 / 共用组件 / 图标集）。
    // 包名不变，所以 :app 里对 ui.theme / ui.components / ui.icons 的 import 一行都没改。
    implementation(project(":ui:design"))

    // 端侧视觉离线兜底：ML Kit 中文 OCR（3B 模型未下载/推理失败时框选控件）
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.common)

    debugImplementation(libs.androidx.ui.tooling)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 版本号已由根 build.gradle.kts 统一计算并回写 version.properties

// ---------------------------------------------------------------------------
// 编码 agent host 同步：从 happy_ACA 上游仓库单向同步 agent-core 源码到
// assets/agent-host（tool/bundle_host.mjs 生成 VERSION 内容指纹，RuntimeBootstrap
// 据指纹决定是否重新解压）。只经脚本单向流动，assets/agent-host 不手改、不提交。
// HACA 检出不存在时跳过（CI/纯 HPA 开发者无需克隆上游仓库），路径可用
// HACA_ROOT 环境变量覆盖，默认 ../happy_ACA。
// ---------------------------------------------------------------------------
val hacaRoot = System.getenv("HACA_ROOT") ?: rootProject.file("../happy_ACA").absolutePath
val bundleHost by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    environment("HACA_ROOT", hacaRoot)
    commandLine("node", "tool/bundle_host.mjs")
    // 声明输入/输出，让「上游源码没变」的构建直接判定 UP-TO-DATE。
    // 之前这里没有任何 inputs/outputs：每次构建都要起一次 node、重写 45 个 asset
    // 文件并抢构建锁。实测写出的内容不变，所以 mergeAssets 本来就命中 UP-TO-DATE；
    // 这项省的是每次约 1.5s 的 node 启动与无谓文件重写，产物不变。
    inputs.dir("$hacaRoot/packages/agent-core/src")
        .withPropertyName("hacaAgentCoreSrc")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .skipWhenEmpty()
    inputs.file(rootProject.file("tool/bundle_host.mjs"))
        .withPropertyName("bundleHostScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(file("src/main/assets/agent-host"))
        .withPropertyName("agentHostAssets")
    onlyIf { file("$hacaRoot/packages/agent-core/src/index.js").exists() }
}

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(bundleHost)
}

// ---------------------------------------------------------------------------
// 权限 Provider APK（assets 内静态资产）：由 :provider 产物同步进
// src/{debug,release}/assets/provider/ 下，**不入库**（`*.apk` 与 `app/src/release/`
// 都被 .gitignore 忽略）。普通主应用构建【不再】先编译 :provider——Provider 变更
// 频率低，避免每次迭代都为它买单。
// 按变体分目录保证签名一致：debug 主应用 ↔ debug 证书 Provider，release ↔
// upload 证书；签名不一致会导致 signature 级 BIND_PROVIDER 绑定被拒。
// release 变体的资产必须用上传证书签名，所以缺 app/upload-signing.properties 时
// 【不会】回落到 debug 包，而是直接失败（见 requireUploadSigning 的注释）。
// Provider 模块有改动后手动执行一次：
//   .\gradlew.bat :app:syncProviderAssets    # debug + release（需上传密钥）
//   .\gradlew.bat :app:syncProviderDebug     # 只同步 debug（CI / 无上传密钥）
// ---------------------------------------------------------------------------
val providerBuildDir = project(":provider").layout.buildDirectory

// release 变体的组件 APK 必须与 release 主应用同证书：Provider 与主应用之间、
// env 组件与主应用之间都是 signature 级权限（BIND_PROVIDER / BIND_ENV）。缺上传密钥时
// 绝不能拿 debug 包顶上，原因有两条：
//   1. 闸门（verifyProviderAssets*）只比源码指纹和文件存在性，看不出签名，
//      于是「装得上、直连全废」的包会一路全绿地发出去；
//   2. 回落写的是 src/release/assets/... 这个**和正式资产同一个路径**的文件，
//      会把已经同步好的正确资产就地覆盖成签名不匹配的版本。
// 因此把「有没有上传密钥」做成独立的前置任务：它先于 :provider:assembleRelease 失败，
// 不白烧一次 release 编译，报错里直接给出该用什么命令。
val requireUploadSigning by tasks.registering {
    group = "provider"
    description = "断言 app/upload-signing.properties 存在（release 变体的组件 APK 必须用上传证书签名）"
    // 只捕获局部 File，不引用脚本对象（配置缓存约束，见根 build.gradle.kts 顶部的说明）
    val propsFile = rootProject.file("app/upload-signing.properties").absoluteFile
    doFirst {
        if (!propsFile.exists()) {
            throw GradleException(
                "缺少 ${propsFile.absolutePath}：无法生成 release 变体的组件 APK（provider / env）。\n" +
                    "组件与主应用之间是 signature 级权限（BIND_PROVIDER / BIND_ENV），" +
                    "签名不一致时组件装得上、但主应用永远绑不上（表现为通道全废），" +
                    "所以这里不回落到 debug 包。\n" +
                    "处理：配好上传密钥后重试；只编 debug 变体时改为执行 " +
                    ":app:syncProviderDebug / :app:syncEnvDebug。",
            )
        }
    }
}

val syncProviderDebug by tasks.registering(Copy::class) {
    group = "provider"
    description = "把最新 provider-debug.apk 同步进主应用 debug assets（产物不入库）"
    dependsOn(":provider:assembleDebug", ":provider:writeProviderVersion")
    from(providerBuildDir.file("outputs/apk/debug/provider-debug.apk")) { rename { "provider.apk" } }
    from(providerBuildDir.file("provider/version.txt"))
    into(file("src/debug/assets/provider"))
    // 同步完成即记下"这份 APK 对应哪份源码"（见下方 verifyProviderAssets）
    doLast {
        providerStampFiles.getValue("debug").apply {
            parentFile.mkdirs()
            writeText(providerSourceDigest())
        }
    }
}

val syncProviderRelease by tasks.registering(Copy::class) {
    group = "provider"
    description = "把最新 provider-release.apk 同步进主应用 release assets（需上传密钥，产物不入库）"
    // 先断言密钥，再编 release 包。实测：断言确实排在 :provider:assembleRelease 之前，
    // 但 parallel=true 下仍可能先跑几个秒级的依赖任务（实测只有 mergeReleaseJniLibFolders），
    // 不会触发 Kotlin 编译或 R8——真正的重活都在断言通过之后。
    dependsOn(requireUploadSigning)
    mustRunAfter(requireUploadSigning)
    dependsOn(":provider:assembleRelease", ":provider:writeProviderVersion")
    from(providerBuildDir.file("outputs/apk/release/provider-release.apk")) { rename { "provider.apk" } }
    from(providerBuildDir.file("provider/version.txt"))
    into(file("src/release/assets/provider"))
    // 同 debug：记下这份 APK 对应的源码指纹
    doLast {
        providerStampFiles.getValue("release").apply {
            parentFile.mkdirs()
            writeText(providerSourceDigest())
        }
    }
}

tasks.register("syncProviderAssets") {
    group = "provider"
    description = "同步两个变体的 Provider APK 到主应用 assets（Provider 改动后手动执行）"
    dependsOn(syncProviderDebug, syncProviderRelease)
}

// ---------------------------------------------------------------------------
// Provider 资产新鲜度闸门
//
// assets/provider 里的 APK 是"构建产物直接提交进仓库"，因此它天然会落后于源码：
// 改了 provider / provider-api 却忘了同步时，主应用会静默地把旧 Provider 打进包，
// 表现为"线上行为还是老版本"，且没有任何编译期信号。
//
// 这里用源码指纹兜住：syncProviderAssets 时把当前指纹写进 config/provider-assets/<variant>.sha256，
// 每次合并 assets 前比对一次，不一致即构建失败并提示执行同步。
// 指纹覆盖 provider / provider-api 的 src/main 与各自的构建脚本（依赖/开关变化同样需要重新打包）。
// ---------------------------------------------------------------------------
val providerStampDir = rootProject.file("config/provider-assets")

fun providerSourceDigest(): String {
    // 注意：不要写 java.security.MessageDigest——脚本里的 `java` 指向 JavaPluginExtension，会解析失败
    val digest = MessageDigest.getInstance("SHA-256")
    val sourceRoots = listOf(
        project(":provider").file("src/main"),
        project(":provider-api").file("src/main"),
    )
    sourceRoots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".apk") }
            .sortedBy { it.absolutePath }.toList()
    }.forEach { f ->
        digest.update(f.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(f.readBytes())
    }
    listOf("provider", "provider-api").forEach { name ->
        project(":$name").file("build.gradle.kts").takeIf { it.exists() }?.let {
            digest.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
            digest.update(it.readBytes())
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// 两个变体的资产各自独立（签名/版本不同），因此各存一份指纹
val providerStampFiles = mapOf(
    "debug" to providerStampDir.resolve("debug.sha256"),
    "release" to providerStampDir.resolve("release.sha256"),
)

val verifyProviderEntries = listOf(
    "debug" to "syncProviderDebug",
    "release" to "syncProviderRelease",
)

// 按变体分别校验。之前是一个任务同时查 debug + release：结果是「只编 debug」也会被
// release 资产的缺失挡住，于是无上传密钥的环境（CI、干净检出）必须靠「把 debug 包填进
// release 目录」才能通过构建——那正是 P0-2 的成因。现在每个 merge*Assets 只依赖自己变体的校验。
val verifyProviderVariant = verifyProviderEntries.associate { (variant, syncTask) ->
    variant to tasks.register("verifyProviderAssets${variant.replaceFirstChar { it.uppercase() }}") {
        group = "provider"
        description = "校验 $variant 变体的 Provider APK 是否与当前源码一致（不一致请执行 :app:$syncTask）"
        val apkFile = file("src/$variant/assets/provider/provider.apk")
        doLast {
            val expected = providerSourceDigest()
            val stampFile = providerStampFiles.getValue(variant)
            val problem = when {
                !stampFile.exists() -> "$variant：缺少源码指纹记录（从未同步过 Provider 资产）"
                stampFile.readText().trim() != expected ->
                    "$variant：Provider 源码已变更，assets 内的 APK 是旧版本"
                // 指纹只能证明"同步过"，不能证明"文件还在"：*.apk 被 .gitignore 忽略，
                // 干净检出后 assets 里往往只剩 version.txt，打包出去就是"装得上、直连全废"的坏包
                !apkFile.exists() -> "$variant：assets 内缺少 provider.apk（干净检出后需执行 :app:$syncTask）"
                else -> null
            }
            if (problem != null) {
                throw GradleException(
                    buildString {
                        appendLine("Provider 资产与源码不一致：")
                        appendLine("  - $problem")
                        appendLine("当前源码指纹：$expected")
                        appendLine("请执行 .\\gradlew.bat :app:$syncTask 重新打包后再构建。")
                    },
                )
            }
        }
    }
}

// 合并入口：:app:verifyProviderAssets 仍然存在（文档与 CHANGELOG 都引用它），两个变体全查；
// 构建期按上面的变体任务分别校验，不再用它。
val verifyProviderAssets by tasks.registering {
    group = "provider"
    description = "校验两个变体的 Provider APK 与源码一致（合并入口，构建期按变体分别校验）"
    dependsOn(verifyProviderVariant.values)
}

// 同一次调用里先同步再校验：sync* 会刷新指纹，不应被校验当成旧版本
verifyProviderVariant.values.forEach { it.configure { mustRunAfter(syncProviderDebug, syncProviderRelease) } }

tasks.matching { it.name == "mergeDebugAssets" }
    .configureEach { dependsOn(verifyProviderVariant.getValue("debug")) }
tasks.matching { it.name == "mergeReleaseAssets" }
    .configureEach { dependsOn(verifyProviderVariant.getValue("release")) }

// 同一次调用里跑 syncProviderAssets + assemble* 时，assets 合并必须等同步落盘完成
// （两个任务共同读写 src/{debug,release}/assets/provider，Gradle 无法自动推断顺序）
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { mustRunAfter(syncProviderDebug) }
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { mustRunAfter(syncProviderRelease) }

// 干净检出兜底：assets 内的 *.apk 被 .gitignore 忽略，全新 clone 上 merge*Assets 会因
// verifyProviderAssets<变体> 报「缺少 provider.apk」直接失败。只在文件确实缺失时补挂同步任务，
// 正常迭代（APK 已在）不会多出依赖，也不会触发 :provider 重编。
// 无上传密钥的机器上，release 这条会在 syncProviderRelease 的 requireUploadSigning 处
// 明确失败（而不是填一个签名不匹配的 debug 包），这是有意的 fail-closed。
if (!file("src/debug/assets/provider/provider.apk").exists()) {
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(syncProviderDebug) }
}
if (!file("src/release/assets/provider/provider.apk").exists()) {
    tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(syncProviderRelease) }
}

// ---------------------------------------------------------------------------
// 编码环境组件 APK（assets 内静态资产）。
// 构建时由 :app:syncEnvDebug / syncEnvRelease 从 :env 产物同步到
// src/{debug,release}/assets/env/ 下，属生成物、不进仓库（见 .gitignore）；
// 需要单独刷新时手动执行：.\gradlew.bat :app:syncEnvAssets
// 不做源码指纹闸门：env 的 AIDL 契约由 env-api 模块参与主应用编译，
// 契约不同步会直接编译失败；APK 落后只影响运行时能力（install/build 由版本号兜底）。
// ---------------------------------------------------------------------------
val envBuildDir = project(":env").layout.buildDirectory

val syncEnvDebug by tasks.registering(Copy::class) {
    group = "env"
    description = "把最新 env-debug.apk 同步进主应用 debug assets（构建产物，不提交）"
    dependsOn(":env:assembleDebug", ":env:writeEnvVersion")
    from(envBuildDir.file("outputs/apk/debug/env-debug.apk")) { rename { "env.apk" } }
    from(envBuildDir.file("env/version.txt"))
    into(file("src/debug/assets/env"))
}

val syncEnvRelease by tasks.registering(Copy::class) {
    group = "env"
    description = "把最新 env-release.apk 同步进主应用 release assets（需上传密钥，构建产物不提交）"
    // 与 syncProviderRelease 同理：env 组件也是 signature 级权限（BIND_ENV），
    // 缺上传密钥时拿 debug 包顶上等于发出「环境通道全废」的包，故 fail-closed。
    dependsOn(requireUploadSigning)
    mustRunAfter(requireUploadSigning)
    dependsOn(":env:assembleRelease", ":env:writeEnvVersion")
    from(envBuildDir.file("outputs/apk/release/env-release.apk")) { rename { "env.apk" } }
    from(envBuildDir.file("env/version.txt"))
    into(file("src/release/assets/env"))
}

tasks.register("syncEnvAssets") {
    group = "env"
    description = "同步两个变体的编码环境组件 APK 到主应用 assets（env 改动后手动执行）"
    dependsOn(syncEnvDebug, syncEnvRelease)
}

// 同一次调用里跑 syncEnvAssets + assemble* 时，assets 合并必须等同步落盘完成
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { mustRunAfter(syncEnvDebug) }
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { mustRunAfter(syncEnvRelease) }

// env.apk 不进仓库：干净检出时自动补一次同步，避免装出「编码通道全部不可用」的静默坏包。
if (!file("src/debug/assets/env/env.apk").exists()) {
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(syncEnvDebug) }
}
if (!file("src/release/assets/env/env.apk").exists()) {
    tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { dependsOn(syncEnvRelease) }
}
