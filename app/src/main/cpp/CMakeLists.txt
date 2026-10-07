cmake_minimum_required(VERSION 3.22.1)
project(visionbridge LANGUAGES CXX)

# 视觉推理 JNI 垫片：运行时加载 libmtmd/libllama，向 Kotlin 暴露图像识别接口。
# 通过 dlsym 动态解析符号，避免在编译期链接 jniLibs 中的 .so（防止 Android 链接器
# 因 symbol visibility 绑定到错误副本）。垫片自身仅依赖 libc/libdl。

set(VISION_BRIDGE_SOURCES
    vision_bridge.cpp
    )

add_library(visionbridge SHARED ${VISION_BRIDGE_SOURCES})

target_compile_features(visionbridge PRIVATE cxx_std_17)

target_include_directories(visionbridge PRIVATE
    # llama.cpp 头文件（仅用于常量/结构体布局，不链接）；cpp → main → src → app → 项目根
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llamacpp/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llamacpp/ggml/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llamacpp/tools/mtmd"
    )

target_link_libraries(visionbridge PRIVATE log dl)
