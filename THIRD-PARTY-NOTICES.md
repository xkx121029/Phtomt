# 第三方代码与许可证声明（Third-Party Notices）

## 随仓库分发的第三方源码

本软件包含以下第三方源码，随本仓库一并分发，各自的许可证与版权声明保留在对应目录内：

- **Shizuku API**（目录 `third_party/shizuku/api`，构建模块 `:shizuku-aidl` / `:shizuku-shared` /
  `:shizuku-api` / `:shizuku-provider` / `:shizuku-server-shared` / `:shizuku-rish`）
  - 来源：https://github.com/RikkaApps/Shizuku-API
  - 许可证：MIT License，Copyright (c) 2021 RikkaW
  - 完整许可证文本：`third_party/shizuku/api/LICENSE`
- **Shizuku server / starter / common**（目录 `third_party/shizuku`，构建模块 `:shizuku-server` /
  `:shizuku-starter` / `:shizuku-common`）
  - 来源：https://github.com/RikkaApps/Shizuku
  - 许可证：Apache License 2.0
  - 完整许可证文本：`third_party/shizuku/LICENSE`
  - 说明：Shizuku 主程序（server/starter/common）源码经少量修改后内嵌进本应用 APK
    （修改点见各源码文件内「vendor 适配」注释），由无线 ADB 以 shell uid 从本 APK 拉起，
    设备上无需安装 Shizuku 官方 App；rish 模块裁掉了原生 librish.so，仅保留 Java 层 API
