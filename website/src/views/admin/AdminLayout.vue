<script setup>
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import AppLogo from '../../components/AppLogo.vue'
import { authed, canWrite, checking, ensureSession, login, loginError, logout, staticBlocked } from './useAdmin.js'

const route = useRoute()
const password = ref('')
const submitting = ref(false)

const NAV = [
  { to: '/admin', label: '概览', exact: true },
  { to: '/admin/releases', label: '版本与 APK' },
  { to: '/admin/changelog', label: '更新日志' },
  { to: '/admin/features', label: '功能特性' },
  { to: '/admin/docs', label: '文档' },
  { to: '/admin/faq', label: '常见问题' },
  { to: '/admin/scenarios', label: '场景示例' },
  { to: '/admin/roadmap', label: '路线图' },
  { to: '/admin/site', label: '站点信息' },
  { to: '/admin/audit', label: '操作留痕' }
]

async function submit() {
  submitting.value = true
  await login(password.value)
  password.value = ''
  submitting.value = false
}

onMounted(ensureSession)
</script>

<template>
  <div class="admin">
    <!-- 校验中：先不闪登录表单，避免已登录用户看到一次「登录页闪一下」 -->
    <div v-if="checking" class="gate">
      <p class="muted">正在校验登录状态…</p>
    </div>

    <!-- 登录 -->
    <div v-else-if="!authed" class="gate">
      <form class="gate__card card" @submit.prevent="submit">
        <AppLogo :size="30" class="gate__logo" />
        <h1 class="gate__title">管理后台</h1>

        <p v-if="staticBlocked" class="msg msg--warn">
          当前是静态构建（GitHub Pages），没有可写的后端。后台需要配合 Node 服务使用。
        </p>

        <template v-else>
          <p class="gate__hint">
            输入 <code>ADMIN_PASSWORD</code>。未在 <code>.env</code> 里配置时，
            服务启动会在控制台打印一个临时口令。
          </p>
          <label class="field">
            <span>管理口令</span>
            <input
              v-model="password"
              class="input"
              type="password"
              autocomplete="current-password"
              placeholder="••••••••"
              autofocus
            />
          </label>
          <p v-if="loginError" class="msg msg--error">{{ loginError }}</p>
          <button class="btn btn--primary gate__submit" type="submit" :disabled="submitting || !password">
            {{ submitting ? '验证中…' : '进入后台' }}
          </button>
        </template>

        <RouterLink to="/" class="gate__back">← 回到官网</RouterLink>
      </form>
    </div>

    <!-- 后台 -->
    <template v-else>
      <aside class="side">
        <div class="side__brand">
          <AppLogo :size="22" />
          <span>内容后台</span>
        </div>

        <nav class="side__nav">
          <RouterLink
            v-for="item in NAV"
            :key="item.to"
            :to="item.to"
            class="side__link"
            :class="{ 'side__link--on': item.exact ? route.path === item.to : route.path.startsWith(item.to) }"
          >{{ item.label }}</RouterLink>
        </nav>

        <div class="side__foot">
          <RouterLink to="/" class="side__link">查看官网</RouterLink>
          <RouterLink
            to="/admin/password"
            class="side__link"
            :class="{ 'side__link--on': route.path === '/admin/password' }"
          >修改密码</RouterLink>
          <button class="side__link side__link--btn" type="button" @click="logout">退出登录</button>
        </div>
      </aside>

      <main class="body">
        <p v-if="!canWrite" class="msg msg--warn body__warn">
          只读模式：当前环境无法写入内容。
        </p>
        <RouterView v-slot="{ Component, route: current }">
          <component :is="Component" :key="current.path" />
        </RouterView>
      </main>
    </template>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  min-height: 100vh;
  background: var(--paper);
}

/* ---------- 登录 ---------- */

.gate {
  display: grid;
  place-items: center;
  width: 100%;
  padding: 40px 24px;
}

.gate__card {
  display: grid;
  gap: 14px;
  width: min(420px, 100%);
  padding: 32px;
  justify-items: start;
}

.gate__logo {
  color: var(--brand);
}

.gate__title {
  font-size: 1.4rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.gate__hint {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.75;
}

.gate__submit {
  width: 100%;
}

.gate__back {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

@media (hover: hover) and (pointer: fine) {
  .gate__back:hover {
    color: var(--brand);
  }
}

/* ---------- 侧栏 ---------- */

.side {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  width: 208px;
  flex: none;
  height: 100vh;
  padding: 18px 12px;
  border-right: 1px solid var(--line);
  background: var(--paper-raised);
}

.side__brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 8px 18px;
  color: var(--brand);
  font-size: var(--t-sm);
  font-weight: 600;
}

.side__brand span {
  color: var(--ink);
}

.side__nav {
  display: grid;
  gap: 1px;
  flex: 1;
  align-content: start;
}

.side__link {
  display: block;
  width: 100%;
  padding: 9px 10px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
  font-size: var(--t-sm);
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--dur-ui) ease,
    color var(--dur-ui) ease;
}

@media (hover: hover) and (pointer: fine) {
  .side__link:hover {
    background: var(--paper-sunken);
    color: var(--ink);
  }
}

.side__link--on {
  background: var(--brand-wash);
  color: var(--brand-strong);
  font-weight: 500;
}

[data-theme="dark"] .side__link--on {
  color: var(--brand);
}

.side__foot {
  display: grid;
  gap: 1px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.side__link--btn {
  color: var(--ink-3);
}

/* ---------- 内容区 ---------- */

.body {
  flex: 1;
  min-width: 0;
  padding: clamp(20px, 3vw, 32px);
}

.body__warn {
  margin-bottom: 18px;
}

.msg {
  padding: 10px 14px;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  line-height: 1.7;
}

.msg--error {
  background: var(--danger-wash);
  color: var(--danger);
}

.msg--warn {
  background: var(--warn-wash);
  color: var(--warn);
}

@media (max-width: 800px) {
  .admin {
    flex-direction: column;
  }

  .side {
    position: static;
    width: 100%;
    height: auto;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }

  .side__nav {
    grid-auto-flow: column;
    grid-auto-columns: max-content;
    overflow-x: auto;
    gap: 4px;
  }

  .side__foot {
    display: flex;
    gap: 8px;
    margin-top: 10px;
  }
}
</style>
