<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import { renderMarkdown, slugify } from '../lib/markdown.js'

const props = defineProps({
  source: { type: String, default: '' },
  /** 是否给标题挂上 id（文档页需要，目录要跳过去） */
  anchorHeadings: { type: Boolean, default: false }
})

const emit = defineEmits(['rendered'])
const host = ref(null)

async function paint() {
  await nextTick()
  if (!host.value || !props.anchorHeadings) return
  const headings = host.value.querySelectorAll('h2, h3')
  headings.forEach((el) => {
    el.id = slugify(el.textContent)
  })
  emit('rendered', [...headings].map((el) => ({ id: el.id, text: el.textContent, level: Number(el.tagName[1]) })))
}

onMounted(paint)
watch(() => props.source, paint)
</script>

<template>
  <div ref="host" class="prose" v-html="renderMarkdown(source)"></div>
</template>
