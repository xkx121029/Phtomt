# 环境探针（两层）—— 阈值与标定关系

> 所有涉及墙钟的结论都必须在旁边标注**「环境近似」**。本文档里的秒数都是
> 同一台 NAS、同一镜像、同一模拟器负载下的近似值，不能当成评测标准时间。

## 为什么不再用「整集」当门

旧做法（`scripts/watch_b1.py`）每次校准都跑完整一集来测 `per_step`。整集要拉起完整
浏览器并跑满步数；在这台可用内存只剩 1GB 左右的机器上，**测量本身就在加重被测的劣化**，
连续多轮 `per_step` 12–19s，再跑六轮只是重复确认「慢」，还多压六次内存。

## 两层设计

| 层 | 频率 | 做什么 | 用途 |
|---|---|---|---|
| 轻探针 | 每 25 分钟 | 起页面 → 取一次元素树 → 截一次图 → 点一次 → 再取一次树，逐段计时；跑完立刻关浏览器 | 判断「机器今天缓过来没有」 |
| 重探针 | 低频（≥1 小时且轻探针连续通过） | 完整一集，从 stopwatch 取**机器侧分段**算 `machine_step_s` | 真正的入场券：`machine_step_s ≤ 2.6s` 才允许跑评测 |

轻探针单次只启一个页面、五个固定动作，跑完即关；重探针才是完整 episode。

## 当前阈值（代码：`hpa_training/probes.py` 顶部）

| 常量 | 值 | 含义 |
|---|---|---|
| `MACHINE_STEP_MAX` | 2.6 s | 重探针入场券：**机器侧**单步上限（代理阈值，见下） |
| `PER_STEP_WARN_MAX` | 40 s | `per_step`（含模型）只告警不拦，用于观察模型侧劣化 |
| `LIGHT_INTERVAL_S` | 1500 s | 轻探针间隔（25 分钟） |
| `HEAVY_MIN_INTERVAL_S` | 3600 s | 重探针最小间隔（1 小时） |
| `LIGHT_PASS_STREAK` | 2 | 标定后：轻探针连续通过 2 次才允许跑重探针 |
| `HEAVY_FAIL_LIMIT` | 3 | 重探针连续失败 3 次 → 永久停掉重探针（轻探针继续） |
| `CAL_MIN_PAIRS` | 3 | 至少 3 对「轻/重」数据才允许标定 |
| `HEAVY_TRIALS` | 1 | 重探针每次跑 1 集 |
| `HEAVY_TASK` | `sms.ReadUnreadConversationCount` | 重探针固定任务（可用 `HPA_PROBE_TASK` 覆盖） |

## 标定关系

轻探针的毫秒数本身**没有意义**，必须先用若干次重探针配对建立对应关系：

```
machine_step_s  ≈  a * light_total_s + b        （最小二乘拟合）
```

- `light_total_s`：轻探针五段耗时之和（秒）；
- `machine_step_s`：同一次重探针的机器侧分段合计 ÷ 步数（**不含 LLM**）；
- 标定前：轻探针**只记录、不作门**；
- 标定期（配对 < 3）：重探针按 1 小时间隔跑，专门用来攒配对数据；
- 标定后：轻探针折算 `machine_step ≈ a * light_total_s + b`，`≤ 2.6s` 才算通过；
- 斜率 `a ≤ 0`（轻探针变慢、重探针没变慢）视为关系不成立，保持「未标定」。

**当前状态：未标定（0/3 对）。** 在攒够 3 对之前，轻探针不承担门控职责。

## 测量口径

- 轻探针分段：`page_ms`（起页面）、`elements1_ms`（首次元素树）、`screenshot_ms`、
  `click_ms`（点击 + 0.3s 稳定）、`elements2_ms`（再次取树）；
- 轻探针结果写在 `/data/probe_light.json`（`latest` + 最近 48 条 `recent`）；
- 标定数据写在 `/data/probe_calibration.json`（`pairs` + `fit`）；
- 循环状态写在 `/data/probe_loop.json`（`light_pass_streak` / `heavy_fail_streak` /
  `heavy_runs` / `heavy_disabled` / `stop_reason`）；
- 首轮实测（2026-11-10 01:05，容器内）：`total=6426.9ms`，
  `page=3296.5 / elements1=2406.3 / screenshot=365.7 / click=328.7 / elements2=29.7`，
  可用内存 1272→1337MB，容器 cgroup 峰值 660MB。**「环境近似」**。

## 泄漏护栏（2026-11-10，已部署）

> 这条比阈值更重要：**它保证测量动作不会改变被测对象。**

任何探针/评测跑完，都比对「跑之前的浏览器实例数」与「跑完之后」：

- 计数口径（`hpa_training/leakguard.py`）：`/proc/*/cmdline` 命中
  `headless_shell|chrome|chromium` 且**不带** `--type=` 的进程算 1 个实例；
  `state=="Z"` 单独记僵尸（僵尸不占内存，但说明有人没 reap）；
- 轻探针 `managed=0`、重探针 `managed=0`（paused 期间 trainer 不保留浏览器）；
  训练/评测每集结束后用 `managed=1 if env 存活 else 0`；
- 多出来就报错并记账：`swallowed("leak.browser")` / `swallowed("orchestrator.leak")`，
  可在 `/api/health` 的 `swallowed.by_site` 里查到，**不会静默关掉了事**。

实测（2026-11-10 01:22 / 01:30，容器内）：

| 动作 | 前后实例数 | 前后僵尸 | 结论 |
|---|---|---|---|
| 轻探针 | 0 → 0 | 0 → 0 | 归零 |
| 重探针（18 步整集） | 0 → 0 | 0 → 0 | 归零（trainer paused 后自动释放） |

## 自污染更正（撤回「机器劣化」结论）

上一版把「per_step 12–19s、整机地板 2.45」当成整机劣化。现在更正：

