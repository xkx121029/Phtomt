<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import CopyButton from '../components/CopyButton.vue'
import { content, apiBaseUrl } from '../api/client.js'
import { channelLabel, downloadUrl, formatDate, isExternalDownload, relativeTime } from '../lib/format.js'
import { PROBE_HINT_DELAY, resolveDownloadSource } from '../lib/downloadSource.js'
import { latestRelease } from '../lib/latest.js'

const site = ref({})
const releases = ref([])
const stats = ref(null)
const ready = ref(false)

/** 择优结果：拿到前主按钮沿用原来的默认直链，拿到后换成首字节响应更快的那个 */
const best = ref(null)
const probing = ref(false)
const probeNote = ref('')
let hintTimer = null

// 与首页同一判据（src/lib/latest.js）：服务端 downloadTarget 优先稳定版，没有稳定版再退最新测试包
const latest = computed(() => latestRelease({ releases: releases.value, site: site.value }))
const rest = computed(() => releases.value.slice(1))

/** 没有稳定版时明说，别让用户以为这是正式版：主卡片已按「稳定版优先」取，取到非 stable 就说明一条稳定版都没有 */
const noStable = computed(() => Boolean(latest.value) && latest.value.channel !== 'stable')
/** 版本数据来自 GitHub；拉不到时走本地兜底，这行提示必须出现 */
const feedDegraded = computed(() => Boolean(site.value.releaseSource?.degraded))
const feedUpdated = computed(() => relativeTime(site.value.releaseSource?.fetchedAt) || '')

const primaryUrl = computed(() =>
  best.value?.url || (latest.value ? downloadUrl(latest.value, site.value) : site.value.links?.releases || '#')
)
/** 备用入口：两个 Release 页；安装包只在这两个平台，官网上没有副本 */
const giteePage = computed(() => {
  const base = String(site.value.links?.gitee || '').replace(/\/$/, '')
  return base ? `${base}/releases` : ''
})

const REQS = computed(() => {
  const r = site.value.requirements || {}
  return [
    { label: '系统版本', value: `Android ${r.minAndroid || '8.0'} 及以上（API ${r.minApi || 26}）` },
    { label: '目标 API', value: String(r.targetApi || 35) },
    { label: 'CPU 架构', value: r.arch || 'arm64-v8a / armeabi-v7a' },
    { label: '包名', value: r.packageName || 'com.phoneagent' }
  ]
})

const STEPS = [
  {
    title: '允许安装未知来源',
    body: '当前提供的是 Debug 构建，系统会拦截。在「设置 → 应用 → 特殊权限」里为浏览器或文件管理器放行。'
  },
  {
    title: '核对 SHA-256',
    body: '把下载到的文件与页面上给出的校验值比对。命令行用 sha256sum（Linux / macOS）或 Get-FileHash（Windows）。'
  },
  {
    title: '逐项授权',
    body: '无障碍服务是必须的——它是 Agent 的手和眼；悬浮窗与通知可选，关掉后任务照常执行，只是看不到实时进度。'
  },
  {
    title: '配置模型',
    body: '设置 → 模型配置，填任意 OpenAI 兼容端点。必须点「测试连接」通过后才允许保存。'
  }
]

/**
 * 后台测速：不阻塞首屏，按钮先用默认直链；检测超过 300ms 才在按钮旁显示「正在检测线路…」，
 * 几毫秒内出结果的话用户根本看不到这行字。全部测不通就留在默认源，并给一句解释。
 */
async function detect(refresh = false) {
  if (!latest.value?.apk?.file) return
  clearTimeout(hintTimer)
  probing.value = false
  probeNote.value = ''
  const started = Date.now()
  hintTimer = setTimeout(() => {
    probing.value = true
  }, PROBE_HINT_DELAY)

  const result = await resolveDownloadSource(latest.value, { site: site.value, refresh })
  const elapsed = Date.now() - started
  clearTimeout(hintTimer)
  probing.value = false

  if (result?.degraded) {
    probeNote.value = '两条线路都没测通，已使用默认下载源'
    return
  }
  if (result?.url) {
    best.value = result
    return
  }
  // 探测本身失败（接口不可达/超时）：默认源照常能点，只是多一句说明
  if (elapsed >= PROBE_HINT_DELAY) probeNote.value = '线路检测超时，已使用默认下载源'
}

