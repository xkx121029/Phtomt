<script setup>
/**
 * DeviceFrame —— 用 CSS 画的手机外壳。
 *
 * 刻意不用截图：截图会随 App 版本过期，而且一望即知是贴图。
 * 这里只负责「壳」，屏幕里放什么由插槽决定。
 *
 * 尺寸约定：整台机器用一个「基准宽」驱动，屏幕内所有字号/圆角都由 cqw 换算而来，
 * 所以窄屏被压窄时内容会等比缩小，不会出现文字被裁掉的情况。
 */
</script>

<template>
  <div class="device">
    <div class="device__frame">
      <div class="device__screen">
        <span class="device__punch" aria-hidden="true"></span>
        <slot />
      </div>
      <span class="device__btn device__btn--power" aria-hidden="true"></span>
      <span class="device__btn device__btn--vol" aria-hidden="true"></span>
    </div>
  </div>
</template>

<style scoped>
.device {
  width: min(clamp(248px, 30vw, 288px), 100%);
  /* 建立尺寸容器：内部用 cqw 换算，宽度被压窄时字号跟着缩 */
  container-type: inline-size;
}

.device__frame {
  position: relative;
  aspect-ratio: 9 / 19.5;
  padding: 3.4%;
  border-radius: 14.5cqw;
  /* 边框不是纯黑：真机的金属中框总有方向性的反光 */
  background: linear-gradient(150deg, #2c322d 0%, #14171500 42%, #0b0e0c 100%),
    linear-gradient(210deg, #232824, #10130f);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.1),
    inset 0 0 0 2px rgba(0, 0, 0, 0.5),
    0 2px 4px rgba(20, 23, 21, 0.16),
    0 30px 60px -28px rgba(20, 23, 21, 0.45),
    0 60px 120px -60px rgba(6, 58, 46, 0.5);
}

.device__screen {
  position: relative;
  height: 100%;
  overflow: hidden;
  border-radius: 11.5cqw;
  background: var(--paper-raised);
  /* 屏幕内所有字号都由这里派生（1/26 机宽），跟着机身等比走 */
  font-size: 3.85cqw;
  line-height: 1.5;
  color: var(--ink);
}

.device__punch {
  position: absolute;
  top: 0.85em;
  left: 50%;
  translate: -50% 0;
  width: 0.72em;
  height: 0.72em;
  border-radius: 50%;
  background: #0b0e0c;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.06);
  z-index: 3;
}

/* 侧键：两枚小竖条，只做轮廓，不抢戏 */
.device__btn {
  position: absolute;
  width: 2px;
  border-radius: 2px;
  background: linear-gradient(180deg, #3a403b, #1b1f1c);
}

.device__btn--power {
  right: -2px;
  top: 24%;
  height: 9%;
}

.device__btn--vol {
  left: -2px;
  top: 17%;
  height: 13%;
}
</style>
