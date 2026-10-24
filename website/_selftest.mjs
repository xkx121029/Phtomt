// 一次性上传闭环测试：打包→上传→解析直链→下载还原→清理。用完即删。
import fs from 'node:fs'
import path from 'node:path'
import * as lanzou from './server/lib/lanzou.js'

const NAME = '_hpa_selftest.apk'
const full = path.join(process.cwd(), NAME)

// 1) 造一个 8KB 的假包，并启用镜像
fs.writeFileSync(full, Buffer.alloc(8192, 0x5a))
lanzou.saveConfig({ enabled: true })
console.log('enabled =', lanzou.loadConfig().enabled)

let uploaded = null
try {
  // 2) 打包 + 上传
  uploaded = await lanzou.mirrorApk(NAME, full)
  console.log('uploaded =', JSON.stringify(uploaded))

  // 3) 解析分享页直链
  const client = new lanzou.LanzouClient()
  const direct = await client.resolveDirect(uploaded.shareUrl)
  console.log('direct =', direct)

  // 4) 下载镜像并解包还原（走 fetchMirrorApk 全链路）
  const got = await lanzou.fetchMirrorApk(NAME, full)
  console.log('fetched bytes =', got.content.length, 'match =', got.content.length === 8192)

  // 5) 从蓝奏云删除测试文件（task=6）
  const res = await client.http.request('https://pc.woozooo.com/doupload.php', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ task: '6', file_id: String(uploaded.fileId) }).toString()
  })
  console.log('delete resp =', res.text.slice(0, 200))
} catch (err) {
  console.log('FAILED =', err.message)
} finally {
  lanzou.removeMirror(NAME)
  lanzou.saveConfig({ enabled: false })
  fs.existsSync(full) && fs.unlinkSync(full)
  console.log('cleaned up')
}