<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'
import { formatDate } from '../lib/format.js'

const site = ref({})

const LINKS = computed(() => {
  const links = site.value.links || {}
  return [
    { label: 'GitHub 仓库', href: links.github, hint: '主仓库，Issue 与 Release 都在这里' },
    { label: 'Gitee 镜像', href: links.gitee, hint: '国内访问更稳的镜像' },
    { label: '问题反馈', href: links.issues, hint: '附上调试页导出的诊断包' },
    { label: '历史版本', href: links.releases, hint: '按标签归档的构建产物' }
  ].filter((l) => l.href)
})

const PALETTE = [
  { name: '玄青', role: '品牌主色', light: '#0E7C66', dark: '#5FD9B4' },
  { name: '琥珀', role: '暖色辅色', light: '#B4632A', dark: '#F0A868' },
  { name: '雾蓝', role: '冷色辅色', light: '#4A7C9B', dark: '#84B6D4' },
  { name: '纸白', role: '浅色底', light: '#F7F6F3', dark: '#121513' }
]

onMounted(async () => {
  try {
    site.value = await content.site()
  } catch {
    site.value = {}
  }
})
</script>

<template>
  <div>
    <PageHero
      eyebrow="关于"
      :title="site.name || 'Happy Phone Agent'"
      :lead="site.description || '运行在 Android 上的 AI 智能体。'"
    >
      <div class="row row--wrap">
        <RouterLink to="/download" class="btn btn--primary btn--sm">下载 APK</RouterLink>
      </div>
    </PageHero>

    <!-- 链接 -->
    <section class="section section--tight">
      <div class="page">
        <div class="grid grid--links">
          <a
            v-for="(link, index) in LINKS"
            :key="link.label"
            v-reveal="index * 50"
            class="card link-card"
            :href="link.href"
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="link-card__label">
              {{ link.label }}
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M5.5 3.5h7v7M12.5 3.5 4 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <span class="link-card__hint">{{ link.hint }}</span>
          </a>
        </div>
      </div>
    </section>

    <!-- 技术栈 -->
    <section class="section section--tight band">
      <div class="page two">
        <div>
          <p v-reveal class="eyebrow">技术栈</p>
          <h2 v-reveal="40" class="h2">选型都是能长期维护的那种</h2>
          <p v-reveal="80" class="lead">
            纯 Kotlin + Compose，没有跨端框架、没有自研渲染层。依赖少意味着升级 Android 大版本时麻烦少。
          </p>
          <dl v-reveal="120" class="meta">
            <div class="meta__row">
              <dt>包名</dt>
              <dd class="mono">{{ site.requirements?.packageName || 'com.phoneagent' }}</dd>
            </div>
            <div class="meta__row">
              <dt>当前版本</dt>
              <dd class="mono">{{ site.version ? `v${site.version}` : '—' }}</dd>
            </div>
            <div class="meta__row">
              <dt>构建号</dt>
              <dd class="mono">{{ site.buildNumber || '—' }}</dd>
            </div>
            <div class="meta__row">
              <dt>内容更新</dt>
              <dd>{{ formatDate(site.updatedAt) }}</dd>
            </div>
          </dl>
        </div>

        <div v-reveal="60" class="table-wrap">
          <table>
            <thead>
              <tr><th>依赖</th><th>版本</th></tr>
            </thead>
            <tbody>
              <tr v-for="tech in site.techStack || []" :key="tech.name">
                <td>{{ tech.name }}</td>
                <td class="mono muted">{{ tech.version }}</td>
              </tr>
              <tr v-if="!site.techStack?.length">
                <td colspan="2" class="muted">正在读取…</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 设计语言 -->
    <section class="section section--tight">
      <div class="page">
        <p v-reveal class="eyebrow">设计语言</p>
        <h2 v-reveal="40" class="h2">玄青 · 流萤</h2>
        <p v-reveal="80" class="lead design__lead">
          以玄青（墨玉绿）为主色，琥珀与雾蓝作辅，底色偏暖纸白与深墨绿黑。官网与 App 共用同一套语义颜色令牌——
          它不是一个「品牌页」，是同一个东西的另一面。
        </p>

        <div class="swatches">
          <div v-for="(color, index) in PALETTE" :key="color.name" v-reveal="index * 50" class="swatch">
            <div class="swatch__chips">
              <span class="swatch__chip" :style="{ background: color.light }" :title="`浅色 ${color.light}`" />
              <span class="swatch__chip" :style="{ background: color.dark }" :title="`深色 ${color.dark}`" />
            </div>
            <p class="swatch__name">{{ color.name }}</p>
            <p class="swatch__role muted small">{{ color.role }}</p>
            <p class="mono swatch__hex">{{ color.light }} / {{ color.dark }}</p>
          </div>
        </div>

        <div v-reveal class="notes">
          <p>
            动效上刻意避开「到处都在弹」的做法：入场统一为自下而上淡入位移，时长控制在 300ms 以内，
            按下有 0.97 的缩放反馈，所有过渡都用 transition 而不是 keyframes——列表项会被反复重排，
            keyframes 每次从头播放，transition 可以被打断并重定向。
          </p>
          <p>
            系统开启「减弱动态效果」时，全部动画降级为不位移的淡入或直接呈现。
          </p>
        </div>
      </div>
    </section>

    <!-- 许可 -->
    <section class="section section--tight band">
      <div class="page two two--narrow">
        <div v-reveal class="card">
          <h3 class="card-title">开源许可</h3>
          <p class="card-body">
            项目基于 MIT 协议开源，可自由使用、修改与分发。应用本身不收费，
            你只需要为自己使用的模型服务付费（如果用云端 API）。
          </p>
        </div>
        <div v-reveal="60" class="card">
          <h3 class="card-title">能力边界</h3>
          <p class="card-body">
            它依赖无障碍服务读取屏幕，因此对完全自绘的界面（游戏、部分视频播放器）识别能力有限；
            验证码、支付确认这类环节会停下来等你操作，不会自行提交。
          </p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  gap: 12px;
}

