pluginManagement {
    repositories {
        // 国内镜像优先：google()/mavenCentral() 在国内直连经常只有几十 KB/s，
        // 会被放在末尾当兜底（镜像缺件时才回落）。两者内容同源，顺序不影响产物。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneAgent"
include(":app")

// Shizuku 客户端库本地源码集成（vendor 自 https://github.com/RikkaApps/Shizuku-API，
// 主仓库 https://github.com/RikkaApps/Shizuku 保留在 third_party/shizuku 作参考）。
// 原依赖 dev.rikka.shizuku:api / provider（Maven）已移除，改用以下四个本地模块：
//   provider（ShizukuProvider）→ api（Shizuku/Sui 门面）→ shared → aidl（IShizukuService 等 AIDL）
// upstream 的 compileSdk/minSdk 由其 settings 里的 com.android.settings 统一注入，
// 并入本工程后该机制不存在，因此在各模块 build.gradle 中显式声明（值与 upstream 一致）。
include(":shizuku-aidl")
project(":shizuku-aidl").projectDir = file("third_party/shizuku/api/aidl")
include(":shizuku-shared")
project(":shizuku-shared").projectDir = file("third_party/shizuku/api/shared")
include(":shizuku-api")
project(":shizuku-api").projectDir = file("third_party/shizuku/api/api")
include(":shizuku-provider")
project(":shizuku-provider").projectDir = file("third_party/shizuku/api/provider")