<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AssistantThread from './AssistantThread.vue'
import { useAssistant } from '../../composables/useAssistant.js'

/**
 * 右下角常驻问答入口。
 *
 * 只在后台配好模型后才出现——接口没配齐时，一个点开是空的入口比没有入口更糟。
 * 打开后是一块自下而上浮起的板：与站内其它浮层同一套动效方向（全站不做横向滑入，也不做缩放）。
 */

const { available, status } = useAssistant()

const open = ref(false)

function onKeydown(event) {
  if (event.key === 'Escape') open.value = false
}

watch(open, (value) => {
  if (value) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div v-if="available" class="dock">
    <Transition name="panel">
      <section v-if="open" class="panel glass glass--sheen" role="dialog" aria-label="站点问答">
        <header class="panel__head">
          <span class="panel__mark" aria-hidden="true">
            <svg width="14" height="14" viewBox="0 0 20 20" fill="none">
              <path d="M3.2 5.6A2.4 2.4 0 0 1 5.6 3.2h8.8a2.4 2.4 0 0 1 2.4 2.4v6a2.4 2.4 0 0 1-2.4 2.4H8.6l-3.9 3v-3A2.4 2.4 0 0 1 3.2 11.6v-6Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
            </svg>
          </span>
          <span class="panel__name">{{ status.name }}</span>
          <RouterLink to="/ask" class="panel__link">打开整页</RouterLink>
          <button class="panel__close" type="button" aria-label="收起" @click="open = false">
            <svg width="15" height="15" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <path d="M5.5 5.5l9 9m0-9-9 9" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
          </button>
        </header>

        <div class="panel__body">
          <AssistantThread variant="dock" />
        </div>
      </section>
    </Transition>

    <button
      v-if="!open"
      class="launcher"
      type="button"
      :aria-expanded="open"
      @click="open = true"
    >
      <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
        <path d="M3.2 5.6A2.4 2.4 0 0 1 5.6 3.2h8.8a2.4 2.4 0 0 1 2.4 2.4v6a2.4 2.4 0 0 1-2.4 2.4H8.6l-3.9 3v-3A2.4 2.4 0 0 1 3.2 11.6v-6Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
      </svg>
      <span>{{ status.name }}</span>
    </button>
  </div>
</template>

<style scoped>
.dock {
  position: fixed;
  right: var(--gutter);
  bottom: 20px;
  z-index: 70;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

/* ---------- 入口胶囊 ---------- */

.launcher {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 18px;
  border: 1px solid var(--brand);
  border-radius: var(--r-pill);
  background: var(--brand);
  color: #fff;
  font-size: var(--t-sm);
  font-weight: 500;
  cursor: pointer;
  box-shadow: var(--shadow-2);
  transition:
    transform var(--dur-press) var(--ease-out),
    background-color var(--dur-ui) ease;
}

[data-theme="dark"] .launcher {
  color: var(--brand-ink);
}

.launcher:active {
  transform: scale(0.96);
}

@media (hover: hover) and (pointer: fine) {
  .launcher:hover {
    background: var(--brand-strong);
    border-color: var(--brand-strong);
  }
}

/* ---------- 面板 ---------- */

/* 压在页面内容之上、背后确实有东西可模糊，是玻璃该出现的地方；
   配方见 base.css 的 .glass，这里只管形状、尺寸与描边。 */
.panel {
  display: flex;
  flex-direction: column;
  width: min(400px, calc(100vw - var(--gutter) * 2));
  height: min(560px, calc(100vh - 140px));
  padding: 14px 16px 14px;
  border: 1px solid var(--glass-line);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-3);
}

.panel__head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
  flex: none;
}

.panel__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--brand-wash);
  color: var(--brand-strong);
}

[data-theme="dark"] .panel__mark {
  color: var(--brand);
}

.panel__name {
  flex: 1;
  min-width: 0;
  font-size: var(--t-sm);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.panel__link {
  flex: none;
  font-size: var(--t-xs);
  color: var(--ink-3);
  transition: color var(--dur-ui) ease;
}

.panel__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: none;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    background-color var(--dur-ui) ease;
}

.panel__close:active {
  transform: scale(0.92);
}

@media (hover: hover) and (pointer: fine) {
  .panel__link:hover {
    color: var(--brand);
  }

  .panel__close:hover {
    background: var(--paper-sunken);
  }
}

.panel__body {
  flex: 1;
  min-height: 0;
  padding-top: 12px;
}

/* 自下而上浮起：全站浮层统一方向 */
.panel-enter-active,
.panel-leave-active {
  transition: opacity var(--dur-ui) ease;
}

.panel-enter-active {
  transition:
    opacity var(--dur-ui) ease,
    transform var(--dur-enter) var(--ease-drawer);
}

.panel-leave-active {
  transition:
    opacity var(--dur-ui) ease,
    transform var(--dur-ui) var(--ease-out);
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateY(24px);
}

@media (max-width: 640px) {
  .dock {
    right: 12px;
    left: 12px;
    bottom: 12px;
    align-items: stretch;
  }

  .panel {
    width: 100%;
    height: min(72vh, 560px);
  }

  .launcher {
    align-self: flex-end;
  }
}

@media (prefers-reduced-motion: reduce) {
  .panel-enter-active,
  .panel-leave-active {
    transition: opacity 160ms ease;
  }

  .panel-enter-from,
  .panel-leave-to {
    transform: none;
  }
}
</style>
