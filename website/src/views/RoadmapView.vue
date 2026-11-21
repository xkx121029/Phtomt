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
const LABELS = { done: '已落地', doing: '进行中', next: '接下来', later: '暂不做' }

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
      label="路线图"
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
              v-reveal="pi * 50"
              :class="['phase', `phase--${phase.state}`]"
            >
              <header class="phase__head">
                <div class="phase__row">
                  <span :class="['phase__mark', `phase__mark--${toneOf(phase.state)}`]" aria-hidden="true"></span>
                  <h2 class="phase__title">{{ phase.title }}</h2>
                  <span :class="['tag', toneOf(phase.state) !== 'plain' ? `tag--${toneOf(phase.state)}` : '']">
                    {{ LABELS[phase.state] || phase.state }}
                  </span>
                  <span class="muted mono phase__count">{{ (phase.items || []).length }} 项</span>
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
  font-size: var(--t-xs);
  color: var(--ink-3);
  letter-spacing: 0.04em;
}

.phases {
  margin-top: 18px;
  border-top: 1px solid var(--line);
}

.phase {
  padding: 24px 0;
  border-bottom: 1px solid var(--line);
}

.phase--later {
  opacity: 0.82;
}

.phase__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.phase__mark {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  background: var(--ink-3);
  flex: none;
}

.phase__mark--ok {
  background: var(--ok);
}

.phase__mark--signal {
  background: var(--signal);
}

.phase__mark--amber {
  background: var(--amber);
}

.phase__title {
  font-size: var(--t-h2);
}

.phase__count {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.phase__summary {
  margin-top: 8px;
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink-2);
  max-width: 70ch;
}

.items {
  list-style: none;
  margin: 16px 0 0;
  padding: 0;
  display: grid;
  gap: 12px;
}

.item {
  display: grid;
  gap: 3px;
  padding-left: 14px;
  border-left: 1px solid var(--line);
}

.item__title {
  font-size: 0.95rem;
  font-weight: 600;
}

.item__detail {
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 72ch;
}

.more {
  display: grid;
  gap: 16px;
  margin-top: clamp(28px, 4vw, 44px);
  padding-top: 20px;
  border-top: 1px solid var(--line-strong);
}

.more__title {
  font-weight: 600;
}

.more__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

@media (min-width: 720px) {
  .items {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px 28px;
  }

  .more {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }
}


/* 栅格子项默认 min-width:auto，一列长文本会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.items > *,
.more > *,
.phase > * {
  min-width: 0;
}
</style>
