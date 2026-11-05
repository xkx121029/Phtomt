# 设备内编码手册（happyac-coding）

这份手册描述 HPA（happy_phone agent）设备内编码环境的真实结构、唯一入口命令，以及构建前后必须遵守的约定。要做设备侧 Android 相关工作时按本手册执行，不要凭经验假设。

## 1. 环境由哪些部分组成

| 组成 | 位置 | 谁负责 |
| --- | --- | --- |
| Node 运行时（libnode.so） | 主应用的 nativeLibraryDir（随 APK 打包） | 主应用，无需配置 |
| 编码 agent（agent-core） | 主应用 assets/agent-host，启动时解压 | 主应用，无需配置 |
| PRoot + Alpine Linux、JDK 17、Gradle、Android SDK、aapt2 兼容层 | 独立组件 com.phoneagent.env | 由 `coding_env` setup 驱动 |
| 工程工作区 | 应用私有目录 `filesDir/workspaces/<name>` | 文件工具与 shell |
| 构建镜像 | `/storage/emulated/0/happyac-build/<工程名>` | `coding_env` 构建前自动镜像 |

关键事实：

- 组件是另一个 uid，读不到主应用私有目录，所以构建前工程会被复制到共享存储。
- 组件 targetSdk 28，需要旧存储权限；主应用需要「所有文件访问」才能写共享存储。
- shell 是 mksh：没有 root、没有 npm、没有系统包管理器。

## 2. 唯一入口：coding_env

| action | 作用 | 何时用 |
| --- | --- | --- |
| `status` | 返回 ready、各部件状态、任务进度 | 每次开始设备侧工作，或不确定环境是否可用时 |
| `setup` | 逐步推进环境，可重复执行 | 构建前，以及 status 显示 ready=false 时 |
| `build` | 在容器里执行 Gradle（默认 `assembleDebug`） | ready=true 之后 |
| `cancel` | 停止当前安装/构建任务 | 用户要求停止，或任务长时间无进展 |

规则：

1. 构建前先 `status`；ready=false 时先 `setup`，不要直接 `build`（只会得到一句 not-ready 和原因）。
2. `setup` 返回 `needsUserAction=true` 表示应用已经拉起系统页面（安装器或授权页）。这时停下来告诉用户要点什么，等用户完成后重跑 `setup`。
3. `setup` 可以反复执行，已完成的步骤会跳过。不要因为它没一次成功就换别的方法或手写脚本。
4. 只有 `coding_env` 报告 ready=true、且 `build` 返回 stage=built 时，才能说「构建成功」。否则如实说明卡在哪一步。

## 3. 用户通常只需亲手做三件事

1. 安装「编码环境」组件（系统安装弹窗点「安装」）。
2. 给主应用开启「所有文件访问」（私有工作区要镜像到共享存储）。
3. 给「编码环境」组件授予存储权限（组件授权页点「允许」）。

每一步之后都重跑 `coding_env` setup。这三步只能由用户在界面上完成，不要试图用 shell 模拟，也不要跳过。

## 4. 工作区与构建镜像

- `workspace_overview` 与文件工具看到的目录就是当前工作区。
- 构建时工程会自动镜像到 `/storage/emulated/0/happyac-build/<工作区目录名>`；构建结束后产物留在这个镜像工程里。
- 不要把密钥、私有大文件放进工程再构建——它们会被复制到共享存储。
- 镜像只补新增/变更的文件（同名同大小跳过），所以改完代码直接重新 `build` 即可，不需要手动清理。

## 5. 工程识别与 Gradle 版本

构建前宿主会读工程文件决定 Gradle 与 SDK 版本：

- Gradle 优先取 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl`；
- 没有 wrapper 时按 AGP 版本查官方配套表；
- compileSdk 取模块 `build.gradle(.kts)` 的 `compileSdk`；buildTools 未声明时用 `<compileSdk>.0.0`。

写工程时遵守：

- 必须有 `settings.gradle(.kts)`，以及至少一个声明了 `com.android.application` 或 `com.android.library` 的模块脚本。
- 不要新增 `local.properties`：SDK 路径由容器提供，手写错误路径会直接构建失败。
- aapt2 兼容层由容器通过 `android.aapt2FromMavenOverride` 注入，不要覆盖这个属性。

## 6. 产物位置

- 构建成功后 APK 位于镜像工程的 `build/outputs/apk/**/*.apk`（debug 通常在 `build/outputs/apk/debug/`）。
- `coding_env` build 的返回里会给出产物绝对路径；报告给用户时用这个路径，不要猜。
- 需要把产物放回私有工作区时，用 shell 的 `cp` 复制过去，并说明这是复制而不是构建该路径下的产物。

## 7. 什么能在 shell 里做，什么不能

可以 `run_command`：`ls`、`cat`、`grep`、`find`、`sed`、`node`（内置运行时）、简单的 mksh 管道。

不要用 shell 做：

- `gradle` / `./gradlew` 构建（必须在容器里，用 `coding_env`）；
- `npm install` / `pip install` / `apt`（没有包管理器，也不支持网络安装）；
- `sudo`、修改系统设置、安装 APK（安装类动作只能交给用户确认）。

## 8. 常见故障与照做动作

| 返回的 stage / 现象 | 含义 | 下一动作 |
| --- | --- | --- |
| `runtime-missing` | 安装包里没有 libnode.so，APK 不完整 | 让用户重新安装完整版 HPA |
| `component-install` | 安装器已拉起 | 等用户点「安装」，然后重跑 setup |
| `all-files-access` | 已打开「所有文件访问」授权页 | 等用户打开开关，然后重跑 setup |
| `env-storage` | 已打开组件存储授权页 | 等用户点「允许」，然后重跑 setup |
| `not-ready` | 构建前置条件缺一项 | 按 message 指出的缺口先 setup |
| `installing` / `building` | 等待窗口到了但任务仍在跑 | 用 `status` 查进度，不要重复发起 |
| `build-failed` | Gradle 或工程本身出错 | 读 message 与日志尾巴；需要全量日志时看「设置 → 编码环境」页 |
| `busy` | 已有任务在执行 | 等它结束，必要时 `cancel` |

补充：

- 构建报 aapt2 失败：先确认没有覆盖 `android.aapt2FromMavenOverride`。
- Gradle 版本报错：检查 wrapper 写的是否为官方存在的发行版版本号。
- 任务超时：安装要下载 JDK/Gradle/SDK，体积很大；把 `timeoutSec` 提到 3600 再等，不要连续重试。

## 9. 汇报规则

- 只报告实际看到的结果：ready、stage、产物路径、失败原因。
- 没有验证过的构建不要说「应该能跑」。
- 失败时给出用户能执行的下一步（通常是重跑 setup，或改某个工程文件）。
- 用用户的语言作答。