1. **`load1` 与 CPU 不支持「整机变慢」**：`/data/b1_watch.log` 五轮校准里
   `trainer_cpu_cores=0.00–0.04`（trainer 闲置），`load1` 从 4.93 → 1.48 → 2.33 → 2.35 → 1.25，
   是**下降**的，而 `per_step` 是 19.53 → 11.57 → 12.79 → 16.16 → 16.50。
   两者方向不一致，不能用「机器累坏了」解释。
2. **直接证据指向自污染**：2026-11-10 01:06 误跑第二个探针 loop（两个浏览器叠加），
   当轮轻探针 `total=12.75s`、`elements1=9.3s`、`mem_available_before=1146.9MB`；
   杀掉多余 loop 后立刻回到 6.02s / 6.30s / 5.20s，`mem_before` 回到 1331→1427MB。
   **多一个自己漏掉的浏览器 = 下一次测量翻倍**，这条相关性成立。
3. 因此「机器慢了两三倍」的正确表述是：**此前的劣化至少有相当部分是我们自己的测量
   进程没有收干净造成的自污染**；「整机地板 2.45」降级为「未排除自污染因素的初步观察」。
4. 历史逐轮浏览器进程数**不可恢复**（当时容器被 force-recreate 三次，`ps` 与 `docker logs` 全丢），
   所以只报可复原的数据，不编造缺失的列。

## 门已换成机器侧（2026-11-10，已部署）

`per_step = runtime_s / steps` **包含 LLM 推理时间**，它不是机器速度，已从「门」降为「记录」。
新门的量来自 `stopwatch_tree` 的**根节点分段**（只取根，避免 `obs` 与其子节点
`screenshot`/`route` 双计）：

- 机器侧：`action` / `delay` / `obs` / `record` / `screenshot` / `route` / `state` /
  `elements` / `waitForData` / `SIM` / `SIM_FS` / `OS`
- 模型侧：`infer`
- 启动一次：`reset` / `wait_ready` / `warm` / `prepare` / `sample` / `post_sample` / `init_obs`

两次干净重探针的分段（容器内实测）：

| 时间 | 步数 | 机器侧/步 | 模型侧/步 | per_step（含模型） | 启动段 | 机器占比 |
|---|---|---|---|---|---|---|
| 01:22（旧代码，18 步） | 18 | **1.72s** | 25.70s | 27.24s | 2.2s | 6.3% |
| 01:44（新代码，8 步） | 8 | **1.14s** | 14.98s | 16.13s | 4.07s | 7.1% |

**代理阈值**：`MACHINE_STEP_MAX = 2.6s`，取值是「当时唯一的干净样本 1.72 × 1.5」。
已知问题：两次干净样本相差 51%（1.14 vs 1.72），说明按单样本定阈值偏脆；
标定满 3 对后应用**中位数 ×1.5** 回填，并替换掉这个代理值。

`per_step` 保留两个用途，不再当门：估 episode 超时上限、观察模型侧是否劣化
（`PER_STEP_WARN_MAX = 40s` 只告警）。`HEAVY_FAIL_LIMIT` 现在统计的是新门，
不再因为模型慢而被触发。

## 端点核对：读数取「运行实例」，不取配置文件

`/api/health` 现在同时给两组值：

- `llm_expected`：配置里的期望值（`settings.model_base_url` / `model_name`）；
- `llm_effective`：**进程里真正在用的 client**（`bench_env.llm.LLMClient` 实例上的
  `base_url` / `model`）+ 真实 chat 调用延迟。写法是 `build_llm` 创建 client 时在实例上
  挂一层计时，不改调用语义，所以看到的就是所有 agent 调用实际走的端点。

2026-11-10 01:47（容器内，agent 角色 8 次真实调用后）：

| 角色 | effective base_url | effective model | calls | p50 | p90 | max |
|---|---|---|---|---|---|---|
| agent | `http://192.168.2.16:8787/v1` | `agnes-3.0-flash` | 8 | 17.18s | 26.83s | 33.80s |
| translate | `http://192.168.2.16:8787/v1` | `spark-x2.6` | 4 | 21.53s | 23.69s | 23.69s |

`llm_expected` 与 `llm_effective` 完全一致（base_url / model / key 尾号 1444）。
即**没有**「配置改了仍走公网」的残留；`p90 24–52s` 是模型与网关自身的耗时，不是网络绕行。

## token 构成（重探针那一集，18 步 / 67,532 token = 3,752/步）

prompt 是**无状态**的：`step_*_prompt.json` 里永远只有 `system` + 当前 `user` 两条消息，
没有历史对话回放。历史只以文本块「历史操作（最近 6 步）」嵌在 user 消息里，且上限 6 步。

| 组成 | 每步 token（估） | 占比 | 说明 |
|---|---|---|---|
| system（11 条规则 + 13 意图表） | ≈2,195 | 58% | 固定开销，3,402 字符 |
| 截图 | ≈1,091 | 29% | 每步 1 张，来自残差 |
| user 文本 | ≈466 | 12% | 其中「历史操作」块 280 token |
| └ 其中 reasoning 回显 | ≈196 | 5.2% | 「历史操作」里 `想:` 部分，按占该块 70% 估 |

结论：**A 的 reasoning 回显只把 prompt 推高约 5%**，不是 3,750/步的主因；
主因是固定的 system 3,402 字符 + 每步一张截图。文本估算按 1.55 字符/token 折算，
截图与「历史块内 reasoning 占比」为估算值，非精确计费口径。

## A/B 对照实验：那 15–27s 是模型服务还是抢资源（2026-11-10）

**结论先说：不是抢资源。环境跑起来后网关延迟不升反降，推理瓶颈在上游首 token 排队，不在本机。**

同一台 NAS、同一个网关（`192.168.2.16:8787/v1`）、同模型 `agnes-3.0-flash`、同参数
（`temperature=0`、`max_tokens=16`）、同一固定短 prompt，连打 10 次：

