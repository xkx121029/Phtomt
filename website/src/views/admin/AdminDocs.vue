<script setup>
import CollectionEditor from './CollectionEditor.vue'

const FIELDS = [
  { key: 'slug', label: '路径标识', mono: true, readonly: true, hint: '文档地址是 /docs/<slug>，创建后不可改' },
  { key: 'order', label: '排序', type: 'number' },
  { key: 'group', label: '分组', hint: '入门 / 进阶 / 支持' },
  { key: 'title', label: '标题' },
  { key: 'summary', label: '摘要', type: 'textarea', rows: 3 },
  {
    key: 'body',
    label: '正文（Markdown）',
    type: 'textarea',
    rows: 16,
    hint: '支持标题、列表、表格、代码块、引用；标题会自动生成右侧目录'
  }
]
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">文档</h1>
      <p class="muted small">正文是 Markdown，二级标题会进右侧目录。分组顺序决定文档中心的排列。</p>
    </header>

    <CollectionEditor
      collection="docs"
      id-key="slug"
      label="文档"
      subtitle-key="summary"
      :fields="FIELDS"
      :blank="{ slug: '', order: 99, group: '进阶', title: '', summary: '', body: '' }"
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
