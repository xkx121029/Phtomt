# Provider 无 UI、无反射依赖面（vendored Shizuku server 保留自身规则）。
# AIDL 生成类与 Parcelable 保持不被混淆
-keep class com.phoneagent.provider.** { *; }
