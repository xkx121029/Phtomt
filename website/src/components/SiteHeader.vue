<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppLogo from './AppLogo.vue'
import { theme, toggleTheme } from '../composables/useTheme.js'
import { content } from '../api/client.js'

const route = useRoute()
const scrolled = ref(false)
const menuOpen = ref(false)
const site = ref({})

/** 主导航只放四组：产品怎么跑 / 能做什么 / 场景 / 下载。其余收进抽屉与页脚。 */
const NAV = [
  { to: '/how-it-works', label: '原理' },
  { to: '/features', label: '能力' },
  { to: '/scenarios', label: '场景' },
  { to: '/download', label: '下载' }
]

const MORE = [
  { to: '/changelog', label: '更新日志' },
  { to: '/docs', label: '文档中心' },
  { to: '/faq', label: '常见问题' },
  { to: '/roadmap', label: '路线图' },
  { to: '/ask', label: 'AI 问答' },
  { to: '/about', label: '关于项目' }
]

const isDark = computed(() => theme.value === 'dark')

function onScroll() {
  scrolled.value = window.scrollY > 6
}

onMounted(async () => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  try {
    site.value = await content.site()
  } catch {
    site.value = {}
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  document.body.style.removeProperty('overflow')
})

// 抽屉打开时锁住页面滚动，否则背景会跟着手指跑
watch(menuOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

watch(() => route.fullPath, () => {
  menuOpen.value = false
})
</script>

<template>
  <header :class="['head', 'glass--sheen', { 'head--solid': scrolled, glass: scrolled }]">
    <div class="page head__inner">
      <RouterLink to="/" class="brand" aria-label="回到首页">
        <AppLogo :size="24" class="brand__mark" />
        <span class="brand__text">Happy Agent</span>
      </RouterLink>

      <nav class="nav" aria-label="主导航">
        <RouterLink v-for="item in NAV" :key="item.to" :to="item.to" class="nav__link">
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="head__actions">
        <button
          class="icon-btn"
          type="button"
          :aria-label="isDark ? '切换到浅色' : '切换到深色'"
          @click="toggleTheme"
        >
          <svg v-if="isDark" width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <circle cx="10" cy="10" r="4" stroke="currentColor" stroke-width="1.5" />
            <path d="M10 1.6v2M10 16.4v2M1.6 10h2M16.4 10h2M4.1 4.1l1.4 1.4M14.5 14.5l1.4 1.4M15.9 4.1l-1.4 1.4M5.5 14.5l-1.4 1.4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M16.5 12.4A7 7 0 0 1 7.6 3.5a7 7 0 1 0 8.9 8.9Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
          </svg>
        </button>

        <a
          v-if="site.links?.github"
          class="icon-btn head__gh"
          :href="site.links.github"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="GitHub 仓库"
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.4 7.4 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
          </svg>
        </a>

        <RouterLink to="/download" class="btn btn--primary btn--sm head__cta">
          下载 APK
          <span v-if="site.version" class="mono head__ver">v{{ site.version }}</span>
        </RouterLink>

        <button
          class="icon-btn head__burger"
          type="button"
          :aria-expanded="menuOpen"
          aria-label="打开导航"
          @click="menuOpen = true"
        >
          <svg width="18" height="18" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M3 6h14M3 10h14M3 14h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </div>
  </header>

  <Teleport to="body">
    <Transition name="sheet">
      <div v-if="menuOpen" class="sheet" @click.self="menuOpen = false">
        <div class="sheet__panel glass glass--sheen" role="dialog" aria-label="导航">
          <div class="sheet__head">
            <span class="eyebrow">全部页面</span>
            <button class="icon-btn" type="button" aria-label="关闭导航" @click="menuOpen = false">
              <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <path d="m5 5 10 10M15 5 5 15" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
              </svg>
            </button>
          </div>
          <div class="sheet__grid">
            <RouterLink v-for="item in NAV" :key="item.to" :to="item.to" class="sheet__link">
              {{ item.label }}
            </RouterLink>
            <RouterLink v-for="item in MORE" :key="item.to" :to="item.to" class="sheet__link sheet__link--sub">
              {{ item.label }}
            </RouterLink>
          </div>
          <RouterLink to="/download" class="btn btn--primary sheet__cta">下载 APK</RouterLink>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.head {
  position: fixed;
  inset: 0 0 auto;
  z-index: 60;
  border-bottom: 1px solid transparent;
  transition:
    border-color var(--dur-ui) ease,
    background-color var(--dur-ui) ease;
}

.head--solid {
  border-bottom-color: var(--line);
}

.head__inner {
  display: flex;
  align-items: center;
  gap: 20px;
  height: 58px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  flex: none;
}

.brand__mark {
  flex: none;
}

.brand__text {
  font-size: 0.95rem;
  font-weight: 650;
  letter-spacing: 0.005em;
  white-space: nowrap;
}

.nav {
  display: none;
  align-items: center;
  gap: 4px;
  margin-left: 12px;
}

.nav__link {
  position: relative;
  padding: 6px 10px;
  font-size: var(--t-sm);
  color: var(--ink-2);
  border-radius: var(--r-xs);
  transition: color var(--dur-ui) ease;
}

.nav__link.router-link-active {
  color: var(--ink);
  font-weight: 500;
}

.nav__link.router-link-active::after {
  content: "";
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 0;
  height: 1.5px;
  background: var(--brand);
}

.head__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.head__cta {
  margin-left: 4px;
}

.head__ver {
  font-size: 0.72rem;
  opacity: 0.66;
}

@media (hover: hover) and (pointer: fine) {
  .icon-btn:hover {
    background: var(--paper-sunken);
    color: var(--ink);
  }

  .nav__link:hover {
    color: var(--ink);
  }
}

@media (max-width: 719px) {
  .head__gh,
  .head__ver {
    display: none;
  }
}

@media (min-width: 860px) {
  .nav {
    display: flex;
  }

  .head__burger {
    display: none;
  }
}

/* ---------- 抽屉 ---------- */

.sheet {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  justify-content: flex-end;
  background: rgba(12, 14, 12, 0.42);
}

.sheet__panel {
  width: min(360px, 100%);
  height: 100%;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  border-left: 1px solid var(--glass-line);
  overflow-y: auto;
}

.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sheet__grid {
  display: grid;
  gap: 2px;
}

.sheet__link {
  padding: 11px 4px;
  border-bottom: 1px solid var(--line);
  font-size: 1rem;
}

.sheet__link--sub {
  font-size: var(--t-sm);
  color: var(--ink-2);
}

.sheet__cta {
  margin-top: auto;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity var(--dur-ui) ease;
}

.sheet-enter-active .sheet__panel,
.sheet-leave-active .sheet__panel {
  transition: transform var(--dur-ui) var(--ease-drawer);
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .sheet__panel,
.sheet-leave-to .sheet__panel {
  transform: translateX(24px);
}
</style>