| 指标 | 条件 A（env 全停，容器 idle） | 条件 B（env 跑一集，浏览器在动） |
|---|---|---|
| p50 | **14.84 s** | **9.48 s** |
| p90 | **45.08 s** | **24.79 s** |
| max | 58.67 s | 26.85 s |
| min | 0.63 s | 0.83 s |
| MemAvailable | 1391 MB | 1081 MB |
| SwapFree（总 7943MB） | 1490 MB | 1385 MB |
| cgroup 内存 | — | 571.8 MB |
| 极短请求「回复 OK」max_tokens=4 | 9.52 s | 16.02 s |
| 流式 TTFT / 生成速度 | 1.02 s / 21.77 tok/s | 1.29 s / 17.80 tok/s |
| prompt_tokens/次 | ≈98 | ≈98 |

**判读**：B 的 p50/p90/max 全部**低于** A。环境一开不但没把网关拖慢，反而（在采样噪声内）
更快。**不支持「推理和模拟抢内存」的假设**——B 比 A 慢一倍以上这个触发条件没有发生。
两组的可用内存差约 300MB、swap 只差约 100MB，也不足以解释 15–27s 量级的波动。

## 首 token 排队 vs 生成速度（同一轮，归因）

| 测试 | 输入 | TTFT | 输出 | 生成速度 |
|---|---|---|---|---|
| 极短请求 | 「回复 OK」 | A 9.52s / B 16.02s | 2 tok | — |
| 长 prompt 预填充 #1 | ≈2,000 prompt tok | **4.48 s** | 2 tok | 预填充 446.7 tok/s |
| 长 prompt 预填充 #2 | ≈2,000 prompt tok | **2.31 s** | 2 tok | 预填充 867.3 tok/s |
| 生成测试 #1 | 输出 1..200 | **20.3 s** | 400 tok | **78.44 tok/s** |
| 生成测试 #2 | 输出 1..200 | **11.32 s** | 400 tok | **73.10 tok/s** |

**判读**：
- 极短请求（2 字）仍要 9–16s → **不是生成慢**，延迟在**首 token 之前**（排队/调度/上游负载）；
- 长 prompt 预填充 447–867 tok/s → **预填充也不慢**，2,000 token 只需 2–4.5s；
- 真生成（400 token）73–78 tok/s → **算力够**；
- 但同样请求两次 TTFT 20.3s vs 11.32s（差近一倍），且与 prompt 长度无关 → **波动来自上游排队，
  不是本机资源**。

因此：`per_step` 里那 15–27s，是模型侧每步 `infer` 的**首 token 等待**，不是模拟器抢资源，
也不是本地算力。改排期/限本机内存不会降低这个数；要降只能从**上游并发/限流/请求合并**入手。
> ⚠️ 上述判断的前提是宿主内存正常。当前宿主 swap 已满、PSI memory `full avg10≈18%`，
> **不能排除**等待来自宿主换页/调度——见文末「结论降级」一节，引用时带条件。

## 网关进程归属（`docker ps`，只查未动）

| 容器 | 端口映射 | 内存 | CPU | 角色 |
|---|---|---|---|---|
| `wharf` | `0.0.0.0:8686 -> 8787/tcp` | 1.547 MiB | 0.00% | 8787 网关，**纯代理** |
| `hpa-trainer` | 8080（内部） | 369.6 MiB / 3 GiB | 41.6% | 我们的训练器 |
| `happy-cloud-backend` | `0.0.0.0:8080 -> 8080` | — | — | 其它服务，未碰 |

`wharf` 只占 1.5MiB、CPU 0.00% → **它不做推理，只是转发**；`agnes-3.0-flash` 的算力在
上游（`192.168.2.16:8787` 背后）。本机不存在「一个吃 CPU/内存的推理进程和模拟器抢」的结构。

## elements1 空树缺陷修复（2026-11-10，已部署）

轻探针首段 `elements1` 偶尔量到**空树**（页面还没渲染完），这段时长既不代表真实取树成本，
又会让一个不成立的分段继续参与标定/门控。修复（`probes.py run_light`）：

- 首次取树为空 → 等 0.5s **重取一次**；
- 重取仍为空 → `elements1_ms=None`，**从分段和 total 里剔除**，并在结果标
  `elements1_valid=false`；
- 重取成功 → 用第二次的时长，标 `elements1_retry=true`；
- `ok` 判定改为「**必需**四段齐全」`{page_ms, screenshot_ms, click_ms, elements2_ms} ⊆ seg`，
  不再要求 `len(seg)==5`——`elements1` 无效不再把整次探针拖成 false。

验证（01:59:42，容器内）：`"elements": [24, 24], "elements1_valid": true, "elements1_retry": true`
——首取空（0 棵）、重取拿到 24 棵，已按有效样本计入。

## 泄漏护栏基线修复（并发假报警，2026-11-10）

现象：01:59:42 轻探针 `leak.ok=false, leaked=1`，但 `before.instances=1`、`after.instances=1`
——前后都有 1 个浏览器、**delta=0**，根本没新增。原因是当时重探针正在并发跑，轻探针
`managed=0` 把「本来就在的 env 浏览器」当成了新泄漏。

修复（`leakguard.diff`）：基线从「只看 `managed`」改为 **`max(before.instances, managed)`**，
只计**新增**实例：

```python
baseline = max(int(before.get("instances", 0)), int(managed))
leaked = max(0, int(after.get("instances", 0)) - baseline)
```

四例自测（容器内）：

| 场景 | before | managed | after | 结果 |
|---|---|---|---|---|
| 并发（别处已有 1 个） | 1 | 0 | 1 | ok=True, leaked=0 ✔ |
| 真泄漏 | 0 | 0 | 1 | ok=False, leaked=1 ✔ |
| 训练中保留 env | 1 | 1 | 1 | ok=True, leaked=0 ✔ |
| 训练中多漏 1 个 | 1 | 1 | 2 | ok=False, leaked=1 ✔ |

注意：这次修的是「跑前已有实例」这一类；**「跑到一半别处新起一个」**（before=0→after=1，
但那个是别人起的）仍需 PID 级归属才能区分。正常循环里轻/重探针**串行**、且只在 trainer
paused 时跑，不会出现这种中途插入；本轮 `heavy_b.log` 的 `leaked=1` 正是我手动 concurrent
跑重探针造成的测试态，不是常态。

