import java.io.File
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    // Shizuku server 内嵌（third_party/shizuku）所需的 hidden API 精化插件
    alias(libs.plugins.rikka.refine) apply false
}

// ---------------------------------------------------------------------------
// 版本号：在 root 统一计算，:app 与 :provider 只读取同一个值。
//
// 之前 app 用 git 提交数当 versionCode，导致「不提交新 commit 就永远不递增」，
// 每次构建出来的 APK 版本号都一样。现在改为：
//   1. `-PbuildNumber=NN` 优先（CI 注入，可用于覆盖/回退）；
//   2. 否则取 version.properties 里记录的上次值（首次以 git 提交数为基线），
//      只要本次调用会产出 APK（assemble / bundle / build），就 +1；
//      **回写发生在 package 任务成功之后**（见 persistBuildNumber），失败构建不消费版本号。
//
// 为什么放在 root：Gradle 会先配置 root 再配置子项目，`org.gradle.parallel=true`
// 下 app/provider 的配置顺序不保证，各自算一遍会算出不同值（两边版本号错位）。
// ---------------------------------------------------------------------------
val versionPropsFile = rootProject.file("version.properties")

fun readStoredBuildNumber(): Int {
    if (!versionPropsFile.exists()) return 0
    val props = Properties().apply {
        runCatching { versionPropsFile.inputStream().use { load(it) } }
    }
    return props.getProperty("BUILD_NUMBER")?.toIntOrNull() ?: 0
}

fun gitCommitCount(): Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

// 只有真正产出 APK 的调用才自增：跑 detekt / test / syncProviderAssets 时
// 不该平白吃掉一个版本号。androidPackage 是可选模块里的"构建 + 归档"任务，
// 它依赖 :app:assembleDebug，同样会产出 APK，必须计入自增判断。
val apkBuilding = run {
    val names = gradle.startParameter.taskNames.map { it.lowercase(Locale.ROOT).substringAfterLast(':') }
    names.any {
        it == "build" || it.contains("assemble") || it.contains("bundle") || it == "androidpackage"
    }
}

val buildNumber: Int = run {
    val stored = readStoredBuildNumber()
    val injected = (findProperty("buildNumber") as? String)?.toIntOrNull()
    // 基线取「已记录值」与「git 提交数」的较大者：即使期间新增了 commit，
    // 版本号也只增不减（versionCode 回退会导致覆盖安装失败）。
    val base = maxOf(stored, gitCommitCount(), 0)
    val value = injected ?: (if (apkBuilding) base + 1 else maxOf(base, 1))
    maxOf(value, 1)
}

/**
 * 版本号回写。
 *
 * 回写时机从「配置阶段」挪到「打包任务成功之后」（见下方 package* 任务的 doLast 挂接）：
 * 配置阶段就写会导致 `--dry-run`、detekt/test、以及**编译失败的构建**同样吃掉一个版本号，
 * 并且让被跟踪的 version.properties 在每次构建后都变脏。现在只有真正产出 APK/AAB 的
 * package* 任务执行成功才消费版本号；构建中途失败时下一次会复用同一个号。
 */
fun persistBuildNumber(value: Int) {
    if (value <= readStoredBuildNumber()) return
    runCatching {
        versionPropsFile.writeText(
            buildString {
                appendLine("#Auto-incremented by Gradle")
                appendLine(
                    "#" + ZonedDateTime.now()
                        .format(DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ROOT))
                )
                appendLine("BUILD_NUMBER=$value")
            }
        )
    }
}

// 子项目通过 rootProject.extra["buildNumber"] 读取
extra["buildNumber"] = buildNumber

// 只有 APK/AAB 真正打包成功后才回写版本号：所有子模块的 package* 任务都挂一次，
// persistBuildNumber 自带「只增不减」判断，多次调用只有第一次生效。
if (apkBuilding) {
    subprojects.forEach { p ->
        p.tasks.matching {
            it.name == "packageDebug" || it.name == "packageRelease" || it.name.endsWith("Bundle")
        }.configureEach {
            doLast { persistBuildNumber(buildNumber) }
        }
    }
}

