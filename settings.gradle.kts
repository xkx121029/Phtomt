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

// 权限 Provider 应用：承载无障碍 / 内置 Shizuku / Termux 三条通道的独立 APK。
// 主应用首次打开时引导安装（参考 Operit 的独立 Provider 方案），并通过 AIDL
// （com.phoneagent.provider.IDeviceProvider）远程调用。Provider 构建产物作为
// 静态资产提交在 app/src/{debug,release}/assets/provider/ 下，主应用构建不依赖
// :provider；改了 Provider 后执行 .\gradlew.bat :app:syncProviderAssets 同步。
include(":provider")
project(":provider").projectDir = file("provider")

// 跨进程契约（AIDL + Parcelable）：主应用与 Provider 共用同一份，避免序列化漂移
include(":provider-api")
project(":provider-api").projectDir = file("provider-api")

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

// Shizuku 主程序（server/starter）内嵌：第三方应用无需安装 Shizuku 官方 App，
// 由本应用 APK 内的 server 类以 app_process（shell uid）直接拉起。
// 以下五个模块 vendor 自 https://github.com/RikkaApps/Shizuku（main repo）：
//   common（BuildUtils/OsUtils 工具）、starter（UserService 启动器，也被 server 依赖）、
//   server-shared（Service/ClientManager 等服务端公共层）、rish（Java 层 API，已裁掉原生 so）、
//   server（ShizukuService 主入口，main() 由 app_process 调起）。
// 与上面四个客户端模块同理：upstream 由独立 settings.gradle 注入的 compileSdk/minSdk
// 改为各模块 build.gradle 显式声明；upstream 模块名（:aidl/:shared/:api…）改映射到
// 本工程的 :shizuku-* 命名空间。
include(":shizuku-common")
project(":shizuku-common").projectDir = file("third_party/shizuku/common")
include(":shizuku-starter")
project(":shizuku-starter").projectDir = file("third_party/shizuku/starter")
include(":shizuku-server-shared")
project(":shizuku-server-shared").projectDir = file("third_party/shizuku/api/server-shared")
include(":shizuku-rish")
project(":shizuku-rish").projectDir = file("third_party/shizuku/api/rish")
include(":shizuku-server")
project(":shizuku-server").projectDir = file("third_party/shizuku/server")