## 瓶颈定位：上游排队，不是本机（2026-11-10，结论）

**结论：训练慢（每步 15–27s、集均约 90s）来自上游模型服务的首 token 排队，
不是本机内存/CPU、不是模拟器、不是训练器代码。** 后面任何人看到「训练很慢」，
先看这一条，不要重新查机器。

### 证据一：低速率单发（每 60s 1 次，`max_tokens=4`，env idle，仅我们）

环境完全 idle，容器内只有这一个测试进程；同一 prompt、同模型 `agnes-3.0-flash`、
`temperature=0`。逐次延迟（客户端实测，relay 记录独立复核一致）：

| # | 客户端 | relay | prompt/completion |
|---|---|---|---|
| 1 | 22.91s | 22.76s | 83 / 2 |
| 2 | 18.04s | 18.02s | 83 / 2 |
| 3 | 17.54s | 17.39s | 83 / 2 |
| 4 | 4.71s | 4.58s | 83 / 2 |
| 5 | 9.44s | 9.43s | 83 / 2 |
| 6 | 27.75s | 27.74s | 83 / 2 |

p50≈17.8s、min 4.7s、max 27.7s。**低速率下没有降到 2–4s，反而中位 17.8s。**
按判据：排队不是我们自己的并发造成的。

### 证据二：这段时间只有我们在打

`happy-relay` 的 `usage_logs`（含 WAL，10:12–10:19 窗口）7 条请求**全部是 `gym`**；
最近 60 分钟 109 条也全部是 `gym`，`Octop`/`解题` 等其它密钥 0 条。逐分钟：10:05–10:19
稳定 1 条/分（就是本测试）。**没有「别人占着队列」的证据**——但要注意，
这只说明队列里没有别的密钥，**不能单独推出「上游服务自身慢」**（宿主内存压力未排除，见文末降级）。

> 注：读 `relay.db` 必须连同 `relay.db-wal` / `relay.db-shm` 一起拷，否则看到的是上次
> checkpoint 的旧快照（只拷主文件时最新停在 09:57，漏掉近期提交）。

### 证据三：生成和预填充都不慢，慢在首 token

见上节「首 token 排队 vs 生成速度」：预填 447–867 tok/s、生成 73–78 tok/s，
但极短请求仍要 9–16s、TTFT 与 prompt 长度无关 → 排队在首 token 之前。

### 证据四：A/B 对照反向

环境跑起来（条件 B）比 idle（条件 A）**更快**（p50 9.48 vs 14.84、p90 24.79 vs 45.08），
环境/模拟器不参与拖慢。

### 网关在本机、但只转发

`happy-relay`（`/usr/local/bin/relay`，`RELAY_ADDR=:8787`，host 网络）就是
`http://192.168.2.16:8787/v1` 的网关；内存 93MB、CPU 0.00%——采样瞬间它只转发，推理在其上游。
**但它是宿主上的进程，宿主 swap 满、PSI `full avg10≈18%` 时同样可能被换出/调度延迟，
所以「只转发所以不慢」不成立。** 客户端侧串行化/节流无用（我们本来就只有 1 条/分）；
要降这个数，得先排除宿主内存压力，再判断是否需要动上游（扩容/限流/换服务）。

### 调用频率（供估算）

最近 28 集：步数均值 14.4、`steps/min ≈ 9.57`（≈ 10 次 infer/分）；按 agnes 总调用
10,764 / 313 集 ≈ 34 次/集（含重试/视觉/文本），折算约 **22–23 次/分**。

## 局限：低速率测试低估了真实每步延迟（2026-11-10）

**不要用 17.8s 去推算「一集应该多久」。**

低速率测试的请求是 `max_tokens=4`、prompt 只有 **83 token**、不带截图，一次调用就是全部；
而训练里的一步是 **3,700 token 的 prompt + 截图**（见「token 构成」一节），并且一步之内
可能有多条模型调用（决策 / 翻译）。两点差异：

1. **预填量差 40 倍以上**：空载时 83 token 的预填几乎不耗时；3,700 token + 图片在排队之外
   还要真实预填（预填 447–867 tok/s → 4–8s）。
2. **空载 ≠ 我们的条件**：低速率测试是「只有 1 条/分」的极端轻载，真实评测是连续串行调用，
   自己也会占住队列。同一份 4-token 请求，idle 时 4.7–27.7s（p50≈17.8s），
   **在 B1 评测跑着一集时测得 49.3s**（`/data/batch_ttft.json`，label `b1-midbatch`）。

因此：

- 17.8s 只能回答「排队是不是我们自己造成的」，**不能**当「每步耗时」的估计；
- 每步真实墙钟要看实测 `per_step`（同一时段：重探针那集 99.3s/步、稳态期 34.1s/步）；
- 每批评测都记一行「批次条件」（1 个 4-token 短请求 TTFT + 本批第一集 `per_step`），
  批与批之间不复用延迟数字。

## 评测主表口径：不依赖墙钟（2026-11-10，已部署）

评测主表固定用判分口径，墙钟只作观测值：

| 主表字段 | 含义 | 可跨轮比较 |
|---|---|---|
| `success_rate_scored` | 成功 / 已判分集数 | 是 |
| `avg_steps` | 每集平均步数 | 是 |
| `tokens_per_episode` | 每集 token | 是 |
| `stop_reason_dist` | 停止原因分布 | 是 |
| `unjudged` | 未判分计数 | 是 |
| `avg_runtime_s` / `per_step_s` | 墙钟 | **否**（受上游排队影响，仅本机观测） |

`/api/eval_tasks` 结果里新增 `overview`（上面这组）与 `metric_note`（表头说明）。
评测本身就是**串行**（`for` + `await`，无 `gather`），并发=1，不会再自己给自己加队列。

### 单集超时改为动态折算

固定 1200s 会把「上游慢」误记成「任务死」。改为：