// ===========================================================================
// 可选构建模块（配置：config/build-modules.properties，说明：docs/build-modules.md）
//
// 设计约束（改这一块之前先读文档）：
//   1. 只"注册任务"，绝不把任何模块任务挂到 build / check / assemble 上——
//      所以模块开启或关闭都不改变默认构建流程的任务图、行为与耗时。
//   2. 每个模块独立读取自己的开关与参数，模块之间不建任务依赖；
//      唯一的构建型依赖是 androidPackage → :app:assembleDebug，属于该模块自身动作。
//   3. 关闭的模块保留同名任务（占位实现）：调用时打印"已禁用"并正常结束（退出码 0），
//      便于 CI 用同一串任务名跑不同配置，也避免"任务不存在"的半可用状态。
//   4. 配置值只接受 true / false；写错在配置阶段立即失败并给出可操作的中文提示。
// ===========================================================================

val buildModulesConfigFile = rootProject.file("config/build-modules.properties")
val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")
val npmExecutable = if (isWindowsHost) "npm.cmd" else "npm"
val moduleGroup = "可选构建模块"

val buildModulesConfig: Map<String, String> = run {
    if (!buildModulesConfigFile.exists()) {
        emptyMap()
    } else {
        Properties().apply { buildModulesConfigFile.inputStream().use { load(it) } }
            .map { (key, value) -> key.toString() to value.toString() }
            .toMap()
    }
}

fun moduleEnabled(name: String, default: Boolean): Boolean {
    val key = "hpa.modules.$name.enabled"
    // 优先级：命令行 -P > 配置文件 > 内置默认值（CI 可临时覆盖，不必改文件）
    val raw = (findProperty(key) as? String) ?: buildModulesConfig[key] ?: default.toString()
    return when (raw.trim().lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> throw GradleException(
            "$key 取值非法：\"$raw\"。只接受 true / false（配置文件 $buildModulesConfigFile，" +
                "或命令行 -P$key=true|false）。",
        )
    }
}

fun moduleOption(name: String, option: String, default: String): String {
    val key = "hpa.modules.$name.$option"
    return (findProperty(key) as? String)?.takeIf { it.isNotBlank() } ?: buildModulesConfig[key] ?: default
}

/** 同步执行外部命令并合并 stderr，返回 退出码 to 输出；超时按失败处理并销毁进程。 */
fun shell(
    vararg command: String,
    workingDir: File? = null,
    timeoutSeconds: Long = 30,
): Pair<Int, String> {
    val builder = ProcessBuilder(*command).redirectErrorStream(true)
    workingDir?.let { builder.directory(it) }
    val process = runCatching { builder.start() }
        .getOrElse { return -1 to (it.message ?: "无法启动命令：${command.joinToString(" ")}") }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    return if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.exitValue() to output
    } else {
        process.destroyForcibly()
        -1 to "$output\n[命令超时 ${timeoutSeconds}s]"
    }
}

fun shellOk(
    vararg command: String,
    workingDir: File? = null,
    timeoutSeconds: Long = 30,
): String? = shell(*command, workingDir = workingDir, timeoutSeconds = timeoutSeconds)
    .let { (code, output) -> output.takeIf { code == 0 } }

fun git(vararg args: String, timeoutSeconds: Long = 30): Pair<Int, String> =
    shell("git", *args, workingDir = rootProject.projectDir, timeoutSeconds = timeoutSeconds)

fun gitOk(vararg args: String, timeoutSeconds: Long = 30): String? =
    git(*args, timeoutSeconds = timeoutSeconds).let { (code, output) -> output.takeIf { code == 0 } }

/** Android SDK 路径：local.properties 的 sdk.dir > ANDROID_HOME > ANDROID_SDK_ROOT。 */
fun androidSdkDir(): File? {
    val fromLocal = runCatching {
        rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
    }.getOrNull()
    val raw = fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    return raw?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
}

