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
| GET | `/api/download/latest` | 重定向到最新版 APK |
| GET | `/api/download/:version` | 下载指定版本 APK（计入统计） |
| GET | `/apk/:file` | APK 静态直出（带 Range 与缓存） |

## 管理端点

全部需要 `Authorization: Bearer <token>`。

### 会话与概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/login` | 口令换取管理令牌 |
| GET | `/api/admin/session` | 校验当前令牌是否有效 |
| GET | `/api/admin/overview` | 后台概览：计数 / 存储占用 / 最近操作 |
| GET | `/api/admin/audit` | 操作留痕（最近 300 条） |

### APK 文件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/apk` | 磁盘上的 APK 文件清单 |
| POST | `/api/admin/apk` | 上传 APK（multipart，字段名 `file`） |
| DELETE | `/api/admin/apk/:file` | 删除 APK 文件 |

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
| `releases.json` | 版本与 APK 绑定 |
| `changelog.json` | 更新日志 |
| `docs.json` | 文档（`body` 为 Markdown） |
| `faq.json` | 常见问题 |
| `stats.json` | 下载计数 |

APK 文件放在 `public/apk/`。写入接口会先写临时文件再改名，避免中途失败留下半截 JSON。

## 静态导出

`npm run build:static` 会把 `server/data` 烘成前端快照 `src/data/fallback.js`（由 `scripts/snapshot.mjs` 生成，勿手工编辑），供 GitHub Pages / Gitee Pages 这类没有后端的部署使用。快照里不含管理端点，也不含任何令牌。
