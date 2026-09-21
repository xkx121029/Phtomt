<script setup>
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'

const LOOP = [
  {
    key: 'observe',
    title: '观察',
    line: '无障碍服务取回屏幕全部可交互元素',
    detail: '元素树里带着 id、文本、类名、可点击性与屏幕坐标。这份数据是后续所有判断的唯一事实来源。'
  },
  {
    key: 'annotate',
    title: '页面标注',
    line: '为 26 类常见控件分配语义 ID',
    detail: 'dlg_allow、ad_skip、search_box、send_btn、confirm_btn…标注结果直接注入模型上下文，模型可以按 id 选控件，不必猜坐标。'
  },
  {
    key: 'decide',
    title: '决策',
    line: '端侧先处理高频场景，其余交给模型',
    detail: '弹窗、加载、广告这类重复场景由 LocalDecisionEngine 本地决策，省掉一次往返；剩余情况才带上完整上下文请求模型。'
  },
  {
    key: 'act',
    title: '执行',
    line: '通道降级执行，动作前后比对页面指纹',
    detail: '点击前取一次页面指纹，点击后再取一次，不一致才算生效。这一步把「AI 以为点了」和「页面真的变了」区分开。'
  },
  {
    key: 'record',
    title: '记录',
    line: '落盘供多轮对话与诊断导出使用',
    detail: '步骤轨迹、日志、历史对话、能力状态分别持久化；导出诊断报告时打包，比口头描述现象有用得多。'
  }
]

const LAYERS = [
  { name: 'core/', desc: '跨层基础设施：AI 客户端、脱敏、通知、人话翻译' },
  { name: 'domain/', desc: '纯领域模型与规则：动作、意图、规则引擎、端侧决策' },
  { name: 'data/', desc: '持久化：DataStore 配置 + 五处 Store' },
  { name: 'device/', desc: '设备能力：无障碍、shell、截图、视觉' },
  { name: 'engine/', desc: 'Agent 编排：ReAct 主循环、转译层、感知、提示词' },
  { name: 'overlay/', desc: '悬浮窗：进度、接管、澄清、完成打勾' },
  { name: 'feature/', desc: '功能域：任务、技能、MCP、文档、跳广告、边缘光效、测试' },
  { name: 'ui/', desc: '界面层：页面 = 入口 + 单一职责拆分文件' }
]

const STORES = [
  { name: 'AppSettings', desc: 'DataStore 配置：模型、通道、语言、开关' },
  { name: 'MemoryStore', desc: '任务记忆：目标 / 用户要求 / 已验证方法 / 状态' },
  { name: 'TaskStore', desc: '检查点 + 任务模板库' },
  { name: 'DebugRecordsStore', desc: '日志 / 轨迹 / 历史 / 对话' },
  { name: 'McpStore', desc: 'MCP 服务与技能配置' },
  { name: 'PromptTemplateStore', desc: '提示词模板' }
]

const PRINCIPLES = [
  {
    title: 'AI 只说「做什么」',
    body: '模型输出的不是坐标和命令，而是 back / home / refresh / search / send / confirm / close / share / collect / copy / delete / download / add / switch / clear_input 这 15 个高层语义意图。转译由端侧实时完成，零网络往返。'
  },
  {
    title: '不确定时先尝试，不编造',
    body: '提示词里禁止「不确定就输出 task_done」。模型必须先试可行解法，卡住就 abort 并说明卡点；同时不得发明不存在的命令——结构化错误会跳过 3 次无效重试并立即失败。'
  },
  {
    title: '完成需要证据',
    body: '只有在当前页面上亲眼看到任务完成的明确证据时才允许输出 task_done，且总结里必须写清证据是什么。执行层还会做二次把关：至少执行 3 步、且达到规划步数的 60%。'
  },
  {
    title: '任务之间不串味',
    body: '每次 run() 开头会彻底清空 AI 上下文：对话消息、失败计数、无效命令计数、上一条 shell 输出、上一页截图、上一任务计划、工作记忆。'
  }
]
</script>

