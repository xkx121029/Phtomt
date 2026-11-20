# HPA 任务执行文档 · 客户端接口契约

版本：v1（2026-11-03）
服务端：`hpa-trainer` FastAPI，默认 `http://192.168.2.16:9817`
客户端：`hpa_monitor` Android（新增「方案」页）

## 1. 文档生成规则（客户端必须理解）

- 只有任务**连续 `task_solve_successes` 次成功**（当前配置为 2）才算达标，才自动生成执行文档。
- 失败任务、只成功一次又失败的任务、未访问任务，都**没有文档**。
- 当前线上 G3–G16 连续 14 代 0 成功，因此文档停在 3 份（全部来自 G1–G2）。这是策略问题，不是接口故障。
- 手动补建：`POST /api/control` body `{"action":"build_docs"}`，按历史成功轨迹补建全部可补文档。
- 文档不是「万能模板」：带随机参数的任务会记录录制时的实例文本，客户端必须展示 `param_mismatch` 提示。

## 2. 端点

### 2.1 `GET /api/taskdocs` — 文档索引

响应：

```json
{
  "count": 3,
  "docs": [
    {
      "task_id": "alipay.EnableDarkMode",
      "task_name": "给支付宝开启深色模式",
      "catalog_task_name": "给支付宝开启深色模式",
      "param_mismatch": false,
      "suite": "alipay",
      "step_count": 7,
      "policy_version": 2,
      "generation": 2,
      "difficulty": "easy",
      "updated_at": 1793600000.0,
      "md": "/data/task_docs/alipay.EnableDarkMode.md",
      "json": "/data/task_docs/alipay.EnableDarkMode.json"
    }
  ]
}
```

字段约定：
- `task_id`：稳定主键，含点号，作为 URL path 段使用（客户端需 URL 编码）。
- `updated_at`：服务端 Unix 秒（浮点），**唯一刷新判据**。
- `task_name`：文档实际执行的任务描述；`catalog_task_name`：任务目录当前描述。
- `param_mismatch=true`：两者不一致，属于随机参数任务，输入文本以本次下发为准。
- `md/json`：服务端本地路径，仅供排查；客户端不直接读文件，走 2.2 / 2.3。

### 2.2 `GET /api/taskdoc/{task_id}` — 完整方案（JSON）

```json
{
  "task_id": "alipay.EnableDarkMode",
  "task_name": "给支付宝开启深色模式",
  "catalog_task_name": "给支付宝开启深色模式",
  "param_mismatch": false,
  "suite": "alipay",
  "difficulty": "easy",
  "policy_version": 2,
  "active_policy_version": 2,
  "used_current_policy": true,
  "generation": 2,
  "step_count": 7,
  "max_steps": 25,
  "judge_passed": true,
  "judge_checks": [
    {"field": "settings.general.darkMode.mode", "expected": "dark", "actual": "dark"}
  ],
  "generated_at": "2026-11-03 12:34:56",
  "updated_at": 1793600000.0,
  "episode_dir": "/data/runs/gen02/trajectory/alipay_EnableDarkMode",
  "progress": {"lifetime_attempts": 3, "best_steps": 7},
  "steps": [
    {
      "step": 1,
      "action": "CLICK",
      "label": "点击",
      "point": [1000.0, 2370.0],
      "value": null,
      "direction": null,
      "key": null,
      "route": {"app": "com.eg.android.AlipayGphone", "path": "/home"},
      "thought": "进入我的页面以寻找设置入口",
      "screenshot": "step_001.jpg"
    }
  ]
}
```

约定：
- `point`：屏幕绝对像素（录制分辨率 1080×2400），`[x, y]`；非点击类动作可能为 `null`。
- `action`：`CLICK / TYPE / SWIPE / LONG_PRESS / PRESS / BACK / ANSWER / COMPLETE / ...`，`label` 为中文展示名。
- `value`：输入文本（`TYPE` 时使用）。
- `route`：该步执行前的页面（app 包名 + path）。
- `steps` 按 `step` 升序，客户端按顺序展示/执行，不跳步。
- `policy_version`：生成该轨迹时的策略版本；`active_policy_version`：生成时刻服务端现役版本。
- `used_current_policy=false` 或 `policy_version != 客户端当前现役版本` → 客户端必须提示「方案可能过时」，不得静默套用。
- 404：该任务无文档（索引里没有也等价）。
- 文档正文 Markdown 版：`GET /api/taskdoc/{task_id}/md`，`text/markdown; charset=utf-8`。

