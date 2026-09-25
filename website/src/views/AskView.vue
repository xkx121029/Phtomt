<script setup>
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import AssistantThread from '../components/assistant/AssistantThread.vue'
import { useAssistant } from '../composables/useAssistant.js'
import { isStaticBuild } from '../api/client.js'

/**
 * 独立问答页。
 *
 * 与右下角浮窗共用同一段对话（见 useAssistant）：在浮窗里问到一半、想换整页读长回答，
 * 点「打开整页」过来时上下文还在。
 */

const { available, probed, loadStatus } = useAssistant()

onMounted(() => loadStatus())
</script>

<template>
  <div>
    <PageHero
      eyebrow="问答"
      title="关于这个项目，先问一句"
      lead="助手只依据本站的功能、文档、常见问题与更新日志作答；资料里没有的，它会直说不确定，并把你引到仓库的 Issues。"
    />

    <section class="section section--tight">
      <div class="page">
        <div v-if="isStaticBuild || (probed && !available)" class="card notice">
          <h2 class="notice__title">问答尚未开启</h2>
          <p class="notice__body">
            {{
              isStaticBuild
                ? '当前是静态站点构建，没有可用的问答后端。请在部署了 Node 服务的站点上使用。'
                : '站点还没有配置模型接口。进入管理后台的「AI 问答」页，填写 OpenAI 兼容端点的地址、模型名与密钥，测试通过后启用即可。'
            }}
          </p>
          <div class="row row--wrap">
            <RouterLink to="/faq" class="btn btn--sm">先看常见问题</RouterLink>
            <RouterLink to="/docs" class="btn btn--sm">浏览文档</RouterLink>
          </div>
        </div>

        <div v-else-if="!probed" class="card notice">
          <p class="muted small">正在检查问答服务…</p>
        </div>

        <div v-else class="card board">
          <AssistantThread variant="page" />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.notice {
  display: grid;
  gap: 14px;
  justify-items: start;
  max-width: 640px;
}

.notice__title {
  font-size: 1.05rem;
  font-weight: 600;
}

.notice__body {
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
}

.board {
  display: flex;
  flex-direction: column;
  height: min(70vh, 760px);
  padding: 20px 22px;
}

@media (max-width: 700px) {
  .board {
    height: min(76vh, 640px);
    padding: 16px;
  }
}
</style>
