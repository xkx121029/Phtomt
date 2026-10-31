import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties

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
//      并且只要本次调用会产出 APK（assemble / bundle / build），就 +1 并回写。
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
// 不该平白吃掉一个版本号。
val apkBuilding = run {
    val names = gradle.startParameter.taskNames.map { it.lowercase(Locale.ROOT).substringAfterLast(':') }
    names.any { it == "build" || it.contains("assemble") || it.contains("bundle") }
}

val buildNumber: Int = run {
    val stored = readStoredBuildNumber()
    val injected = (findProperty("buildNumber") as? String)?.toIntOrNull()
    // 基线取「已记录值」与「git 提交数」的较大者：即使期间新增了 commit，
    // 版本号也只增不减（versionCode 回退会导致覆盖安装失败）。
    val base = maxOf(stored, gitCommitCount(), 0)
    val value = injected ?: (if (apkBuilding) base + 1 else maxOf(base, 1))
    if (apkBuilding && (injected == null || injected > stored)) {
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
    maxOf(value, 1)
}

// 子项目通过 rootProject.extra["buildNumber"] 读取
extra["buildNumber"] = buildNumber
