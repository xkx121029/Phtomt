/**
 * 路由表与端点清单的唯一来源。
 *
 * 为什么单独放一个模块：这三份清单同时被三处消费——Express 路由（sitemap 与
 * /api/routes）、OpenAPI 文档、以及构建期快照脚本。分散定义必然会漂移，
 * 而「文档说有的端点实际不存在」是最难排查的一类问题。
 */

/** 前端路由表（同时驱动 sitemap.xml 与后台的导航预览） */
export const SITE_ROUTES = [
  { path: '/', name: 'home', title: '首页', group: '主要' },
  { path: '/features', name: 'features', title: '功能特性', group: '主要' },
  { path: '/how-it-works', name: 'how', title: '工作原理', group: '主要' },
  { path: '/download', name: 'download', title: '下载', group: '主要' },
  { path: '/changelog', name: 'changelog', title: '更新日志', group: '内容' },
  { path: '/changelog/:version', name: 'changelog-detail', title: '版本详情', group: '内容' },
  { path: '/docs', name: 'docs', title: '文档中心', group: '内容' },
  { path: '/docs/:slug', name: 'docs-detail', title: '文档详情', group: '内容' },
  { path: '/faq', name: 'faq', title: '常见问题', group: '内容' },
  { path: '/about', name: 'about', title: '关于项目', group: '其他' },
  { path: '/admin', name: 'admin', title: '管理后台', group: '其他' }
]

/** 公开端点：无需鉴权，供官网前端与第三方只读消费 */
export const PUBLIC_ENDPOINTS = [
  { method: 'GET', path: '/api/health', desc: '服务健康检查与内容集合计数' },
  { method: 'GET', path: '/api/site', desc: '站点元信息 + 最新版本 + 下载统计' },
  { method: 'GET', path: '/api/bootstrap', desc: '首屏聚合：站点 / 特性 / 版本 / 更新 / FAQ / 文档索引' },
  { method: 'GET', path: '/api/features', desc: '功能特性列表' },
  { method: 'GET', path: '/api/releases', desc: '版本列表，支持 ?channel=stable|beta|all &limit=' },
  { method: 'GET', path: '/api/releases/latest', desc: '最新可下载版本，支持 ?channel=' },
  { method: 'GET', path: '/api/releases/:version', desc: '指定版本详情' },
  { method: 'GET', path: '/api/changelog', desc: '更新日志，支持 ?limit= 或 ?page=&size=' },
  { method: 'GET', path: '/api/changelog/:version', desc: '指定版本的更新记录' },
  { method: 'GET', path: '/api/docs', desc: '文档索引（不含正文）' },
  { method: 'GET', path: '/api/docs/:slug', desc: '单篇文档（含 Markdown 正文）' },
  { method: 'GET', path: '/api/faq', desc: '常见问题，支持 ?group=' },
  { method: 'GET', path: '/api/stats', desc: '下载统计：总量 / 分版本 / 近 14 天' },
  { method: 'GET', path: '/api/routes', desc: '站点路由表与端点清单' },
  { method: 'GET', path: '/api/openapi', desc: 'OpenAPI 3.1 规范（机器可读）' },
  { method: 'GET', path: '/api/download/latest', desc: '重定向到最新版 APK' },
  { method: 'GET', path: '/api/download/:version', desc: '下载指定版本 APK（计入统计）' },
  { method: 'GET', path: '/apk/:file', desc: 'APK 静态直出（带 Range 与缓存）' }
]

/** 管理端点：需要 Authorization: Bearer <token> */
export const ADMIN_ENDPOINTS = [
  { method: 'POST', path: '/api/admin/login', desc: '口令换取管理令牌' },
  { method: 'GET', path: '/api/admin/session', desc: '校验当前令牌是否有效' },
  { method: 'GET', path: '/api/admin/overview', desc: '后台概览：计数 / 存储占用 / 最近操作' },
  { method: 'GET', path: '/api/admin/audit', desc: '操作留痕（最近 300 条）' },
  { method: 'GET', path: '/api/admin/apk', desc: '磁盘上的 APK 文件清单' },
  { method: 'POST', path: '/api/admin/apk', desc: '上传 APK（multipart，字段名 file）' },
  { method: 'DELETE', path: '/api/admin/apk/:file', desc: '删除 APK 文件' },
  { method: 'GET', path: '/api/admin/content/:name', desc: '读取原始集合（site / features / releases / changelog / docs / faq）' },
  { method: 'PUT', path: '/api/admin/content/:name', desc: '整包写入原始集合' },
  { method: 'PUT', path: '/api/admin/site', desc: '整包更新站点元信息' },
  { method: 'PATCH', path: '/api/admin/site', desc: '局部更新站点元信息（深合并）' },
  { method: 'PUT', path: '/api/admin/features', desc: '整包替换功能特性列表' },
  { method: 'POST', path: '/api/admin/features', desc: '新增一条特性' },
  { method: 'PUT', path: '/api/admin/features/:id', desc: '更新指定特性' },
  { method: 'DELETE', path: '/api/admin/features/:id', desc: '删除指定特性' },
  { method: 'POST', path: '/api/admin/releases', desc: '发布新版本（可带 apk 文件名绑定）' },
  { method: 'PUT', path: '/api/admin/releases/:version', desc: '更新版本信息' },
  { method: 'DELETE', path: '/api/admin/releases/:version', desc: '删除版本' },
  { method: 'PUT', path: '/api/admin/changelog', desc: '整包替换更新日志' },
  { method: 'POST', path: '/api/admin/changelog', desc: '新增一条更新日志' },
  { method: 'PUT', path: '/api/admin/changelog/:version', desc: '更新指定版本日志' },
  { method: 'DELETE', path: '/api/admin/changelog/:version', desc: '删除指定版本日志' },
  { method: 'PUT', path: '/api/admin/docs', desc: '整包替换文档' },
  { method: 'POST', path: '/api/admin/docs', desc: '新增文档' },
  { method: 'PUT', path: '/api/admin/docs/:slug', desc: '更新文档' },
  { method: 'DELETE', path: '/api/admin/docs/:slug', desc: '删除文档' },
  { method: 'PUT', path: '/api/admin/faq', desc: '整包替换 FAQ' },
  { method: 'POST', path: '/api/admin/faq', desc: '新增 FAQ' },
  { method: 'PUT', path: '/api/admin/faq/:id', desc: '更新 FAQ' },
  { method: 'DELETE', path: '/api/admin/faq/:id', desc: '删除 FAQ' },
  { method: 'POST', path: '/api/admin/import/changelog', desc: '从 CHANGELOG.md 文本导入更新日志' }
]
