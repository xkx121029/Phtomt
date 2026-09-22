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
      { to: '/features', label: '功能特性' },
      { to: '/how-it-works', label: '工作原理' },
      { to: '/scenarios', label: '场景示例' },
      { to: '/download', label: '下载安装' },
      { to: '/changelog', label: '更新日志' }
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
    title: '其他',
    links: [
      { to: '/faq', label: '常见问题' },
      { to: '/roadmap', label: '路线图' },
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
            <AppLogo :size="24" class="foot__mark" />
            <strong>Happy Phone Agent</strong>
          </div>
          <p class="small muted foot__desc">
            用一句自然语言描述目标，让 Android 上的 AI 智能体自己观察屏幕、拆解步骤、动手执行。
          </p>
          <div class="row row--wrap foot__tags">
            <span v-if="site.version" class="tag mono">v{{ site.version }}</span>
            <span class="tag">MIT License</span>
            <span v-if="isStaticBuild" class="tag">静态站点</span>
          </div>
        </div>

        <div class="foot__cols">
          <div v-for="col in COLUMNS" :key="col.title" class="foot__col">
            <h4 class="foot__col-title">{{ col.title }}</h4>
            <RouterLink v-for="link in col.links" :key="link.to" :to="link.to" class="foot__link">
              {{ link.label }}
            </RouterLink>
          </div>

          <div class="foot__col">
            <h4 class="foot__col-title">仓库</h4>
            <a
              v-if="site.links?.github"
              :href="site.links.github"
              class="foot__link"
              target="_blank"
              rel="noopener noreferrer"
              >GitHub</a
            >
            <a
              v-if="site.links?.gitee"
              :href="site.links.gitee"
              class="foot__link"
              target="_blank"
              rel="noopener noreferrer"
              >Gitee</a
            >
            <a
              v-if="site.links?.issues"
              :href="site.links.issues"
              class="foot__link"
              target="_blank"
              rel="noopener noreferrer"
              >问题反馈</a
            >
            <RouterLink to="/admin" class="foot__link">管理后台</RouterLink>
          </div>
        </div>
      </div>

      <div class="foot__bottom">
        <span class="small muted">© {{ year }} Happy Phone Agent · Phtomt</span>
        <div class="foot__legal">
          <RouterLink to="/docs/privacy" class="foot__legal-link">隐私政策</RouterLink>
          <RouterLink to="/docs/disclaimer" class="foot__legal-link">免责声明</RouterLink>
          <a
            v-if="site.links?.github"
            :href="`${site.links.github}/blob/main/LICENSE`"
            class="foot__legal-link"
            target="_blank"
            rel="noopener noreferrer"
            >MIT 协议</a
          >
        </div>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.foot {
  border-top: 1px solid var(--line);
  background: var(--paper-sunken);
  padding-block: 56px 32px;
  margin-top: 40px;
}

.foot__top {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(0, 1.6fr);
  gap: 48px;
}

.foot__mark {
  color: var(--brand);
}

.foot__desc {
  margin-top: 14px;
  max-width: 40ch;
  line-height: 1.75;
}

.foot__tags {
  margin-top: 16px;
}

.foot__cols {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 24px;
}

.foot__col {
  display: grid;
  align-content: start;
  gap: 9px;
}

.foot__col-title {
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ink-3);
  margin-bottom: 3px;
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
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 48px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}

/* 合规链接放底栏而不是「其他」列：它们是随时可查的常驻条目，
   和导航性质不同，混在栏目里会把栏目撑长、也显得像功能入口 */
.foot__legal {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
}

.foot__legal-link {
  font-size: var(--t-xs);
  color: var(--ink-3);
  transition: color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .foot__legal-link:hover {
    color: var(--brand);
  }
}

@media (max-width: 860px) {
  .foot__top {
    grid-template-columns: 1fr;
    gap: 36px;
  }

  .foot__cols {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 28px;
  }
}
</style>