<template>
  <div>
    <PageHero
      eyebrow="工作原理"
      title="它是怎么跑的"
      lead="一句话描述目标之后，到屏幕上真的出现结果，中间发生了什么。下面这些不是概念图，是代码里真实存在的分层与顺序。"
    >
      <div class="row row--wrap">
        <RouterLink to="/docs/architecture" class="btn btn--sm">架构文档</RouterLink>
        <RouterLink to="/docs/intent-layer" class="btn btn--sm">意图转译层</RouterLink>
        <RouterLink to="/docs/safety" class="btn btn--sm">安全与隐私</RouterLink>
      </div>
    </PageHero>

    <!-- 主循环 -->
    <section class="section section--tight">
      <div class="page">
        <p v-reveal class="eyebrow">一轮循环</p>
        <h2 v-reveal="40" class="h2">观察 → 标注 → 决策 → 执行 → 记录</h2>

        <ol class="loop">
          <li v-for="(item, index) in LOOP" :key="item.key" v-reveal="index * 60" class="loop__item">
            <div class="loop__rail" aria-hidden="true">
              <span class="loop__num mono">{{ String(index + 1).padStart(2, '0') }}</span>
            </div>
            <div class="loop__body">
              <h3 class="loop__title">{{ item.title }}</h3>
              <p class="loop__line">{{ item.line }}</p>
              <p class="loop__detail">{{ item.detail }}</p>
            </div>
          </li>
        </ol>
      </div>
    </section>

    <!-- 分层 -->
    <section class="section section--tight band">
      <div class="page">
        <div class="split">
          <div>
            <p v-reveal class="eyebrow">代码分层</p>
            <h2 v-reveal="40" class="h2">八层，各管一段</h2>
            <p v-reveal="80" class="lead split__lead">
              分层的目的是让「换个模型」或「换条执行通道」不牵动其它部分。每一层的边界都在代码目录上看得见。
            </p>
          </div>
          <ul v-reveal="60" class="layers">
            <li v-for="layer in LAYERS" :key="layer.name" class="layers__item">
              <span class="mono layers__name">{{ layer.name }}</span>
              <span class="layers__desc">{{ layer.desc }}</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- 设计原则 -->
    <section class="section section--tight">
      <div class="page">
        <p v-reveal class="eyebrow">硬约束</p>
        <h2 v-reveal="40" class="h2">四条不许绕过的规矩</h2>

        <div class="grid">
          <article v-for="(item, index) in PRINCIPLES" :key="item.title" v-reveal="index * 60" class="card principle">
            <h3 class="principle__title">{{ item.title }}</h3>
            <p class="principle__body">{{ item.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 持久化 -->
    <section class="section section--tight band">
      <div class="page">
        <p v-reveal class="eyebrow">持久化</p>
        <h2 v-reveal="40" class="h2">六处存储，各存各的</h2>

        <div v-reveal class="table-wrap">
          <table>
            <thead>
              <tr><th>存储</th><th>内容</th></tr>
            </thead>
            <tbody>
              <tr v-for="store in STORES" :key="store.name">
                <td class="mono">{{ store.name }}</td>
                <td>{{ store.desc }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section class="section cta">
      <div class="page cta__inner">
        <h2 v-reveal class="h2">想看更细的？</h2>
        <p v-reveal="40" class="lead cta__lead">文档中心里有每个子系统的完整说明，包括为什么这么设计、踩过哪些坑。</p>
        <div v-reveal="80" class="cta__actions">
          <RouterLink to="/docs" class="btn btn--primary btn--lg">进入文档中心</RouterLink>
          <RouterLink to="/download" class="btn btn--lg">直接下载试试</RouterLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.loop {
  list-style: none;
  margin: 40px 0 0;
  padding: 0;
  display: grid;
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.loop__item {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 0;
  background: var(--paper-raised);
}

.loop__rail {
  display: flex;
  justify-content: center;
  padding: 22px 0;
  border-right: 1px solid var(--line);
}

.loop__num {
  font-size: var(--t-xs);
  color: var(--brand);
  letter-spacing: 0.08em;
}

.loop__body {
  padding: 22px 24px 24px;
}

.loop__title {
  font-size: var(--t-h3);
}

.loop__line {
  margin-top: 6px;
  font-size: var(--t-sm);
  color: var(--ink-2);
}

.loop__detail {
  margin-top: 10px;
  font-size: var(--t-sm);
  color: var(--ink-3);
  line-height: 1.78;
  max-width: 76ch;
}

.split {
  display: grid;
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.2fr);
  gap: clamp(28px, 4vw, 56px);
  align-items: start;
}

.split__lead {
  margin-top: 16px;
}

.layers {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 1px;
  background: var(--line);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
}

.layers__item {
  display: grid;
  grid-template-columns: 118px minmax(0, 1fr);
  gap: 14px;
  padding: 13px 18px;
  background: var(--paper-raised);
  font-size: var(--t-sm);
}

.layers__name {
  color: var(--brand);
}

.layers__desc {
  color: var(--ink-2);
}

.grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  margin-top: 32px;
}

.principle {
  display: grid;
  gap: 10px;
  align-content: start;
}

.principle__title {
  font-size: var(--t-h3);
}

.principle__body {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.8;
}

.cta {
  text-align: center;
}

.cta__inner {
  display: grid;
  justify-items: center;
}

.cta__lead {
  margin-top: 16px;
  max-width: 52ch;
}

.cta__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 28px;
}

@media (max-width: 860px) {
  .split {
    grid-template-columns: 1fr;
  }

  .loop__item {
    grid-template-columns: 52px minmax(0, 1fr);
  }
}
</style>
