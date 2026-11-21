package com.phoneagent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构建参数拼装的回归用例。
 *
 * 钉住的是"这两项配置不来自工程文件"这条不变量：主应用每次请求都会把工作区整份镜像覆盖到
 * 共享存储，写进工程 gradle.properties 的值活不过一次覆盖。谁把 aapt2 覆盖或 jvmargs 从
 * 命令行挪回文件里，这里必须红。
 */
class BuildInvocationTest {

    private fun plan(hasWrapper: Boolean) = BuildInvocation.plan(
        task = "assembleDebug",
        projectDir = "/storage/emulated/0/happyac-build/demo",
        hasWrapper = hasWrapper,
        jdkDir = "/opt/jdk17",
        gradleDir = "/opt/gradle",
        sdkDir = "/opt/android-sdk",
    )

    @Test
    fun 每次构建都在命令行带上aapt2覆盖与jvmargs() {
        listOf(true, false).forEach { hasWrapper ->
            val argv = plan(hasWrapper).argv
            assertTrue("argv=$argv", "-Pandroid.aapt2FromMavenOverride=/opt/aapt2" in argv)
            assertTrue("argv=$argv", "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8" in argv)
            assertTrue("argv=$argv", "--no-daemon" in argv)
            assertEquals("任务名必须排在最后", "assembleDebug", argv.last())
        }
    }

    @Test
    fun 工程自带gradlew与内置Gradle用同一组属性() {
        val owned = BuildInvocation.ownedProperties().map { it.first }
        listOf(true, false).forEach { hasWrapper ->
            val argv = plan(hasWrapper).argv
            owned.forEach { key ->
                assertTrue("$key 缺失：argv=$argv", argv.any { it.contains(key) })
            }
        }
    }

    @Test
    fun jvmargs取1536而不是工程里桌面端的4096() {
        val value = BuildInvocation.ownedProperties()
            .first { it.first == "org.gradle.jvmargs" }.second
        assertTrue("value=$value", value.contains("-Xmx1536m"))
        assertFalse("value=$value", value.contains("4096"))
    }

    @Test
    fun jvmargs用D传而不是P_这是实测唯一能压过工程文件的写法() {
        // 实测（Gradle 8.14.4）：工程 gradle.properties 写 -Xmx4096m 时，
        // 只有 -Dorg.gradle.jvmargs 能改出 1536m；用户级 gradle.properties 压不过工程级。
        val argv = plan(true).argv
        assertTrue("argv=$argv", argv.any { it.startsWith("-Dorg.gradle.jvmargs=") })
        assertFalse("argv=$argv", argv.any { it.startsWith("-Porg.gradle.jvmargs=") })
        // aapt2 覆盖是 AGP 读的 project property，必须是 -P
        assertTrue("argv=$argv", argv.any { it.startsWith("-Pandroid.aapt2FromMavenOverride=") })
    }

    @Test
    fun 用户级gradle目录固定且环境变量一致() {
        val env = plan(true).extraEnv
        assertEquals(BuildInvocation.GRADLE_USER_HOME, env["GRADLE_USER_HOME"])
        assertEquals("/opt/android-sdk", env["ANDROID_HOME"])
        assertTrue(env["GRADLE_OPTS"].orEmpty().contains("-Xmx1536m"))
        assertEquals("/opt/jdk17", env["JAVA_HOME"])
    }
}