### 2.3 `GET /api/taskdoc/{task_id}/md`

直接返回 Markdown（人读 / 下发给 agent 的纯文本方案），无额外包装。404 语义同上。

### 2.4 辅助端点

- `GET /api/tasks`：任务目录（`task_id / task_name / suite / difficulty / solved / best_steps`），客户端用于「选任务」，包含无文档任务。
- `GET /api/summary`：`task_progress {cycle, solved, visited, total, max_attempts, solve_successes}` 与 `history[]`，客户端用于解释「为什么没有新文档」。
- `GET /api/health`：`state.policy_version` 等，客户端用于现役策略版本校验。

## 3. 客户端行为（必须实现）

### 3.1 轮询

1. 进入「方案」页立即拉一次 `/api/taskdocs` 与 `/api/tasks`。
2. 停留在该页时每 **5 分钟** 重拉索引（`docs` 体积很小，成本可忽略）。
3. 用每条 `updated_at` 与本地缓存比较：**只有变化才重取 `/api/taskdoc/{id}`**，避免无意义请求。
4. 手动「刷新」按钮等价于立即重拉索引；索引未变化时不应清空已展示的正文。

### 3.2 选任务与无文档判定

明确优先级（不是隐含行为）：

1. 索引里有该 `task_id` → 请求 `/api/taskdoc/{id}`；
2. 索引里没有 → **直接判定无文档**，不请求详情；
3. 详情返回 404 → 同样判定无文档；
4. 无文档路径 = **回退实时 agent**：训练侧按现役策略在线执行，不阻塞、不报错、不弹「失败」。

UI 文案固定为：「判定：无执行文档 → 回退实时 agent」。

### 3.3 版本校验

- 顶部展示「现役策略 vX」（来自 `/api/health`）。
- 文档展示「方案 vY · G{n} 轨迹」。
- `vY != vX` → 黄色提示：「方案产自 vY，当前现役 vX，执行前确认版本差异」。
- `used_current_policy=false` 同样提示。

### 3.4 参数实例

- `param_mismatch=true` 时展示：「本方案录制时执行『A』，当前目录描述『B』，输入文本以本次下发为准」。
- 不得把文档里的搜索词/输入值当作本轮任务真值。

### 3.5 「按文档执行」的范围

- 客户端只做**步骤引导 + 指令复制**（上一步/下一步、进度、复制当前步骤指令）。
- 不自动点击设备，不接管训练容器。
- 实际设备操作仍由 HPA agent 完成；客户端是方案下发/查阅端。

## 4. 错误与降级

| 情况 | 客户端表现 |
|---|---|
| 索引请求失败 | 保留上次缓存，顶部提示「同步失败：…（显示的是上次缓存）」 |
| 详情失败（非 404） | 详情页提示读取失败，不进入无文档判定 |
| 详情 404 | 进入无文档 → 实时 agent 判定 |
| 服务端重启 | 正常重试；索引 `updated_at` 可能不变，无需清缓存 |
| 文档为空（0 份） | 展示生成规则 + 「截至 G{n} 尚无任务达标，本页为空是正常的」 |

## 5. 兼容性与变更

- 本文档字段为 v1；服务端新增字段可忽略，删除/重命名字段视为 breaking。
- 客户端以 `task_id` 为主键，不依赖列表顺序。
- `updated_at` 精度为秒，比较用「不等」而非「更大」。
- 后续若增加推送（SSE/WebSocket），客户端保留 5 分钟兜底轮询。

## 6. 客户端实现位置

- `app/src/main/java/com/hpa/monitor/Api.kt`：`TaskEntry / DocEntry / TaskDoc / DocStep / DocCheck` 与三个端点调用，404 以 `ApiException(code)` 暴露。
- `app/src/main/java/com/hpa/monitor/TaskDocsScreen.kt`：列表、搜索/筛选、生成规则解释、详情、逐步执行、无文档判定。
- `app/src/main/java/com/hpa/monitor/MainActivity.kt`：3 个 Tab（实况/总览/方案）、5 分钟轮询、`updated_at` 变更才重取、现役策略校验、剪贴板复制。