/**
 * 按钮直接指向外部 Release 资产时，下载不再经过本站，用 beacon 补记一次下载数。
 * （默认跳转入口由服务端 302，那边已经计过数，不要重复记。）
 */
function onPrimaryClick() {
  if (!best.value?.url || !latest.value?.version) return
  try {
    navigator.sendBeacon(`${apiBaseUrl()}/api/download/hit/${encodeURIComponent(latest.value.version)}`)
  } catch {
    /* 计数失败不影响下载 */
  }
}

onMounted(async () => {
  try {
    const boot = await content.bootstrap()
    site.value = boot.site || {}
    releases.value = boot.releases || []
    stats.value = boot.site?.stats || null
  } catch {
    /* 读取失败时页面仍可展示骨架，下载按钮退回 GitHub Releases */
  } finally {
    ready.value = true
    detect()
  }
})
</script>

<template>
  <div>
    <PageHero
      eyebrow="下载"
      title="拿到 APK，装到自己的手机上"
      lead="当前只有 Debug 构建，面向内测与自用。每个版本都给出 SHA-256，装之前可以自行核对。"
    />

    <section class="section section--tight">
      <div class="page">
        <!-- 主下载卡 -->
        <article v-if="latest" v-reveal class="card hero-card">
          <div class="hero-card__main">
            <div class="hero-card__meta">
              <span class="tag tag--brand">{{ channelLabel(latest.channel) }}</span>
              <span v-if="latest.build" class="tag mono">build {{ latest.build }}</span>
              <span class="tag">{{ formatDate(latest.date) }}</span>
            </div>

            <h2 class="hero-card__version mono">v{{ String(latest.version).replace(/^v/i, '') }}</h2>
            <p v-if="latest.title" class="hero-card__title">{{ latest.title }}</p>
            <p v-if="latest.summary" class="hero-card__summary">{{ latest.summary }}</p>

            <ul v-if="latest.notes?.length" class="notes">
              <li v-for="note in latest.notes" :key="note">{{ note }}</li>
            </ul>

            <div class="hero-card__actions">
              <a
                class="btn btn--primary btn--lg"
                :href="primaryUrl"
                :target="isExternalDownload(latest) ? '_blank' : undefined"
                :rel="isExternalDownload(latest) ? 'noopener noreferrer' : undefined"
                @click="onPrimaryClick"
              >
                <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M10 3v9m0 0 3.5-3.5M10 12 6.5 8.5M4 15.5h12" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <template v-if="latest.apk">
                  下载 {{ latest.apk.file }}（{{ latest.apk.sizeText }}）
                </template>
                <template v-else>前往 GitHub Releases</template>
              </a>
              <RouterLink to="/docs/getting-started" class="btn btn--lg">安装说明</RouterLink>
              <span v-if="probing" class="hero-card__probing">正在检测线路…</span>
            </div>

            <p class="hero-card__sources">
              <span v-if="probeNote" class="hero-card__note">{{ probeNote }}</span>
              其他下载源：
              <a
                :href="site.links?.releases || site.links?.github || '#'"
                target="_blank"
                rel="noopener noreferrer"
              >GitHub Release 页</a>
              <template v-if="giteePage">
                ·
                <a :href="giteePage" target="_blank" rel="noopener noreferrer">Gitee Release 页</a>
              </template>
              <span v-if="latest.apk">·</span>
              <button
                v-if="latest.apk"
                type="button"
                class="hero-card__redetect"
                :disabled="probing"
                @click="detect(true)"
              >重新检测</button>
            </p>


            <p class="hint hero-card__hint">
              安装包托管在 GitHub / Gitee 的 Release 里，本站不保存文件；按钮已自动选择响应更快的下载源——按首字节时间判断，不代表实际下载速度，如速度不理想，可用上方「其他下载源」手动切换。
            </p>
            <p v-if="noStable" class="hint hero-card__hint">
              暂无稳定版，以下为最新测试版。稳定版发布后这里会自动切过去。
            </p>
            <p v-if="feedDegraded" class="hint hero-card__hint">
              当前展示的是本地兜底的版本数据{{ feedUpdated ? `（更新于 ${feedUpdated}）` : '' }}，可能不是最新；GitHub 恢复后会自动刷新。
            </p>
          </div>

          <aside class="hero-card__side">
            <dl class="facts">
              <div class="facts__row">
                <dt>文件大小</dt>
                <dd class="mono">{{ latest.apk?.sizeText || '—' }}</dd>
              </div>
              <div class="facts__row">
                <dt>更新时间</dt>
                <dd>{{ relativeTime(latest.apk?.updatedAt || latest.date) || '—' }}</dd>
              </div>
              <div class="facts__row">
                <dt>下载次数</dt>
                <dd class="mono">{{ latest.downloads ?? 0 }}</dd>
              </div>
              <div v-if="stats" class="facts__row">
                <dt>累计下载</dt>
                <dd class="mono">{{ stats.totalDownloads ?? 0 }}</dd>
              </div>
            </dl>

            <div v-if="latest.apk?.sha256" class="sha">
              <p class="sha__label">SHA-256</p>
              <p class="mono sha__value">{{ latest.apk.sha256 }}</p>
              <CopyButton :text="latest.apk.sha256" label="复制校验值" />
            </div>
          </aside>
        </article>

        <p v-else-if="ready" class="card empty">
          暂时没有可下载的版本。可以到
          <a :href="site.links?.releases || site.links?.github || '#'" target="_blank" rel="noopener noreferrer">GitHub Releases</a>
          查看历史构建。
        </p>
      </div>
    </section>

    <!-- 历史版本 -->
    <section v-if="rest.length" class="section section--tight band">
      <div class="page">
        <p v-reveal class="eyebrow">历史版本</p>
        <h2 v-reveal="40" class="h2">其余 {{ rest.length }} 个可下载版本</h2>

        <div v-reveal class="table-wrap table-gap">
          <table>
            <thead>
              <tr>
                <th>版本</th>
                <th>渠道</th>
                <th>日期</th>
                <th>大小</th>
                <th>下载</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in rest" :key="item.version">
                <td class="mono">
                  <RouterLink :to="`/changelog/${item.version}`" class="ver-link">
                    {{ item.version }}
                  </RouterLink>
                </td>
                <td>{{ channelLabel(item.channel) }}</td>
                <td>{{ formatDate(item.date) }}</td>
                <td class="mono">{{ item.apk?.sizeText || '—' }}</td>
                <td>
                  <a
                    class="btn btn--sm"
                    :href="downloadUrl(item, site)"
                    :target="isExternalDownload(item) ? '_blank' : undefined"
                    :rel="isExternalDownload(item) ? 'noopener noreferrer' : undefined"
                  >{{ item.apk ? '下载' : 'Releases' }}</a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 安装步骤 + 环境要求 -->
    <section class="section section--tight">
      <div class="page two-col">
        <div>
          <p v-reveal class="eyebrow">安装</p>
          <h2 v-reveal="40" class="h2">四步装好</h2>
          <ol class="steps">
            <li v-for="(step, index) in STEPS" :key="step.title" v-reveal="index * 60" class="steps__item">
              <span class="steps__num mono">{{ String(index + 1).padStart(2, '0') }}</span>
              <div>
                <h3 class="steps__title">{{ step.title }}</h3>
                <p class="steps__body">{{ step.body }}</p>
              </div>
            </li>
          </ol>
        </div>

        <div>
          <p v-reveal class="eyebrow">环境要求</p>
          <h2 v-reveal="40" class="h2">装之前先确认</h2>
          <dl v-reveal="60" class="reqs">
            <div v-for="req in REQS" :key="req.label" class="reqs__row">
              <dt>{{ req.label }}</dt>
              <dd>{{ req.value }}</dd>
            </div>
          </dl>

          <div v-reveal="100" class="callout">
            <p class="callout__title">不需要 Root</p>
            <p class="callout__body">
              基础能力靠无障碍服务即可运行。想要系统级命令（am / pm / settings）时，用 Android 11+ 自带的无线调试配对，
              或安装 Shizuku——两者都不需要 Root。
            </p>
            <RouterLink to="/docs/execution-channels" class="link-arrow">通道怎么选</RouterLink>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.hero-card {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: clamp(24px, 3vw, 44px);
  padding: clamp(24px, 3vw, 34px);
}

