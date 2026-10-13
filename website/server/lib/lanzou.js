import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import { fetch as undiciFetch, Agent } from 'undici'
import { config } from '../config.js'
import * as store from './store.js'
import { zipSingleFile, readFirstFile } from './zip.js'

/**
 * 蓝奏云（lanzou）镜像上传与直链解析。
 *
 * 背景：蓝奏云没有公开 API，这里用的是社区长期沿用的「网页接口」（mlogin / doupload /
 * html5up / ajaxm），随时可能因对方改版而失效。因此本模块的定位是**尽力而为的加速镜像**，
 * 不是更新的唯一通路：任何一步失败，客户端下载都会自动回退到官网直链（/apk）。
 * 管理后台同时提供「手填分享链接 + 提取码」的兜底入口，接口挂了也能手工维持。
 *
 * 凭据单独落一个 lanzou.json（与 credentials.js 同思路），不进 store 的 COLLECTIONS——
 * 那套集合会被 snapshot 脚本烤进静态站，账号一旦混进去等于发到公网。
 */

const CONFIG_FILE = 'lanzou.json'
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

const DEFAULTS = {
  enabled: false,
  autoMirror: true,
  preferMirror: true,
  username: '',
  password: '',
  /** 蓝奏云目标文件夹 id，-1 表示根目录 */
  folderId: -1,
  /** 分享页域名，账号不同域名不同（如 https://www.lanzoue.com）；留空时按官网默认值 */
  domain: 'https://www.lanzoue.com',
  /**
   * 登录成功后落下来的会话 cookie（含 acw_sc__v2 / PHPSESSID 等）。
   *
   * 蓝奏云的登录页挂阿里云智能验证（浏览器 JS），服务端无法自动通过；但登录接口本身
   * 目前不强制它，账密直登大多能过。把拿到的会话 cookie 存下来复用，既省掉每次上传都
   * 重登，也让「真遇到验证码时人工过一次」的收益持续更久（约 1 小时起步）。
   */
  cookies: ''
}

let cache

function configFile() {
  return path.join(config.dataDir, CONFIG_FILE)
}

/** 读配置；文件损坏时留档后回落默认值，不静默重置 */
export function loadConfig() {
  if (cache !== undefined) return cache
  const file = configFile()
  if (!fs.existsSync(file)) {
    cache = { ...DEFAULTS }
    return cache
  }
  try {
    cache = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(file, 'utf8')) }
  } catch (err) {
    const backup = `${file}.broken-${Date.now()}`
    fs.copyFileSync(file, backup)
    console.error(`[lanzou] 配置解析失败，已备份到 ${backup}：${err.message}`)
    cache = { ...DEFAULTS }
  }
  return cache
}

export function saveConfig(patch = {}) {
  const next = { ...loadConfig(), ...patch }
  // 口令留空视为「不修改」，避免前端把打码值回写把真口令冲掉
  if (!patch.password) next.password = loadConfig().password
  const file = configFile()
  const tmp = `${file}.${process.pid}.tmp`
  fs.writeFileSync(tmp, `${JSON.stringify(next, null, 2)}\n`, 'utf8')
  fs.renameSync(tmp, file)
  cache = next
  return next
}

/** 给管理后台看的配置：口令只回「是否已配置」，绝不回明文 */
export function publicConfig() {
  const c = loadConfig()
  return {
    enabled: c.enabled,
    autoMirror: c.autoMirror,
    preferMirror: c.preferMirror,
    username: c.username,
    passwordSet: Boolean(c.password),
    folderId: c.folderId,
    domain: c.domain,
    /** 只回「有没有可复用会话」，绝不回 cookie 明文 */
    cookieSet: Boolean(c.cookies),
    configured: Boolean((c.username && c.password) || c.cookies)
  }
}

// ---------- 阿里云 ESA 反爬挑战（acw_sc__v2）----------
//
// 蓝奏云前面挂着阿里云 ESA（原 WAF），对**非浏览器请求**（服务端 fetch / curl）返回的
// 不是真实页面，而是一段自包含的 JS 挑战：脚本由页面里的 arg1 算出 acw_sc__v2，
// 写进 document.cookie 后 location.reload()，带该 cookie 重放才放行（约 1 小时有效）。
// 这就是为什么「服务端用账密 POST 登录」一直失败——不是账号错，是被反爬拦住了。
//
// 这里不改写算法（对方随时可能换），而是把挑战脚本原样丢进 vm 沙箱跑一遍，
// 只提供一个假的 document.cookie 收集器和一个空的 location.reload，
// 从执行副作用里取回 cookie。算法升级也能自适应。