fun registerDisabledModuleStub(taskName: String, moduleName: String, capability: String) {
    tasks.register(taskName) {
        group = moduleGroup
        description = "【已禁用】$capability（开关 hpa.modules.$moduleName.enabled=false）"
        doLast {
            logger.lifecycle(
                "模块 $moduleName 已禁用：$capability 未执行。" +
                    "启用方式：把 $buildModulesConfigFile 的 hpa.modules.$moduleName.enabled 改为 true，" +
                    "或临时加 -Phpa.modules.$moduleName.enabled=true。",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 模块 1：Android 平台构建支持
//   androidCheck   —— JDK / SDK / Wrapper 前置校验（不改任何产物）
//   androidPackage —— 构建 Debug APK 并按时间戳归档（依赖 :app:assembleDebug）
// 关闭时这两个任务只打印提示；AGP 自身的 assembleDebug 始终可用。
// ---------------------------------------------------------------------------
if (moduleEnabled("android", true)) {
    tasks.register("androidCheck") {
        group = moduleGroup
        description = "校验 Android 平台构建前置条件（JDK 17+ / SDK 36 / Gradle Wrapper）"
        doLast {
            val problems = mutableListOf<String>()
            val javaVersion = System.getProperty("java.version").orEmpty()
            val javaMajor = Regex("""^(?:1\.)?(\d+)""").find(javaVersion)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            if (javaMajor < 17) problems += "JDK 版本过低（$javaVersion），AGP 8.x 要求 JDK 17+"
            val sdk = androidSdkDir()
            when {
                sdk == null ->
                    problems += "未找到 Android SDK：请在 local.properties 写 sdk.dir，或设置 ANDROID_HOME"
                !sdk.resolve("platforms/android-36").isDirectory ->
                    problems += "Android SDK 缺少 platforms/android-36（:app 的 compileSdk=36），请用 sdkmanager 安装"
            }
            if (!rootProject.file("gradlew.bat").exists() && !rootProject.file("gradlew").exists()) {
                problems += "缺少 Gradle Wrapper（gradlew / gradlew.bat）"
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Android 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew androidCheck。")
                    },
                )
            }
            logger.lifecycle("Android 模块校验通过：JDK $javaVersion；SDK ${sdk?.absolutePath}")
        }
    }

    tasks.register("androidPackage") {
        group = moduleGroup
        description = "构建 Debug APK 并按时间戳归档（归档目录见 hpa.modules.android.archiveDir）"
        dependsOn(":app:assembleDebug")
        doLast {
            val apk = project(":app").layout.buildDirectory
                .file("outputs/apk/debug/app-debug.apk").get().asFile
            if (!apk.exists()) throw GradleException("未找到构建产物：${apk.absolutePath}")
            // 归档是"复制"，不动 app/build/outputs 下的原始产物，避免破坏后续发布脚本
            val archiveDir = rootProject.file(moduleOption("android", "archiveDir", "build/apk-archive"))
            archiveDir.mkdirs()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val target = archiveDir.resolve("app-debug_$timestamp.apk")
            apk.copyTo(target, overwrite = true)
            logger.lifecycle(
                "APK 归档：${target.absolutePath}（${target.length() / 1024 / 1024} MB，versionCode=$buildNumber）",
            )
        }
    }
} else {
    registerDisabledModuleStub("androidCheck", "android", "Android 平台构建前置校验")
    registerDisabledModuleStub("androidPackage", "android", "Android 平台构建与归档")
}

// ---------------------------------------------------------------------------
// 模块 2：Vue 开发服务器集成（官网 website/）
//   vueCheck   —— Node 18+ / npm / package.json 脚本校验
//   vueInstall —— node_modules 缺失时安装依赖
//   vueDev     —— 长驻开发服务器（npm run dev:web），输出直通终端
//   vueBuild   —— 静态构建（npm run build → website/dist）
// 关闭时全部只打印提示，不触碰 website/。
// ---------------------------------------------------------------------------
val vueDir = rootProject.file(moduleOption("vue", "dir", "website"))

if (moduleEnabled("vue", false)) {
    tasks.register("vueCheck") {
        group = moduleGroup
        description = "校验 Vue 开发环境（Node 18+ / npm / website 依赖与脚本）"
        doLast {
            val problems = mutableListOf<String>()
            val nodeVersion = shellOk("node", "--version")
            val npmVersion = shellOk(npmExecutable, "--version")
            if (nodeVersion == null) {
                problems += "未找到 Node.js（website 要求 >=18），请安装后重试"
            } else {
                val major = nodeVersion.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0
                if (major < 18) problems += "Node.js 版本过低（$nodeVersion），website 要求 >=18"
            }
            if (npmVersion == null) problems += "未找到 npm（$npmExecutable）"
            if (!vueDir.isDirectory) problems += "Vue 工程目录不存在：${vueDir.absolutePath}"
            val packageJson = vueDir.resolve("package.json")
            if (!packageJson.exists()) {
                problems += "缺少 ${packageJson.absolutePath}"
            } else {
                val text = packageJson.readText()
                val devScript = moduleOption("vue", "devScript", "dev:web")
                listOf(devScript, "build").forEach { script ->
                    if (!text.contains("\"$script\"")) problems += "package.json 缺少脚本 \"$script\""
                }
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Vue 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew vueCheck。")
                    },
                )
            }
            val installed = if (vueDir.resolve("node_modules").isDirectory) "已安装" else "未安装（先跑 vueInstall）"
            logger.lifecycle("Vue 模块校验通过：node $nodeVersion / npm $npmVersion；依赖$installed")
        }
    }

    tasks.register<Exec>("vueInstall") {
        group = moduleGroup
        description = "安装官网前端依赖（node_modules 已存在时跳过）"
        workingDir = vueDir
        commandLine(npmExecutable, "install")
        onlyIf {
            val installed = vueDir.resolve("node_modules").isDirectory
            if (installed) logger.lifecycle("website/node_modules 已存在，跳过 npm install")
            !installed
        }
    }

    tasks.register<Exec>("vueDev") {
        group = moduleGroup
        description = "启动 Vue 开发服务器（长驻任务，Ctrl+C 结束）"
        dependsOn("vueInstall")
        workingDir = vueDir
        commandLine(npmExecutable, "run", moduleOption("vue", "devScript", "dev:web"))
        // 长驻服务必须直通终端，否则用户在 Gradle 里看不到任何日志
        standardOutput = System.out
        errorOutput = System.err
    }

    tasks.register<Exec>("vueBuild") {
        group = moduleGroup
        description = "构建官网静态产物（npm run build → website/dist）"
        dependsOn("vueInstall")
        workingDir = vueDir
        commandLine(npmExecutable, "run", "build")
    }
} else {
    registerDisabledModuleStub("vueCheck", "vue", "Vue 开发环境校验")
    registerDisabledModuleStub("vueInstall", "vue", "Vue 前端依赖安装")
    registerDisabledModuleStub("vueDev", "vue", "Vue 开发服务器")
    registerDisabledModuleStub("vueBuild", "vue", "Vue 静态构建")
}