.hero-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hero-card__version {
  margin-top: 18px;
  font-size: clamp(1.8rem, 1.3rem + 2vw, 2.6rem);
  font-weight: 600;
  letter-spacing: -0.03em;
  line-height: 1.1;
  /* v0.2.2000092-beta 这种 17 字符的版本号在 360px 屏上会顶出卡片，
     断行只在真的放不下时触发，正常版本号仍是一行 */
  overflow-wrap: anywhere;
}

.hero-card__title {
  margin-top: 8px;
  font-size: 1.05rem;
  font-weight: 600;
}

.hero-card__summary {
  margin-top: 12px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.8;
  max-width: 68ch;
  display: -webkit-box;
  -webkit-line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notes {
  margin: 16px 0 0;
  padding-left: 18px;
  font-size: var(--t-sm);
  color: var(--ink-3);
  line-height: 1.9;
}

.hero-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 26px;
}

.hero-card__hint {
  margin-top: 12px;
}

/* 测速状态常驻在按钮同一行，不占额外高度；测完即消失，不做进度条也不做动画 */
.hero-card__probing {
  align-self: center;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.hero-card__sources {
  margin-top: 14px;
  font-size: var(--t-xs);
  color: var(--ink-3);
  line-height: 1.9;
}

.hero-card__sources a {
  color: var(--ink-2);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.hero-card__note {
  margin-right: 8px;
  color: var(--ink-3);
}

.hero-card__redetect {
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  color: var(--ink-3);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.hero-card__redetect:disabled {
  opacity: 0.55;
  cursor: default;
}

.hero-card__side {
  display: grid;
  gap: 18px;
  align-content: start;
  padding-left: clamp(0px, 2vw, 28px);
  border-left: 1px solid var(--line);
}

.facts {
  display: grid;
  gap: 1px;
  margin: 0;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.facts__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 14px;
  background: var(--paper);
}

.facts__row dt {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.facts__row dd {
  margin: 0;
  font-size: var(--t-sm);
  font-weight: 500;
}

.sha {
  display: grid;
  gap: 8px;
  justify-items: start;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--paper-sunken);
}

.sha__label {
  font-size: var(--t-xs);
  color: var(--ink-3);
  letter-spacing: 0.1em;
}

.sha__value {
  font-size: 0.72rem;
  line-height: 1.6;
  word-break: break-all;
  color: var(--ink-2);
}

.empty {
  display: grid;
  gap: 8px;
  color: var(--ink-2);
}

.table-gap {
  margin-top: 32px;
}

.ver-link {
  color: var(--brand);
}

.two-col {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: clamp(32px, 5vw, 64px);
  align-items: start;
}

.steps {
  list-style: none;
  margin: 28px 0 0;
  padding: 0;
  display: grid;
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.steps__item {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  padding: 18px 20px;
  background: var(--paper-raised);
}

.steps__num {
  font-size: var(--t-xs);
  color: var(--brand);
}

.steps__title {
  font-size: 1rem;
  font-weight: 600;
}

.steps__body {
  margin-top: 6px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.78;
}

.reqs {
  display: grid;
  gap: 1px;
  margin: 28px 0 0;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.reqs__row {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 14px;
  padding: 13px 18px;
  background: var(--paper-raised);
  font-size: var(--t-sm);
}

.reqs__row dt {
  color: var(--ink-3);
}

.reqs__row dd {
  margin: 0;
}

.callout {
  display: grid;
  gap: 8px;
  margin-top: 22px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--brand-wash);
}

.callout__title {
  font-weight: 600;
  color: var(--brand-strong);
}

[data-theme="dark"] .callout__title {
  color: var(--brand);
}

.callout__body {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.8;
}

.link-arrow {
  position: relative;
  justify-self: start;
  margin-top: 6px;
  padding-right: 16px;
  font-size: var(--t-sm);
  color: var(--brand);
}

.link-arrow::after {
  content: "→";
  position: absolute;
  right: 0;
  transition: transform var(--dur-ui) var(--ease-out);
}

@media (hover: hover) and (pointer: fine) {
  .link-arrow:hover::after {
    transform: translateX(3px);
  }
}

@media (max-width: 880px) {
  .hero-card {
    grid-template-columns: 1fr;
  }

  .hero-card__side {
    padding-left: 0;
    border-left: none;
    border-top: 1px solid var(--line);
    padding-top: 20px;
  }
}
</style>
