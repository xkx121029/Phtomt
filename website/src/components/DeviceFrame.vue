<script setup>
/**
 * DeviceFrame —— 承载真实截图的手机外壳。
 *
 * 老版本用 CSS 画了一个「示意界面」。示意界面会随 App 版本过期，而且一眼就能看出
 * 是画出来的，抵掉了「这是我手机上真跑着的东西」这句话的可信度。这一版只做壳：
 * 屏幕里放真机截图（public/shots/*.webp，取自 docs/ui-shots），壳负责边框与比例。
 *
 * 宽高比按截图本身来（1080×2400 = 9:20），壳的圆角与外框用 em 换算自宽度，
 * 所以在 375px 的窄屏上整机等比缩小，不会把截图裁掉。
 */
defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  ratio: { type: String, default: '9 / 20' }
})
</script>

<template>
  <div class="device">
    <div class="device__frame">
      <div class="device__screen" :style="{ aspectRatio: ratio }">
        <img v-if="src" class="device__shot" :src="src" :alt="alt" loading="lazy" decoding="async" />
        <slot v-else />
      </div>
      <span class="device__punch" aria-hidden="true"></span>
    </div>
  </div>
</template>

<style scoped>
.device {
  width: 100%;
  max-width: 300px;
}

.device__frame {
  position: relative;
  padding: 7px;
  border-radius: 34px;
  background: linear-gradient(160deg, #2a2f2a, #141813 46%, #232823);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.18) inset,
    0 0 0 1px rgba(0, 0, 0, 0.35),
    var(--shadow-3);
}

[data-theme="dark"] .device__frame {
  background: linear-gradient(160deg, #34392f, #191d18 46%, #2a2f27);
}

.device__screen {
  position: relative;
  overflow: hidden;
  border-radius: 28px;
  background: #0b0d0b;
}

.device__shot {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top center;
}

/* 挖孔：只是给「这是一台真机」一个视觉锚点，不遮挡内容 */
.device__punch {
  position: absolute;
  top: 14px;
  left: 50%;
  width: 44px;
  height: 8px;
  border-radius: 999px;
  transform: translateX(-50%);
  background: #0b0d0b;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.06);
}

@media (min-width: 1024px) {
  .device {
    max-width: 322px;
  }
}
</style>
