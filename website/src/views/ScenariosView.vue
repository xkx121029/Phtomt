<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'

const all = ref([])
const active = ref('全部')
const open = ref('')

const categories = computed(() => ['全部', ...new Set(all.value.map((s) => s.category).filter(Boolean))])

const groups = computed(() => {
  const list = active.value === '全部' ? all.value : all.value.filter((s) => s.category === active.value)
  const map = new Map()
  for (const item of list) {
    const key = item.category || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(item)
  }
  return [...map.entries()].map(([category, items]) => ({ category, items }))
})

function toggle(id) {
  open.value = open.value === id ? '' : id
}

onMounted(async () => {
  try {
    all.value = await content.scenarios()
  } catch {
    all.value = []
  }
})
</script>

<template>
  <div>
    <PageHero
      eyebrow="场景示例"
      title="这些事，说一句话就够了"
      lead="下面每一条都是可以直接粘进输入框的原话，以及它实际会怎么走。写出来是为了让你在装之前就知道边界在哪：哪一步靠无障碍、哪一步要 shell、哪一步会停下来问你。"
    >
      <div class="chips">
        <button
          v-for="cat in categories"
          :key="cat"
          type="button"
          :class="['chip', { 'chip--on': active === cat }]"
          @click="active = cat"
        >
          {{ cat }}
        </button>
      </div>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!all.length" class="muted">正在读取场景…</p>

        <div v-for="group in groups" :key="group.category" class="group">
          <h2 v-reveal class="group__title">
            {{ group.category }}
            <span class="muted mono group__count">{{ group.items.length }}</span>
          </h2>

          <div class="grid">
            <article
              v-for="(item, index) in group.items"
              :key="item.id"
              v-reveal="index * 40"
              :class="['card', 'item', { 'item--open': open === item.id }]"
            >
              <button class="item__head" type="button" :aria-expanded="open === item.id" @click="toggle(item.id)">
                <span class="item__text">
                  <span class="item__title">
                    {{ item.title }}
                    <span v-if="item.channel" class="tag item__channel">{{ item.channel }}</span>
                  </span>
                  <span class="item__goal">{{ item.goal }}</span>
                </span>
                <svg class="item__caret" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M4 6.5 8 10.5l4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>

              <div class="item__body">
                <ol class="steps">
                  <li v-for="(step, i) in item.steps || []" :key="i" class="steps__item">
                    <span class="steps__index mono">{{ String(i + 1).padStart(2, '0') }}</span>
                    <span class="steps__text">
                      <strong class="steps__name">{{ step.title }}</strong>
                      <span class="steps__detail">{{ step.detail }}</span>
                    </span>
                  </li>
                </ol>
                <p v-if="item.note" class="note">{{ item.note }}</p>
              </div>
            </article>
          </div>
        </div>

        <div v-reveal class="more">
          <div>
            <p class="more__title">没找到你要的那件事？</p>
            <p class="muted small">把目标写成一句人话就行，不必迁就固定句式。执行通道怎么选、模型怎么配，见文档中心。</p>
          </div>
          <div class="more__actions">
            <RouterLink to="/docs/getting-started" class="btn btn--sm">快速开始</RouterLink>
            <RouterLink to="/docs/execution-channels" class="btn btn--sm">执行通道</RouterLink>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  padding: 6px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-pill);
  background: var(--paper-raised);
  color: var(--ink-2);
  font-size: var(--t-xs);
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    background-color var(--dur-ui) ease,
    border-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.chip:active {
  transform: scale(0.96);
}

.chip--on {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}

[data-theme="dark"] .chip--on {
  color: var(--brand-ink);
}

@media (hover: hover) and (pointer: fine) {
  .chip:hover {
    border-color: var(--line-2);
    color: var(--ink);
  }

  .chip--on:hover {
    color: #fff;
  }

  [data-theme="dark"] .chip--on:hover {
    color: var(--brand-ink);
  }
}

.group + .group {
  margin-top: clamp(40px, 5vw, 64px);
}

.group__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 18px;
  font-size: var(--t-h3);
}

.group__count {
  font-size: var(--t-xs);
  font-weight: 400;
}

.grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  align-items: start;
}

.item {
  padding: 0;
  overflow: hidden;
}

.item__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  padding: 20px 22px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

/* 与能力清单、问答同一套交互：桌面端悬停即展开，移动端没有 hover、仍走点击。
   不再叠悬停底色——卡片已经是亚克力，再压一层 --paper-sunken 会把材质糊掉。 */
@media (hover: hover) and (pointer: fine) {
  .item:hover .item__body {
    grid-template-rows: 1fr;
  }

  .item:hover .item__caret {
    transform: rotate(180deg);
  }
}

.item__text {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.item__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: -0.018em;
}

/* 通道标签不抢视线：它标的是「这条靠什么跑」，不是卖点 */
.item__channel {
  font-size: var(--t-xs);
  font-weight: 500;
  letter-spacing: 0;
}

/* 指令用等宽字并带一道左侧竖线：一眼能和说明文字区分开，
   也方便用户直接照着抄进输入框 */
.item__goal {
  padding-left: 10px;
  border-left: 2px solid var(--line-2);
  font-family: var(--font-mono);
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--ink-2);
}

.item__caret {
  flex: none;
  margin-top: 3px;
  color: var(--ink-3);
  transition: transform var(--dur-enter) var(--ease-out);
}

.item--open .item__caret {
  transform: rotate(180deg);
}

.item__body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows var(--dur-enter) var(--ease-out);
}

.item--open .item__body {
  grid-template-rows: 1fr;
}

.steps {
  display: grid;
  gap: 14px;
  margin: 0;
  padding: 0 22px 20px;
  list-style: none;
}

.steps__item {
  display: flex;
  gap: 12px;
  overflow: hidden;
}

.steps__index {
  flex: none;
  padding-top: 1px;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.steps__text {
  display: grid;
  gap: 3px;
  font-size: var(--t-sm);
  line-height: 1.72;
}

.steps__name {
  font-weight: 600;
  letter-spacing: -0.01em;
}

.steps__detail {
  color: var(--ink-2);
}

.note {
  margin: 0 22px 20px;
  padding: 10px 14px;
  border-radius: var(--r-sm);
  background: var(--paper-sunken);
  font-size: var(--t-xs);
  line-height: 1.75;
  color: var(--ink-2);
}

.more {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: clamp(36px, 5vw, 56px);
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--paper-sunken);
}

.more__title {
  font-weight: 600;
  margin-bottom: 4px;
}

.more__actions {
  display: flex;
  gap: 8px;
}

@media (prefers-reduced-motion: reduce) {
  .item__body,
  .item__caret {
    transition: none;
  }
}
</style>
