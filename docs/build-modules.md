# 可选构建模块（Optional Build Modules）

本项目的构建环境在默认 Android 构建之外，提供四类**用户可选**的扩展模块：
Android 平台构建支持、Vue 开发服务器集成、Git 版本控制工具链、GitHub 仓库关联与同步。

所有模块都由同一个开关文件控制，**默认不参与任何构建任务图**：开启或关闭只影响
「你主动调用的那几个任务」，`assembleDebug` / `build` / `check` 的行为、产物与耗时完全不变。

---

## 1. 设计原则

| 原则 | 具体实现 |
|---|---|
| **可选** | 每个模块一个 `hpa.modules.<模块>.enabled` 开关，默认值写在配置文件中，也可用命令行临时覆盖 |
| **解耦** | 模块之间不建任务依赖；每个模块只注册自己的任务，读取自己的参数 |
| **默认零影响** | 模块任务不挂到 `build` / `check` / `assemble` 上，默认构建图里不会出现任何模块任务 |
| **禁用不报错** | 关闭的模块保留同名任务作占位，调用时打印「已禁用」并以退出码 0 结束，CI 可用同一串任务名跑不同配置 |
| **先校验再执行** | 每个模块都有独立的 `check` 任务，缺前置条件时给出可操作的中文提示并失败，而不是让后续任务半途出错 |
| **不自动产生副作用** | GitHub 推送默认是「演练模式」，必须显式 `-PconfirmSync=true` 才真正推送；Git 模块全部只读 |

---

## 2. 配置文件

文件：`config/build-modules.properties`（随仓库提交，直接编辑即可）。

### 2.1 开关

| 键 | 默认 | 说明 |
|---|---|---|
| `hpa.modules.android.enabled` | `true` | Android 平台构建支持（环境校验 + APK 时间戳归档） |
| `hpa.modules.vue.enabled` | `false` | Vue 开发服务器集成（依赖安装 / dev server / 静态构建） |
| `hpa.modules.git.enabled` | `false` | Git 版本控制工具链（状态摘要 / 构建信息落盘） |
| `hpa.modules.github.enabled` | `false` | GitHub 仓库关联与同步（校验 / 关联 / 推送） |

取值只接受 `true` / `false`（大小写不敏感）。**写错会在配置阶段直接失败**，避免开关静默失效：

```
hpa.modules.vue.enabled 取值非法："yes"。只接受 true / false（配置文件 config/build-modules.properties，或命令行 -Phpa.modules.vue.enabled=true|false）。
```

### 2.2 模块参数

| 键 | 默认 | 说明 |
|---|---|---|
| `hpa.modules.android.archiveDir` | `build/apk-archive` | APK 归档目录，相对项目根目录或绝对路径 |
| `hpa.modules.vue.dir` | `website` | Vue 工程目录 |
| `hpa.modules.vue.devScript` | `dev:web` | 开发服务器脚本名（对应 `website/package.json` 的 `scripts`） |
| `hpa.modules.git.output` | `build/build-info/git-info.json` | Git 构建信息输出路径 |
| `hpa.modules.github.remote` | `github` | `git remote` 的条目名（不是 URL） |

### 2.3 优先级与临时覆盖

```
命令行 -P  >  config/build-modules.properties  >  内置默认值
```

不改文件的临时启用（PowerShell 下 `-P` 参数建议加引号，否则点号可能被拆成多个参数）：

```powershell
.\gradlew.bat buildModulesCheck "-Phpa.modules.vue.enabled=true"
```

---

## 3. 模块清单

| 模块 | 开关 | 任务 | 前置条件 |
|---|---|---|---|
| Android 平台构建 | `hpa.modules.android.enabled` | `androidCheck`、`androidPackage` | JDK 17+、Android SDK（含 `platforms/android-36`）、Gradle Wrapper |
| Vue 开发服务器 | `hpa.modules.vue.enabled` | `vueCheck`、`vueInstall`、`vueDev`、`vueBuild` | Node.js 18+、npm、`website/package.json` |
| Git 工具链 | `hpa.modules.git.enabled` | `gitCheck`、`gitStatus`、`gitInfo` | git 已加入 PATH、当前目录是 Git 仓库 |
| GitHub 关联与同步 | `hpa.modules.github.enabled` | `githubCheck`、`githubLink`、`githubSync` | 已配置对应远程（`gh` CLI 可选） |

