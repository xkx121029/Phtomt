# 官网内容 API

官网服务（`website/server`）的完整端点说明。这份文档替代了原先网站上的「API 参考」页面与「官网内容 API」文档——接口说明属于工程文档，不该出现在面向用户的站点上。

机器可读版本：服务启动后访问 `/api/openapi`（OpenAPI 3.1）。
端点清单的唯一来源：`server/lib/endpoints.js`，本文件与它保持同步；改接口时请一并更新。

## 基地址

| 环境 | 地址 |
| --- | --- |
| 本地开发 | `http://localhost:5180` |
| 自定义端口 | 设环境变量 `PORT`（默认 `5180`） |
| 生产 | 由 `SITE_ORIGIN` 决定对外地址 |

下文示例统一用 `$BASE` 指代基地址。

## 鉴权

管理端点需要令牌，公开端点不需要。

```bash
# 口令来自 .env 的 ADMIN_PASSWORD；未配置时每次启动随机生成，只在控制台打印
curl -s -X POST "$BASE/api/admin/login" \
  -H 'Content-Type: application/json' \
  -d '{"password":"你的口令"}'
# → {"token":"1758...xxxx","expiresAt":1758...}
```

之后所有管理请求带上 `Authorization: Bearer <token>`。令牌默认有效期 72 小时（`ADMIN_TOKEN_TTL` 可调）。

## 公开端点

无需鉴权，可直接在 CI 或第三方页面里读取。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 服务健康检查与内容集合计数 |
| GET | `/api/site` | 站点元信息 + 最新版本 + 下载统计 |
| GET | `/api/bootstrap` | 首屏聚合：站点 / 特性 / 版本 / 更新 / FAQ / 文档索引 |
| GET | `/api/features` | 功能特性列表 |
| GET | `/api/releases` | 版本列表，支持 `?channel=stable\|beta\|all` 与 `?limit=` |
| GET | `/api/releases/latest` | 最新可下载版本，支持 `?channel=` |
| GET | `/api/releases/:version` | 指定版本详情 |
| GET | `/api/changelog` | 更新日志，支持 `?limit=` 或 `?page=&size=` |
| GET | `/api/changelog/:version` | 指定版本的更新记录 |
| GET | `/api/docs` | 文档索引（不含正文） |
| GET | `/api/docs/:slug` | 单篇文档（含 Markdown 正文） |
| GET | `/api/faq` | 常见问题，支持 `?group=` |
| GET | `/api/stats` | 下载统计：总量 / 分版本 / 近 14 天 |
| GET | `/api/routes` | 站点路由表与端点清单 |
| GET | `/api/openapi` | OpenAPI 3.1 规范（机器可读） |
| GET | `/api/download/latest` | 302 到最新版对应的 Release 资产 |
| GET | `/api/download/:version` | 302 到指定版本的 Release 资产（计入统计）；`?redirect=0` 返回 JSON |
| GET | `/api/download/sources/:version` | 该版本可用的下载候选（Gitee 已验证 + GitHub 按规则拼） |
| GET | `/api/update/check` | 客户端检查更新（频道 + 版本判定） |
| GET | `/api/update/download/:version` | 客户端下载更新（302 到 Release 资产，与官网同一套择优规则） |
| POST | `/api/feedback` | 客户端执行数据反馈（用户主动开启后上报，服务端按白名单重建并落盘） |

### 客户端自动更新

`/api/update/check` 与 `/api/releases` 的区别：后者给官网页面看（返回列表与装饰字段），前者给**装好的 App 自己**用——按客户端所在频道判定该不该升级，并给出一个拿得到就能装的下载地址。

频道矩阵（`server/lib/update.js` 的 `CHANNEL_ORDER`）是产品约定，只在那一个文件里定义：

| 客户端频道 | 可接收的发布渠道 |
| --- | --- |
| `stable` | stable |
| `beta` | beta、stable |
| `dev` | dev、beta、stable |
| `nightly` | nightly、dev、beta、stable |

```bash
curl -s "$BASE/api/update/check?channel=beta&version=0.2.780&build=780"
```

| 参数 | 说明 |
| --- | --- |
| `channel` | 客户端接收频道，未知值按 `stable` 处理 |
| `version` | 客户端当前 versionName；**留空则只回该频道可见范围内的最新一版，`hasUpdate` 恒为 false** |
| `build` | 客户端当前 versionCode；版本号相同、构建号更高也算有更新 |

