// 联网抓取工具（能力 #17/#18 的桌面侧补充：手机上浏览网页走宿主 WebView，
// 但查文档、读 README、拉 API 说明这类"只要文本"的场景，用 HTTP 抓更省事）。
//
// 边界：
//   - 只允许 http/https（file: / data: 之类直接拒）；
//   - 拦内网/回环/链路本地/云元数据地址（SSRF 防护），重定向每一跳都重新校验；
//   - 20s 超时、2MB 上限（流式读取，不到 2MB 就把整个 body 读进内存）；
//   - HTML 粗转文本，保留段落换行，去掉脚本/样式；
//   - 只做 GET，不发送工作区内容——所以归为 read 风险，不打断用户。

const MAX_BYTES = 2_000_000;
const TIMEOUT_MS = 20_000;
const MAX_REDIRECTS = 3;

/** 内网/回环/元数据地址判断（SSRF 防护）。主机名解析到内网 IP 的 DNS 绕过不在此列。 */
function isPrivateHost(hostname) {
  const h = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '');
  if (!h) return true;
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) return true;
  if (h === 'metadata.google.internal' || h === 'metadata.goog' || h === 'instance-data') return true;
  // IPv6 回环 / 链路本地 / 唯一本地
  if (h === '::1' || h.startsWith('fe80:') || h.startsWith('fc') || h.startsWith('fd')) return true;
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  if (a === 0 || a === 10 || a === 127) return true; // 0/8, 10/8, 127/8
  if (a === 169 && b === 254) return true; // 169.254/16 链路本地 + 云元数据
  if (a === 172 && b >= 16 && b <= 31) return true; // 172.16/12
  if (a === 192 && b === 168) return true; // 192.168/16
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
  if (a >= 224) return true; // 组播/保留
  return false;
}

function isPublicHttpUrl(u) {
  try {
    const parsed = new URL(u);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
    return !isPrivateHost(parsed.hostname);
  } catch {
    return false;
  }
}

/** 流式读 body，最多 maxBytes；超了就地停（防慢速大响应拖死 agent）。 */
async function readCapped(res, maxBytes) {
  if (!res.body) return (await res.text()).slice(0, maxBytes);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  const chunks = [];
  let total = 0;
  try {
    while (total < maxBytes) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      chunks.push(decoder.decode(value, { stream: true }));
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* already closed */
    }
  }
  return chunks.join('').slice(0, maxBytes);
}

function htmlToText(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<svg[\s\S]*?<\/svg>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<\/(p|div|li|tr|h[1-6]|section|article|pre)>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/[ \t\u00a0]+/g, ' ')
    .replace(/\n[ \t]+/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

export function createWebTools({ fetchImpl = fetch } = {}) {
  const web_fetch = {
    name: 'web_fetch',
    description:
      'Fetch a URL over HTTP(S) and return its text (HTML is converted to readable text). Use it to check library docs, READMEs or API references. Only GET, no credentials are sent.',
    risk: 'read',
    parameters: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'http:// or https:// URL' },
        max_chars: { type: 'integer', description: 'Max characters to return (default 8000)' },
      },
      required: ['url'],
    },
    async execute({ url, max_chars = 8000 }) {
      let target = String(url);
      if (!isPublicHttpUrl(target)) {
        try {
          new URL(target);
        } catch {
          throw new Error(`invalid URL: ${url}`);
        }
        throw new Error(
          `refusing to fetch ${target}: only http/https public URLs are allowed ` +
            '(internal, loopback and cloud-metadata addresses are blocked — SSRF guard)',
        );
      }

      const ac = new AbortController();
      const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
      let res;
      let current = new URL(target);
      try {
        // 手动跟随重定向，每一跳都重新过 SSRF 检查：
        // redirect:'follow' 会让一个公网 URL 用 302 把请求送进内网。
        for (let hop = 0; ; hop += 1) {
          res = await fetchImpl(current.toString(), {
            method: 'GET',
            redirect: 'manual',
            signal: ac.signal,
            headers: { 'user-agent': 'happy_ACA/0.4 (+agent)', accept: 'text/html,text/plain,application/json;q=0.9,*/*;q=0.5' },
          });
          const location = res.headers.get('location');
          const isRedirect = res.status >= 300 && res.status < 400 && location;
          if (!isRedirect) break;
          if (hop >= MAX_REDIRECTS) throw new Error(`too many redirects (>${MAX_REDIRECTS}) from ${target}`);
          const next = new URL(location, current);
          if (!isPublicHttpUrl(next.toString())) {
            throw new Error(`refusing to follow redirect to ${next} (SSRF guard)`);
          }
          current = next;
        }
      } catch (err) {
        if (err?.name === 'AbortError') throw new Error(`timed out after ${TIMEOUT_MS / 1000}s: ${current}`);
        throw new Error(`fetch failed: ${err.message}`);
      } finally {
        clearTimeout(timer);
      }

      if (!res.ok) throw new Error(`HTTP ${res.status} from ${current}`);

      const type = res.headers.get('content-type') || '';
      const raw = await readCapped(res, MAX_BYTES);
      const isHtml = type.includes('html') || /^\s*<(!doctype|html)/i.test(raw);
      const title = isHtml ? (/<title[^>]*>([\s\S]*?)<\/title>/i.exec(raw)?.[1] || '').trim() : '';
      const body = isHtml ? htmlToText(raw) : raw;
      const limit = Math.min(50000, Math.max(500, max_chars));
      const clipped = body.length > limit ? `${body.slice(0, limit)}\n... [truncated, ${body.length - limit} more chars]` : body;

      const head = [`url: ${current}`, `content-type: ${type || '(none)'}`, title ? `title: ${title}` : null]
        .filter(Boolean)
        .join('\n');
      return `${head}\n\n${clipped}`;
    },
  };

  return [web_fetch];
}
