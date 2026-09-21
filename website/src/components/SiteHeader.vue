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

const NAV = [
  { to: '/features', label: '功能' },
  { to: '/how-it-works', label: '原理' },
  { to: '/download', label: '下载' },
  { to: '/changelog', label: '更新' },
  { to: '/docs', label: '文档' },
  { to: '/faq', label: '问答' }
]

const isDark = computed(() => theme.value === 'dark')

function onScroll() {
  scrolled.value = window.scrollY > 8
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
  <header :class="['head', { 'head--solid': scrolled }]">
    <div class="page head__inner">
      <RouterLink to="/" class="brand" aria-label="回到首页">
        <AppLogo :size="26" class="brand__mark" />
        <span class="brand__text">
          <strong>Happy Agent</strong>
          <em v-if="site.version" class="mono">v{{ site.version }}</em>
        </span>
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
          <svg v-if="isDark" width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <circle cx="10" cy="10" r="4" stroke="currentColor" stroke-width="1.5" />
            <path d="M10 1.6v2M10 16.4v2M1.6 10h2M16.4 10h2M4.1 4.1l1.4 1.4M14.5 14.5l1.4 1.4M15.9 4.1l-1.4 1.4M5.5 14.5l-1.4 1.4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
          </svg>
          <svg v-else width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M16.5 12.4A7 7 0 0 1 7.6 3.5a7 7 0 1 0 8.9 8.9Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
          </svg>
        </button>

        <a
          v-if="site.links?.github"
          class="icon-btn"
          :href="site.links.github"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="GitHub 仓库"
        >
          <svg width="17" height="17" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.4 7.4 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
          </svg>
        </a>

        <RouterLink to="/download" class="btn btn--primary btn--sm head__cta">下载 APK</RouterLink>

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
        <div class="sheet__panel" role="dialog" aria-label="导航">
          <div class="sheet__grip" aria-hidden="true"></div>
          <RouterLink v-for="item in NAV" :key="item.to" :to="item.to" class="sheet__link">
            {{ item.label }}
          </RouterLink>
          <RouterLink to="/about" class="sheet__link">关于项目</RouterLink>
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
  height: 66px;
  border-bottom: 1px solid transparent;
  transition:
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease,
    backdrop-filter var(--dur-ui) ease;
}

/* 只在滚动后才给底色：停在顶部时让点阵背景透上来，画面才连成一片 */
.head--solid {
  background: var(--veil);
  backdrop-filter: saturate(1.6) blur(14px);
  border-bottom-color: var(--line);
}

.head__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  gap: 20px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--brand);
  flex: none;
}

.brand__text {
  display: flex;
  align-items: baseline;
  gap: 7px;
  color: var(--ink);
}

.brand__text strong {
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: -0.015em;
}

.brand__text em {
  font-style: normal;
  font-size: 0.7rem;
  color: var(--ink-3);
}

.nav {
  display: flex;
  align-items: center;
  gap: 2px;
}

.nav__link {
  padding: 7px 12px;
  border-radius: var(--r-pill);
  font-size: var(--t-sm);
  color: var(--ink-2);
  transition:
    color var(--dur-ui) ease,
    background-color var(--dur-ui) ease;
}

.nav__link.router-link-active {
  color: var(--ink);
  background: var(--paper-sunken);
}

@media (hover: hover) and (pointer: fine) {
  .nav__link:hover {
    color: var(--ink);
  }
}

.head__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.icon-btn:active {
  transform: scale(0.94);
}

@media (hover: hover) and (pointer: fine) {
  .icon-btn:hover {
    background: var(--paper-sunken);
    color: var(--ink);
  }
}

.head__burger {
  display: none;
}

.head__cta {
  margin-left: 4px;
}

/* ---------- 移动端抽屉：自下而上，不做横向滑入 ---------- */

.sheet {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  align-items: flex-end;
  background: rgba(10, 12, 10, 0.36);
}

.sheet__panel {
  width: 100%;
  padding: 10px 20px calc(24px + env(safe-area-inset-bottom));
  background: var(--paper-raised);
  border-top: 1px solid var(--line);
  border-radius: var(--r-xl) var(--r-xl) 0 0;
  display: grid;
  gap: 2px;
  box-shadow: var(--shadow-3);
}

.sheet__grip {
  width: 38px;
  height: 4px;
  margin: 2px auto 14px;
  border-radius: var(--r-pill);
  background: var(--line-2);
}

.sheet__link {
  padding: 13px 4px;
  font-size: 1rem;
  color: var(--ink);
  border-bottom: 1px solid var(--line);
}

.sheet__link:last-of-type {
  border-bottom: none;
}

.sheet__cta {
  margin-top: 14px;
  width: 100%;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity var(--dur-ui) ease;
}

.sheet-enter-active .sheet__panel,
.sheet-leave-active .sheet__panel {
  transition: transform var(--dur-enter) var(--ease-drawer);
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .sheet__panel,
.sheet-leave-to .sheet__panel {
  transform: translateY(100%);
}

@media (max-width: 900px) {
  .nav {
    display: none;
  }

  .head__burger {
    display: inline-flex;
  }

  .head__cta {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .sheet-enter-active .sheet__panel,
  .sheet-leave-active .sheet__panel {
    transition: opacity 160ms ease;
  }

  .sheet-enter-from .sheet__panel,
  .sheet-leave-to .sheet__panel {
    transform: none;
  }
}
</style>
