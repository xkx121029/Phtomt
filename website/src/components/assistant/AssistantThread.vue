<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownBody from '../MarkdownBody.vue'
import { useAssistant } from '../../composables/useAssistant.js'

/**
 * 问答正文区：消息流 + 输入区。
 *
 * 刻意不做「气泡 + 小尾巴」那套聊天室外形——那是对话机器人的刻板印象，也和信息密度无关。
 * 这里按「访谈记录」排版：每一轮先用小标签标出是谁说的，用户的话落在一块下沉底色的引用块上，
 * 助手的回答直接进长文排版。回答里的 Markdown（列表、表格、代码、链接）因此天然成立。
 */

defineProps({
  /** dock 用在浮窗里（紧凑），page 用在 /ask 独立页（宽松） */
  variant: { type: String, default: 'dock' }
})

const { messages, status, sending, available, ask, stop, reset } = useAssistant()

const draft = ref('')
const scroller = ref(null)
const box = ref(null)

const suggestions = computed(() => status.value.suggestions || [])
const isEmpty = computed(() => messages.value.length === 0)
const canSend = computed(() => Boolean(draft.value.trim()) && !sending.value)

async function toEnd() {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}

// 流式输出时内容长度一直在变，deep 才能跟着追上
watch(messages, toEnd, { deep: true })