响应字段：`channel` / `clientVersion` / `clientBuild` / `hasUpdate` / `latest` / `download`。`download.url` 恒为 `/api/update/download/:version`；`download.mirror` 恒为 `release`，`shareUrl` 为 `null`、`password` 为空串（已没有网盘镜像这一层）。

`/api/update/download/:version` 与 `/api/download/:version` 同源：按版本记录里的资产名去 Gitee 查附件（有则优先）、按仓库规则拼 GitHub 资产地址，命中第一个就 302（响应头 `X-Update-Source` 标明来源）。两边都拿不到时 302 到 Release 页面，而不是回落到本站文件——本站不再保存任何安装包。

## 执行数据反馈

App 里的「云端反馈」默认关闭。用户主动开启后，每次任务结束向 `/api/feedback` 上报一条脱敏记录；关闭时客户端不采集、不落盘、不联网。

请求体只包含结构性指标，客户端与服务端都会做白名单裁剪：

| 字段 | 说明 |
| --- | --- |
| `schema` / `event` | 协议版本与事件名（固定 `task_finished`） |
| `installId` | 客户端随机生成的安装 UUID，不来自系统标识 |
| `app` | version / build / android API 级别 |
| `task` | 状态、总耗时、步数、成功步数、动作类型分布、每步 `{type, send, verify, ok, ms}` |
| `metrics` | token 用量、请求次数、平均延迟、视觉与执行次数/耗时 |

**不接收**：任务文本、用户输入内容、页面元素文字、坐标、截图、应用包名、设备型号、账号信息。`4xx` 明确拒绝，`5xx` 时客户端保留在本地队列下次重试。

响应：`202 { "ok": true, "id": "<记录 ID>" }`，数据按月写入 `server/data/feedback/YYYY-MM.jsonl`。

## 管理端点

全部需要 `Authorization: Bearer <token>`。

### 会话与概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/login` | 口令换取管理令牌 |
| GET | `/api/admin/session` | 校验当前令牌是否有效 |
| GET | `/api/admin/overview` | 后台概览：计数 / 数据目录 / 最近操作 |
| GET | `/api/admin/audit` | 操作留痕（最近 300 条） |
| GET | `/api/admin/feedback` | 客户端执行数据反馈（最近两个月，默认 100 条）与统计 |
| DELETE | `/api/admin/feedback` | 清除反馈记录；`?month=YYYY-MM` 指定月份，缺省清空最近 12 个月 |

### 版本与安装包

安装包实体不在本站：后台只登记资产名、体积与 SHA-256，实体由 CI 上传到 GitHub / Gitee 的 Release。没有上传、列表、删除 APK 的端点。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/releases` | 版本列表（含登记的 `apk` 字段） |
| POST | `/api/admin/releases` | 新增版本（`apk` 传 `{ file, size, sha256 }`） |
| PUT | `/api/admin/releases/:version` | 更新版本 |
| DELETE | `/api/admin/releases/:version` | 删除版本 |

### 内容集合

`:name` 取 `site` / `features` / `releases` / `changelog` / `docs` / `faq`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/content/:name` | 读取原始集合 |
| PUT | `/api/admin/content/:name` | 整包写入原始集合 |

### 站点信息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/admin/site` | 整包更新 |
| PATCH | `/api/admin/site` | 局部更新（深合并） |

### 功能特性

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/admin/features` | 整包替换列表 |
| POST | `/api/admin/features` | 新增一条 |
| PUT | `/api/admin/features/:id` | 更新指定条目 |
| DELETE | `/api/admin/features/:id` | 删除指定条目 |

### 版本

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/releases` | 发布新版本（可带 apk 文件名绑定） |
| PUT | `/api/admin/releases/:version` | 更新版本信息 |
| DELETE | `/api/admin/releases/:version` | 删除版本 |

### 更新日志

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/admin/changelog` | 整包替换 |
| POST | `/api/admin/changelog` | 新增一条 |
| PUT | `/api/admin/changelog/:version` | 更新指定版本 |
| DELETE | `/api/admin/changelog/:version` | 删除指定版本 |
| POST | `/api/admin/import/changelog` | 从 CHANGELOG.md 文本导入 |

### 文档

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/admin/docs` | 整包替换 |
| POST | `/api/admin/docs` | 新增 |
| PUT | `/api/admin/docs/:slug` | 更新 |
| DELETE | `/api/admin/docs/:slug` | 删除 |

