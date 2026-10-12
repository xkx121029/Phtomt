plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    // Shizuku server 内嵌（third_party/shizuku）所需的 hidden API 精化插件
    alias(libs.plugins.rikka.refine) apply false
}