```
timeout_s = clamp(per_step_med × 预期最大步数 × margin, floor, cap)
          = clamp(per_step_med × step_cap × 1.5, 600, 3600)
```

- `per_step_med`：最近 ≤20 集「正常结束且有步数」的 `runtime_s/steps` 中位数；
  超时/崩溃集不参与（它们的 runtime 不代表稳定链路速度）；
- 无样本时 `per_step` 取 `per_step_assumed_s=40`；
- 上游越慢 → per_step 越大 → 超时越宽，不把排队当任务死；
- `ab_text_first` 诊断仍在 900s 上限内（临时把 `cap` 压到 900 并恢复）。

## 运行与停止

```bash
# 容器内一次性
docker exec hpa-trainer sh -lc 'cd /app && python -m hpa_training.probes light'
docker exec hpa-trainer sh -lc 'cd /app && python -m hpa_training.probes heavy'
docker exec hpa-trainer sh -lc 'cd /app && python -m hpa_training.probes status'

# 常驻循环（轻探针每 25 分钟；重探针受门控）
docker exec -d hpa-trainer sh -lc 'cd /app && exec python -m hpa_training.probes loop >> /data/probe_loop.log 2>&1'

# 停止
docker exec hpa-trainer sh -lc "pkill -f 'hpa_training.probes loop'"
```

## 约束

- 只在 trainer 处于 `paused` 且未 `running` 时采样，不与训练抢资源；
- 重探针连续 3 次不达标即永久停掉重探针，但轻探针继续——它几乎不吃内存，
  正是用来等机器缓过来的；
- 轻探针的 `per_step` 折算只用于**同机同镜像的入场判断**，不是评测成绩；
  任何引用它的结论必须标注「环境近似」；
- 要动 B1 那 5 集之前，先看轻探针的连续采样，由人来判断能不能跑。

## 一步打了几次模型（2026-11-10，relay usage_logs 实测）

数据源：`happy-relay` 的 `usage_logs`（连 `-wal`/`-shm` 一起拷），`api_key_name='gym'`，
取最近 5 个已完成 episode 的时间窗（+08:00），剔除校准探针。

| role | 次数 | 每步 | p50 | p90 | max | 可砍 | 依据 |
|---|---|---|---|---|---|---|---|
| agent | 53 | **1.02** | 20.4s | 70.8s | 179.8s | 否 | 每步决策本身 |
| translate | 29 | 0.56 | 14.2s | 25.2s | 58.2s | 训练期可关 | 只回填面板，异步、不进下一步 prompt、缓存命中 0 |
| probe | 16（仅 E4 窗口） | +1.78 | 15.0s | 26.4s | 28.4s | 已停 | 固定 prompt 4 分钟内重复 20 次，属测量污染 |
| vision | 0 | — | — | — | — | — | 无独立调用，截图塞在 agent 请求里（1 张 image_url） |

- 合计 **1.58 次/步**（52 步 82 次）。E4 窗口的 3.22 次/步是 16 次探针混入造成的假象，真实 1.44。
- 用途：agent = system「Happy Phone Agent…铁律」+ 历史 + 当前截图，prompt 2,955–3,452 token，
  `max_tokens=4096`；translate = `spark-x2.6`、system「你是专业翻译…」、输入 129–235 token；
  probe = 「回复 OK」「12+30」「数数」「2000 token 填充」。
- 重试：54 次 agent 调用里 1 次（1.9%）是 180s 客户端超时后重发
  （10:30:20，status 502，`context canceled`，`prompt_tokens=0`，179.79s）；
  无「同输入瞬时重发」（同 pt/ct 且间隔≤10s 的成对记录 = 0）。
- 可砍：关 `translate` 后 1.58 → 1.02 次/步；按 translate p50 14.2s × 0.56 ≈ **8s/步**，
  但它是异步且与 agent 共用同一个上游队列，实际节省在 0–8s 之间（估算，非实测）。
- 旧口径「约 34 次/集」把离线调用（诊断/进化/taskdoc）与探针一起算进去了；
  训练链路内实际 ≈ 1.58 × 步数（14.4 步 ≈ 23 次）。

## 结论降级：上游 17.8s 未排除宿主内存压力（2026-11-10）

**原结论**：低速率单发仍 p50≈17.8s → 「瓶颈在上游排队，不在本机」。

**不能这么写的原因**：上游网关与训练器**同跑在一台宿主上**。实测宿主：
7.7G 内存 / used 6.1G / available 1.6–2.7G、**swap 7621–7943 MB（接近满）**、
PSI memory `some avg10≈20% / full avg10≈18%`、load ≈16。`wharf` / `happy-relay` 都是宿主进程，
在这个条件下自己就可能被换出；4-token 请求的等待无法区分「上游排队」还是「宿主换页/调度」。

**现在的表述**：上游首 token 排队是**主要嫌疑，但未排除宿主内存压力**；
**需在宿主内存正常（swap 有大量空闲、PSI full < 1%）后重测 6 次 4-token 请求才能定论**。
引用 17.8s / 49.3s 这些数字时必须带上这句条件。

**为什么强调**：仅今天就有「机器慢」「抢内存」「上游慢」三个结论被同一因素推翻。

## 评测开跑前的环境门禁（2026-11-10，写入流程）

B1 的 5 集里 4 集在第 1 步就 `Target crashed`，整批作废。原因是「评测能不能跑」这件事
从来没有被单独验证过。现规定：

1. **任何评测（B/C/G 或以后的批次）开跑前，先连做 3 次 20 秒空载自检**
   （只起浏览器 + 截图循环，不调模型、不写业务数据）：
   ```
   docker exec -e PYTHONPATH=/app hpa-trainer python -m hpa_training.env_gate
   ```
2. **3 次全 PASS 才允许开跑；任一失败 → 不开跑**，先查环境，不要用评测去试环境。
3. 三次结果（PASS/FAIL、截图数、是否崩溃）**原样写进该批报告头部**，与 TTFT、首集 per_step 并列。

判定标准：每次 20s 内截图循环无 `Target crashed` / `TargetClosed`，且最后 `Mouse.move` 成功。

