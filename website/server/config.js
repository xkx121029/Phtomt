import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
export const ROOT = path.resolve(here, '..')

// 极简 .env 读取：不引 dotenv，避免为几行配置多一个依赖。
function loadEnvFile(file) {
  if (!fs.existsSync(file)) return
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq === -1) continue
    const key = line.slice(0, eq).trim()
    let value = line.slice(eq + 1).trim()
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1)
    }
    if (process.env[key] === undefined) process.env[key] = value
  }
}

loadEnvFile(path.join(ROOT, '.env'))

const abs = (p) => (path.isAbsolute(p) ? p : path.join(ROOT, p))

const adminPassword = process.env.ADMIN_PASSWORD?.trim() || ''
const generatedPassword = adminPassword ? null : crypto.randomBytes(6).toString('base64url')

// happy-hub 对象前缀：默认把官网的安装包放在 packages 桶的 apk/ 目录下。
// 留空表示直接用文件名作为对象键。
const hubPrefix = (process.env.HUB_PREFIX ?? 'apk')
  .trim()
  .replace(/^\/+|\/+$/g, '')

export const config = {
  port: Number(process.env.PORT || 5180),
  /** 未显式配置口令时，每次启动随机生成，仅在控制台可见 */
  adminPassword: adminPassword || generatedPassword,
  generatedPassword,
  adminSecret: process.env.ADMIN_SECRET?.trim() || 'phtomt-dev-secret-do-not-use-in-production',
  /**
   * 是否还在用代码里写死的那个签名密钥。
   * 这个密钥是公开在仓库里的，用它签的令牌谁都能伪造——改密码页会据此给出警告。
   */
  adminSecretIsDefault: !process.env.ADMIN_SECRET?.trim(),
  adminTokenTtlHours: Number(process.env.ADMIN_TOKEN_TTL || 72),
  dataDir: abs(process.env.DATA_DIR || 'server/data'),
  apkDir: abs(process.env.APK_DIR || 'public/apk'),
  distDir: abs('dist'),
  siteOrigin: (process.env.SITE_ORIGIN || '').replace(/\/$/, ''),
  maxApkBytes: Number(process.env.MAX_APK_MB || 200) * 1024 * 1024,
  corsOrigins: (process.env.CORS_ORIGINS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),
  /**
   * happy-hub 文件中枢。APK 实体存到中枢后，下载接口只做 302 跳转，
   * 不再把几百兆的安装包字节压在本服务上；未配置时自动回退本地磁盘。
   */
  hub: {
    baseUrl: (process.env.HUB_URL || '').trim().replace(/\/+$/, ''),
    token: (process.env.HUB_TOKEN || '').trim(),
    bucket: (process.env.HUB_BUCKET || 'packages').trim() || 'packages',
    prefix: hubPrefix ? `${hubPrefix}/` : ''
  },
  /**
   * GitHub Releases 同步。发布版本时把安装包再传一份到 Release，
   * 未配置 repo 或 token 时自动跳过，不影响官网发布。
   */
  github: {
    repo: (process.env.GITHUB_REPO || '').trim(),
    token: (process.env.GITHUB_TOKEN || '').trim(),
    /** 这些渠道的版本建成 pre-release，避免抢占 latest */
    prereleaseChannels: (process.env.GITHUB_PRERELEASE_CHANNELS || 'beta,dev,nightly')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  },
  isProd: process.env.NODE_ENV === 'production'
}

// 站点对外地址缺省时按请求头推断，保证下载链接在任意部署环境下都正确。
export function originOf(req) {
  if (config.siteOrigin) return config.siteOrigin
  const proto = req.headers['x-forwarded-proto'] || req.protocol || 'http'
  return `${proto}://${req.get('host')}`
}

for (const dir of [config.dataDir, config.apkDir]) {
  fs.mkdirSync(dir, { recursive: true })
}
