<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHero from '../components/PageHero.vue'
import SectionHead from '../components/SectionHead.vue'
import StepRail from '../components/StepRail.vue'
import { content } from '../api/client.js'

const site = ref({})
const channels = computed(() => site.value.channels || [])

const LOOP = [
  {
    title: '观察',
    line: '无障碍服务取回屏幕全部可交互元素',
    detail: '元素树里带着 id、文本、类名、可点击性与屏幕坐标。这份数据是后续所有判断的唯一事实来源——模型不能凭记忆点一个屏幕上不存在的东西。'
  },
  {
    title: '页面标注',
    line: '为常见控件分配语义 ID',
    detail: 'dlg_allow、ad_skip、search_box、send_btn、confirm_btn…标注结果直接注入模型上下文，模型按语义 ID 选控件，不必猜坐标。'
  },
  {
    title: '决策',
    line: '端侧先处理高频场景，其余交给模型',
    detail: '弹窗、加载、广告这类重复场景由 LocalDecisionEngine 本地决断，省掉一次往返；剩余情况才带上完整上下文请求模型。'
  },
  {
    title: '执行',
    line: '通道降级执行，动作前后比对页面指纹',
    detail: '点击前取一次页面指纹，点击后再取一次，不一致才算生效。这一步把「AI 以为点了」和「页面真的变了」区分开。'
  },
  {
    title: '记录',
    line: '落盘供多轮对话、续跑与诊断导出使用',
    detail: '步骤轨迹、日志、历史对话、能力状态分别持久化；任务中断后从检查点续跑，导出诊断包时一并打包。'
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
    body: '模型输出的不是坐标和命令，而是 65 个语义意图之一（tap / input / scroll_to / open_app / settings_op…）。转译由端侧 IntentTranslator 实时完成——选通道、算坐标、拼命令都在本地，零网络往返。'
  },
  {
    title: '不确定时先尝试，不编造',
    body: '提示词里禁止「不确定就宣布完成」。模型必须先试可行解法，卡住就放弃并说明卡点；同时不得发明不存在的意图——不在 IntentType.ALL 里的输出会被判为非法。'
  },
  {
    title: '完成需要证据',
    body: '只有在当前页面上看到任务完成的明确证据时才允许收尾，总结里必须写清证据是什么。执行层还会二次把关：至少执行 3 步、且达到规划步数的 60%。'
  },
  {
    title: '任务之间不串味',
    body: '每次 run() 开头彻底清空 AI 上下文：对话消息、失败计数、无效命令计数、上一条 shell 输出、上一页截图、上一任务计划、工作记忆。'
  }
]

onMounted(async () => {
  try {
    site.value = await content.site()
  } catch {
    site.value = {}
  }
})
</script>

<template>
  <div>
    <PageHero
      no="02 / 原理"
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
        <SectionHead
          no="01"
          eyebrow="一轮循环"
          title="观察 → 标注 → 决策 → 执行 → 记录"
          lead="每一轮都回到屏幕本身。页面变了，证据就变了，下一步跟着变。"
        />
        <StepRail :items="LOOP" />
      </div>
    </section>

    <!-- 通道 -->
    <section class="section section--tight band">
      <div class="page">
        <SectionHead
          no="02"
          eyebrow="执行通道"
          title="三条通道，能力装在独立的 Provider 里"
          lead="无障碍、内置 Shizuku、Termux 各自封装在签名一致的 Provider APK 中，主应用经 AIDL 远程调用。AUTO 模式下按无线 ADB → Shizuku → Termux 降级，一条失败不阻塞主流程。"
        />

        <div class="channels">
          <article v-for="(ch, index) in channels" :key="ch.id" class="channel">
            <div class="channel__head">
              <span class="no">{{ String(index + 1).padStart(2, '0') }}</span>
              <h3 class="channel__name">{{ ch.name }}</h3>
              <span class="tag">{{ ch.level }}</span>
              <span v-if="ch.default" class="tag tag--brand">默认</span>
            </div>
            <p class="channel__desc">{{ ch.desc }}</p>
          </article>
          <p v-if="!channels.length" class="muted small">正在读取通道信息…</p>
        </div>
      </div>
    </section>

    <!-- 分层 -->
    <section class="section section--tight">
      <div class="page">
        <SectionHead
          no="03"
          eyebrow="代码分层"
          title="八层，各管一段"
          lead="分层的目的是让「换个模型」或「换条执行通道」不牵动其它部分。每一层的边界都在代码目录上看得见。"
        />
        <ol class="layers">
          <li v-for="layer in LAYERS" :key="layer.name" class="layers__item">
            <span class="mono layers__name">{{ layer.name }}</span>
            <span class="layers__desc">{{ layer.desc }}</span>
          </li>
        </ol>
      </div>
    </section>

    <!-- 设计原则 -->
    <section class="section section--tight band">
      <div class="page">
        <SectionHead
          no="04"
          eyebrow="硬约束"
          title="四条不许绕过的规矩"
          lead="这些规矩写在提示词与执行层里，不是运营口径。"
        />

        <div class="principles">
          <article v-for="(item, index) in PRINCIPLES" :key="item.title" v-reveal="index * 50" class="principle">
            <h3 class="principle__title">
              <span class="no">{{ String(index + 1).padStart(2, '0') }}</span>
              {{ item.title }}
            </h3>
            <p class="principle__body">{{ item.body }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 持久化 -->
    <section class="section section--tight">
      <div class="page">
        <SectionHead no="05" eyebrow="持久化" title="六处存储，各存各的" />

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
        <div v-reveal="70" class="cta__actions">
          <RouterLink to="/docs" class="btn btn--primary btn--lg">进入文档中心</RouterLink>
          <RouterLink to="/download" class="btn btn--lg">直接下载试试</RouterLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.channels {
  border-top: 1px solid var(--line);
}

.channel {
  padding: 20px 0;
  border-bottom: 1px solid var(--line);
  max-width: 76ch;
}

.channel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.channel__name {
  font-size: var(--t-h3);
}

.channel__desc {
  margin-top: 8px;
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink-2);
}

.layers {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--line);
}

.layers__item {
  display: grid;
  grid-template-columns: minmax(0, 7.5rem) minmax(0, 1fr);
  gap: 16px;
  padding: 13px 0;
  border-bottom: 1px solid var(--line);
  font-size: var(--t-sm);
}

.layers__name {
  color: var(--brand);
}

.layers__desc {
  color: var(--ink-2);
  line-height: 1.8;
}

.principles {
  display: grid;
  gap: 0;
  border-top: 1px solid var(--line);
}

.principle {
  padding: 22px 0;
  border-bottom: 1px solid var(--line);
}

.principle__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: var(--t-h3);
}

.principle__body {
  margin-top: 8px;
  font-size: var(--t-sm);
  line-height: 1.9;
  color: var(--ink-2);
  max-width: 74ch;
}

.cta {
  border-top: 1px solid var(--line);
}

.cta__lead {
  margin-top: 14px;
  max-width: 46ch;
}

.cta__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 24px;
}

@media (min-width: 900px) {
  .principles {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: clamp(32px, 4vw, 56px);
  }
}

/* 栅格子项默认 min-width:auto，一列长文本就会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.principles > * {
  min-width: 0;
}
</style>