**PSI 判据（2026-11-10 追加）**：每次自检启动浏览器前先读 `/proc/pressure/memory` 的
`full avg10`，`> 10%` 直接判 FAIL 且**不启动浏览器**（`HPA_PSI_MAX` 可覆盖）。
依据是崩溃率与 PSI 单调相关（27.4% → 6/6、18% → 1/3、3.5–9% → 2/13），
这条比调 chromium 参数贴近证据。前置判据未过时不再空跑后两次，直接按「任一失败即禁止开跑」处理。

## 机会式排期（2026-11-10，写入流程）

不再「等宿主内存被腾出来」，改成挑窗口跑。实现：`hpa_training/opportunity.py`
（批次由它驱动，每集一次 `eval_tasks` 单任务调用，批次边界天然是暂停点）。

| 规则 | 取值 |
|---|---|
| PSI 采样间隔 | 2 分钟（`full avg10`） |
| 开跑窗口 | 连续 10 分钟 `< 10%` |
| 开跑前门禁 | 3×20s（门禁自身含 PSI 判据） |
| 批内暂停 | 连续 2 次 `> 15%` → 当前集跑完后暂停 |
| 恢复条件 | 连续 8 分钟 `< 10%` + 重新过门禁 |
| 一票否决 | 一批暂停 ≥ 3 次 → 标「环境不足，数据仅供观察」 |
| **停机规则** | **连续 3 小时开不出一批（一直被窗口/门禁拦）→ 直接停，报告「今天宿主压力全程不合格」** |

报告头固定五项：开跑时 PSI、门禁三次结果、批内 PSI 峰值（含时刻）、中途暂停次数与每次触发/恢复时刻、
环境标签。单集超时上限首集 1800s，之后按上一集 `per_step × 25 × 1.5` 折算并夹在 1200–5400s。

**停机规则（2026-11-10 追加，2026-11-11 改口径）**：连续 `--idle-limit`（默认 10800s = 3 小时）都没能开出一批，
runner 仍以 `exit 4` 收尾，并把这段时间的 PSI 时间序列（每 10 分钟一个点）写进该批目录的
`host_unfit.md`，`summary.json` 记 `reason=host_unfit`。
**但这不再等于「任务终结」**：由 `hpa_training/supervisor.py` 接管 —— `exit 4` 归为「冷却重试」，
冷却 30–60 分钟（`--idle-cooldown`，默认 45 分钟）后重新采样 PSI 再试；连续 6 小时没完成任何一集
才升级为 `/data/opportunity/ALERT_never_ran.md` 告警（口径：「不是没跑，是一直没跑成」）。
重启 runner 时可用 `--idle-since HH:MM` 承接重启前已等待的起点，避免停机规则被重启清零。

**自动续跑（2026-11-11 上线）**：`supervisor.py` 由容器入口 `hpa_training/main.py` 在启动时拉起
（`HPA_SUPERVISOR=0` 可关闭），因此容器重启、宿主重启后都会自己回来；不需要宿主 root 权限，
且与 trainer 同容器，PSI 读法不变。退避 30s→1min→2min→5min（上限 5min）；退出码分类见下：

| 退出码 | 含义 | 处理 |
|---|---|---|
| 0 | 全批结束 | 进度未满则 60s 后再来；已满则待命 |
| 3 | 训练正在运行 | 可重试（退避） |
| 4 | idle-limit 开不出一批 | **冷却 45 分钟后重测 PSI**（不再终结） |
| 2 | API/参数前置失败 | 可重试；连续 2 次同样签名 → 判死并停 |
| 其他 | 异常退出 | 日志命中 PSI/门禁/崩溃关键词 → 重试；否则连续 2 次同样签名 → 判死 |

台账 `/data/opportunity/supervisor.jsonl`（每轮 `{round,start,end,exit_code,reason,kind,episodes_this_round,episodes_total,next_retry_at,drill}`），
状态出口 `/data/opportunity/supervisor_status.json`（第几轮 / 上次退出原因 / 下次重试时间 / 累计集数 / 各批 done/planned）。
批次进度另存 `/data/opportunity/progress.json`，重启后按已完成集数接续，**不重复计数也不跳号**。

### 结论：`full avg10 < 10%` 只是必要条件（2026-11-10 实测，写死）

2026-11-10 04:12–07:16 共 6 次门禁、13 个 run：

| 门禁 run 的 PSI full avg10 | 结果 |
|---|---|
| 1.47 / 2.00 / 3.50 / 5.36 | PASS |
| 2.64 / 3.10 / 4.94 / 5.43 / 9.64 | FAIL（`Page.screenshot: Target crashed` / 30s 超时） |
| 10.20 / 10.92 / 12.08 / 17.54 | 未启动浏览器（PSI 判据直接拦下） |

**结论：PSI 是必要不充分条件。** 2.64%–5.43% 这个区间里既有 PASS 也有 renderer crash，
说明低 PSI 不能保证浏览器稳定；`3×20s 门禁全过` 才是充分性检查，当前通过标准不变。
另：本次 6 次门禁全败，其中 3 次是「窗口合格但 run 崩」，不是 PSI 超阈值 ——
所以停在 07:16 的原因是「**宿主/门禁不稳定**」，不能简化成「PSI 全程不合格」。

补充判据建议（按优先级，均不改变现有 `3/3` 通过标准）：

1. **最小页面冒烟前置**：门禁第 0 步先 `new_page → goto("data:text/html,<h1>ok</h1>") → screenshot`，
   失败即判「浏览器起不来」并中止本次门禁（不跑完整 3×20s）。作用是把「环境没起来」与
   「业务页面太重」分开，避免误判成 PSI 问题。
2. **门禁失败计入环境不合格连续计数**：一次门禁整体失败 +1，**连续 3 次（跨窗口、不限时）**
   → 直接判「今天宿主不合格」并停，不等满 3 小时。按本次数据，05:36 左右就该收工，省约 100 分钟。