const ACW_COOKIE = 'acw_sc__v2'

/** 判断响应体是不是 acw_sc__v2 挑战页 */
function isChallenge(text) {
  return typeof text === 'string' && text.includes(ACW_COOKIE) && text.includes('arg1')
}

/**
 * 在 vm 沙箱里执行挑战脚本，返回 { name, value }；失败返回 null。
 * 沙箱不注入 require/process/fetch，脚本拿不到任何宿主能力。
 */
function solveChallenge(html) {
  const script = html.match(/<script>([\s\S]*?)<\/script>/i)?.[1]
  if (!script) return null

  let cookie = ''
  const location = { reload() {}, replace() {}, href: '' }
  const document = {
    get cookie() {
      return ''
    },
    set cookie(v) {
      cookie = String(v)
    },
    location
  }
  const sandbox = { document, location, navigator: { userAgent: UA } }
  sandbox.window = sandbox
  sandbox.self = sandbox
  sandbox.globalThis = sandbox
  try {
    vm.runInNewContext(script, sandbox, { timeout: 2000, filename: 'acw-sc-v2.js' })
  } catch (err) {
    console.warn(`[lanzou] acw_sc__v2 挑战脚本执行失败：${err.message}`)
    return null
  }
  const pair = cookie.split(';')[0]
  const eq = pair.indexOf('=')
  if (eq <= 0) return null
  return { name: pair.slice(0, eq).trim(), value: pair.slice(eq + 1).trim() }
}

// ---------- HTTP 小工具（自带 cookie jar，Node 内置 fetch 不保留会话） ----------

/**
 * 蓝奏云专用的连接池。
 *
 * 为什么不用全局 fetch：Node 内置 fetch（undici）默认 headersTimeout / bodyTimeout 都是
 * 300s。从 NAS 到蓝奏云的带宽实测只有百来 KB/s，一个 39MB 的包实测要 366s（原型验证），
 * 跑到一半就被默认超时掐断，抛出来的还只是一句没有细节的 `fetch failed`——这就是
 * 「自动打包不好使」的根因。这里单独接一个宽松超时的 Agent，只喂给蓝奏云的请求，
 * 不动进程里其它 fetch。超时给「大但有限」的值（而非 0/无限），真遇到卡死仍能自己收场。
 */
const lanzouAgent = new Agent({
  bodyTimeout: 1800000,
  headersTimeout: 600000,
  connect: { timeout: 30000 }
})

function client(initialCookies = '') {
  const jar = new Map()
  // 用历史会话预置 jar：命中就跳过登录，失败再由调用方回退账密
  for (const pair of String(initialCookies).split(';')) {
    const eq = pair.indexOf('=')
    if (eq > 0) jar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim())
  }
  function cookieHeader() {
    return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
  }
  function absorb(res) {
    const list =
      typeof res.headers.getSetCookie === 'function'
        ? res.headers.getSetCookie()
        : [res.headers.get('set-cookie')].filter(Boolean)
    for (const raw of list) {
      const pair = String(raw).split(';')[0]
      const eq = pair.indexOf('=')
      if (eq > 0) jar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim())
    }
  }
  /**
   * 发一次请求并带回完整响应体。
   * 命中反爬挑战时自动解出 cookie 后重放（最多 3 次）。
   * 返回 { ok, status, headers, buffer, text }——body 只读一次，故在此统一读成 Buffer。
   */
  async function request(url, { method = 'GET', headers = {}, body, redirect = 'follow' } = {}) {
    for (let attempt = 0; attempt < 3; attempt++) {
      const res = await undiciFetch(url, {
        method,
        redirect,
        dispatcher: lanzouAgent,
        headers: {
          'User-Agent': UA,
          // 蓝奏云用 Accept-Language 判定「是不是浏览器」：缺了它，直链会返回「验证并下载」
          // 中间页而不是文件本体（实测 text/html vs application/octet-stream）。这是提取直链的硬性前提。
          'Accept-Language': 'zh-CN,zh;q=0.9',
          Accept: '*/*',
          ...(cookieHeader() ? { Cookie: cookieHeader() } : {}),
          ...headers
        },
        body
      })
      absorb(res)
      const buffer = Buffer.from(await res.arrayBuffer())
      const text = buffer.toString('utf8')
      if (isChallenge(text)) {
        const solved = solveChallenge(text)
        if (!solved) throw new Error('蓝奏云反爬挑战解析失败（页面可能已改版）')
        jar.set(solved.name, solved.value)
        continue
      }
      return { ok: res.ok, status: res.status, headers: res.headers, buffer, text }
    }
    throw new Error('蓝奏云反爬挑战未能通过')
  }
  return { request, cookieHeader }
}

