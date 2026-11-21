package com.phoneagent.env

/**
 * [IEnvProvider] 契约常量：主应用与编码环境组件共用同一份，避免两边各写一套导致漂移。
 */
object EnvApi {

    /** 契约版本：增删 [IEnvProvider] 方法时递增 */
    const val API_VERSION = 1

    /** 环境组件包名（与 :env 模块 applicationId 一致） */
    const val PACKAGE = "com.phoneagent.env"

    /** AIDL 服务 action（与组件 Manifest 中 EnvService 的 intent-filter 一致） */
    const val ACTION = "com.phoneagent.env.IEnvProvider"

    /** 绑定权限：signature 级，只有同签名的主应用能拿到 binder */
    const val PERMISSION = "com.phoneagent.permission.BIND_ENV"

    /** 存储权限引导页（组件没有常驻 UI，共享存储的运行时授权只能由它申请） */
    const val SETUP_ACTIVITY = "com.phoneagent.env.EnvSetupActivity"

    /** 请求操作：装 Linux 根文件系统 + JDK + Gradle + Android SDK + aapt2 兼容层 */
    const val OP_INSTALL = "install"

    /** 请求操作：在容器内执行一次 Gradle 构建 */
    const val OP_BUILD = "build"

    /** 请求操作：清掉下载缓存与解包中间产物（已装好的工具链原样保留） */
    const val OP_CLEAN = "clean"

    /** 请求操作：重置编码环境（rootfs + 全部工具链 + 缓存），回到未装配 */
    const val OP_RESET = "reset"

    // ---- 请求体键 ----
    const val KEY_OP = "op"

    /** 工程根目录的宿主绝对路径（容器内以相同路径绑定） */
    const val KEY_PROJECT = "project"

    /** Gradle 发行版版本号，如 "8.13"；为空时用安装记录里的值 */
    const val KEY_GRADLE = "gradle"

    /** AGP 版本号，只用于 UI 展示与依赖预取，安装不依赖它 */
    const val KEY_AGP = "agp"

    /** 工程 compileSdk，决定要装的 platforms;android-N */
    const val KEY_COMPILE_SDK = "compileSdk"

    /** Build Tools 版本，如 "36.0.0" */
    const val KEY_BUILD_TOOLS = "buildTools"

    /** 要安装的 JDK 大版本（主应用按工程 compileOptions / jvmTarget 与 AGP 最低要求推导） */
    const val KEY_JDK = "jdk"

    /** 构建任务名，默认 assembleDebug */
    const val KEY_TASK = "task"

    // ---- stateJson 键 ----
    const val STATE_LINUX = "linux"

    /** 人可读的 JDK 状态，如 "JDK 17.0.16" / "未安装" */
    const val STATE_JDK = "jdk"

    /** `java -version` 实测出来的版本号，如 "17.0.16" */
    const val STATE_JDK_VERSION = "jdkVersion"
    const val STATE_GRADLE = "gradle"

    /** `gradle --version` 实测出来的版本号，如 "8.13" */
    const val STATE_GRADLE_VERSION = "gradleVersion"

    /** `sdkmanager --list_installed` 实测出的平台，如 "android-35、android-36" */
    const val STATE_SDK = "sdk"

    /** `sdkmanager --list_installed` 实测出的 build-tools，如 "36.0.0" */
    const val STATE_BUILD_TOOLS = "buildTools"
    const val STATE_AAPT2 = "aapt2"
    const val STATE_INSTALLED_AT = "installedAt"

    /** 实测得到的可操作问题清单（用「；」分隔）；全部就绪时为空串 */
    const val STATE_PROBLEMS = "problems"
    /** 环境占用的磁盘（rootfs + 引导集 + 下载缓存 + 临时目录），格式化后的可读字符串 */
    const val STATE_DISK = "disk"

    /** 「清理缓存」能释放多少（下载缓存 + 临时目录 + 中断留下的半装目录） */
    const val STATE_DISK_RECLAIM = "reclaimable"

    /** 步骤状态：[IEnvCallback.onStep] 的 state 取值 */
    const val STEP_START = "start"
    const val STEP_OK = "ok"
    const val STEP_FAIL = "fail"
}
