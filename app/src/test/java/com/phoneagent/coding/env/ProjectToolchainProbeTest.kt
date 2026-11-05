package com.phoneagent.coding.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 工程版本自动识别的回归用例。
 *
 * 这些解析决定了「一键安装该下哪个 Gradle」——版本猜错时设备端只会得到一句
 * 难以定位的 Gradle 报错（或干脆下载不到发行版），所以把工程里可能的几种写法
 * （kts / groovy / version catalog / wrapper 有无）都固化成用例。
 */
class ProjectToolchainProbeTest {

    @Test
    fun parseDistributionUrl_取出地址并还原转义冒号() {
        val props = """
            distributionBase=GRADLE_USER_HOME
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
            zipStoreBase=GRADLE_USER_HOME
        """.trimIndent()
        assertEquals(
            "https://services.gradle.org/distributions/gradle-8.13-bin.zip",
            ProjectToolchainProbe.parseDistributionUrl(props),
        )
        assertNull(ProjectToolchainProbe.parseDistributionUrl(null))
        assertNull(ProjectToolchainProbe.parseDistributionUrl("distributionBase=GRADLE_USER_HOME"))
    }

    @Test
    fun parseGradleVersion_支持bin与all后缀() {
        assertEquals(
            "8.13",
            ProjectToolchainProbe.parseGradleVersion(
                "https://services.gradle.org/distributions/gradle-8.13-bin.zip",
            ),
        )
        assertEquals(
            "9.6.0",
            ProjectToolchainProbe.parseGradleVersion(
                "https://services.gradle.org/distributions/gradle-9.6.0-all.zip",
            ),
        )
        assertNull(ProjectToolchainProbe.parseGradleVersion("https://example.com/gradle.zip"))
    }

    @Test
    fun parseAgpVersion_覆盖kts与groovy与版本目录三种写法() {
        val kts = """plugins { id("com.android.application") version "8.6.1" apply false }"""
        val groovy = """classpath 'com.android.tools.build:gradle:8.2.2'"""
        val catalog = """
            [versions]
            agp = "8.11.0"
        """.trimIndent()
        assertEquals("8.6.1", ProjectToolchainProbe.parseAgpVersion(kts))
        assertEquals("8.2.2", ProjectToolchainProbe.parseAgpVersion(groovy))
        assertEquals("8.11.0", ProjectToolchainProbe.parseAgpVersion(catalog))
        assertNull(ProjectToolchainProbe.parseAgpVersion("plugins { }"))
    }

    @Test
    fun parseCompileSdk与BuildTools_两种DSL写法() {
        assertEquals(35, ProjectToolchainProbe.parseCompileSdk("android { compileSdk = 35 }"))
        assertEquals(34, ProjectToolchainProbe.parseCompileSdk("compileSdkVersion(34)"))
        assertNull(ProjectToolchainProbe.parseCompileSdk("android { }"))
        assertEquals(
            "35.0.0",
            ProjectToolchainProbe.parseBuildTools("""buildToolsVersion "35.0.0""""),
        )
        assertNull(ProjectToolchainProbe.parseBuildTools("android { }"))
    }

    @Test
    fun gradleForAgp_命中配套表与未知版本兜底() {
        assertEquals("8.7", ProjectToolchainProbe.gradleForAgp("8.6.1"))
        assertEquals("8.13", ProjectToolchainProbe.gradleForAgp("8.13.2"))
        assertEquals("9.6.0", ProjectToolchainProbe.gradleForAgp("9.5.0"))
        // 表里没有的 8.x 取最新的 8 系（不低于配套要求），9.x 同理
        assertEquals("8.13", ProjectToolchainProbe.gradleForAgp("7.4.2"))
        assertEquals("9.6.0", ProjectToolchainProbe.gradleForAgp("9.9.0"))
        assertNull(ProjectToolchainProbe.gradleForAgp("unknown"))
    }

    @Test
    fun probe_wrapper优先于配套表() {
        // AGP 8.6 的配套表给 8.7，但工程 wrapper 明确要 8.9：wrapper 才是工程的真实选择
        val project = tempProject(
            "gradle/wrapper/gradle-wrapper.properties" to
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n",
            "build.gradle.kts" to
                """plugins { id("com.android.application") version "8.6.1" apply false }""",
            "app/build.gradle.kts" to """
                android {
                    compileSdk = 35
                    buildToolsVersion "35.0.0"
                }
            """.trimIndent(),
        )
        val result = ProjectToolchainProbe.probe(project)
        assertEquals("8.9", result.gradleVersion)
        assertEquals("wrapper", result.gradleSource)
        assertEquals("8.6.1", result.agpVersion)
        assertEquals(35, result.compileSdk)
        assertEquals("35.0.0", result.buildTools)
        assertTrue(result.ready)
    }

    @Test
    fun probe_无wrapper时按AGP配套表推算() {
        val project = tempProject(
            "settings.gradle.kts" to
                """plugins { id("com.android.application") version "8.11.0" apply false }""",
            "app/build.gradle.kts" to """android { compileSdk = 36 }""",
        )
        val result = ProjectToolchainProbe.probe(project)
        assertEquals("8.13", result.gradleVersion)
        assertEquals("agp", result.gradleSource)
        assertEquals(36, result.compileSdk)
        // 未声明 buildTools 时按 compileSdk 推一个同版本号，交给 sdkmanager 去装
        assertEquals("36.0.0", result.buildTools)
    }

    @Test
    fun probe_空目录视为未识别() {
        val project = tempProject()
        val result = ProjectToolchainProbe.probe(project)
        assertEquals("", result.gradleVersion)
        assertEquals("fallback", result.gradleSource)
        assertNull(result.agpVersion)
    }

    private fun tempProject(vararg files: Pair<String, String>): File {
        val root = Files.createTempDirectory("toolchain-probe").toFile()
        files.forEach { (path, content) ->
            val target = File(root, path)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        return root
    }
}