/** 表单编码 */
function form(data) {
  const usp = new URLSearchParams()
  for (const [k, v] of Object.entries(data)) usp.append(k, String(v))
  return usp
}

function json(text) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

// ---------- 蓝奏云客户端 ----------

/** 登录被要求人机验证时抛出，交由调用方转成「取验证码图 → 人工输入 → 续登」 */
export class CaptchaRequiredError extends Error {
  constructor(message) {
    super(message || '需要人机验证')
    this.name = 'CaptchaRequiredError'
    this.code = 'captcha'
  }
}

/** 上游 info 文案里是否暗示要验证码 */
function isCaptchaRequired(info) {
  return typeof info === 'string' && /验证码|captcha|verify/i.test(info)
}

export class LanzouClient {
  constructor(opts = {}) {
    const stored = loadConfig()
    const c = { ...stored, ...opts }
    // 表单把口令留空表示「沿用已保存的那份」（与 saveConfig 同语义），别让空值把它冲掉
    this.username = String(c.username || stored.username || '')
    this.password = String(c.password || stored.password || '')
    this.folderId = Number(c.folderId) || -1
    this.domain = String(c.domain || DEFAULTS.domain).replace(/\/$/, '')
    this.http = client(c.cookies)
    this.hasSession = /PHPSESSID=/i.test(String(c.cookies || ''))
  }

  /** 当前 cookie jar，写成请求头格式；登录成功后由调用方存回配置供复用 */
  sessionCookies() {
    return this.http.cookieHeader()
  }

  /**
   * 取一张图片验证码（PNG，绑定当前会话 cookie）。
   * 返回可直接塞进 <img src> 的 data URL；拿不到就抛错。
   */
  async fetchCaptcha() {
    const res = await this.http.request('https://up.woozooo.com/imgcode?verycode_type=2', {
      headers: { Accept: 'image/*,*/*', Referer: 'https://up.woozooo.com/mlogin.php' }
    })
    if (!res.ok || !res.buffer.length) throw new Error('未能取到验证码图片，请稍后重试')
    return `data:image/png;base64,${res.buffer.toString('base64')}`
  }

