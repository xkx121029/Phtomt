<script setup>
import CollectionEditor from './CollectionEditor.vue'

const FIELDS = [
  { key: 'id', label: '标识', mono: true, readonly: true, hint: '主键，创建后不可改（接口以 URL 上的 id 为准）' },
  { key: 'order', label: '排序', type: 'number', hint: '数字越小越靠前' },
  { key: 'tag', label: '分类', hint: '决策 / 执行 / 感知 / 安全 / 性能…' },
  { key: 'title', label: '标题' },
  { key: 'summary', label: '一句话说明', type: 'textarea', rows: 3 },
  {
    key: 'detail',
    label: '详细说明',
    type: 'textarea',
    rows: 6,
    hint: '写清它在解决什么问题、对应哪个模块，而不是重复标题'
  }
]
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">功能特性</h1>
      <p class="muted small">首页取前 6 条，功能页全部展示。分类名会自动生成筛选标签。</p>
    </header>

    <CollectionEditor
      collection="features"
      id-key="id"
      label="特性"
      subtitle-key="summary"
      :fields="FIELDS"
      :blank="{ id: '', order: 99, tag: '', title: '', summary: '', detail: '' }"
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
