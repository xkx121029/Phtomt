<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import SignalField from '../components/SignalField.vue'
import DeviceFrame from '../components/DeviceFrame.vue'
import AnnotatedScreen from '../components/AnnotatedScreen.vue'
import CopyButton from '../components/CopyButton.vue'
import { content } from '../api/client.js'
import { downloadUrl, formatDate, relativeTime, sectionTone, channelLabel } from '../lib/format.js'

const data = ref(null)
const failed = ref(false)

const site = computed(() => data.value?.site || {})
const latest = computed(() => site.value.latest || null)
const features = computed(() => (data.value?.features || []).slice(0, 6))
const releases = computed(() => data.value?.releases || [])
const entries = computed(() => data.value?.changelog || [])

const QUICK_START = `# 1. 装好 APK 后，先授予无障碍与悬浮窗权限
# 2. 设置 → 模型配置，填任意 OpenAI 兼容端点
API 地址   http://192.168.1.5:8000/v1
模型名     qwen2.5-vl-7b

# 3. 回首页，用一句人话说出目标
打开设置，把屏幕亮度调到一半`

const LOOP = [
  { step: '观察', desc: '无障碍服务取回当前屏幕的全部可交互元素' },
  { step: '标注', desc: '为 26 类常见控件分配语义 ID，直接注入模型上下文' },
  { step: '决策', desc: '端侧先处理高频场景，其余交给你的模型' },
  { step: '执行', desc: '通道降级执行，动作前后做页面指纹比对' },
  { step: '记录', desc: '落盘供多轮对话与诊断导出使用' }
]

onMounted(async () => {
  try {
    data.value = await content.bootstrap()
  } catch {
    failed.value = true
  }
})
</script>

