<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SiteHeader from './components/SiteHeader.vue'
import SiteFooter from './components/SiteFooter.vue'
import { useCardTilt } from './composables/useCardTilt.js'

const route = useRoute()
// 管理后台自带一套外壳，不套官网的页头页脚
const isAdmin = computed(() => route.matched.some((r) => r.meta?.admin))

// 卡片悬停微倾斜：文档级代理，路由切换后新渲染的卡片也照样覆盖
useCardTilt()
</script>

<template>
  <div class="shell">
    <!-- 环境色雾：卡片亚克力的「背景物」。没有它，半透明卡片底下是一片死平的纸色，
         模糊无物可糊，玻璃效果就等于没做。纯装饰，不进无障碍树。 -->
    <div class="ambient" aria-hidden="true"></div>
    <SiteHeader v-if="!isAdmin" />
    <main :class="['main', { 'main--admin': isAdmin }]">
      <RouterView v-slot="{ Component, route: current }">
        <component :is="Component" :key="current.path" />
      </RouterView>
    </main>
    <SiteFooter v-if="!isAdmin" />
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
  padding-top: 66px;
}

.main--admin {
  padding-top: 0;
}
</style>