function grow() {
  const el = box.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(132, el.scrollHeight)}px`
}

function send(text) {
  const value = String(text ?? draft.value).trim()
  if (!value || sending.value || !available.value) return
  draft.value = ''
  grow()
  ask(value)
  toEnd()
}

function onKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  send()
}
</script>

<template>
  <div :class="['thread', `thread--${variant}`]">
    <div ref="scroller" class="thread__scroll">
      <div v-if="isEmpty" class="intro">
        <p class="intro__greeting">{{ status.greeting }}</p>
        <ul v-if="suggestions.length" class="chips">
          <li v-for="item in suggestions" :key="item">
            <button class="chip" type="button" @click="send(item)">{{ item }}</button>
          </li>
        </ul>
      </div>

      <ol v-else class="turns">
        <li v-for="m in messages" :key="m.id" :class="['turn', `turn--${m.role}`]">
          <p class="turn__who">{{ m.role === 'assistant' ? status.name : '你' }}</p>

          <div v-if="m.role === 'user'" class="turn__say">{{ m.content }}</div>

          <div v-else class="turn__reply">
            <span v-if="m.state === 'streaming' && !m.content" class="typing" aria-label="正在生成">
              <i></i><i></i><i></i>
            </span>
            <MarkdownBody v-else-if="m.content" :source="m.content" />
            <p v-if="m.state === 'error'" class="turn__error">{{ m.error }}</p>
          </div>
        </li>
      </ol>
    </div>

    <div class="composer">
      <textarea
        ref="box"
        v-model="draft"
        class="composer__box"
        rows="1"
        :placeholder="available ? '问点什么…（Enter 发送，Shift+Enter 换行）' : '问答尚未开启'"
        :disabled="!available"
        @input="grow"
        @keydown="onKeydown"
      ></textarea>

      <button v-if="sending" class="composer__send composer__send--stop" type="button" @click="stop">
        停止
      </button>
      <button v-else class="composer__send" type="button" :disabled="!canSend" @click="send()">
        <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <path d="M10 16.5V3.5m0 0L4.8 8.7M10 3.5l5.2 5.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span class="sr-only">发送</span>
      </button>
    </div>

    <p class="foot">
      <span>回答由 AI 依据站内资料生成，关键信息请以文档与仓库为准。</span>
      <button v-if="!isEmpty" class="foot__reset" type="button" @click="reset">清空</button>
    </p>
  </div>
</template>

<style scoped>
.thread {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}

.thread__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 4px 2px 8px;
}

/* ---------- 开场 ---------- */

.intro {
  display: grid;
  gap: 16px;
  padding: 8px 2px 4px;
}

.intro__greeting {
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
}

.chips {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.chip {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--paper-raised);
  color: var(--ink-2);
  font-size: var(--t-sm);
  text-align: left;
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    border-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

.chip:active {
  transform: scale(0.985);
}

@media (hover: hover) and (pointer: fine) {
  .chip:hover {
    border-color: var(--brand);
    color: var(--ink);
  }
}

/* ---------- 访谈记录 ---------- */

.turns {
  display: grid;
  gap: 22px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.turn {
  display: grid;
  gap: 8px;
}

.turn__who {
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--ink-3);
}

.turn--assistant .turn__who {
  color: var(--brand);
}

/* 用户的话不放进彩色气泡，改用下沉底色 + 左侧品牌竖线：
   与站内「引用块」的观感一致，也避免整屏出现两套高饱和色块 */
.turn__say {
  padding: 12px 14px;
  border-left: 2px solid var(--brand);
  border-radius: 0 var(--r-sm) var(--r-sm) 0;
  background: var(--paper-sunken);
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.turn__reply {
  min-width: 0;
}

.turn__reply :deep(.prose) {
  font-size: var(--t-sm);
  line-height: 1.8;
  max-width: none;
}

.turn__reply :deep(.prose > * + *) {
  margin-top: 0.9em;
}

.turn__reply :deep(.prose h2) {
  margin-top: 1.4em;
  font-size: 1.02rem;
}

.turn__reply :deep(.prose h3),
.turn__reply :deep(.prose h4) {
  margin-top: 1.2em;
  font-size: 0.96rem;
}

.turn__reply :deep(.prose pre) {
  margin: 0;
}

.turn__error {
  margin-top: 8px;
  padding: 9px 12px;
  border-radius: var(--r-sm);
  background: var(--danger-wash);
  color: var(--danger);
  font-size: var(--t-xs);
  line-height: 1.7;
  word-break: break-word;
}

/* 生成中的三点：不用旋转圈——那是「加载中」，这里要表达的是「正在写」 */
.typing {
  display: inline-flex;
  gap: 4px;
  padding: 6px 0;
}

.typing i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--brand);
  opacity: 0.35;
  animation: pulse 1.1s var(--ease-in-out) infinite;
}

.typing i:nth-child(2) {
  animation-delay: 0.16s;
}

.typing i:nth-child(3) {
  animation-delay: 0.32s;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  40% {
    opacity: 0.9;
    transform: translateY(-2px);
  }
}

/* ---------- 输入区 ---------- */

.composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 8px 8px 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--paper-raised);
  transition: border-color var(--dur-ui) ease;
}

.composer:focus-within {
  border-color: var(--brand);
}

.composer__box {
  flex: 1;
  min-width: 0;
  height: 34px;
  max-height: 132px;
  padding: 8px 0;
  border: none;
  background: transparent;
  outline: none;
  resize: none;
  font-size: var(--t-sm);
  line-height: 1.6;
}

.composer__box:disabled {
  color: var(--ink-3);
  cursor: not-allowed;
}

.composer__send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  height: 34px;
  min-width: 34px;
  padding: 0 12px;
  border: 1px solid var(--brand);
  border-radius: var(--r-pill);
  background: var(--brand);
  color: #fff;
  font-size: var(--t-xs);
  font-weight: 500;
  cursor: pointer;
  transition:
    transform var(--dur-press) var(--ease-out),
    background-color var(--dur-ui) ease;
}

[data-theme="dark"] .composer__send {
  color: var(--brand-ink);
}

.composer__send:active {
  transform: scale(0.95);
}

.composer__send:disabled {
  border-color: var(--line);
  background: var(--paper-sunken);
  color: var(--ink-3);
  cursor: not-allowed;
}

.composer__send:disabled:active {
  transform: none;
}

.composer__send--stop {
  border-color: var(--line);
  background: var(--paper-raised);
  color: var(--danger);
}

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.foot__reset {
  border: none;
  background: none;
  padding: 0;
  color: var(--ink-3);
  font-size: var(--t-xs);
  cursor: pointer;
  flex: none;
}

@media (hover: hover) and (pointer: fine) {
  .foot__reset:hover {
    color: var(--brand);
  }
}

.thread--page .turns {
  gap: 26px;
}

.thread--page .turn__reply :deep(.prose) {
  font-size: var(--t-body);
}

@media (prefers-reduced-motion: reduce) {
  .typing i {
    animation: none;
    opacity: 0.6;
  }
}
</style>
