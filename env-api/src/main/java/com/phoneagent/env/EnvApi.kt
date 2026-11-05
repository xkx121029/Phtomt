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

    /** 构建任务名，默认 assembleDebug */
    const val KEY_TASK = "task"

    // ---- stateJson 键 ----
    const val STATE_LINUX = "linux"
    const val STATE_JDK = "jdk"
    const val STATE_GRADLE = "gradle"
    const val STATE_SDK = "sdk"
    const val STATE_AAPT2 = "aapt2"
    const val STATE_INSTALLED_AT = "installedAt"

    /** 步骤状态：[IEnvCallback.onStep] 的 state 取值 */
    const val STEP_START = "start"
    const val STEP_OK = "ok"
    const val STEP_FAIL = "fail"
}
