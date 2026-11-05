package com.phoneagent.coding.env

import java.io.File

/**
 * 工程工具链识别结果。
 *
 * Gradle 版本优先取自工程自带的 wrapper（它就是"这个工程想用的版本"），
 * 没有 wrapper 时才按 AGP 版本查官方配套表——设备端没有 AS 的 SDK 同步，
 * 版本对不上只会得到一句难懂的 Gradle 报错，所以入口这一步就要算准。
 */
data class ProjectToolchain(
    val gradleVersion: String,
    /** wrapper / agp / fallback：版本号的来源，UI 用来解释"为什么是这个版本" */
    val gradleSource: String,
    val agpVersion: String?,
    val compileSdk: Int,
    val buildTools: String,
    val projectDir: File,
) {
    val ready: Boolean get() = gradleVersion.isNotBlank() && projectDir.isDirectory
}

/**
 * 从工程文件里读出构建所需的版本信息。
 *
 * 全部解析函数只吃字符串、不碰磁盘，这样纯 JVM 单测能直接覆盖各种工程写法
 * （kts / groovy / version catalog），真机上跑一遍只是把文件读进来喂给它们。
 */
object ProjectToolchainProbe {

    /** AGP ↔ Gradle 官方配套表（键是 AGP 的 major.minor，值是官方要求的最低 Gradle） */
    private val GRADLE_FOR_AGP = mapOf(
        "8.0" to "8.0",
        "8.1" to "8.0",
        "8.2" to "8.2",
        "8.3" to "8.4",
        "8.4" to "8.6",
        "8.5" to "8.7",
        "8.6" to "8.7",
        "8.7" to "8.9",
        "8.8" to "8.10.2",
        "8.9" to "8.11.1",
        "8.10" to "8.11.1",
        "8.11" to "8.13",
        "8.12" to "8.13",
        "8.13" to "8.13",
        "9.0" to "9.0",
        "9.1" to "9.1",
        "9.2" to "9.4.1",
        "9.3" to "9.5",
        "9.4" to "9.6.0",
    )

    private const val DEFAULT_COMPILE_SDK = 36
    private const val FALLBACK_GRADLE_8 = "8.13"
    private const val FALLBACK_GRADLE_9 = "9.6.0"

    /** AGP 从 9 起换成 Gradle 9 线；配套表外的版本按主版本落在这条分界的两侧 */
    private const val AGP_MAJOR_GRADLE_9 = 9

    private val GRADLE_ZIP = Regex("""gradle-(\d+\.\d+(?:\.\d+)?(?:-[a-z0-9]+(?:\.\d+)?)?)-(?:bin|all)\.zip""")
    private val AGP_MODULE = Regex(
        """com\.android\.(?:application|library|dynamic-feature|test)[^\n]*?version[^\n]*?(\d+\.\d+\.\d+)""",
    )
    private val AGP_CLASSPATH = Regex("""com\.android\.tools\.build:gradle:(\d+\.\d+\.\d+)""")
    private val AGP_CATALOG_KEY = Regex("""(?m)^\s*agp\s*=\s*"(\d+\.\d+\.\d+)"""")
    private val COMPILE_SDK_DSL = Regex("""compileSdk\s*=\s*(\d+)""")
    private val COMPILE_SDK_GROOVY = Regex("""compileSdkVersion\s*[=(]?\s*(\d+)""")
    private val BUILD_TOOLS = Regex("""buildToolsVersion\s*[=(]?\s*"(\d+(?:\.\d+){1,2})"""")

    /** `distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip` */
    fun parseDistributionUrl(text: String?): String? =
        text?.lines()?.firstOrNull { it.trimStart().startsWith("distributionUrl") }
            ?.substringAfter('=', "")?.trim()?.replace("\\:", ":")?.takeIf { it.isNotBlank() }

    /** 从发行版 URL 里取版本号；`-bin` / `-all` 后缀都认 */
    fun parseGradleVersion(distributionUrl: String?): String? =
        distributionUrl?.let { GRADLE_ZIP.find(it)?.groupValues?.getOrNull(1) }

    fun parseAgpVersion(vararg texts: String?): String? {
        for (text in texts) {
            if (text == null) continue
            val hit = AGP_MODULE.find(text) ?: AGP_CLASSPATH.find(text) ?: AGP_CATALOG_KEY.find(text)
            if (hit != null) return hit.groupValues[1]
        }
        return null
    }

    fun parseCompileSdk(vararg texts: String?): Int? {
        for (text in texts) {
            if (text == null) continue
            val hit = COMPILE_SDK_DSL.find(text) ?: COMPILE_SDK_GROOVY.find(text)
            if (hit != null) return hit.groupValues[1].toIntOrNull()
        }
        return null
    }

    fun parseBuildTools(vararg texts: String?): String? {
        for (text in texts) {
            if (text == null) continue
            val hit = BUILD_TOOLS.find(text)
            if (hit != null) return hit.groupValues[1]
        }
        return null
    }

    /** 配置表里查不到时的保守取值：宁可给一个能跑的版本，也不要空着让用户猜 */
    fun gradleForAgp(agpVersion: String): String? {
        val parts = agpVersion.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return GRADLE_FOR_AGP["$major.$minor"]
            ?: if (major >= AGP_MAJOR_GRADLE_9) FALLBACK_GRADLE_9 else FALLBACK_GRADLE_8
    }

    /** 读一个工程目录，产出构建所需的全部版本信息。缺文件一律按"没识别到"处理。 */
    fun probe(projectDir: File): ProjectToolchain {
        val wrapperProps = read(File(projectDir, "gradle/wrapper/gradle-wrapper.properties"))
        val rootBuild = read(File(projectDir, "build.gradle.kts")) ?: read(File(projectDir, "build.gradle"))
        val settings = read(File(projectDir, "settings.gradle.kts"))
            ?: read(File(projectDir, "settings.gradle"))
        val versionCatalog = read(File(projectDir, "gradle/libs.versions.toml"))
        val moduleBuild = findAndroidModuleBuild(projectDir)

        val agp = parseAgpVersion(rootBuild, settings, versionCatalog, moduleBuild)
        val wrapperVersion = parseGradleVersion(parseDistributionUrl(wrapperProps))
        val gradle = wrapperVersion ?: agp?.let { gradleForAgp(it) }
        val compileSdk = parseCompileSdk(moduleBuild, rootBuild) ?: DEFAULT_COMPILE_SDK
        val buildTools = parseBuildTools(moduleBuild) ?: "$compileSdk.0.0"

        return ProjectToolchain(
            gradleVersion = gradle.orEmpty(),
            gradleSource = when {
                wrapperVersion != null -> "wrapper"
                gradle != null -> "agp"
                else -> "fallback"
            },
            agpVersion = agp,
            compileSdk = compileSdk,
            buildTools = buildTools,
            projectDir = projectDir,
        )
    }

    /**
     * 找承载 Android 插件的模块构建脚本。
     *
     * 优先 `app/`，否则扫一级子目录里第一个声明了 com.android 的模块脚本——
     * 设备端构建的惯例题是单模块工程，再深的目录结构不猜。
     */
    private fun findAndroidModuleBuild(projectDir: File): String? {
        val dedicated = read(File(projectDir, "app/build.gradle.kts"))
            ?: read(File(projectDir, "app/build.gradle"))
        if (dedicated != null) return dedicated
        val children = projectDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        return children
            .map { read(File(it, "build.gradle.kts")) ?: read(File(it, "build.gradle")) }
            .firstOrNull { it != null && it.contains("com.android") }
    }

    private fun read(file: File): String? =
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()
}
