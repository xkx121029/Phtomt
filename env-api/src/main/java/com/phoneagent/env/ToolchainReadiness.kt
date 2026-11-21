package com.phoneagent.env

/**
 * 工具链就绪判定：把组件实测出来的**事实**（`stateJson` 里的 [EnvApi.STATE_*]）与工程声明的
 * **要求**对起来。
 *
 * 组件侧负责"真跑一次"（`java -version` / `gradle --version` / `sdkmanager --list_installed` /
 * `aapt2 version`），这里只做对齐与措辞，不猜任何东西。两边共用同一份实现：容器内构建前的自检、
 * Agent 看到的 `coding_env status` 与设置页显示的结论不会各说一套。
 *
 * 放在 env-api 而不是某一侧：主应用（`com.phoneagent`）和环境组件（`com.phoneagent.env`）是两个
 * 进程，判定逻辑一旦分叉，"组件认为就绪、主应用认为不就绪"这种最难查的偏差就会出现。
 */
object ToolchainReadiness {

    /** [EnvApi.STATE_LINUX] 的就绪取值 */
    const val READY_LINUX = "就绪"

    /** [EnvApi.STATE_AAPT2] 的就绪取值 */
    const val READY_AAPT2 = "已配置"

    /** 各键"没有"的取值 */
    const val MISSING = "未安装"

    private val SEPARATOR = "；"
    private val PLATFORM = Regex("""android-(\d+)""")

    /** [problems] 已去重，[summary] 是拼给人看的一句话 */
    data class Report(val problems: List<String>) {
        val ready: Boolean get() = problems.isEmpty()
        val summary: String get() = problems.joinToString(SEPARATOR)
    }

    /**
     * @param env 组件 stateJson 解析成键值对
     * @param compileSdk 当前工程要求的 compileSdk；null 表示不知道，只校验组件自报的部分
     * @param buildTools 当前工程要求的 build-tools；null / 空表示不校验
     */
    fun evaluate(env: Map<String, String>, compileSdk: Int? = null, buildTools: String? = null): Report {
        if (env[EnvApi.STATE_LINUX] != READY_LINUX) {
            return Report(listOf("Linux 根文件系统未装配：先执行一键安装"))
        }
        val problems = LinkedHashSet<String>()
        env[EnvApi.STATE_PROBLEMS].orEmpty()
            .split(SEPARATOR)
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .forEach(problems::add)

        val platforms = platforms(env)
        if (compileSdk != null) {
            when {
                platforms.isEmpty() -> problems += "Android SDK 平台未安装"
                compileSdk !in platforms -> problems +=
                    "工程要 android-$compileSdk，设备装的是 ${platforms.joinToString("、") { "android-$it" }}"
            }
        }
        val installedBuildTools = buildToolsInstalled(env)
        if (!buildTools.isNullOrBlank() && installedBuildTools.isNotEmpty() &&
            buildTools.trim() !in installedBuildTools
        ) {
            problems += "工程要 build-tools ${buildTools.trim()}，设备装的是 " +
                installedBuildTools.joinToString("、")
        }
        if (env[EnvApi.STATE_AAPT2] != READY_AAPT2 && problems.none { it.contains("aapt2") }) {
            problems += "aapt2 兼容层未配置：arm64 设备上无法执行 x86_64 版 aapt2"
        }
        return Report(problems.toList())
    }

    /** [EnvApi.STATE_SDK] 里实测到的平台版本号，如 [35, 36] */
    fun platforms(env: Map<String, String>): List<Int> =
        PLATFORM.findAll(env[EnvApi.STATE_SDK].orEmpty())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .sorted()
            .toList()

    /** [EnvApi.STATE_BUILD_TOOLS] 里实测到的版本号；"未安装"这类占位不算 */
    fun buildToolsInstalled(env: Map<String, String>): List<String> =
        env[EnvApi.STATE_BUILD_TOOLS].orEmpty()
            .split('、')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != MISSING }
}
