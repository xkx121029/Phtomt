# 默认数字助理申请契约（UI 接入说明）

引擎侧实现：[AssistantRolePlan.kt](../app/src/main/java/com/phoneagent/engine/runtime/AssistantRolePlan.kt)
（纯 Kotlin，不返回 Intent/Activity；单测见 `AssistantRolePlanTest`）。

## 1. UI 从系统读事实（4 个字段）

| 字段 | 来源 |
|---|---|
| `apiLevel` | `Build.VERSION.SDK_INT` |
| `roleAvailable` | `RoleManager.isRoleAvailable(ROLE_ASSISTANT)`（API 29+；低版本传 false） |
| `roleHeld` | `RoleManager.isRoleHeld(ROLE_ASSISTANT)`（API 29+；低版本传 false） |
| `roleHolderPackage` | 角色持有者包名。RoleManager 不直接给，可由 `PackageManager` 探测默认助理；拿不到传 null |
| `selfPackage` | `context.packageName` |

## 2. 调 `AssistantRolePlanner.plan(facts)` 得到纯数据

```kotlin
data class AssistantRolePlan(
    val state: AssistantRoleState,          // held / not_held / occupied / unsupported
    val primaryAction: AssistantPrimaryAction, // none / request_role / open_settings
    val primaryLabel: String,               // 按钮文字（中文成品）
    val explanation: String,                // 直接展示，不要自己拼
    val redirects: List<AssistantRedirect>, // 有序，已按 API 过滤
    val failureKey: String?,                // 失败原因键；非失败为 null
    val failureText: String?,               // 失败原因中文默认值；直接展示
    val requestBlockedReason: String?,      // 不能弹授权框的原因
)
```

必做分支：

1. `primaryAction == NONE`：按钮置灰，显示 `primaryLabel`。
2. `primaryAction == REQUEST_ROLE`：调 `AssistantRole.requestRole(activity)`；
   - 返回 Intent → `launch`；
   - 返回 null → 按 `redirects` 顺序跳设置页（见第 3 步）。
3. `primaryAction == OPEN_SETTINGS`：直接按 `redirects` 顺序跳设置页；
   把 `failureText`（或 `explanation`）显示在按钮下方，说明为什么不能一键授权。

授权框返回后：**重新读 facts 再调一次 `plan()`**。仍未持有则展示
`AssistantRoleMessages.textOf(KEY_DENIED)`，不要只说“失败”。

## 3. 跳转候选（按顺序 resolve，失败就试下一个）

| key | action | 条件 |
|---|---|---|
| `voice_interaction` | `android.settings.VOICE_INTERACTION_SETTINGS` | Android 8.0+ 且 ROM 有该页（原生优先） |
| `default_apps` | `android.settings.MANAGE_DEFAULT_APPS` | Android 7.0+；部分 ROM 把助手归在这里 |
| `voice_input` | `android.settings.VOICE_INPUT_SETTINGS` | Android 8.0+；EMUI/部分国产 ROM |
| `system_settings` | `android.settings.SETTINGS` | 最终兜底，`requiresResolve=false` |

`requiresResolve=true` 的候选必须先 `resolveActivity`，为 null 就试下一个；
全部失败展示 `AssistantRoleMessages.textOf(KEY_NO_SETTINGS_PAGE)`。

## 4. 文案约束

- `primaryLabel` / `explanation` / `failureText` 都是中文成品，直接展示；
  UI 不要再写第二套说法。
- 失败键固定为：`assistant_role_low_api`、`assistant_role_unavailable`、
  `assistant_role_occupied`、`assistant_role_denied`、`assistant_role_no_settings_page`。

## 5. 已知限制

- `ACTION_VOICE_INTERACTION_SETTINGS` 不是公开 SDK 常量，部分 ROM 会删掉该页，因此必须探测。
- `roleHolderPackage` 无法通过公开 API 稳定获取；拿不到时只影响“被占用”的说明文案，
  不影响主流程（仍会尝试弹授权框）。
