<script setup>
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import SiteHeader from './components/SiteHeader.vue'
import SiteFooter from './components/SiteFooter.vue'
import AssistantDock from './components/assistant/AssistantDock.vue'
import { useAssistant } from './composables/useAssistant.js'

const route = useRoute()
// 管理后台自带一套外壳，不套官网的页头页脚
const isAdmin = computed(() => route.matched.some((r) => r.meta?.admin))
// 独立问答页里已经有一整块对话，右下角再挂一个入口就是重复
const showDock = computed(() => !isAdmin.value && route.name !== 'ask')

const { loadStatus, restore } = useAssistant()
onMounted(() => {
  // 先把上次的对话接回来再探状态：反过来的话，探测失败也不会影响已经读出来的历史
  restore()
  loadStatus()
})
</script>

<template>
  <div class="shell">
    <!-- 环境色：三层极淡色斑，只为让大片纸底不死平。纯装饰，不进无障碍树。 -->
    <div class="ambient" aria-hidden="true"></div>
    <SiteHeader v-if="!isAdmin" />
    <main :class="['main', { 'main--admin': isAdmin }]">
      <RouterView v-slot="{ Component, route: current }">
        <component :is="Component" :key="current.path" />
      </RouterView>
    </main>
    <SiteFooter v-if="!isAdmin" />
    <AssistantDock v-if="showDock" />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
}

.main {
  flex: 1;
  /* 顶栏是固定的，内容整体下移；后台页不需要 */
  padding-top: 58px;
}

.main--admin {
  padding-top: 0;
}
</style>
