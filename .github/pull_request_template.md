## 改动内容

<!-- 一句话说明改了什么、为什么改 -->

## 影响的渠道

- [ ] 只影响 nightly（dev 包）
- [ ] 只影响 main（beta 包）
- [ ] 两条都影响

## 是否触碰这些高风险区域

- [ ] 运行时闭包（`app/src/main/jniLibs/**/*.so`、`app/src/main/assets/agent-host/**`）
- [ ] 签名配置 / 包名 / `applicationId`
- [ ] AIDL 接口（`*-api` 模块）或 `apiVersion`
- [ ] CI workflow 或发布脚本
- [ ] `.gitignore` / `.gitattributes`

以上任一项勾选时，请在下方写明验证方式。

## 验证方式

<!-- 例如：./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -->
<!-- 涉及 APK 时写明 versionName 与包内运行时 5 个文件是否齐全 -->

## 备注

<!-- 截图、日志、关联 issue -->