3. **门禁 run 级证据落盘** `/data/opportunity/gate_log.jsonl`：每 run 记
   `{ts, run, pass, shots, seconds, psi_full, err}`，用于拟合「PSI→崩溃率」曲线，
   而不是只留通过/失败两态。
4. **PSI 采样逐点落盘** `/data/opportunity/psi_series.jsonl`（已接入 `opportunity.py`）：
   重启不再丢序列。本次 04:12–04:58 的 17 个采样点就是因为只在内存里而丢失，
   只能事后从 runner.log 重建（`host_unfit_full.md`）。

其中 1、2、3 尚未接线，需确认后再改门禁/停机策略；4 已生效。

启动（容器内，trainer 已 paused）：
```
docker exec -d hpa-trainer sh -c 'PYTHONPATH=/app python -m hpa_training.opportunity \
  --batches "C=notes.ReadNoteContent,G=spotify.QueueAndLikeSong" --episodes 5 \
  >> /data/opportunity/runner.log 2>&1'
```

## 计数器必须落盘（2026-11-10，立为规则）

**规则：所有计数器必须落盘；只在内存里的计数一律视为「重启即失效」，报告里不允许引用
跨重启的累计值。**

触发原因：`swallowed` 本来是用来发现「静默失败」的，结果计数只存在进程内存里，
容器一重建就归零 —— 审计口径问「自上线以来累计多少次」时答不出来，等于自己把数据静默丢了。

实现（`hpa_training/swallowed.py`）：

- 每次记账追加一行到 `/data/swallowed.jsonl`：`{ts, site, exc_type, msg, note}`；
- `GET /api/swallowed?limit=N`（默认 2000）返回**落盘尾部**按 site 的聚合，只回看文件尾部
  （单次 64KB 分块、上限 8MB），不做全量扫描；
- `GET /api/health.swallowed` 只给进程内视图（`scope=process`），它会随重启归零，不能当累计值；
- 落盘失败会 `logger.error` 并计入 `write_errors`，不允许再出现「记账自己失败还静默」。

## 判分结果口径：`env_error` 与 `unjudged`（2026-11-10，统一）

| 字段 | 含义 | 成功率分子 | 成功率分母 | 处理 |
|---|---|---|---|---|
| `env_error` | 环境崩了（崩溃/超时/驱动断开），判分没发生 | 否 | **否** | 单列，算环境账 |
| `unjudged` | 环境正常、跑完了，但判分器没跑（bug） | 否 | **否** | **必须计数并告警**（秤的问题） |
| `judge_skipped` | 判分前置条件不满足（init/last obs 缺失） | 否 | 否 | 单列 |
| `judge_error` | 判分器自身抛异常 | 否 | 否 | 占比 >2% 先修判分器 |

两者历史上有混用过：旧代码 `summary.get("judge_outcome") or "unjudged"` 会把「环境崩了、
根本没判分」误记成 `unjudged`，导致「秤坏了」的数字虚高、环境账被藏进秤里。现已统一：

- `orchestrator._judge_outcome_of()`：有 error → `env_error`；无 error 且无判分 → `unjudged`；
- `opportunity.py` 的 `RESULT_TIMEOUT` / `NO_ROW` 归 `env_error`（环境没给出结果）；
- `judge_health` / `overview` / `failure_stats` 三处同时输出两个计数 + `definitions`。

**报告纪律**：报告里出现这两个词必须带定义；引用历史报告前先确认它是按哪个口径写的。


## 渲染进程崩溃的信号级取证（2026-11-10）

现象（B1）：第 1 步 `Page.screenshot: Target crashed` 反复出现，随后
`Mouse.move: Target page, context or browser has been closed`（t1/t2/t3）或 `Target crashed`（t4）。

取证（只读，不调模型）：

- 容器配额：`memory.max=3G`、`memory.peak=760MB`、`memory.events oom_kill=0`
  → **不是容器配额打满**；宿主 `/proc/vmstat oom_kill=0`（开机至今零次）→ **也不是内核 OOM 击杀**。
  这两句要分开说：前者排除 cgroup 限额，后者排除宿主内核。
- 内核日志（特权容器 `dmesg --ctime`）：崩溃时刻（03:18:47Z）附近**没有任何**
  `segfault` / `general protection fault` / `traps:` 记录 → 不是被内核记下的段错误打死。
- 浏览器 stderr（`DEBUG=pw:browser` + `--enable-logging=stderr --v=1`）：崩溃前**没有**
  `Failed to allocate` / `out of memory` / `mmap` 失败字样；日志末尾只有
  `<- process did exit: exitCode=0, signal=null>`——注意这是 **browser 进程**被 Playwright
  正常关闭的退出码，**不能**当成 renderer 的退出方式。
- Playwright 侧能确认 `page.on("crash")` 被触发（= renderer 进程死亡），随后才是
  `page_close` / `browser_disconnected`。
- 启动参数：`--ignore-certificate-errors`、`--enable-unsafe-swiftshader`、`--disable-dev-shm-usage`；
  **没有** `--single-process`；容器 `/dev/shm` 256MB 且空闲 256MB（未用满）。
- 复现：同一脚本连跑 3 次，1 次在 5.9s 崩（截图 2 张后），2 次跑满 30s 正常；
  容器刚重建、无评测、无探针在跑时同样复现 → 与「残留进程 / 外部关闭」无关。

**当前结论（带条件）**：这是**渲染进程自身终止**（abort / 内部失败退出），
既不是外部关闭，也不是 OOM 击杀；但**无法排除宿主内存压力**（swap 满 + PSI 18%）。
没拿到信号级别（SIGSEGV/SIGBUS/abort）的直接证据，因为 Playwright 默认 `--disable-breakpad`，
renderer 的 stderr 不会被转发；要拿到需要改启动参数 / 开 crashpad，属下一步。

**待办**：宿主内存正常后重跑本节取证；若仍崩，再用启用 crashpad 的启动参数抓信号。

## 启动参数对照：崩溃是不是 SwiftShader 造成的（2026-11-10）