汇总任务：

| 任务 | 作用 | 是否会失败 |
|---|---|---|
| `buildModules` | 打印四个模块的开关、前置条件与任务清单；配置里有未知模块键时告警 | 永不失败（只读） |
| `buildModulesCheck` | 对**已启用**的模块依次执行各自的 `check`；禁用的模块只打印提示 | 任一已启用模块校验不通过时失败 |

### 3.1 Android 平台构建支持

Android 应用本身的编译、打包由 AGP（`com.android.application`）提供，**不**受这个开关控制。
该模块增强的是「构建前后的可用性」，适合本地出包与手工验收：

- `androidCheck`：校验 JDK 17+（AGP 8.x 要求）、Android SDK 路径（`local.properties` 的
  `sdk.dir` → `ANDROID_HOME` → `ANDROID_SDK_ROOT`）、`platforms/android-36`（与 `:app`
  的 `compileSdk = 36` 对齐）、Gradle Wrapper 是否存在。
- `androidPackage`：依赖 `:app:assembleDebug` 构建 APK，再复制一份带时间戳的副本到归档目录
  （默认 `build/apk-archive/app-debug_yyyyMMdd_HHmmss.apk`）。原始产物
  `app/build/outputs/apk/debug/app-debug.apk` 保持不动，不影响既有发布脚本。

```powershell
.\gradlew.bat androidCheck
.\gradlew.bat androidPackage
```

版本号规则不变：`androidPackage` 计入「产出 APK」的调用，会照常自增
`version.properties` 的 `BUILD_NUMBER`（见根 `build.gradle.kts` 的 `apkBuilding`）。

### 3.2 Vue 开发服务器集成

针对 `website/`（Vue3 SPA + Express 内容 API）：

- `vueCheck`：Node.js >= 18、npm 可用、`website/package.json` 存在、`devScript` 与 `build`
  两个脚本存在。
- `vueInstall`：`website/node_modules` 不存在时执行 `npm install`；已存在则跳过。
- `vueDev`：运行 `npm run dev:web`，**长驻任务**，输出直通终端，`Ctrl+C` 结束。
- `vueBuild`：运行 `npm run build`，产物写入 `website/dist`（已被 `.gitignore` 忽略）。

```powershell
.\gradlew.bat vueInstall
.\gradlew.bat vueDev        # 前台长驻，另开终端做别的
.\gradlew.bat vueBuild
```

> 需要同时起 API 与前端时，仍可直接在 `website/` 下跑 `npm run dev`（该脚本用 concurrently
> 并行启动 `dev:api` + `dev:web`）；Gradle 侧只接管单进程 `vueDev`，避免把进程管理搬进构建脚本。

### 3.3 Git 版本控制工具链

全部只读，不会提交、不会改动仓库：

- `gitCheck`：校验 `git` 可用、当前目录是 Git 工作区。
- `gitStatus`：打印当前分支、上游跟踪（如 `gitea/main`）与工作区变更摘要（最多 50 行）。
- `gitInfo`：把 `branch` / `commit` / `commitShort` / `commitCount` / `dirty` / `remotes` /
  `buildNumber` 写入 JSON，默认 `build/build-info/git-info.json`，便于发布脚本或排查时引用。

```powershell
.\gradlew.bat gitStatus
.\gradlew.bat gitInfo
```

### 3.4 GitHub 仓库关联与同步

- `githubCheck`：只校验本地远程配置（不联网，离线可用）；`gh` CLI 缺失只提示、不失败。
- `githubLink`：`-PgithubUrl=<url>` 时添加或更新远程地址（仅接受 `https://` / `git@` / `ssh://`）；
  不带参数时只打印当前远程，不改动任何配置。
