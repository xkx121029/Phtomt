# Provider 无 UI、无反射依赖面（vendored Shizuku server 保留自身规则）。
# AIDL 生成类与 Parcelable 保持不被混淆
-keep class com.phoneagent.provider.** { *; }

# 内嵌 Shizuku server 由 app_process 以类名字符串拉起
# （--nice-name=shizuku_server_<pkg> rikka.shizuku.server.ShizukuService），
# 代码里没有任何静态引用，R8 会把整棵 server 树剥掉——release 包从 2.8MB 掉到 260KB，
# 安装后「内置 Shizuku」永远起不来。starter 的 UserService 启动器与 api 侧 AIDL
# 回调同样走反射/类名路径，一并整包保留。
-keep class rikka.** { *; }
-keep class moe.shizuku.** { *; }

# server 以 shell uid 在 app_process 里运行，直接引用 framework 隐藏 API
# （ServiceManager / SystemProperties / IPermissionManager / IWindowManager /
# IAppOpsService / IActivityManager …）。这些类在 SDK 里不可见，R8 解析不到
# 会直接编译失败；运行时由 boot classpath 提供，故只做 dontwarn。
-dontwarn android.os.**
-dontwarn android.permission.**
-dontwarn android.view.**
-dontwarn android.content.**
-dontwarn android.app.**
-dontwarn com.android.internal.**
-dontwarn rikka.**
