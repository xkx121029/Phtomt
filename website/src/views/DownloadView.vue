<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import CopyButton from '../components/CopyButton.vue'
import { content } from '../api/client.js'
import { channelLabel, downloadUrl, formatDate, isExternalDownload, relativeTime } from '../lib/format.js'

const site = ref({})
const releases = ref([])
const stats = ref(null)
const ready = ref(false)

const latest = computed(() => releases.value[0] || site.value.latest || null)
const rest = computed(() => releases.value.slice(1))

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
                :href="downloadUrl(latest, site)"
                :target="isExternalDownload(latest) ? '_blank' : undefined"
                :rel="isExternalDownload(latest) ? 'noopener noreferrer' : undefined"
                download
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
            </div>

            <p v-if="isExternalDownload(latest)" class="hint hero-card__hint">
              本站未托管该版本的安装包，按钮会跳到 GitHub Releases 页面。
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
