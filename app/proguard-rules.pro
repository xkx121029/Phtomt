# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.phoneagent.**$$serializer { *; }
-keepclassmembers class com.phoneagent.** { *** Companion; }
-keepclasseswithmembers class com.phoneagent.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# 端侧视觉 JNI：libvisionbridge 按包名+类名绑定符号（Java_com_phoneagent_device_vision_NativeVisionEngine_*），
# 类名/方法名被 R8 重命名后 dlsym 找不到入口，native 方法与类名都必须保留
-keep class com.phoneagent.device.vision.NativeVisionEngine { *; }