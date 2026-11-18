# 贡献指南

## 分支模型

| 分支 | 渠道 | 产物 | 说明 |
|---|---|---|---|
| `main` | beta | `v0.2.<号>-beta` | 稳定线，偏旧 |
| `nightly` | dev | `v0.2.<号>-dev` | 每日线，功能最新 |

两条分支**允许**在 `version.properties`、渠道后缀、发布配置上不同；**不允许**在以下方面不同：

- 运行时闭包：`app/src/main/jniLibs/**/*.so`（至少 `libnode.so`、`libicudtl.dat` 对应的 3 个 `libicu*`）与 `app/src/main/assets/agent-host/**`
- 签名指纹、`applicationId`
- CI 中的 `Assert bundled coding runtime` 断言

`nightly` → `main` 用 `git merge --no-ff nightly`，不要 rebase、不要 squash。

## 提交前

1. `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
2. 改到 `detekt` 基线相关文件时，确认问题数不增加（只减不增）
3. 不要提交：`.workbuddy/`、`local.properties`、`*.log`、`Screenshot_*.png`、`_probe*`

## 提交信息

遵循 `type(scope): 描述`，type 取 `feat` / `fix` / `ci` / `docs` / `refactor` / `perf` / `test` / `build` / `chore`。

`[skip ci]` **只允许**用于纯计数器对齐（例如 `version.properties` 回写），带功能改动的提交禁止使用，否则 nightly 会出现“推了但没验证”的盲区。

## 高风险改动

触碰运行时闭包、签名、AIDL、CI 或发布脚本时，PR 描述里必须写明验证方式；涉及 APK 的，写明 `versionName` 与包内 5 个运行时文件是否齐全。

## 文档

`docs/` 是权威源，Wiki 是对外门户。改动先落 `docs/`，再同步到 Wiki，避免两边漂移。
