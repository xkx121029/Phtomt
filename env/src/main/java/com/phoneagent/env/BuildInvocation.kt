package com.phoneagent.env

/**
 * 一次 Gradle 构建的 argv 与环境变量拼装。
 *
 * ## 为什么必须在调用时传，而不是写进工程的 gradle.properties
 * 主应用每次请求（构建也一样）都会先把工作区整份镜像到共享存储
 * （`CodingEnvClient.stageProject` → `copyTree`）。安装阶段写进工程 gradle.properties 的
 * 两个键，下一次构建前就被镜像覆盖擦掉了：aapt2 覆盖路径丢失后 AGP 会退回 Maven 里那份
 * x86_64 版 aapt2（arm64 设备上必失败），jvmargs 丢失后桌面端的 `-Xmx4096m` 直接接管，
 * 设备内存不够会被 OOM killer 杀掉。命令行参数不落在任何会被覆盖的文件里。
 *
 * 命令行优先级高于 gradle.properties（Gradle 官方优先级表：Command-line > 文件），
 * 所以工程里写死的值一定输给这里。**实测过**（Gradle 8.14.4，工程 gradle.properties 写
 * `org.gradle.jvmargs=-Xmx4096m`，任务里读 `Runtime.maxMemory()`）：
 *   - 直接跑：4096 MB（工程文件生效）
 *   - 加 `-Dorg.gradle.jvmargs=-Xmx1536m`：1536 MB ✅
 *   - 只在该用户级 `GRADLE_USER_HOME/gradle.properties` 写 1024m：仍是 4096 MB ❌
 * 即"用户级配置文件优先于工程级"对 jvmargs 并不成立，所以唯一的抗覆盖路径就是命令行。
 */
object BuildInvocation {

    /** aapt2 兼容层（qemu-x86_64 + x86_64 glibc sysroot 的包装脚本） */
    const val AAPT2_OVERRIDE = "/opt/aapt2"

    /** 设备内存远小于桌面端：工程里常见的 -Xmx4096m 在手机上会被 OOM killer 杀掉 */
    const val JVM_ARGS = "-Xmx1536m -Dfile.encoding=UTF-8"

    /** 设备端一次构建就跑完，常驻守护进程只会白占内存 */
    const val DAEMON = "false"

    const val GRADLE_USER_HOME = "/root/.gradle"

    /** 安装器拥有的 Gradle 属性：这些键由安装器决定，不接受工程里已有的值 */
    fun ownedProperties(): List<Pair<String, String>> = listOf(
        "android.aapt2FromMavenOverride" to AAPT2_OVERRIDE,
        "org.gradle.jvmargs" to JVM_ARGS,
        "org.gradle.daemon" to DAEMON,
    )

    data class Plan(val argv: List<String>, val extraEnv: Map<String, String>)

    /**
     * 拼一次构建调用。[hasWrapper] 决定用工程自带 gradlew 还是内置 Gradle；
     * 两者都带上同一组属性，行为一致。
     */
    fun plan(
        task: String,
        projectDir: String,
        hasWrapper: Boolean,
        jdkDir: String,
        gradleDir: String,
        sdkDir: String,
    ): Plan {
        val launcher = if (hasWrapper) {
            listOf("/bin/sh", "gradlew")
        } else {
            listOf("/bin/sh", "$gradleDir/bin/gradle", "-p", projectDir)
        }
        val options = ownedProperties().map { (key, value) ->
            // android.* 是 AGP 读的 project property；org.gradle.* 是 Gradle 属性
            if (key.startsWith("android.")) "-P$key=$value" else "-D$key=$value"
        }
        return Plan(
            argv = launcher + options + listOf("--no-daemon", task),
            extraEnv = javaEnv(jdkDir, gradleDir) + mapOf(
                "ANDROID_HOME" to sdkDir,
                "ANDROID_SDK_ROOT" to sdkDir,
                "GRADLE_USER_HOME" to GRADLE_USER_HOME,
                // 第三道保险：Gradle 启动器 JVM 直接吃这两个参数
                "GRADLE_OPTS" to JVM_ARGS,
            ),
        )
    }

    /** JVM 相关的固定环境：JDK 与 PATH 由安装器决定，不依赖工程里的任何配置 */
    fun javaEnv(jdkDir: String, gradleDir: String): Map<String, String> = mapOf(
        "JAVA_HOME" to jdkDir,
        "PATH" to "$jdkDir/bin:$gradleDir/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )
}