<template>
  <div class="home">
    <!-- 首屏：点阵信号场 + 主张 + 实机演示 -->
    <section class="hero">
      <SignalField />
      <div class="page hero__inner">
        <div class="hero__grid">
          <div class="hero__text">
            <p v-reveal class="eyebrow">{{ site.name || 'Happy Phone Agent' }}</p>
            <h1 v-reveal="40" class="display hero__title">
              让手机<br />自己动手
            </h1>
            <p v-reveal="90" class="lead hero__lead">
              {{
                site.description ||
                '一句自然语言描述目标，它自己观察屏幕、拆解步骤、动手执行——点按、滑动、输入、跳转，全程可见可接管。'
              }}
            </p>

            <div v-reveal="140" class="hero__actions">
              <a
                class="btn btn--primary btn--lg"
                :href="latest ? downloadUrl(latest, site) : '/download'"
                :target="latest && !latest.apk ? '_blank' : undefined"
                :rel="latest && !latest.apk ? 'noopener noreferrer' : undefined"
              >
                <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M10 3v9m0 0 3.5-3.5M10 12 6.5 8.5M4 15.5h12" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                下载 APK
                <span v-if="latest?.apk" class="mono hero__size">{{ latest.apk.sizeText }}</span>
              </a>
              <RouterLink to="/docs/getting-started" class="btn btn--lg">快速开始</RouterLink>
              <RouterLink to="/how-it-works" class="btn btn--ghost btn--lg">它是怎么跑的</RouterLink>
            </div>
          </div>

          <div v-reveal="110" class="hero__device">
            <!-- 纯示意画面，对读屏用户没有信息量；含义由下方说明文字承担 -->
            <DeviceFrame aria-hidden="true">
              <AnnotatedScreen />
            </DeviceFrame>
            <p class="hero__caption">
              屏幕上的控件被逐个识别并赋予语义 ID，
              <span class="muted">模型拿到的是一张结构表，不是一张图。</span>
            </p>
          </div>
        </div>

        <dl v-reveal="190" class="specs">
          <div class="specs__item">
            <dt>当前版本</dt>
            <dd class="mono">{{ site.version ? `v${site.version}` : '—' }}</dd>
          </div>
          <div class="specs__item">
            <dt>系统要求</dt>
            <dd>Android {{ site.requirements?.minAndroid || '8.0' }}+</dd>
          </div>
          <div class="specs__item">
            <dt>架构</dt>
            <dd class="mono">{{ site.requirements?.arch || 'arm64-v8a' }}</dd>
          </div>
          <div class="specs__item">
            <dt>执行通道</dt>
            <dd>{{ site.channels?.length || 4 }} 条可选</dd>
          </div>
        </dl>
      </div>
    </section>

    <!-- 通栏粗线：首屏到此为止，下面开始读内容 -->
    <hr class="rule" />

    <p v-if="failed" class="page notice">
      内容接口暂不可用，页面显示的是构建期快照。若为本地开发，请确认 <code>npm run dev:api</code> 已启动。
    </p>

    <!-- 功能矩阵 -->
    <section class="section">
      <div class="page">
        <header class="sec-head">
          <p v-reveal class="eyebrow">能力</p>
          <h2 v-reveal="40" class="h2">不是把点击录下来重放</h2>
          <p v-reveal="80" class="lead sec-head__lead">
            录制回放在页面改版的那一刻就失效了。这里的每一步都以「当前屏幕上真实存在什么」为唯一依据。
          </p>
        </header>

        <div class="grid grid--3">
          <article
            v-for="(item, index) in features"
            :key="item.id"
            v-reveal="index * 50"
            :class="['card', 'feature', index === 0 ? 'card--ink grain' : '']"
          >
            <div class="feature__top">
              <span class="feature__no mono">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="tag tag--brand">{{ item.tag }}</span>
            </div>
            <h3 class="feature__title">{{ item.title }}</h3>
            <p class="feature__summary">{{ item.summary }}</p>
          </article>
        </div>

        <div v-reveal class="sec-more">
          <RouterLink to="/features" class="btn">查看全部 {{ data?.features?.length || 18 }} 项能力</RouterLink>
        </div>
      </div>
    </section>

    <!-- 执行通道 -->
    <section class="section section--tight band">
      <div class="page">
        <header class="sec-head">
          <p v-reveal class="eyebrow">执行通道</p>
          <h2 v-reveal="40" class="h2">四条通道，能力边界写清楚</h2>
        </header>

        <div class="grid grid--2">
          <article
            v-for="(ch, index) in site.channels || []"
            :key="ch.id"
            v-reveal="index * 60"
            class="card channel"
          >
            <div class="channel__head">
              <h3 class="channel__name">{{ ch.name }}</h3>
              <span :class="['tag', ch.level.includes('shell') ? 'tag--amber' : '']">{{ ch.level }}</span>
            </div>
            <p class="channel__desc">{{ ch.desc }}</p>
            <p v-if="ch.default" class="channel__badge">
              <span class="dot" aria-hidden="true"></span>默认通道
            </p>
          </article>
        </div>
      </div>
    </section>

    <!-- 执行循环 -->
    <section class="section">
      <div class="page">
        <header class="sec-head">
          <p v-reveal class="eyebrow">主循环</p>
          <h2 v-reveal="40" class="h2">五步一轮，每轮都回到屏幕本身</h2>
        </header>

        <ol class="loop">
          <li v-for="(item, index) in LOOP" :key="item.step" v-reveal="index * 60" class="loop__item">
            <span class="loop__index mono">{{ String(index + 1).padStart(2, '0') }}</span>
            <h3 class="loop__step">{{ item.step }}</h3>
            <p class="loop__desc">{{ item.desc }}</p>
          </li>
        </ol>

        <div v-reveal class="sec-more">
          <RouterLink to="/how-it-works" class="btn">看完整架构</RouterLink>
        </div>
      </div>
    </section>

    <!-- 快速开始 -->
    <section class="section section--tight">
      <div class="page start">
        <div class="start__text">
          <p v-reveal class="eyebrow">上手</p>
          <h2 v-reveal="40" class="h2">三步跑通第一个任务</h2>
          <p v-reveal="80" class="lead">
            模型可以是自建的 Ollama、LM Studio 或 one-api。地址不写协议会自动补全，内网 http 服务直接可用。
          </p>
          <div v-reveal="120" class="start__links">
            <RouterLink to="/docs/model-config" class="link-arrow">模型配置怎么填</RouterLink>
            <RouterLink to="/docs/execution-channels" class="link-arrow">通道怎么选</RouterLink>
          </div>
        </div>
        <div v-reveal="60" class="start__code">
          <div class="code-block">
            <div class="code-block__bar">
              <span class="code-block__dot" aria-hidden="true"></span>
              <span class="mono code-block__name">快速开始</span>
              <CopyButton :text="QUICK_START" />
            </div>
            <pre class="code code--dark"><code>{{ QUICK_START }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- 更新 -->
    <section class="section section--tight band">
      <div class="page">
        <header class="sec-head sec-head--row">
          <div>
            <p v-reveal class="eyebrow">更新</p>
            <h2 v-reveal="40" class="h2">最近改了什么</h2>
          </div>
          <RouterLink v-reveal to="/changelog" class="btn btn--sm">全部记录</RouterLink>
        </header>

        <div class="stack stack--lg">
          <article v-for="(entry, index) in entries" :key="entry.version" v-reveal="index * 60" class="card entry">
            <div class="entry__head">
              <RouterLink :to="`/changelog/${entry.version}`" class="mono entry__ver">
                {{ entry.version }}
              </RouterLink>
              <span class="muted small">{{ formatDate(entry.date) }}</span>
              <span v-for="sec in entry.sections || []" :key="sec.type" :class="['tag', `tag--${sectionTone(sec.type)}`]">
                {{ sec.type }} {{ sec.items?.length || 0 }}
              </span>
            </div>
            <p v-if="entry.summary" class="entry__summary">{{ entry.summary }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 收尾 CTA -->
    <section class="section cta">
      <span class="glow" aria-hidden="true"></span>
      <div class="page cta__inner">
        <h2 v-reveal class="h2">把它装到自己的手机上试试</h2>
        <p v-reveal="40" class="lead cta__lead">
          当前提供 Debug 构建，用于内测与自用。每个版本的 SHA-256 都写在下载页上，装之前可以自行核对。
        </p>
        <div v-reveal="80" class="cta__actions">
          <RouterLink to="/download" class="btn btn--primary btn--lg">
            <template v-if="latest?.apk">下载 v{{ latest.version.replace(/^v/i, '') }}</template>
            <template v-else>前往下载页</template>
          </RouterLink>
          <a
            v-if="site.links?.github"
            class="btn btn--lg"
            :href="site.links.github"
            target="_blank"
            rel="noopener noreferrer"
          >GitHub 仓库</a>
        </div>
        <p v-if="latest" v-reveal="120" class="muted small cta__meta">
          {{ channelLabel(latest.channel) }} · 发布于 {{ formatDate(latest.date) }}（{{ relativeTime(latest.date) }}）
          <template v-if="releases.length > 1"> · 共 {{ releases.length }} 个可下载版本</template>
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ---------- 首屏 ---------- */

.hero {
  position: relative;
  overflow: hidden;
  padding-block: clamp(56px, 8vw, 104px) clamp(40px, 5vw, 64px);
}

.hero__inner {
  position: relative;
  z-index: 1;
}

/* 左文右机：文字窄一点，机器宽一点，视线自然落到屏幕上 */
.hero__grid {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
  gap: clamp(32px, 5vw, 72px);
  align-items: center;
}

.hero__title {
  margin-top: 18px;
}

.hero__lead {
  margin-top: 24px;
  max-width: 46ch;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 34px;
}

.hero__size {
  font-size: 0.72em;
  opacity: 0.72;
  margin-left: 2px;
}

.hero__device {
  display: grid;
  justify-items: center;
  gap: 26px;
}

.hero__caption {
  max-width: 34ch;
  text-align: center;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-2);
}

/* 规格条：不要格子了，改用一条横线压住，列间用竖线分——像刊物的页脚 */
.specs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: clamp(44px, 5vw, 72px) 0 0;
  padding: 20px 0 0;
  border-top: 1px solid var(--line-2);
}