同一页面（`http://127.0.0.1:8081/`）、同一动作序列（20s 截图循环 + 中心 hover/click + 复检截图）、
同一镜像同一容器，只改 `MobileGymEnv.get_launch_args` 追加的 flag（运行时 monkeypatch，
未改产品默认值）。每组之间留 30s，组前后各记一次 `leakguard.scan()`。

### 第一轮（按 A→E 顺序，每组 6 次）

| 组 | 参数 | 崩溃 | 崩溃时刻(s) | 平均首帧(s) | 平均启动(s) | 截图均值 | 泄漏 | 可用 |
|---|---|---|---|---|---|---|---|---|
| A | 现状 | **6/6** | 3.5 / 3.0 / 3.1 / 2.6 / 3.0 / 2.6 | — | 2.84 | 0.0 | 0 | 否 |
| B | +`--disable-gpu` | 2/6 | 8.3 / 3.6 | 5.17 | 2.95 | 15.0 | 0 | 否 |
| C | +`--use-gl=angle --use-angle=swiftshader` | 2/6 | 12.3 / 5.4 | 4.80 | 2.54 | 16.2 | 0 | 否 |
| D | +`--disable-gpu-compositing` | 3/6 | 12.8 / 3.4 / 2.5 | 4.57 | 2.24 | 11.2 | 0 | 否 |
| E | +`--no-zygote` | **0/6** | — | 5.28 | 2.54 | 20.3 | 0 | 是 |

每组前后实例数都是 0 → 0，没有自污染。首帧 = `env.start()` 完成 + 首张截图成功（含浏览器启动与 goto）。

### 但这个顺序有混杂：同期宿主压力在下降

| 时点 | PSI memory some/full（avg10） | MemAvailable | Swap 剩余 |
|---|---|---|---|
| 第一轮开始时（A 组在跑） | 31.05% / **27.44%** | 1.08 G | **16 KB** |
| 第一轮结束时（E 组刚跑完） | 6.87% / 4.97% | 2.79 G | 53.6 MB |

A 跑在最紧的窗口、E 跑在最松的窗口，所以「A 6/6、E 0/6」不能直接归因给参数。

### 第二轮：A/E 交替（各 3 次）

A 1/3（2.9s 崩）、E 0/3 → 差距缩小到不显著。

### 第三轮：6 个随机分块（每组各 6 次，共 30 次，间隔 5s）

| 组 | 崩溃 | 平均首帧(s) | 平均截图 |
|---|---|---|---|
| A | 0/6 | **3.73** | 21.3 |
| B | 0/6 | 4.86 | 21.8 |
| C | 0/6 | 3.81 | 21.7 |
| D | 0/6 | 4.70 | 21.3 |
| E | 0/6 | **5.47** | 20.5 |

此轮 PSI full avg10 3.5–5.0%、MemAvailable 2.05 G：**A 自己也是 0/6**，而且首帧最快；
E 反而最慢（比 A 多约 1.4s，+37%）。

### 崩溃率随宿主压力走，不随参数走

| 观测窗口 | PSI full(avg10) | 基线 A 的崩溃率 |
|---|---|---|
| 第一轮前段 | 27.4% | 6/6（≈3s，都是首帧后第一张截图） |
| 之前的信号取证 | ≈18% | 1/3 |
| 第二/三轮 + 机制采样 | 3.5–9% | 2/13（2.9s、23.3s 各一次，其余 11 次正常） |

剂量-反应关系清楚：压力越高，renderer 越容易在首帧后立刻死；压力低时也不是零，
只是从「每次必崩」降到「十几分之一」——这解释了 B1 里 t1–t4 连续崩、而更早的 3 次取证只崩 1 次。

### 机制：`--no-zygote` 去掉的是 fork 路径，不是 GPU 路径

- 现状（A）进程树里**有两个 zygote**：`--type=zygote --no-zygote-sandbox` 与 `--type=zygote`，
  renderer 由 zygote fork 出来；
- E 组进程树里**没有 zygote**，renderer 由 browser 直接 exec；
- Playwright 自己已经加了 `--no-sandbox`、`--disable-breakpad`、`--disable-dev-shm-usage`，
  子进程带 `--headless=old`、`--use-angle=swiftshader-webgl`；
- B/C/D 只改 GPU/合成路径，**仍然走 zygote**，所以在高压窗口里照样崩（2–3/6）；
  E 是唯一去掉 zygote 的组；
- 合理机制：swap 已满时 fork 一个 VSZ ~33GB 的 zygote，子进程在首次光栅化（~3s）分配内存时失败退出，
  表现为 `page.on("crash")` + `Target crashed`，而内核不记 OOM、容器不记 oom_kill。
  这是**放大器的解释，不是根因**：压力正常时 fork 也没事（第三轮 A 0/6）。

### 结论与建议（不换默认参数）

1. **不把 `--no-zygote` 写进默认启动参数**：它 0/6 的优势被时序混杂解释掉了；在压力正常时 A 同样 0/6，
   且 A 首帧快 1.4s（3.73 vs 5.47）。以当前证据换参数属于「拿速度买一个没被证明的稳定性」。
2. **崩溃的第一自变量是宿主内存压力**，与 SwiftShader/GPU 参数无排他关系；B/C/D 只能把阈值往后挪一点。
3. 入场门（3×20s）保持不变；若要再加一层，应在门禁里同时读
   `/proc/pressure/memory`（full avg10 > 10% 直接判不通过），这比改 chromium 参数更贴近证据。
4. 若高压窗口里仍必须跑，`--no-zygote` 是唯一「去掉 fork 放大路径」的选项，可作为**应急开关**使用，
   但文档里必须写成「降低放大，不解决压力」，并记 +1.4s 首帧成本。

复现脚本：`D:\mobilegym_test\_arg_probe.py`（`--groups A,B,C,D,E --runs 6 --seconds 20 --gap 30`），
NAS 侧 runner `_argprobe/run_arg_probe.sh`，原始日志 `_argprobe/run.log` / `control.log` / `block.log`。
