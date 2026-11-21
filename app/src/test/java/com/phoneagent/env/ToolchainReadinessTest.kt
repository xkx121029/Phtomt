package com.phoneagent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就绪判定的回归用例。
 *
 * 钉住两条：[EnvApi.STATE_PROBLEMS] 里组件实测出的原因必须原样透传（不许被吞成"环境异常"），
 * 以及工程要求的 compileSdk / build-tools 与实际装的不一致时必须点名"要哪个、装了哪个"。
 */
class ToolchainReadinessTest {

    private fun state(
        linux: String = "就绪",
        jdk: String = "JDK 17.0.16",
        gradle: String = "8.13",
        sdk: String = "android-35",
        buildTools: String = "35.0.0",
        aapt2: String = "已配置",
        problems: String = "",
    ): Map<String, String> = mapOf(
        EnvApi.STATE_LINUX to linux,
        EnvApi.STATE_JDK to jdk,
        EnvApi.STATE_GRADLE to gradle,
        EnvApi.STATE_SDK to sdk,
        EnvApi.STATE_BUILD_TOOLS to buildTools,
        EnvApi.STATE_AAPT2 to aapt2,
        EnvApi.STATE_PROBLEMS to problems,
    )

    @Test
    fun 版本与工程完全一致时视为就绪() {
        val report = ToolchainReadiness.evaluate(state(), compileSdk = 35, buildTools = "35.0.0")
        assertTrue(report.summary, report.ready)
        assertEquals(emptyList<String>(), report.problems)
    }

    @Test
    fun 平台版本与工程不一致时点名要哪个装了哪个() {
        val report = ToolchainReadiness.evaluate(state(sdk = "android-34、android-35"), 36, "35.0.0")
        assertFalse(report.ready)
        assertTrue(report.summary, report.summary.contains("工程要 android-36"))
        assertTrue(report.summary, report.summary.contains("android-34、android-35"))
    }

    @Test
    fun 组件实测出的原因原样透传() {
        val report = ToolchainReadiness.evaluate(
            state(problems = "java -version 执行失败（退出码 1）：/opt/jdk17/bin/java 不存在"),
        )
        assertFalse(report.ready)
        assertTrue(report.summary, report.summary.contains("java -version 执行失败（退出码 1）"))
    }

    @Test
    fun Linux未装配时只给这一条原因() {
        val report = ToolchainReadiness.evaluate(state(linux = "未装配"))
        assertEquals(listOf("Linux 根文件系统未装配：先执行一键安装"), report.problems)
    }

    @Test
    fun buildTools不一致也算未就绪() {
        val report = ToolchainReadiness.evaluate(state(buildTools = "34.0.0"), 35, "36.0.0")
        assertFalse(report.ready)
        assertTrue(report.summary, report.summary.contains("工程要 build-tools 36.0.0"))
        assertTrue(report.summary, report.summary.contains("34.0.0"))
    }

    @Test
    fun aapt2未配置时给出可操作原因() {
        val report = ToolchainReadiness.evaluate(state(aapt2 = "无法执行"), 35, "35.0.0")
        assertFalse(report.ready)
        assertTrue(report.summary, report.summary.contains("aapt2"))
    }

    @Test
    fun 平台清单从未安装占位里读不出任何版本() {
        assertEquals(emptyList<Int>(), ToolchainReadiness.platforms(state(sdk = "未安装")))
        assertEquals(listOf(35, 36), ToolchainReadiness.platforms(state(sdk = "android-35、android-36")))
        assertEquals(emptyList<String>(), ToolchainReadiness.buildToolsInstalled(state(buildTools = "未安装")))
    }
}