- `githubSync`：**默认演练模式**，只打印远程与分支；显式 `-PconfirmSync=true` 才执行
  `git ls-remote` 连通性检查 + `git push <remote> <branch>`。

```powershell
# 查看当前关联
.\gradlew.bat githubLink

# 关联 / 改地址
.\gradlew.bat githubLink -PgithubUrl=https://github.com/<user>/<repo>.git

# 演练（不推送）
.\gradlew.bat githubSync

# 真正推送
.\gradlew.bat githubSync -PconfirmSync=true
```

> 本模块永不自动推送。项目另有 `gitea` / `gitee` 远程，需要三处同时推送时按既有发布流程处理，
> 本模块只负责 GitHub 这一处。

---

## 4. 兼容性校验

```powershell
.\gradlew.bat buildModules        # 先看状态：开关、前置条件、任务、启用方式
.\gradlew.bat buildModulesCheck   # 再跑校验：只检查已启用的模块
```

校验失败时会一次性列出全部问题，而不是遇到第一个就退出，例如：

```
Android 模块兼容性校验未通过：
  - Android SDK 缺少 platforms/android-36（:app 的 compileSdk=36），请用 sdkmanager 安装
修复后重跑 .\gradlew androidCheck。
```

模块级校验也可以单独跑：`androidCheck` / `vueCheck` / `gitCheck` / `githubCheck`。
**禁用的模块**只打印「已禁用」提示，不执行检查，也不会让 `buildModulesCheck` 失败。

---

## 5. 启用 / 禁用组合的验证结果

以下组合均在本机实际执行过（Windows / JDK 17 / Node 24 / Git 2.53）：

| 场景 | 执行内容 | 结果 |
|---|---|---|
| 默认配置（仅 Android 启用） | `buildModules` | 输出状态表：1/4 启用，各模块前置条件正常 |
| 全部禁用（含 Android） | 12 个模块任务 + `buildModulesCheck` | 全部成功；禁用任务逐条打印提示，`buildModulesCheck` 报告「未执行任何检查」 |
| Vue/Git/GitHub 全启用 | `vueCheck` `vueInstall` `vueBuild` `vueDev`（跳过长驻）`gitCheck` `gitStatus` `gitInfo` `githubCheck` `githubLink` `githubSync` `buildModulesCheck` | 全部成功；`vueBuild` 产出 `website/dist`，`gitInfo` 正确落盘，`githubSync` 停留在演练模式 |
| 默认构建图 | `:app:assembleDebug --dry-run` | 计划任务 107 个，**不含**任何 `vue*` / `git*` / `buildModules*` / `androidCheck` / `androidPackage` |
| 非法开关值 | `buildModules -Phpa.modules.vue.enabled=yes` | 配置阶段按预期失败，中文提示指出非法值与该改哪个键 |

---

## 6. 与默认构建流程的关系

- 模块任务都注册在**根项目**，与 `:app` / `:provider` / `:env` 的构建无关。
- `version.properties` 的自增逻辑只多认了 `androidPackage` 一个任务名，其余模块任务不消耗版本号。
- CI（`.github/workflows/android-ci.yml`）不调用任何模块任务，现有门禁顺序与耗时不变。
- `website/dist`、`build/` 均已被忽略，模块产物不会污染仓库。

---

## 7. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `-Phpa.modules.vue.enabled=true` 被当成任务名 | PowerShell 把参数按点号拆开了，给整个参数加引号 |
| `vueDev` 没有输出 | 该任务已配置直通终端；若仍无输出，先单独跑 `vueCheck` 确认 node/npm 可用 |
| `githubSync` 只打印「演练模式」 | 默认行为。确认远程与分支后加 `-PconfirmSync=true` |
| `githubCheck` 说未配置远程 | 用 `githubLink -PgithubUrl=...` 关联，或 `git remote add github <url>` |
| 禁用模块时任务名不存在？ | 不会。禁用模块仍有同名占位任务，只是打印提示后正常结束 |