.specs__item {
  padding-inline: clamp(14px, 1.8vw, 26px);
  border-left: 1px solid var(--line);
}

.specs__item:first-child {
  padding-left: 0;
  border-left: none;
}

.specs__item dt {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.specs__item dd {
  margin: 5px 0 0;
  font-size: var(--t-sm);
  font-weight: 500;
}

/* ---------- 通用节标题 ---------- */

.sec-head {
  margin-bottom: clamp(32px, 4vw, 48px);
}

.sec-head__lead {
  margin-top: 16px;
  max-width: 62ch;
}

.sec-head--row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.sec-more {
  margin-top: 32px;
}

.band {
  background: var(--paper-tint);
  border-block: 1px solid var(--line);
}

.notice {
  margin-block: 24px;
  padding: 14px 18px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--amber);
  border-radius: var(--r-sm);
  background: var(--amber-wash);
  color: var(--ink);
  font-size: var(--t-sm);
}

/* ---------- 栅格 ---------- */

.grid {
  display: grid;
  gap: 16px;
}

.grid--3 {
  grid-template-columns: repeat(auto-fit, minmax(268px, 1fr));
}

.grid--2 {
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.feature {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.feature__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.feature__no {
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--line-2);
  font-variant-numeric: tabular-nums;
}

/* 深色锚点卡里的编号要用浅色，否则整块黑上什么都看不见 */
.card--ink .feature__no {
  color: rgba(255, 255, 255, 0.42);
}

.feature__title {
  font-size: var(--t-h3);
}

.feature__summary {
  color: var(--ink-2);
  font-size: var(--t-sm);
  line-height: 1.75;
}

/* 深色卡上的正文必须重新指定颜色：scoped 选择器权重比 base 里的
   `.card--ink p` 高，不覆盖的话会留下深灰字压在墨绿底上 */
.card--ink .feature__summary {
  color: rgba(238, 244, 240, 0.78);
}

/* ---------- 通道 ---------- */

.channel {
  display: grid;
  gap: 10px;
  align-content: start;
}

.channel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.channel__name {
  font-size: var(--t-h3);
}

.channel__desc {
  color: var(--ink-2);
  font-size: var(--t-sm);
  line-height: 1.75;
}

.channel__badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: var(--t-xs);
  color: var(--ok);
}

