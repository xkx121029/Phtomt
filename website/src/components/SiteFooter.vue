<script setup>
import { onMounted, ref } from 'vue'
import AppLogo from './AppLogo.vue'
import { content, isStaticBuild } from '../api/client.js'

const site = ref({})
const year = new Date().getFullYear()

const COLUMNS = [
  {
    title: '产品',
    links: [
      { to: '/how-it-works', label: '工作原理' },
      { to: '/features', label: '能力清单' },
      { to: '/scenarios', label: '场景示例' },
      { to: '/download', label: '下载安装' }
    ]
  },
  {
    title: '文档',
    links: [
      { to: '/docs', label: '文档中心' },
      { to: '/docs/getting-started', label: '快速开始' },
      { to: '/docs/execution-channels', label: '执行通道' },
      { to: '/docs/troubleshooting', label: '疑难排查' }
    ]
  },
  {
    title: '更多',
    links: [
      { to: '/changelog', label: '更新日志' },
      { to: '/roadmap', label: '路线图' },
      { to: '/faq', label: '常见问题' },
      { to: '/about', label: '关于项目' }
    ]
  }
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
  <footer class="foot">
    <div class="page">
      <div class="foot__top">
        <div class="foot__brand">
          <div class="row">
            <AppLogo :size="22" />
            <strong class="foot__name">Happy Phone Agent</strong>
          </div>
          <p class="small foot__desc">
            用一句自然语言描述目标，让 Android 上的 AI 智能体自己观察屏幕、拆解步骤、动手执行。
          </p>
          <div class="foot__tags">
            <span v-if="site.version" class="tag mono">v{{ site.version }}</span>
            <span class="tag">MIT</span>
            <span v-if="isStaticBuild" class="tag">静态站点</span>
          </div>
        </div>

        <nav class="foot__cols" aria-label="页脚导航">
          <div v-for="col in COLUMNS" :key="col.title" class="foot__col">
            <h4 class="foot__col-title">{{ col.title }}</h4>
            <RouterLink v-for="link in col.links" :key="link.to" :to="link.to" class="foot__link">
              {{ link.label }}
            </RouterLink>
          </div>

          <div class="foot__col">
            <h4 class="foot__col-title">仓库</h4>
            <a v-if="site.links?.github" :href="site.links.github" class="foot__link" target="_blank" rel="noopener noreferrer">GitHub</a>
            <a v-if="site.links?.gitee" :href="site.links.gitee" class="foot__link" target="_blank" rel="noopener noreferrer">Gitee 镜像</a>
            <a v-if="site.links?.issues" :href="site.links.issues" class="foot__link" target="_blank" rel="noopener noreferrer">问题反馈</a>
            <RouterLink to="/admin" class="foot__link">管理后台</RouterLink>
          </div>
        </nav>
      </div>

      <div class="foot__bottom">
        <span class="small">© {{ year }} Happy Phone Agent · Phtomt</span>
        <div class="foot__legal">
          <RouterLink to="/docs/privacy" class="foot__legal-link">隐私政策</RouterLink>
          <RouterLink to="/docs/disclaimer" class="foot__legal-link">免责声明</RouterLink>
          <a
            v-if="site.links?.github"
            :href="`${site.links.github}/blob/main/LICENSE`"
            class="foot__legal-link"
            target="_blank"
            rel="noopener noreferrer"
          >MIT 协议</a>
        </div>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.foot {
  margin-top: clamp(48px, 7vw, 96px);
  border-top: 1px solid var(--line);
  background: var(--paper-tint);
  padding-block: clamp(32px, 4vw, 52px) 22px;
}

.foot__top {
  display: grid;
  gap: 32px;
}

.foot__name {
  font-size: 0.92rem;
  font-weight: 650;
}

.foot__desc {
  margin-top: 12px;
  max-width: 34ch;
  color: var(--ink-2);
  line-height: 1.8;
}

.foot__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 16px;
}

.foot__cols {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px 16px;
}

.foot__col {
  display: grid;
  gap: 8px;
  align-content: start;
}

.foot__col-title {
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: var(--track-cap);
  color: var(--ink-3);
  text-transform: uppercase;
}

.foot__link {
  font-size: var(--t-sm);
  color: var(--ink-2);
  width: fit-content;
  transition: color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .foot__link:hover {
    color: var(--brand);
  }
}

.foot__bottom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 36px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
  color: var(--ink-3);
}

.foot__legal {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.foot__legal-link {
  font-size: var(--t-sm);
  color: var(--ink-3);
}

@media (hover: hover) and (pointer: fine) {
  .foot__legal-link:hover {
    color: var(--ink);
  }
}

@media (min-width: 720px) {
  .foot__cols {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (min-width: 960px) {
  .foot__top {
    grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.5fr);
    gap: 48px;
  }

  .foot__desc {
    max-width: 30ch;
  }
}
</style>
