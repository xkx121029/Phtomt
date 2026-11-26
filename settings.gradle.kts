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

// ---------------------------------------------------------------------------
// 从 :app 抽出的纯逻辑模块（模块化 P0）。
//
// 包名不变、只换编译单元：`com.phoneagent.core.text` / `com.phoneagent.core.log` 仍在原包，
// 因此 :app 里这 45 处 import（core.text 22 + core.log 21 + AgentBudgets 2）一行都不用改。代价是这些包不再随 :app 的测试源集编译，
// 连带的三处闸门（detekt baseline、覆盖率、doc 锚点）必须各自跟着搬——见各模块
// build.gradle.kts 与 app/build.gradle.kts 里 checkPureLogicCoverage 的注释。
//
// :core:text 是纯 Kotlin JVM 模块（零 android.* 依赖）；
// :core:log 必须是 com.android.library（AppLog 直接用 android.util.Log）。
// ---------------------------------------------------------------------------
include(":core:text")
project(":core:text").projectDir = file("core/text")

include(":core:log")
project(":core:log").projectDir = file("core/log")

// ---------------------------------------------------------------------------
// 模块化 P1：`:domain`（纯模型）、`:core:security`（Keystore + 脱敏）、`:core:perf`（耗时打点）、
// `:data`（持久化层）。
//
// 依赖方向：app → data → core:security / core:text / core:log → domain，没有反向边。
// 这条顺序不是设计选择，是被实测逼出来的：`com.phoneagent.data` 的 11 个文件反向引用了
// `:app` 里 9 个包的类型（StepTrace / TaskSession / MemoryEvent / McpServerConfig /
// TemplateMatcher / TabPrefsCodec / HumanTranslator / SecretCodec+DataSanitizer /
// ModelCatalog+GlmDefaults / domain.model），而 `:app → :data` 是既有方向，
// 所以这些类型必须先下沉，否则 `:data` 与 `:app` 成环、一期根本编不过。
// ---------------------------------------------------------------------------
include(":domain")
project(":domain").projectDir = file("domain")

include(":core:security")
project(":core:security").projectDir = file("core/security")

include(":core:perf")
project(":core:perf").projectDir = file("core/perf")

include(":data")
project(":data").projectDir = file("data")

// ---------------------------------------------------------------------------
// 模块化 P2：`:ui:design` —— UI 设计层（`com.phoneagent.ui.theme` 整套主题令牌、
// `com.phoneagent.ui.components` 的共用组件、`com.phoneagent.ui.icons` 图标集）。
//
// 依赖方向只有一条：app → ui:design，下一期的 feature:coding → ui:design；
// `:ui:design` 自己不依赖本仓库任何模块（只有 Compose / Lucide / Haze），
// 所以它需要的那一个 UI 形态枚举 `AgentSurface` 必须先下沉到 `:domain`。
//
// 包名一个没改（仍是 ui.theme / ui.components / ui.icons），:app 里数百处 import 零改动；
// 代价是 `com.phoneagent.ui.components` 被两个模块各持一半，与 P1 下沉记录类型同一取舍。
// ---------------------------------------------------------------------------
include(":ui:design")
project(":ui:design").projectDir = file("ui/design")

// ---------------------------------------------------------------------------
// 模块化 P3 终章：`:feature:coding` —— Coding 模式整片切片（agent 宿主 / 工作区 /
// 设备桥 / 编码环境 / 预览 / 编辑器 Activity）。
//
// 依赖方向只有一条：app → feature:coding → { core:log, env-api, androidx.* }；
// **没有 feature:coding → app 这条边**，这是这一期唯一的硬约束（它一旦存在，
// 整片切片就又被钉死在宿主上，搬出来只剩形式意义）。
//
// 包名一个没改（仍是 com.phoneagent.coding.**，命名空间也是），因此 :app 里 9 个引用点
// 一行 import 都不用改，AndroidManifest 里 `.coding.*` 三条相对类名照旧解析。
// 代价是跨模块 `internal` 不再可见，三个原本给 `:app` 组合根用的符号已放开为 public。
// ---------------------------------------------------------------------------
include(":feature:coding")
project(":feature:coding").projectDir = file("feature/coding")

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

// 编码环境组件：独立 APK，targetSdk 28 承载 PRoot + Alpine（Android 10+ 的 W^X
// 只允许 targetSdk < 29 的应用 execve 私有目录文件，主应用 targetSdk 36 做不到）。
// 产物与 Provider 一样作为静态资产提交在 app/src/{debug,release}/assets/env/ 下，
// 改了 env 后执行 .\gradlew.bat :app:syncEnvAssets 同步。
include(":env")
project(":env").projectDir = file("env")

// 编码环境跨进程契约（IEnvProvider AIDL）：主应用与 env 组件共享同一份
include(":env-api")
project(":env-api").projectDir = file("env-api")

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