  /**
   * 登录。
   *
   * 顺序：① 有历史会话且本次不是「补验证码」→ 先探活，能动就直接复用，省掉一次登录；
   * ② 否则走账密接口；被要求人机验证时抛 CaptchaRequiredError 由上层弹窗处理；
   * ③ 最后以「能否列目录」作为真实成功判据（老接口返回体是 HTML，不能只看字段）。
   */
  async login({ verifycode = '' } = {}) {
    if (this.hasSession && !verifycode) {
      const ok = await this.listFiles(-1).then(Array.isArray, () => false)
      if (ok) return true
    }
    if (!this.username || !this.password) {
      throw new Error(this.hasSession ? '蓝奏云会话已失效，请重新登录' : '尚未配置蓝奏云账号')
    }
    const res = await this.http.request('https://up.woozooo.com/mlogin.php?istoken=3', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        Referer: 'https://up.woozooo.com/mlogin.php'
      },
      body: form({
        task: 3,
        uid: this.username,
        // 新接口（istoken=3）用 pwd 收口令；老接口的 upass 会被忽略，导致「密码不对」
        pwd: this.password,
        setSessionId: '',
        ...(verifycode ? { verifycode } : {})
      }).toString()
    })
    const data = json(res.text)
    if (isCaptchaRequired(data?.info)) throw new CaptchaRequiredError(data.info)
    const ok = await this.listFiles(-1).then((list) => Array.isArray(list), () => false)
    if (!ok) throw new Error(data?.info || '蓝奏云登录失败，请检查账号或口令')
    this.hasSession = true
    return true
  }

  /** 文件列表（folder_id=-1 为根目录）；未登录/被踢会返回 html，这里统一抛错 */
  async listFiles(folderId = -1) {
    const res = await this.http.request('https://pc.woozooo.com/doupload.php', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({ task: 5, folder_id: folderId, page: 1 }).toString()
    })
    const data = json(res.text)
    if (!data || !Array.isArray(data.text)) throw new Error('蓝奏云返回异常（可能未登录或接口变更）')
    return data.text
  }

  /** 文件夹列表，供后台上传位置下拉 */
  async listFolders() {
    const res = await this.http.request('https://pc.woozooo.com/doupload.php', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({ task: 47 }).toString()
    })
    const data = json(res.text)
    if (!data || !Array.isArray(data.text)) return []
    return data.text
      .filter((f) => f && (f.fol_id || f.folder_id))
      .map((f) => ({ id: Number(f.fol_id ?? f.folder_id), name: String(f.name ?? f.fol_name ?? '') }))
  }

  /**
   * 上传一个文件，返回 { fileId, shareUrl }。
   * fileId 是蓝奏云给的文件 id，分享页形如 `${domain}/${fileId}`。
   */
  async upload(name, buffer) {
    const boundary = `----hpa${Date.now().toString(16)}`
    const parts = []
    const push = (s) => parts.push(Buffer.from(s, 'utf8'))
    const fields = {
      task: '1',
      id: 'WU_FILE_0',
      name,
      type: 'application/octet-stream',
      lastModifiedDate: new Date().toString(),
      size: String(buffer.length),
      folder_id: String(this.folderId)
    }
    for (const [k, v] of Object.entries(fields)) {
      push(`--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`)
    }
    push(`--${boundary}\r\nContent-Disposition: form-data; name="upload_file"; filename="${name}"\r\n`)
    push('Content-Type: application/octet-stream\r\n\r\n')
    parts.push(buffer)
    push(`\r\n--${boundary}--\r\n`)

    const res = await this.http.request('https://up.woozooo.com/html5up.php', {
      method: 'POST',
      headers: {
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        Referer: 'https://up.woozooo.com/mydisk.php'
      },
      body: Buffer.concat(parts)
    })
    const data = json(res.text)
    const info = data?.text?.[0]
    if (!info || !(info.f_id || info.id)) {
      throw new Error(data?.info || '蓝奏云上传失败（接口可能已变更）')
    }
    // info.f_id 是分享码（拼分享页用），info.id 是数字文件 id（查提取码用）
    const shareCode = String(info.f_id || info.id)
    const numId = String(info.id || info.f_id)
    // is_newd 是账号真实分享域名，没有就退回配置值
    let host = String(info.is_newd || '').startsWith('http')
      ? String(info.is_newd).replace(/\/$/, '')
      : this.domain
    let password = ''
    // 免费账号上传后会被强制加提取码（onof=1 且不可关闭），必须把 pwd 读回来存下，
    // 否则后续解析直链会因为缺提取码报「密码错误」。
    try {
      const shareRes = await this.http.request('https://pc.woozooo.com/doupload.php', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form({ task: 22, file_id: numId }).toString()
      })
      const share = json(shareRes.text)?.info
      if (share) {
        if (String(share.onof) === '1' && share.pwd) password = String(share.pwd)
        if (String(share.is_newd || '').startsWith('http')) host = String(share.is_newd).replace(/\/$/, '')
        if (share.f_id) return { fileId: String(share.f_id), shareUrl: `${host}/${share.f_id}`, password, name: String(info.name_all || name) }
      }
    } catch (err) {
      console.warn(`[lanzou] 读取提取码失败：${err.message}`)
    }
    return { fileId: shareCode, shareUrl: `${host}/${shareCode}`, password, name: String(info.name_all || name) }
  }

  /**
   * 解析分享页 → 直链。
   *
   * 新版分享页已经没有 iframe：页面内联出 isngis(sign) 与 kdns(kd)，直接 POST 到
   * apifile.woozooo.com/ajaxfile.php 拿 {dom,url}。此处优先走新版；老版 iframe(/fn) +
   * ajaxm.php 链路作为兜底。页面改版会让正则失配，任何一步解析不出都抛错，由调用方回落官网直链。
   */
  async resolveDirect(shareUrl, password = '') {
    const page = await this.http.request(shareUrl, { headers: { Referer: `${this.domain}/` } })
    const pageHtml = page.text
    const host = new URL(shareUrl).host

    // 新版：内联 sign + ajaxfile.php
    const sign = pageHtml.match(/isngis\s*=\s*'([^']+)'/)?.[1]
    const kd = pageHtml.match(/kdns\s*=\s*(\d+)/)?.[1] || '1'
    const fid = pageHtml.match(/ajaxfile\.php\?file=(\d+)/)?.[1]
    if (sign && fid) {
      const res = await this.http.request(`https://apifile.woozooo.com/ajaxfile.php?file=${fid}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          Referer: shareUrl,
          'X-Requested-With': 'XMLHttpRequest'
        },
        body: form({ action: 'downprocess', sign, kd, p: password || '' }).toString()
      })
      const data = json(res.text)
      if (data && String(data.zt) === '1' && data.url) {
        return data.dom ? `${data.dom}/file/${data.url}` : `https://${host}/file/${data.url}`
      }
      throw new Error(data?.inf || '蓝奏云未返回直链')
    }

    // 老版：iframe(/fn) + ajaxm.php
    const iframe = pageHtml.match(/<iframe[^>]*src=["']\/?(fn\?[^"']+)["']/i)
    if (!iframe) throw new Error('未能从分享页解析出下载入口（页面可能已改版）')
    const fnUrl = `https://${host}/${iframe[1].replace(/^\//, '')}`
    const fnPage = await this.http.request(fnUrl, {
      headers: { Referer: shareUrl, 'X-Requested-With': 'XMLHttpRequest' }
    })
    const fnHtml = fnPage.text

    // 参数可能以 `var url='/ajaxm.php'` + base64 的 ajaxdata 出现，也可能是内联 JSON
    const urlMatch = fnHtml.match(/url\s*[:=]\s*['"]([^'"]*ajaxm\.php[^'"]*)['"]/i)
    let params = null
    const dataMatch =
      fnHtml.match(/data\s*[:=]\s*['"]([A-Za-z0-9+/=]{20,})['"]/) ||
      fnHtml.match(/['"]([A-Za-z0-9+/=]{40,})['"]/)
    if (dataMatch) {
      const raw = dataMatch[1]
      const decoded = Buffer.from(raw, 'base64').toString('utf8')
      params = json(decoded) || json(decodeURIComponent(decoded))
    }
    if (!params) {
      // 老版页面直接把 file/sign 写进 DOM
      const file = fnHtml.match(/['"]file['"]\s*[:=]\s*['"]?(\d+)/i)?.[1]
      const sign = fnHtml.match(/['"]sign['"]\s*[:=]\s*['"]([^'"]+)/i)?.[1]
      if (file && sign) params = { file, sign }
    }
    if (!params) throw new Error('未能解析出下载参数（页面可能已改版）')

    const ajaxPath = urlMatch ? urlMatch[1] : '/ajaxm.php'
    const ajaxUrl = ajaxPath.startsWith('http') ? ajaxPath : `https://${host}${ajaxPath}`
    const res = await this.http.request(ajaxUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        Referer: fnUrl,
        'X-Requested-With': 'XMLHttpRequest'
      },
      body: form({ action: 'downprocess', ...params, kd: 1 }).toString()
    })
    const data = json(res.text)
    if (!data || String(data.zt) !== '1' || !data.url) {
      throw new Error(data?.inf || '蓝奏云未返回直链')
    }
    const direct = data.dom ? `${data.dom}/file/${data.url}` : `https://${host}/file/${data.url}`
    return direct
  }
}

// ---------- 后台登录（含验证码续登） ----------

/**
 * 待续登的会话：token → { cookies, expiresAt }。
 *
 * 验证码图绑定在会话 cookie（PHPSESSID）上，所以「取图」和「提交验证码」必须共用同一
 * 个 jar。进程内暂存即可——它就活在人机交互的这几十秒里，重启丢失无害。
 */
const pendingCaptcha = new Map()
const CAPTCHA_TTL = 10 * 60 * 1000

function prunePending() {
  const now = Date.now()
  for (const [k, v] of pendingCaptcha) if (v.expiresAt < now) pendingCaptcha.delete(k)
}

/**
 * 用账密尝试登录；命中人机验证时改走「取图 → 人工输入 → 续登」。
 *
 * @returns {{ok:true, folders:Array}|{needCaptcha:true, token:string, image:string, hint:string}}
 */
export async function attemptLogin(input = {}, { captchaToken = '', verifycode = '' } = {}) {
  prunePending()
  const pending = captchaToken ? pendingCaptcha.get(captchaToken) : null
  // 有 pending 时沿用它的 jar；否则不显式传 cookies，让客户端回落到配置里已保存的会话
  const client = new LanzouClient({ ...input, ...(pending ? { cookies: pending.cookies } : {}) })
  try {
    await client.login({ verifycode })
  } catch (err) {
    if (!(err instanceof CaptchaRequiredError)) throw err
    // 续登失败时旧会话仍有效，沿用原 token；首次触发才签发新的
    const token = pending ? captchaToken : `${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`
    const image = await client.fetchCaptcha()
    pendingCaptcha.set(token, { cookies: client.sessionCookies(), expiresAt: Date.now() + CAPTCHA_TTL })
    return { needCaptcha: true, token, image, hint: err.message }
  }
  saveConfig({ cookies: client.sessionCookies() })
  const folders = await client.listFolders().catch(() => [])
  return { ok: true, folders }
}

// ---------- 镜像登记 ----------

export function listMirrors() {
  return store.read('mirrors')
}

export function getMirror(apkFile) {
  return listMirrors()[apkFile] || null
}

/** 记录一条镜像（自动上传与手工录入共用同一张表） */
export function putMirror(apkFile, info) {
  store.update('mirrors', (m) => ({ ...(m || {}), [apkFile]: info }))
  return info
}

export function removeMirror(apkFile) {
  store.update('mirrors', (m) => {
    const next = { ...(m || {}) }
    delete next[apkFile]
    return next
  })
}

/**
 * 把磁盘上的 APK 打包成 zip 上传到蓝奏云，并登记分享信息。
 *
 * 为什么打包成 zip：蓝奏云对 apk/exe 这类可执行后缀做拦截，压成 zip 才能传上去；
 * 客户端下载时由服务端解包还原，用户侧仍是无感的。
 */
export async function mirrorApk(apkFile, apkFullPath) {
  const cfg = loadConfig()
  if (!cfg.enabled) throw new Error('蓝奏云镜像未启用')
  const client = new LanzouClient()
  await client.login()
  // 登录一次就把会话存下来，后续上传/解析复用，少一次被反爬拦的机会
  saveConfig({ cookies: client.sessionCookies() })
  const apkBuffer = fs.readFileSync(apkFullPath)
  const zipName = `${apkFile.replace(/\.apk$/i, '')}.zip`
  const zipBuffer = zipSingleFile(apkFile, apkBuffer)
  const uploaded = await client.upload(zipName, zipBuffer)
  const info = {
    shareUrl: uploaded.shareUrl,
    // 免费账号的文件带强制提取码，解析直链要用它
    password: uploaded.password || '',
    mirrorFile: uploaded.name,
    apkFile,
    size: apkBuffer.length,
    zipSize: zipBuffer.length,
    uploadedAt: new Date().toISOString(),
    source: 'auto'
  }
  putMirror(apkFile, info)
  return info
}

/**
 * 取回镜像里的 APK 字节：下载 zip → 解包出 apk。
 * 任一步失败抛错，调用方负责回退官网直链。
 */
export async function fetchMirrorApk(apkFile, apkFullPath) {
  const mirror = getMirror(apkFile)
  if (!mirror?.shareUrl) throw new Error('该文件没有蓝奏云镜像')
  const client = new LanzouClient()
  const direct = await client.resolveDirect(mirror.shareUrl, mirror.password || '')
  const res = await client.http.request(direct, { headers: { Referer: mirror.shareUrl } })
  if (!res.ok) throw new Error(`蓝奏云下载失败：HTTP ${res.status}`)
  // 反爬兜底：正常情况下直链返回 application/octet-stream，若回的是 text/html
  // 说明又中了「验证并下载」中间页（多见于请求头不合规），此时解包必然失败，给出可读原因。
  if (/text\/html/i.test(res.headers.get('content-type') || '')) {
    throw new Error('蓝奏云返回了验证页（下载被风控拦截）')
  }
  const zipBuffer = res.buffer
  const inner = readFirstFile(zipBuffer)
  if (!inner) throw new Error('蓝奏云返回的包无法解包')
  // 校验解出来的确实是这次要的 APK（大小一致），否则宁可靠回官网那份
  const expected = fs.existsSync(apkFullPath) ? fs.statSync(apkFullPath).size : 0
  if (expected && inner.content.length !== expected) {
    throw new Error('蓝奏云镜像与官网 APK 大小不一致')
  }
  return { content: inner.content, mirror }
}
