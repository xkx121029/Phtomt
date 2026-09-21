import { PUBLIC_ENDPOINTS, SITE_ROUTES, ADMIN_ENDPOINTS } from './endpoints.js'


/**
 * 生成 OpenAPI 3.1 规范。
 *
 * 为什么手写而不是从代码注解生成：端点只有三十来个、结构稳定，
 * 手写反而更准，也不需要为了生成规范给项目塞一套装饰器运行时。
 */
export function openApiDocument(origin) {
  const all = [...PUBLIC_ENDPOINTS, ...ADMIN_ENDPOINTS]
  const paths = {}
  for (const ep of all) {
    const path = ep.path.replace(/:([A-Za-z0-9_]+)/g, '{$1}')
    const params = [...ep.path.matchAll(/:([A-Za-z0-9_]+)/g)].map((m) => ({
      name: m[1],
      in: 'path',
      required: true,
      schema: { type: 'string' }
    }))
    paths[path] = paths[path] || {}
    paths[path][ep.method.toLowerCase()] = {
      summary: ep.desc,
      tags: [ep.path.includes('/admin/') ? '管理' : ep.path.includes('/download') ? '下载' : '公开'],
      security: ep.path.includes('/admin/') && !ep.path.endsWith('/login') ? [{ bearerAuth: [] }] : undefined,
      parameters: params.length ? params : undefined,
      responses: {
        200: { description: '成功' },
        401: { description: '未授权（管理端点）' },
        404: { description: '资源不存在' }
      }
    }
  }

  return {
    openapi: '3.1.0',
    info: {
      title: 'Happy Phone Agent 官网内容 API',
      version: '1.0.0',
      description:
        '官网全部内容都由这些端点提供。公开端点只读，管理端点需要 `Authorization: Bearer <token>`。'
    },
    servers: [{ url: origin }],
    tags: [
      { name: '公开', description: '无需鉴权，供官网前端与第三方只读消费' },
      { name: '下载', description: 'APK 下载与统计' },
      { name: '管理', description: '内容写入，需要管理令牌' }
    ],
    components: {
      securitySchemes: {
        bearerAuth: { type: 'http', scheme: 'bearer', description: 'POST /api/admin/login 获取' }
      }
    },
    paths,
    'x-site-routes': SITE_ROUTES
  }
}
