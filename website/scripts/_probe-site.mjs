// 临时探针：验证 content.site() 到底 resolve 出什么（用完即删）
import { createServer } from 'vite'

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})

// 用真实接口的响应体喂给 fetch 桩，避免 Node 相对 URL 的限制
const res = await fetch('http://127.0.0.1:5180/api/site')
const body = await res.text()
globalThis.fetch = async () => ({ ok: true, status: 200, text: async () => body })

const mod = await vite.ssrLoadModule('/src/api/client.js')
const site = await mod.content.site()
console.log('content.site() ->', site === undefined ? 'undefined' : `object, version=${site.version}`)

await vite.close()
