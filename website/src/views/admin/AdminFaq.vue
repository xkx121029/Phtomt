<script setup>
import CollectionEditor from './CollectionEditor.vue'

const FIELDS = [
  { key: 'id', label: '标识', mono: true, readonly: true, hint: '主键，创建后不可改' },
  { key: 'order', label: '排序', type: 'number' },
  { key: 'group', label: '分组', hint: '使用 / 安全 / 下载 / 开发' },
  { key: 'q', label: '问题' },
  { key: 'a', label: '回答', type: 'textarea', rows: 6 }
]
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">常见问题</h1>
      <p class="muted small">首页取前 6 条。回答里可以直接写文件名、命令和参数——页面会按原文展示。</p>
    </header>

    <CollectionEditor
      collection="faq"
      id-key="id"
      label="问题"
      :fields="FIELDS"
      :blank="{ id: '', order: 99, group: '使用', q: '', a: '' }"
    />
  </div>
</template>

<style scoped>
.page {
  display: grid;
  gap: 18px;
  max-width: 1180px;
}

.page__head {
  display: grid;
  gap: 6px;
}
</style>