### 常见问题

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/api/admin/faq` | 整包替换 |
| POST | `/api/admin/faq` | 新增 |
| PUT | `/api/admin/faq/:id` | 更新 |
| DELETE | `/api/admin/faq/:id` | 删除 |

## 常用操作

### 发布一个新版本

```bash
# 1) 上传 APK
curl -X POST "$BASE/api/admin/apk" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@app/build/outputs/apk/debug/app-debug.apk"
# → {"file":"app-debug.apk","size":14942825,"sha256":"..."}

# 2) 绑定到版本
curl -X POST "$BASE/api/admin/releases" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"version":"v0.1.372","build":372,"channel":"stable",
       "apk":{"file":"app-debug.apk"},
       "notes":["修复悬浮窗遮挡"]}'
```

### 导入 CHANGELOG

把项目根目录的 `CHANGELOG.md` 直接推上来，服务端会解析成结构化条目：

```bash
curl -X POST "$BASE/api/admin/import/changelog" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(node -e 'console.log(JSON.stringify({mode:"merge",markdown:require("fs").readFileSync("CHANGELOG.md","utf8")}))')"
```

`mode` 取 `merge`（按版本号合并，默认）或 `replace`（整包替换）。

### 改站点文案

`PATCH` 做深合并，只传要改的字段：

```bash
curl -X PATCH "$BASE/api/admin/site" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tagline":"让手机自己动手","version":"0.1.372"}'
```

### 只读消费

```bash
curl -s "$BASE/api/bootstrap"        # 首屏聚合
curl -s "$BASE/api/releases/latest"  # 最新版本
curl -s "$BASE/api/stats"            # 下载统计
```

## 数据存储

内容是 JSON 文件，落在 `server/data/`，没有数据库：

| 文件 | 内容 |
| --- | --- |
| `site.json` | 站点元信息、外链、通道定义、系统要求 |
| `features.json` | 功能特性 |
| `releases.json` | 版本记录（含资产名 / 体积 / SHA-256 登记，不含包体） |
| `changelog.json` | 更新日志 |
| `docs.json` | 文档（`body` 为 Markdown） |
| `faq.json` | 常见问题 |
| `stats.json` | 下载计数 |

写入接口会先写临时文件再改名，避免中途失败留下半截 JSON。

安装包**不落本站磁盘**：`/api/download/*` 与 `/api/update/download/*` 只做 302，指向 GitHub / Gitee 的 Release 资产；旧的 `/apk/*` 一律返回 410。

## 下载源解析

服务端不做文件存储，也没有镜像开关或发布同步：`server/lib/externalAssets.js` 把版本记录翻译成候选地址——

1. 用 `site.links.gitee` 里的仓库去查 Gitee API 的 Release 列表（5 分钟内存缓存），按 tag / 资产名匹配附件，命中才算候选；
2. 用 `site.links.github` 里的仓库按 `releases/download/<tag>/<资产名>` 规则直接拼 GitHub 地址（不依赖 API，避免限流）。

`/api/download/sources/:version` 返回这份候选列表；浏览器对每个候选做一次 no-cors HEAD 实测首字节，取响应更快的那个（只反映 TTFB，不代表实际下载吞吐）。都测不通时页面回落到服务端 302（第一个候选或 Release 页）。

## 静态导出

`npm run build:static` 会把 `server/data` 烘成前端快照 `src/data/fallback.js`（由 `scripts/snapshot.mjs` 生成，勿手工编辑），供 GitHub Pages / Gitee Pages 这类没有后端的部署使用。快照里不含管理端点，也不含任何令牌。

构建时若注入 `VITE_API_BASE`（例如 `https://hpa.linshuxu.us.kg`），静态站会直接请求主站的真实接口，更新日志、发布信息与问答都实时同步；接口不可达（网络失败或 5xx）时自动回落到快照，页面不会白屏。

跨域回连需要主站放行来源，否则浏览器会拦截读请求：

```bash
# 主站（Cloudflare 等）环境变量
CORS_ORIGINS=https://xkx121029.github.io
```

未配置 `VITE_API_BASE` 时行为不变：读快照、隐藏写操作与问答入口。
