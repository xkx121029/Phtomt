# 石墨主题证据帧

本组帧用于「石墨（Graphite）主题 + 经典主题可回退」的视觉走查，对应 `ui/theme/Graphite.kt`、
`ui/theme/ThemeSkin.kt` 与设置页「主题皮肤」开关。只作为**模拟器观感核对**，
不是真机验收结论。

## 取证环境

- 设备：Android Emulator `happyclaw-test`，x86_64，Android 13（API 33），1080x2400
- 光栅：`-gpu swiftshader_indirect`（软件光栅，headless）
- 构建：`./gradlew :app:assembleDebug -PincludeX86_64=true`
  （该属性只给 debug 变体追加 x86_64 ABI，默认产物仍是纯 arm64-v8a；
  该变体不含端侧 `.so`，仅用于 UI 观感核对）
- 字体：除 `graphite-06-agent-font130.png` 外均为系统默认 1.0 倍

## 帧清单

| 文件 | 主题 / 深浅 | 内容 |
| --- | --- | --- |
| `graphite-01-home-light.png` | 石墨 · 浅色 | 主页：冷灰底 + 白卡、靛蓝 Hero、状态分隔 |
| `graphite-02-settings-light.png` | 石墨 · 浅色 | 设置首页：分区卡、图标底盘、权限入口的冷调统一 |
| `graphite-03-agent-dark.png` | 石墨 · 深色 | Agent：输入浮层、提示条、深色下的卡片层次 |
| `graphite-04-theme-dark.png` | 石墨 · 深色 | 主题页：皮肤选项、预览块、分段选项卡选中态 |
| `graphite-05-home-dark.png` | 石墨 · 深色 | 主页深色：底/卡明度关系与导航 |
| `graphite-06-agent-font130.png` | 石墨 · 浅色 · 字体 1.3× | Agent 在 1.3 倍字体下的换行与输入区自适应 |
| `legacy-01-home-light.png` | 经典 · 浅色 | 主页：纸感底色、大圆角浮卡、绿色品牌（回退基线） |
| `legacy-02-theme-dark.png` | 经典 · 深色 | 主题页：经典皮肤下的配色 / 框线分区，确认未被石墨影响 |

## 已知局限（不要据此下最终结论）

1. **模糊与光晕不可信**：软件光栅下 Haze 的 `blurRadius` 观感偏弱，页眉玻璃几乎呈实色板，
   品牌径向光晕偏淡；真机上这两处会更明显，需要在真机复核页眉玻璃与浮板边缘。
2. **动效掉帧**：模拟器无硬件加速，入场错峰与按压缩放在帧里体现不出来。
3. **API 33 限定**：Haze 的 `RenderEffect` 路径为 API 31+，API 30 及以下的降级（fallback tint）
   本次未实测；该降级代码路径未改动。
4. 端侧视觉引擎的 `.so` 仅 arm64，本 x86_64 变体不加载，Agent 的模型相关状态不代表真实能力。
