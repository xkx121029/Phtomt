<script setup>
import CollectionEditor from './CollectionEditor.vue'

const FIELDS = [
  { key: 'id', label: '标识', mono: true, readonly: true, hint: '主键，创建后不可改' },
  { key: 'order', label: '排序', type: 'number' },
  {
    key: 'category',
    label: '分类',
    hint: '系统设置 / 应用操作 / 信息查询 / 内容整理 / 网页与文档 / 日常事务'
  },
  { key: 'title', label: '标题' },
  { key: 'goal', label: '一句话目标', hint: '用户实际会打进输入框的那句话，页面按原文展示' },
  { key: 'channel', label: '通道', hint: '无线 ADB / 无障碍 / 内置浏览器 等，写这条配方实际走的通道' },
  {
    key: 'steps',
    label: '步骤',
    type: 'steps',
    rows: 8,
    hint: '一行一步：「步骤名 | 这一步具体做什么」。只按第一个竖线切分，说明里可以写竖线'
  },
  { key: 'note', label: '备注', type: 'textarea', rows: 3, hint: '可留空' }
]
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="h2">场景示例</h1>
      <p class="muted small">
        页面按分类分组、按排序字段排列，点卡片展开步骤。
        步骤写的是 Agent 实际会走的路，不是理想流程——降级、回屏确认这些都要写进去。
      </p>
    </header>

    <CollectionEditor
      collection="scenarios"
      id-key="id"
      label="场景"
      :fields="FIELDS"
      :blank="{ id: '', order: 99, category: '日常事务', title: '', goal: '', channel: '无障碍', steps: [], note: '' }"
      subtitle-key="goal"
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