// ---------------------------------------------------------------------------
// 模块 3：Git 版本控制工具链集成
//   gitCheck  —— git 可用性与仓库有效性校验
//   gitStatus —— 分支、上游与工作区变更摘要（只读）
//   gitInfo   —— 分支 / 提交 / 脏标记 / 远程 / 版本号落盘为 JSON（只读）
// ---------------------------------------------------------------------------
if (moduleEnabled("git", false)) {
    tasks.register("gitCheck") {
        group = moduleGroup
        description = "校验 Git 工具链与仓库有效性"
        doLast {
            val problems = mutableListOf<String>()
            val version = shellOk("git", "--version") ?: run {
                problems += "未找到 git，请安装并加入 PATH"
                null
            }
            if (gitOk("rev-parse", "--is-inside-work-tree") != "true") {
                problems += "当前目录不是 Git 仓库：${rootProject.projectDir}"
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Git 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew gitCheck。")
                    },
                )
            }
            logger.lifecycle("Git 模块校验通过：$version")
        }
    }

    tasks.register("gitStatus") {
        group = moduleGroup
        description = "打印当前分支、上游跟踪与工作区变更摘要（只读，不改仓库）"
        doLast {
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD") ?: "未知"
            val upstream = gitOk("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
            logger.lifecycle("分支：$branch；上游：${upstream ?: "未设置"}")
            val changes = gitOk("status", "--short").orEmpty()
            if (changes.isBlank()) {
                logger.lifecycle("工作区干净，无未提交改动")
            } else {
                val lines = changes.lines()
                logger.lifecycle("未提交改动 ${lines.size} 项：")
                lines.take(50).forEach { logger.lifecycle("  $it") }
                if (lines.size > 50) logger.lifecycle("  … 其余 ${lines.size - 50} 项已省略")
            }
        }
    }

    tasks.register("gitInfo") {
        group = moduleGroup
        description = "把分支/提交/脏标记/远程/版本号写入 JSON（默认 build/build-info/git-info.json）"
        doLast {
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD")
            val commit = gitOk("rev-parse", "HEAD")
            val commitShort = gitOk("rev-parse", "--short", "HEAD")
            val commitCount = gitOk("rev-list", "--count", "HEAD")
            val dirty = !gitOk("status", "--porcelain").isNullOrBlank()
            val remotes = gitOk("remote", "-v").orEmpty()
                .lines()
                .filter { it.contains("(fetch)") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) "\"${jsonEscape(parts[0])}\": \"${jsonEscape(parts[1])}\"" else null
                }
            val outFile = rootProject.file(moduleOption("git", "output", "build/build-info/git-info.json"))
            outFile.parentFile?.mkdirs()
            outFile.writeText(
                buildString {
                    appendLine("{")
                    appendLine("  \"generatedAt\": \"${LocalDateTime.now()}\",")
                    appendLine("  \"buildNumber\": $buildNumber,")
                    appendLine("  \"branch\": \"${jsonEscape(branch ?: "")}\",")
                    appendLine("  \"commit\": \"${jsonEscape(commit ?: "")}\",")
                    appendLine("  \"commitShort\": \"${jsonEscape(commitShort ?: "")}\",")
                    appendLine("  \"commitCount\": ${commitCount?.toIntOrNull() ?: 0},")
                    appendLine("  \"dirty\": $dirty,")
                    appendLine("  \"remotes\": {${remotes.joinToString(", ")}}")
                    appendLine("}")
                },
            )
            logger.lifecycle("Git 构建信息已写入：${outFile.absolutePath}")
        }
    }
} else {
    registerDisabledModuleStub("gitCheck", "git", "Git 工具链校验")
    registerDisabledModuleStub("gitStatus", "git", "Git 工作区状态摘要")
    registerDisabledModuleStub("gitInfo", "git", "Git 构建信息落盘")
}

fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

// ---------------------------------------------------------------------------
// 模块 4：GitHub 仓库关联与同步
//   githubCheck —— 校验远程是否已关联（不联网，离线可用）
//   githubLink  —— 按 -PgithubUrl=<url> 添加或更新远程地址；不带参数时只打印现状
//   githubSync  —— 推送当前分支；默认"演练"，必须显式 -PconfirmSync=true 才真正推送
// 该模块永不自动推送，也不会改写远程配置（除非显式调用 githubLink + githubUrl）。
// ---------------------------------------------------------------------------
val githubRemoteName = moduleOption("github", "remote", "github")

if (moduleEnabled("github", false)) {
    tasks.register("githubCheck") {
        group = moduleGroup
        description = "校验 GitHub 远程关联与可选工具（gh CLI）"
        doLast {
            val problems = mutableListOf<String>()
            val url = gitOk("remote", "get-url", githubRemoteName)
            if (url == null) {
                problems += "未配置远程 \"$githubRemoteName\"。关联方式：" +
                    ".\\gradlew githubLink -PgithubUrl=https://github.com/<user>/<repo>.git"
            }
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD")
            if (branch == null) problems += "当前不在有效 Git 分支上"
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("GitHub 模块兼容性校验未通过：")
                        problems.forEach { appendLine("  - $it") }
                        appendLine("修复后重跑 .\\gradlew githubCheck。")
                    },
                )
            }
            val gh = shellOk("gh", "--version")?.lineSequence()?.firstOrNull()
            logger.lifecycle(
                "GitHub 模块校验通过：远程 $githubRemoteName → $url；分支 $branch；" +
                    "gh CLI ${if (gh != null) "可用（$gh）" else "未安装（可选，仅影响体验）"}",
            )
        }
    }

    tasks.register("githubLink") {
        group = moduleGroup
        description = "关联 GitHub 远程：-PgithubUrl=<url> 添加或更新；不带参数仅打印当前配置"
        doLast {
            val url = (findProperty("githubUrl") as? String)?.trim()
            val current = gitOk("remote", "get-url", githubRemoteName)
            if (url.isNullOrBlank()) {
                logger.lifecycle("当前远程 $githubRemoteName：${current ?: "未配置"}")
                logger.lifecycle(
                    "关联/更新方式：.\\gradlew githubLink -PgithubUrl=https://github.com/<user>/<repo>.git",
                )
                return@doLast
            }
            if (!url.startsWith("https://") && !url.startsWith("git@") && !url.startsWith("ssh://")) {
                throw GradleException("githubUrl 必须是 https:// 或 git@/ssh:// 形式的仓库地址，当前为：$url")
            }
            val (code, output) = if (current == null) {
                git("remote", "add", githubRemoteName, url)
            } else {
                git("remote", "set-url", githubRemoteName, url)
            }
            if (code != 0) throw GradleException("远程配置失败：$output")
            logger.lifecycle("远程 $githubRemoteName 已关联：$url")
        }
    }

    tasks.register("githubSync") {
        group = moduleGroup
        description = "推送当前分支到 GitHub 远程（需 -PconfirmSync=true；默认只做演练检查）"
        doLast {
            val url = gitOk("remote", "get-url", githubRemoteName)
                ?: throw GradleException(
                    "未配置远程 \"$githubRemoteName\"，先执行 githubLink -PgithubUrl=<url>。",
                )
            val branch = gitOk("rev-parse", "--abbrev-ref", "HEAD")
                ?: throw GradleException("当前不在有效 Git 分支上，无法推送。")
            val confirm = (findProperty("confirmSync") as? String)?.equals("true", ignoreCase = true) == true
            logger.lifecycle("GitHub 远程：$url")
            logger.lifecycle("待推送分支：$branch")
            if (!confirm) {
                logger.lifecycle("演练模式：未执行推送。确认无误后加 -PconfirmSync=true 再跑一次。")
                return@doLast
            }
            val reachable = git("ls-remote", "--heads", githubRemoteName, branch, timeoutSeconds = 20)
            if (reachable.first != 0) {
                throw GradleException(
                    "无法访问 GitHub 远程（网络或凭据问题）：$url\n${reachable.second}\n" +
                        "可先用 githubCheck 校验关联，网络恢复后重试。",
                )
            }
            val (code, output) = git("push", githubRemoteName, branch, timeoutSeconds = 120)
            if (code != 0) throw GradleException("推送失败（$branch → $githubRemoteName）：\n$output")
            logger.lifecycle("已推送 $branch → $githubRemoteName")
        }
    }
} else {
    registerDisabledModuleStub("githubCheck", "github", "GitHub 远程关联校验")
    registerDisabledModuleStub("githubLink", "github", "GitHub 远程关联/更新")
    registerDisabledModuleStub("githubSync", "github", "GitHub 分支同步")
}

