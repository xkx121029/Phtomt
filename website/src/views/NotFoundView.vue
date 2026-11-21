<script setup>
import { RouterLink } from 'vue-router'

const LINKS = [
  { to: '/', label: '首页' },
  { to: '/how-it-works', label: '工作原理' },
  { to: '/features', label: '能力清单' },
  { to: '/download', label: '下载' },
  { to: '/docs', label: '文档中心' },
  { to: '/faq', label: '常见问题' }
]
</script>

<template>
  <section class="notfound">
    <div class="page">
      <p class="eyebrow">404</p>
      <h1 class="display notfound__code">这个地址<br />没有页面</h1>
      <p class="lead notfound__lead">
        可能是链接过期了，也可能是版本号写错了——更新日志里的版本号带 <code>v</code> 前缀。
      </p>
      <div class="notfound__links">
        <RouterLink v-for="link in LINKS" :key="link.to" :to="link.to" class="btn btn--sm">
          {{ link.label }}
        </RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
.notfound {
  padding-block: clamp(56px, 10vw, 128px);
}

.notfound__code {
  font-size: clamp(2.2rem, 1.5rem + 4vw, 4rem);
  margin-top: 14px;
}

.notfound__lead {
  margin-top: 20px;
  max-width: 44ch;
}

.notfound__links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 30px;
  padding-top: 22px;
  border-top: 1px solid var(--line);
}
</style>
