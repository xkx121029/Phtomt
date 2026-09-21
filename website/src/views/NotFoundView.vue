<script setup>
import { RouterLink } from 'vue-router'

const LINKS = [
  { to: '/', label: '首页' },
  { to: '/features', label: '功能特性' },
  { to: '/download', label: '下载' },
  { to: '/docs', label: '文档中心' },
  { to: '/changelog', label: '更新日志' },
  { to: '/faq', label: '常见问题' }
]
</script>

<template>
  <section class="notfound">
    <div class="page notfound__inner">
      <p v-reveal class="eyebrow">404</p>
      <h1 v-reveal="40" class="display notfound__code">404</h1>
      <p v-reveal="80" class="lead notfound__lead">
        这个地址下没有页面。可能是链接过期了，也可能是版本号写错了——更新日志里的版本号带 <code>v</code> 前缀。
      </p>

      <div v-reveal="120" class="notfound__links">
        <RouterLink v-for="link in LINKS" :key="link.to" :to="link.to" class="btn btn--sm">
          {{ link.label }}
        </RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
.notfound {
  display: flex;
  align-items: center;
  min-height: calc(100vh - 66px - 260px);
  padding-block: clamp(64px, 10vw, 120px);
}

.notfound__inner {
  display: grid;
  justify-items: start;
}

.notfound__code {
  margin-top: 12px;
  color: var(--line-2);
  letter-spacing: -0.05em;
  line-height: 0.9;
}

.notfound__lead {
  margin-top: 18px;
  max-width: 54ch;
}

.notfound__links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 30px;
}
</style>
