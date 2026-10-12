<script setup>
/**
 * 站点标记：直接用手机端 App 的图标，与用户桌面上看到的是同一个。
 * 走 BASE_URL 而不是写死 `/`，静态导出（GitHub Pages）在子路径下也能取到。
 */
defineProps({
  size: { type: Number, default: 28 }
})

const src = `${import.meta.env.BASE_URL}logo-mark.png`
</script>

<template>
  <img
    :src="src"
    :width="size"
    :height="size"
    :style="{ width: `${size}px`, height: `${size}px` }"
    alt=""
    aria-hidden="true"
    decoding="async"
  />
</template>

<style scoped>
/* 标记是行内替换元素，默认带基线下沉；块级化后与文字基线对齐交给容器的 align-items */
img {
  display: block;
}
</style>
