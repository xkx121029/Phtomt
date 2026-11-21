<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { theme, toggleTheme } from '../composables/useTheme.js'
import { content } from '../api/client.js'

const route = useRoute()
const scrolled = ref(false)
const menuOpen = ref(false)
const site = ref({})

const NAV = [
  { to: '/how-it-works', label: '原理' },
  { to: '/features', label: '能力' },
  { to: '/scenarios', label: '场景' },
  { to: '/docs', label: '文档' }
]

const MORE = [
  { to: '/changelog', label: '更新日志' },
  { to: '/faq', label: '常见问题' },
  { to: '/roadmap', label: '路线图' },
  { to: '/ask', label: 'AI 问答' },
  { to: '/about', label: '关于项目' }
]

const isDark = computed(() => theme.value === 'dark')

function onScroll() {
  scrolled.value = window.scrollY > 4
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

watch(menuOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

watch(() => route.fullPath, () => {
  menuOpen.value = false
})
</script>

<template>
  <header :class="['head', 'glass', { 'head--solid': scrolled }]">
    <div class="page head__in">
      <RouterLink to="/" class="brand" aria-label="回到首页">
        <span class="brand__dot" aria-hidden="true"></span>
        <span class="brand__name">Happy Agent</span>
      </RouterLink>

      <nav class="nav" aria-label="主导航">
        <RouterLink v-for="item in NAV" :key="item.to" :to="item.to" class="nav__link">
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="head__acts">
        <button
          class="icon"
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

        <RouterLink to="/download" class="btn btn--primary btn--sm head__cta">
          下载
          <span v-if="site.version" class="mono head__ver">{{ site.version }}</span>
        </RouterLink>

        <button
          class="icon head__burger"
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
        <div class="sheet__panel glass" role="dialog" aria-label="导航">
          <div class="sheet__head">
            <span class="mono sheet__title">导航</span>
            <button class="icon" type="button" aria-label="关闭导航" @click="menuOpen = false">
              <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <path d="m5 5 10 10M15 5 5 15" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
              </svg>
            </button>
          </div>
          <RouterLink v-for="item in NAV" :key="item.to" :to="item.to" class="sheet__link">
            {{ item.label }}
          </RouterLink>
          <RouterLink v-for="item in MORE" :key="item.to" :to="item.to" class="sheet__link sheet__link--sub">
            {{ item.label }}
          </RouterLink>
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
  transition: border-color var(--dur-ui) ease;
}

.head--solid {
  border-bottom-color: var(--line-soft);
}

.head__in {
  display: flex;
  align-items: center;
  gap: 26px;
  height: 58px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  flex: none;
}

.brand__dot {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  background: var(--signal);
}

.brand__name {
  font-size: 0.95rem;
  font-weight: 650;
  letter-spacing: 0.004em;
  white-space: nowrap;
}

.nav {
  display: none;
  gap: 20px;
  margin-left: 8px;
}

.nav__link {
  font-size: var(--t-sm);
  color: var(--ink-2);
  padding: 4px 0;
  border-bottom: 1px solid transparent;
  transition:
    color var(--dur-ui) ease,
    border-color var(--dur-ui) ease;
}

.nav__link.router-link-active {
  color: var(--ink);
  border-bottom-color: var(--signal);
}

.head__acts {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.head__ver {
  font-size: 0.72rem;
  opacity: 0.7;
}

@media (hover: hover) and (pointer: fine) {
  .icon:hover {
    background: var(--panel-2);
    color: var(--ink);
  }

  .nav__link:hover {
    color: var(--ink);
  }
}

@media (max-width: 759px) {
  .head__ver {
    display: none;
  }
}

@media (min-width: 880px) {
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
  background: rgba(5, 8, 7, 0.55);
}

.sheet__panel {
  width: min(340px, 100%);
  height: 100%;
  padding: 16px 18px 24px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-left: 1px solid var(--line);
  overflow-y: auto;
}

.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--line-soft);
}

.sheet__title {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.sheet__link {
  padding: 12px 2px;
  border-bottom: 1px solid var(--line-soft);
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
  transition: transform var(--dur-ui) var(--ease-out);
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .sheet__panel,
.sheet-leave-to .sheet__panel {
  transform: translateX(20px);
}
</style>
