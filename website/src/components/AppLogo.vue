<script setup>
/**
 * 站点标记：一个圆角「屏幕」里，三道渐强的弧线 + 一个实心点。
 * 弧线是信号，点是 Agent 当前锁定的那个控件——比抽象的首字母缩写更有指向性。
 */
defineProps({
  size: { type: Number, default: 28 }
})
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 28 28"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <rect x="1.25" y="1.25" width="25.5" height="25.5" rx="7.75" stroke="currentColor" stroke-width="1.5" opacity="0.42" />
    <path d="M9 19.5a7 7 0 0 1 0-11" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" opacity="0.5" />
    <path d="M13 17.2a3.9 3.9 0 0 1 0-6.4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" opacity="0.75" />
    <circle cx="18.6" cy="14" r="2.4" fill="currentColor" />
  </svg>
</template>