.grid--links {
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
}

.link-card {
  display: grid;
  gap: 6px;
  align-content: start;
  transition:
    transform var(--dur-press) var(--ease-out),
    border-color var(--dur-ui) ease;
}

.link-card:active {
  transform: scale(0.985);
}

@media (hover: hover) and (pointer: fine) {
  .link-card:hover {
    border-color: var(--line-2);
  }
}

.link-card__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 1rem;
}

.link-card__hint {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.7;
}

.two {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: clamp(28px, 4vw, 56px);
  align-items: start;
}

.two--narrow {
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
}

.meta {
  display: grid;
  gap: 1px;
  margin: 26px 0 0;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.meta__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 18px;
  background: var(--paper);
  font-size: var(--t-sm);
}

.meta__row dt {
  color: var(--ink-3);
}

.meta__row dd {
  margin: 0;
}

.design__lead {
  margin-top: 16px;
  max-width: 68ch;
}

.swatches {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
  margin-top: 32px;
}

.swatch {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--paper-raised);
}

.swatch__chips {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}

.swatch__chip {
  flex: 1;
  height: 34px;
  border-radius: var(--r-xs);
  border: 1px solid var(--line);
}

.swatch__name {
  font-weight: 600;
}

.swatch__role {
  margin-top: 2px;
}

.swatch__hex {
  margin-top: 8px;
  font-size: 0.72rem;
  color: var(--ink-3);
}

.notes {
  display: grid;
  gap: 14px;
  margin-top: 28px;
  padding-left: 18px;
  border-left: 2px solid var(--line-2);
  max-width: 78ch;
}

.notes p {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.85;
}

.card-title {
  font-size: 1.05rem;
  font-weight: 600;
  margin-bottom: 10px;
}

.card-body {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.82;
}

@media (max-width: 880px) {
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