/* ---------- 主循环 ---------- */

/* 横向编号轨道：不要格子，每条轨道只有一根顶线和一枚大号编号，
   五步并排看下来像一条流水线，而不是五块砖。 */
.loop {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(178px, 1fr));
  gap: clamp(22px, 2.8vw, 40px);
}

.loop__item {
  padding-top: 16px;
  border-top: 1px solid var(--line-2);
}

.loop__index {
  display: block;
  font-size: clamp(1.9rem, 1.2rem + 2vw, 2.7rem);
  font-weight: 500;
  line-height: 1;
  letter-spacing: -0.05em;
  color: var(--brand);
  font-variant-numeric: tabular-nums;
}

.loop__step {
  margin-top: 14px;
  font-size: var(--t-h3);
}

.loop__desc {
  margin-top: 8px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.72;
}

/* ---------- 快速开始 ---------- */

.start {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.15fr);
  gap: clamp(28px, 4vw, 56px);
  align-items: center;
}

.start__text .lead {
  margin-top: 16px;
}

.start__links {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 24px;
}

.link-arrow {
  position: relative;
  font-size: var(--t-sm);
  color: var(--brand);
  padding-right: 16px;
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

.code-block {
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--line);
  background: var(--paper-raised);
}

.code-block__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--paper-sunken);
}

.code-block__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--brand);
  flex: none;
}

.code-block__name {
  flex: 1;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.code-block .code {
  border: none;
  border-radius: 0;
  margin: 0;
}

/* ---------- 更新条目 ---------- */

.entry {
  display: grid;
  gap: 10px;
}

.entry__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.entry__ver {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--brand);
}

.entry__summary {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.78;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ---------- 收尾 ---------- */

.cta {
  position: relative;
  overflow: hidden;
  text-align: center;
}

.cta .glow {
  --glow-x: 50%;
  --glow-y: 100%;
  --glow-size: 78% 82%;
}

.cta__inner {
  position: relative;
  display: grid;
  justify-items: center;
}

.cta__lead {
  margin-top: 16px;
  max-width: 56ch;
}

.cta__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 30px;
}

.cta__meta {
  margin-top: 20px;
}

@media (max-width: 900px) {
  .hero__grid {
    grid-template-columns: 1fr;
  }

  /* 窄屏上机器排到文字下方，并且不用居中得太高，免得首屏全是空白 */
  .hero__device {
    margin-top: 12px;
  }

  .hero__lead {
    max-width: none;
  }

  .start {
    grid-template-columns: 1fr;
  }

  .specs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 22px 0;
  }

  .specs__item:nth-child(odd) {
    padding-left: 0;
    border-left: none;
  }

  .sec-head--row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
