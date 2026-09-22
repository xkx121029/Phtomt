<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import { content } from '../api/client.js'
import { formatDate } from '../lib/format.js'

const roadmap = ref({ updatedAt: null, note: '', phases: [] })

const phases = computed(() => roadmap.value.phases || [])

/** 状态到徽标配色的映射。没有的取值退回中性徽标，不硬编一个错的颜色 */
const TONES = { done: 'ok', doing: 'brand', next: 'amber', later: 'plain' }
const toneOf = (state) => TONES[state] || 'plain'

onMounted(async () => {
  try {
    roadmap.value = await content.roadmap()
  } catch {
    roadmap.value = { updatedAt: null, note: '', phases: [] }
  }
})
</script>

<template>
  <div>
    <PageHero
      eyebrow="路线图"
      title="打算做什么，以及不打算做什么"
      lead="按主题分档，不承诺具体时间。最后一档是明确不做的事——一份只写「要做」的路线图没有信息量，取舍才说明方向。"
    >
      <p v-if="roadmap.note" class="muted small">{{ roadmap.note }}</p>
    </PageHero>

    <section class="section section--tight">
      <div class="page">
        <p v-if="!phases.length" class="muted">正在读取路线图…</p>

        <template v-else>
          <p class="stamp mono">更新于 {{ formatDate(roadmap.updatedAt) }}</p>

          <div class="phases">
            <section
              v-for="(phase, pi) in phases"
              :key="phase.id"
              v-reveal="pi * 60"
              :class="['card', 'phase', `phase--${phase.state}`]"
            >
              <header class="phase__head">
                <div class="phase__row">
                  <h2 class="phase__title">{{ phase.title }}</h2>
                  <span :class="['tag', toneOf(phase.state) !== 'plain' ? `tag--${toneOf(phase.state)}` : '']">
                    {{ (phase.items || []).length }} 项
                  </span>
                </div>
                <p v-if="phase.summary" class="phase__summary">{{ phase.summary }}</p>
              </header>

              <ul class="items">
                <li v-for="item in phase.items || []" :key="item.title" class="item">
                  <h3 class="item__title">{{ item.title }}</h3>
                  <p v-if="item.detail" class="item__detail">{{ item.detail }}</p>
                </li>
              </ul>
            </section>
          </div>

          <div v-reveal class="more">
            <div>
              <p class="more__title">想看已经落地的部分？</p>
              <p class="muted small">每个版本改了什么、为什么改，都记在更新日志里。方向上的讨论在 Issue 区。</p>
            </div>
            <div class="more__actions">
              <RouterLink to="/changelog" class="btn btn--sm">更新日志</RouterLink>
              <RouterLink to="/features" class="btn btn--sm">能力清单</RouterLink>
            </div>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.stamp {
  margin-bottom: 22px;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.phases {
  display: grid;
  gap: 12px;
}

/* 档位之间靠左侧一道竖线串起来：它是「顺序」的暗示，
   比给每档配一种背景色克制得多——四块不同颜色的卡片是典型的 AI 味。 */
.phase {
  position: relative;
  padding: 24px 26px;
  border-left-width: 3px;
}

.phase--done {
  border-left-color: var(--ok);
}

.phase--doing {
  border-left-color: var(--brand);
}

.phase--next {
  border-left-color: var(--amber);
}

.phase--later {
  border-left-color: var(--line-2);
}

.phase__head {
  margin-bottom: 18px;
}

.phase__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.phase__title {
  font-size: var(--t-h3);
}

.phase__summary {
  margin-top: 8px;
  max-width: 68ch;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.78;
}

.items {
  display: grid;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 条目之间用极淡的分隔线而不是各自套卡片：一屏几十个方框会把眼睛累死 */
.item + .item {
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.item__title {
  font-size: 0.98rem;
  font-weight: 600;
  letter-spacing: -0.014em;
}

.item__detail {
  margin-top: 4px;
  max-width: 72ch;
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.78;
}

/* 「暂不计划」整档压暗：它要能被读到，但不该和「正在做」抢注意力 */
.phase--later .item__title {
  color: var(--ink-2);
}

.phase--later .item__detail {
  color: var(--ink-3);
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

@media (max-width: 640px) {
  .phase {
    padding: 20px;
  }
}
</style>