// ---------------------------------------------------------------------------
// 汇总入口：状态报告 + 兼容性校验
//   buildModules      —— 只读状态表（开关、前置条件、任务、启用方式），永不失败
//   buildModulesCheck —— 对"已启用"的模块逐个跑各自的 check（禁用的只打印提示）
// ---------------------------------------------------------------------------
tasks.register("buildModules") {
    group = moduleGroup
    description = "列出全部可选构建模块的开关状态、前置条件与任务（只读，不触发构建）"
    doLast {
        val enabled = mapOf(
            "android" to moduleEnabled("android", true),
            "vue" to moduleEnabled("vue", false),
            "git" to moduleEnabled("git", false),
            "github" to moduleEnabled("github", false),
        )
        val sdk = androidSdkDir()
        val node = shellOk("node", "--version")
        val npm = shellOk(npmExecutable, "--version")
        val gitVersion = shellOk("git", "--version")
        val gh = shellOk("gh", "--version")?.lineSequence()?.firstOrNull()
        val githubUrl = gitOk("remote", "get-url", githubRemoteName)
        val vueDeps = if (vueDir.resolve("node_modules").isDirectory) "已安装" else "未安装"

        fun state(on: Boolean) = if (on) "启用" else "禁用"

        logger.lifecycle(
            buildString {
                appendLine("可选构建模块状态（配置文件：${buildModulesConfigFile.name}）")
                appendLine("  [${state(enabled.getValue("android"))}] android  Android 平台构建")
                appendLine(
                    "        前置：JDK ${System.getProperty("java.version")}；" +
                        "SDK ${sdk?.absolutePath ?: "未找到"}",
                )
                appendLine("        任务：androidCheck、androidPackage")
                appendLine("  [${state(enabled.getValue("vue"))}] vue      Vue 开发服务器")
                appendLine("        前置：node ${node ?: "未找到"}；npm ${npm ?: "未找到"}；依赖$vueDeps")
                appendLine("        任务：vueCheck、vueInstall、vueDev、vueBuild")
                appendLine("  [${state(enabled.getValue("git"))}] git      Git 版本控制工具链")
                appendLine("        前置：${gitVersion ?: "未找到 git"}")
                appendLine("        任务：gitCheck、gitStatus、gitInfo")
                appendLine("  [${state(enabled.getValue("github"))}] github   GitHub 仓库关联与同步")
                appendLine(
                    "        前置：远程 $githubRemoteName → ${githubUrl ?: "未配置"}；" +
                        "gh ${gh ?: "未安装（可选）"}",
                )
                appendLine("        任务：githubCheck、githubLink、githubSync")
                appendLine(
                    "已启用 ${enabled.count { it.value }}/4。禁用模块的任务仍可调用，只打印提示。" +
                        "整体校验：.\\gradlew buildModulesCheck",
                )
            },
        )
        // 配置里出现未知模块键时给出告警（不失败，避免拼错一个键就卡住构建）
        val known = setOf("android", "vue", "git", "github")
        buildModulesConfig.keys
            .filter { it.startsWith("hpa.modules.") }
            .mapNotNull { it.split('.').getOrNull(2) }
            .filter { it !in known }
            .distinct()
            .forEach { logger.warn("警告：配置中存在未知模块键 hpa.modules.$it.*，已忽略") }
    }
}

tasks.register("buildModulesCheck") {
    group = moduleGroup
    description = "对已启用的可选模块执行兼容性校验（禁用模块自动跳过）"
    dependsOn("androidCheck", "vueCheck", "gitCheck", "githubCheck")
    doLast {
        val enabled = listOf(
            "android" to moduleEnabled("android", true),
            "vue" to moduleEnabled("vue", false),
            "git" to moduleEnabled("git", false),
            "github" to moduleEnabled("github", false),
        ).filter { it.second }.map { it.first }
        logger.lifecycle(
            if (enabled.isEmpty()) {
                "可选模块校验完成：四个模块全部禁用，未执行任何检查（默认构建流程不受影响）"
            } else {
                "可选模块校验完成：已启用 ${enabled.joinToString("、")}（${enabled.size}/4）"
            },
        )
    }
}
