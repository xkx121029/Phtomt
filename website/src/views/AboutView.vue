<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import SectionHead from '../components/SectionHead.vue'
import { content } from '../api/client.js'
import { formatDate } from '../lib/format.js'

const site = ref({})

const LINKS = computed(() => {
  const links = site.value.links || {}
  return [
    { label: 'GitHub 仓库', href: links.github, hint: '主仓库，Issue 与 Release 都在这里' },
    { label: 'Gitee 代码镜像', href: links.gitee, hint: '仅同步源码，不提供安装包下载' },
    { label: '问题反馈', href: links.issues, hint: '附上调试页导出的诊断包' },
    { label: '历史版本', href: links.releases, hint: '按标签归档的构建产物' }
  ].filter((l) => l.href)
})

const PALETTE = [
  { name: '玄青', role: '品牌主色', light: '#0D6C58', dark: '#64E0BB' },
  { name: '琥珀', role: '暖色辅色', light: '#92551A', dark: '#EFAB72' },
  { name: '雾蓝', role: '冷色辅色', light: '#366B88', dark: '#8FB8D3' },
  { name: '纸白', role: '浅色底', light: '#F7F5EF', dark: '#0F1211' }
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
      no="09 / 关于"
      eyebrow="关于项目"
      :title="site.name || 'Happy Phone Agent'"
      :lead="site.tagline || '让手机自己动手。'"
    >
      <RouterLink to="/download" class="btn btn--primary btn--sm">下载 APK</RouterLink>
    </PageHero>

    <!-- 是什么 -->
    <section class="section section--tight">
      <div class="page about__intro">
        <p class="about__lede">
          {{ site.description || '运行在 Android 上的 AI 智能体：一句自然语言描述目标，它自己观察屏幕、拆解步骤、动手执行。' }}
        </p>
        <dl class="meta">
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
    </section>

    <!-- 链接 -->
    <section class="section section--tight band">
      <div class="page">
        <SectionHead no="01" eyebrow="入口" title="去哪找它" />
        <div class="links">
          <a
            v-for="(link, index) in LINKS"
            :key="link.label"
            v-reveal="index * 40"
            class="link"
            :href="link.href"
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="link__label">
              {{ link.label }}
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M5.5 3.5h7v7M12.5 3.5 4 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <span class="link__hint">{{ link.hint }}</span>
          </a>
        </div>
      </div>
    </section>

    <!-- 技术栈 -->
    <section class="section section--tight">
      <div class="page">
        <SectionHead
          no="02"
          eyebrow="技术栈"
          title="选型都是能长期维护的那种"
          lead="纯 Kotlin + Compose，没有跨端框架、没有自研渲染层。依赖少意味着升级 Android 大版本时麻烦少。"
        />
        <div v-reveal class="table-wrap">
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
    <section class="section section--tight band">
      <div class="page">
        <SectionHead
          no="03"
          eyebrow="设计语言"
          title="玄青 · 流萤"
          lead="以玄青（墨玉绿）为主色，琥珀与雾蓝作辅，底色偏暖纸白与深墨绿黑。官网与 App 共用同一套语义颜色令牌——它不是一个「品牌页」，是同一个东西的另一面。"
        />

        <div class="swatches">
          <div v-for="(color, index) in PALETTE" :key="color.name" v-reveal="index * 40" class="swatch">
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
            动效刻意克制：只做进入视口淡入与 hover / 按下反馈，时长都在 300ms 以内，没有滚动劫持，也没有视差。
            所有过渡都用 transition 而不是 keyframes——列表项会被反复重排，keyframes 每次从头播放。
          </p>
          <p>
            系统开启「减弱动态效果」时，全部动画降级为不位移的淡入或直接呈现。
          </p>
        </div>
      </div>
    </section>

    <!-- 许可 -->
    <section class="section section--tight">
      <div class="page two">
        <div v-reveal class="note-card">
          <h3 class="note-card__title">开源许可</h3>
          <p class="note-card__body">
            项目基于 MIT 协议开源，可自由使用、修改与分发。应用本身不收费，
            你只需要为自己使用的模型服务付费（如果用云端 API）。
          </p>
        </div>
        <div v-reveal="50" class="note-card">
          <h3 class="note-card__title">能力边界</h3>
          <p class="note-card__body">
            它依赖无障碍服务读取屏幕，因此对完全自绘的界面（游戏、部分视频播放器）识别能力有限；
            验证码、支付确认这类环节会停下来等你操作，不会自行提交。
          </p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.about__intro {
  display: grid;
  gap: 28px;
}

.about__lede {
  font-size: clamp(1.05rem, 0.98rem + 0.5vw, 1.32rem);
  line-height: 1.85;
  color: var(--ink);
  max-width: 48ch;
}

.meta {
  margin: 0;
  border-top: 1px solid var(--line);
}

.meta__row {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--line);
  font-size: var(--t-sm);
}

.meta__row dt {
  color: var(--ink-3);
}

.meta__row dd {
  margin: 0;
}

.links {
  display: grid;
  grid-template-columns: 1fr;
  gap: 1px;
  background: var(--line);
  border-bottom: 1px solid var(--line);
}

.link {
  display: grid;
  gap: 5px;
  padding: 18px 16px;
  background: var(--paper-tint);
  transition: background-color var(--dur-ui) ease;
}

.link__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.link__hint {
  font-size: var(--t-sm);
  color: var(--ink-2);
}

@media (hover: hover) and (pointer: fine) {
  .link:hover {
    background: var(--paper-raised);
  }
}

.swatches {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.swatch__chips {
  display: flex;
  height: 56px;
  border-radius: var(--r-sm);
  overflow: hidden;
  border: 1px solid var(--line);
}

.swatch__chip {
  flex: 1;
}

.swatch__name {
  margin-top: 10px;
  font-weight: 600;
  font-size: var(--t-sm);
}

.swatch__role {
  font-size: var(--t-xs);
}

.swatch__hex {
  margin-top: 4px;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.notes {
  display: grid;
  gap: 12px;
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
  max-width: 72ch;
}

.two {
  display: grid;
  gap: 16px;
}

.note-card {
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--paper-raised);
}

.note-card__title {
  font-size: var(--t-h3);
}

.note-card__body {
  margin-top: 8px;
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
}

@media (min-width: 720px) {
  .about__intro {
    grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
    align-items: start;
  }

  .links {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .swatches {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .two {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 栅格子项默认 min-width:auto，一列长文本就会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.about__intro > *,
.two > *,
.meta > * {
  min-width: 0;
}
</style>
