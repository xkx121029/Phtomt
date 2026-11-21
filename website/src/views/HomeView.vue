<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import SectionHead from '../components/SectionHead.vue'
import DeviceFrame from '../components/DeviceFrame.vue'
import ShotFrame from '../components/ShotFrame.vue'
import StepRail from '../components/StepRail.vue'
import CopyButton from '../components/CopyButton.vue'
import { content } from '../api/client.js'
import { downloadUrl, formatDate, relativeTime, sectionTone, channelLabel } from '../lib/format.js'
import { cachedDownloadSource } from '../lib/downloadSource.js'
import { latestRelease } from '../lib/latest.js'

const data = ref(null)
const failed = ref(false)

const site = computed(() => data.value?.site || {})
// 与下载页同一判据：服务端 downloadTarget 优先稳定版，没有稳定版再退最新测试包
const latest = computed(() => latestRelease(data.value))
const featureCount = computed(() => (data.value?.features || []).length)
const releases = computed(() => data.value?.releases || [])
const entries = computed(() => (data.value?.changelog || []).slice(0, 2))

/**
 * 首页主按钮：优先复用下载页测好的择优结果（只读 sessionStorage，不发任何请求、不阻塞首屏）。
 * 没有缓存、版本对不上或已过期，就与改动前完全一致——走默认直链。
 */
const primaryUrl = computed(() => {
  const cached = cachedDownloadSource(latest.value?.version)
  if (cached?.url) return cached.url
  return latest.value ? downloadUrl(latest.value, site.value) : '/download'
})

/** 首屏硬指标。意图数与动作档位来自应用代码（AgentIntent.kt / ActionPolicy），不随站点内容变。 */
const SPECS = [
  { value: '65', unit: '个', label: '语义意图，模型只说做什么' },
  { value: '3', unit: '条', label: '执行通道，能力装在独立 Provider' },
  { value: '3B', unit: '端侧', label: '视觉模型，高频页面不出设备' },
  { value: '0', unit: 'Root', label: '无障碍即可起步，系统命令用无线调试' }
]

/**
 * 六个「凭什么」。每条都写成「机制 → 后果」：
 * 机制是代码里真实存在的东西，后果是用户能感知的差别。没有形容词。
 */
const MECHANISMS = [
  {
    key: 'intent',
    title: 'AI 只描述意图，不写命令',
    how: '模型输出的永远是 65 个语义意图之一（tap / input / scroll_to…），端侧转译层在另一侧把它翻成具体动作：有稳定 id 就用 id，只有图表这类元素树里没有的控件才退到坐标。',
    then: '换模型、换分辨率、换语言，都不需要重写口令；模型也没有能力递给你一条危险命令。'
  },
  {
    key: 'provider',
    title: '执行能力装在独立 Provider 里',
    how: '无障碍服务、内置 Shizuku server、Termux 桥接各自封装在签名一致的 Provider APK 中，主应用经 AIDL 远程调用，自身不持有高权限的系统操作代码。',
    then: '某条通道失败会降级到另一条，任务不至于整体卡死；卸载主应用不会在系统里留下一个常驻的高权限服务。'
  },
  {
    key: 'verify',
    title: '每一次点击都带验证',
    how: '动作前后各取一次页面指纹（控件特征），不一致才算真正生效。「已送达但未确认」是独立状态，不会被折成成功。',
    then: '长线任务不靠「我以为点到了」往前推——没确认的步骤会被看见、被重试，而不是一路点到黑屏。'
  },
  {
    key: 'vision',
    title: '多源视觉，按页面复杂度分流',
    how: '元素树可读（控件数 > 3）时优先交给端侧 3B 视觉框选；稀疏页面（≤ 3）自动切云端视觉描述，两条路都不通时由 ML Kit 中文 OCR 兜底。',
    then: '高频页面不烧云端额度，游戏和 WebView 这类自绘界面也不至于完全看不见。'
  },
  {
    key: 'safety',
    title: '三档动作模式 + 安全闸',
    how: '保守 / 均衡 / 自由三档决定可用意图（35 + 21 + 5 条分档，另有 4 条自由模式专属）。敏感页面切只读，手机号、身份证号、卡号先脱敏再进上下文，删除类不可逆动作必弹确认。',
    then: '越权动作根本不在可选集里，而不是「弹个提示希望你自觉」。'
  },
  {
    key: 'coding',
    title: '手机上就能编译',
    how: 'PRoot + Alpine + JDK + Gradle + Android SDK 装在应用私有目录，编码 Agent 直接在设备上跑 assembleDebug，工具调用按授权等级放行。',
    then: '没有电脑也能改自己的 App，改完立刻装回去验证。'
  }
]

const LOOP = [
  { title: '观察', line: '无障碍服务取回当前屏幕的全部可交互元素', desc: '元素树里带着 id、文本、类名、可点击性和坐标。这份数据是后面所有判断的唯一事实来源。' },
  { title: '标注', line: '为常见控件分配语义 ID，直接注入模型上下文', desc: '模型拿到的是「搜索框 / 发送按钮 / 跳过广告」这样的结构表，不是一张要它自己猜的图。' },
  { title: '决策', line: '端侧先处理高频场景，其余交给你的模型', desc: '弹窗、加载、广告这类重复场景本地决断，省掉一次往返；剩下的才带完整上下文请求模型。' },
  { title: '执行', line: '通道按能力降级，动作前后比对页面指纹', desc: '能点就点，点不到先滚动查找，仍找不到就中止并说明原因——不会盲点。' },
  { title: '验证', line: '指纹变了才算生效，没变就记一笔未确认', desc: '每一步的结论都带着证据进任务记忆，长任务中断后可以从检查点续跑。' }
]

const INSTALL = [
  { title: '放行未知来源', desc: '当前提供 Debug 构建，系统会拦截。在「设置 → 应用 → 特殊权限」里为浏览器或文件管理器放行。' },
  { title: '核对 SHA-256', desc: '下载页每个版本都给出校验值，装之前自己比一遍：sha256sum 或 Get-FileHash。' },
  { title: '授予无障碍权限', desc: '它是 Agent 的手和眼，必须给。悬浮窗与通知可选——不给也能跑，只是看不到实时进度。' },
  { title: '填一个模型端点', desc: '任意 OpenAI 兼容端点都行：自建 Ollama、LM Studio、one-api。测试连接通过后才允许保存。' }
]

const SCREENS = [
  {
    src: './shots/home-agent.webp',
    alt: 'Happy Agent 应用首页：顶部是任务输入框，下方是历史任务与快捷入口',
    label: '01 / 首页',
    caption: '一句人话进输入框，下面是它跑过的任务。没有表单、没有步骤向导。'
  },
  {
    src: './shots/task-card.webp',
    alt: '任务执行中的任务卡：显示当前阶段、已完成步骤与可展开的单步详情',
    label: '02 / 执行中',
    caption: '每一步都写着它看到了什么、做了什么、下一步准备做什么，随时可以插话改方向。'
  },
  {
    src: './shots/workbench.webp',
    alt: '深色主题下的 Agent 页：任务进度与对话并列',
    label: '03 / 深色',
    caption: '深色主题下同一套语义色令牌，任务进度与对话在宽屏上并排，窄屏收成单列。'
  }
]

const QUICK_START = `# 1. 装好 APK，授予无障碍与悬浮窗权限
# 2. 设置 → 模型配置，填任意 OpenAI 兼容端点
API 地址   http://192.168.1.5:8000/v1
模型名     qwen2.5-vl-7b

# 3. 回首页，用一句人话说出目标
打开设置，把屏幕亮度调到一半`

onMounted(async () => {
  try {
    data.value = await content.bootstrap()
  } catch {
    failed.value = true
  }
})
</script>

<template>
  <div class="home">
    <!-- 首屏：主张 + 一句机制 + 真机截图 + 下载 -->
    <section class="hero">
      <div class="page hero__inner">
        <div class="hero__text">
          <p v-reveal class="eyebrow">Android · 开源 · MIT</p>
          <h1 v-reveal="30" class="display hero__title">
            一句话，<br />手机自己把事做完。
          </h1>
          <p v-reveal="60" class="lead hero__lead">
            你说「把屏幕亮度调到一半」，它读当前页面、拆出四步、用系统命令真的改掉。
            不是告诉你去哪点，是替你把这件事做完。
          </p>

          <div v-reveal="90" class="hero__actions">
            <a
              class="btn btn--primary btn--lg"
              :href="primaryUrl"
              :target="latest && !latest.apk ? '_blank' : undefined"
              :rel="latest && !latest.apk ? 'noopener noreferrer' : undefined"
            >
              <svg width="15" height="15" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <path d="M10 3v9m0 0 3.5-3.5M10 12 6.5 8.5M4 15.5h12" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              下载 APK
              <span v-if="latest?.apk" class="mono hero__size">{{ latest.apk.sizeText }}</span>
            </a>
            <RouterLink to="/how-it-works" class="btn btn--lg">看它怎么跑</RouterLink>
            <RouterLink to="/scenarios" class="btn btn--ghost btn--lg">能干什么</RouterLink>
          </div>

          <p v-if="latest" v-reveal="110" class="hero__version">
            <span class="mono">v{{ String(latest.version).replace(/^v/i, '') }}</span>
            <span class="muted">· {{ channelLabel(latest.channel) }} · {{ formatDate(latest.date) }}</span>
            <span v-if="latest.channel !== 'stable'" class="muted">· 暂无稳定版</span>
          </p>
        </div>

        <div v-reveal="80" class="hero__device">
          <DeviceFrame
            src="./shots/home-agent.webp"
            alt="Happy Agent 首页截图：一个自然语言输入框，下面是最近的任务"
          />
          <p class="hero__caption">
            截图是应用的真实界面。
            <span class="muted">首页只有一个输入框——它没有菜单需要你学。</span>
          </p>
        </div>
      </div>

      <div class="page">
        <dl v-reveal="140" class="specs">
          <div v-for="spec in SPECS" :key="spec.label" class="specs__item">
            <dt class="specs__value">
              <span class="specs__num">{{ spec.value }}</span>
              <span class="specs__unit">{{ spec.unit }}</span>
            </dt>
            <dd class="specs__label">{{ spec.label }}</dd>
          </div>
        </dl>
      </div>
    </section>

    <p v-if="failed" class="page notice">
      内容接口暂不可用，页面显示的是构建期快照。若为本地开发，请确认 <code>npm run dev:api</code> 已启动。
    </p>

    <!-- 01 凭什么 -->
    <section class="section">
      <div class="page">
        <SectionHead
          no="01"
          eyebrow="机制"
          title="凭什么它能自己把事做完"
          lead="六件事决定了它和「录一遍点击再重放」的区别。每条都写清机制，以及这个机制换来了什么。"
        >
          <RouterLink to="/features" class="btn btn--sm">
            全部 {{ featureCount || '' }} 项能力
          </RouterLink>
          <RouterLink to="/how-it-works" class="btn btn--sm">架构与循环</RouterLink>
        </SectionHead>

        <div class="mech">
          <article v-for="(item, index) in MECHANISMS" :key="item.key" v-reveal="index * 40" class="mech__item">
            <div class="mech__head">
              <span class="no">{{ String(index + 1).padStart(2, '0') }}</span>
              <h3 class="mech__title">{{ item.title }}</h3>
            </div>
            <p class="mech__how">{{ item.how }}</p>
            <p class="mech__then">{{ item.then }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 02 截图 -->
    <section class="section band">
      <div class="page">
        <SectionHead
          no="02"
          eyebrow="实机"
          title="长这样"
          lead="以下三张都取自当前版本的应用，不是设计稿。控制台式的表格并没有藏起来——它想让你看清每一步在做什么。"
        />
        <div class="shots">
          <ShotFrame
            v-for="shot in SCREENS"
            :key="shot.src"
            v-reveal
            :src="shot.src"
            :alt="shot.alt"
            :label="shot.label"
            :caption="shot.caption"
            :tone="shot.label.includes('深色') ? 'dark' : 'light'"
          />
        </div>
      </div>
    </section>

    <!-- 03 一轮循环 -->
    <section class="section">
      <div class="page">
        <SectionHead
          no="03"
          eyebrow="主循环"
          title="一轮五步，每轮都回到屏幕本身"
          lead="没有「先规划好全部再执行」这一步：每一步都以当前屏幕上真实存在什么为依据，所以页面变了它也跟得上。"
        />
        <StepRail :items="LOOP" dense />
        <div class="after-rail">
          <RouterLink to="/how-it-works" class="link-arrow">看八层代码结构与设计原则</RouterLink>
        </div>
      </div>
    </section>

    <!-- 04 安全：深色锚点块 -->
    <section class="section safety">
      <span class="glow" aria-hidden="true"></span>
      <div class="page safety__inner">
        <SectionHead
          no="04"
          eyebrow="安全"
          title="把手机交出去之前，先看清闸门在哪"
          lead="一个能替你点屏幕的程序，安全不能只是「我们会小心的」。下面三条是硬编码在动作下发之前的。"
        />
        <div class="gates">
          <div class="gate">
            <h3 class="gate__title">敏感页面只读</h3>
            <p class="gate__body">
              识别到支付、个人信息这类页面时整体切只读横切：能看，不能点、不能提交。验证码与支付确认一律停下来等人。
            </p>
          </div>
          <div class="gate">
            <h3 class="gate__title">进模型前先脱敏</h3>
            <p class="gate__body">
              11 位手机号、18 位身份证号、银行卡号在写入上下文之前替换掉，模型看到的是掩码而不是原文。
            </p>
          </div>
          <div class="gate">
            <h3 class="gate__title">不可逆动作二次确认</h3>
            <p class="gate__body">
              删除、清通知这类回不去的操作自动带 needs_confirmation，弹卡由人决定；自由模式下 AI 自写命令才放行。
            </p>
          </div>
        </div>
        <div class="safety__foot">
          <RouterLink to="/docs/safety" class="link-arrow">安全与隐私的完整说明</RouterLink>
        </div>
      </div>
    </section>

    <!-- 05 怎么装 -->
    <section class="section">
      <div class="page">
        <SectionHead
          no="05"
          eyebrow="上手"
          title="四步装好，五分钟跑通第一个任务"
          lead="模型可以完全跑在你自己的机器上。地址不写协议会自动补全，内网 http 服务直接可用。"
        >
          <RouterLink to="/download" class="btn btn--sm">下载页：版本与校验值</RouterLink>
        </SectionHead>

        <div class="install">
          <StepRail :items="INSTALL" />
          <div class="install__code">
            <div class="code-block">
              <div class="code-block__bar">
                <span class="mono code-block__name">快速开始</span>
                <CopyButton :text="QUICK_START" />
              </div>
              <pre class="code code--dark"><code>{{ QUICK_START }}</code></pre>
            </div>
            <p class="hint install__hint">
              更细的填法见
              <RouterLink to="/docs/getting-started" class="link-arrow">快速开始</RouterLink>
              与
              <RouterLink to="/docs/execution-channels" class="link-arrow">执行通道</RouterLink>。
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- 06 最近更新 -->
    <section v-if="entries.length" class="section band">
      <div class="page">
        <SectionHead no="06" eyebrow="更新" title="最近改了什么">
          <RouterLink to="/changelog" class="btn btn--sm">全部记录</RouterLink>
        </SectionHead>

        <div class="entries">
          <article v-for="(entry, index) in entries" :key="entry.version" v-reveal="index * 50" class="entry">
            <div class="entry__head">
              <RouterLink :to="`/changelog/${entry.version}`" class="mono entry__ver">
                {{ entry.version }}
              </RouterLink>
              <span class="muted small">{{ formatDate(entry.date) }}</span>
              <span v-for="sec in entry.sections || []" :key="sec.type" :class="['tag', `tag--${sectionTone(sec.type)}`]">
                {{ sec.type }} {{ sec.items?.length || 0 }}
              </span>
            </div>
            <p v-if="entry.summary" class="entry__summary">{{ entry.summary }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 收尾 CTA -->
    <section class="section cta">
      <div class="page cta__inner">
        <h2 v-reveal class="h2 cta__title">装到自己的手机上试试</h2>
        <p v-reveal="40" class="lead cta__lead">
          当前提供 Debug 构建，用于内测与自用。每个版本的 SHA-256 都写在下载页上，装之前可以自行核对。
        </p>
        <div v-reveal="70" class="cta__actions">
          <RouterLink to="/download" class="btn btn--primary btn--lg">
            <template v-if="latest?.apk">下载 v{{ String(latest.version).replace(/^v/i, '') }}</template>
            <template v-else>前往下载页</template>
          </RouterLink>
          <a
            v-if="site.links?.github"
            class="btn btn--lg"
            :href="site.links.github"
            target="_blank"
            rel="noopener noreferrer"
          >GitHub 仓库</a>
        </div>
        <p v-if="latest" v-reveal="90" class="muted small cta__meta">
          {{ channelLabel(latest.channel) }} · 发布于 {{ formatDate(latest.date) }}（{{ relativeTime(latest.date) }}）
          <template v-if="releases.length > 1"> · 共 {{ releases.length }} 个可下载版本</template>
        </p>
        <p v-if="site.releaseSource?.degraded" v-reveal="90" class="muted small cta__meta">
          版本数据为本地兜底，可能不是最新；GitHub 恢复后会自动刷新。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ---------- 首屏 ---------- */

.hero {
  padding-block: clamp(28px, 5vw, 64px) 0;
}

.hero__inner {
  display: grid;
  gap: clamp(28px, 5vw, 56px);
  align-items: center;
}

.hero__title {
  margin-top: 16px;
}

.hero__lead {
  margin-top: 20px;
  max-width: 34ch;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 28px;
}

.hero__size {
  font-size: 0.76em;
  opacity: 0.7;
}

.hero__version {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: var(--t-xs);
}

.hero__device {
  display: grid;
  justify-items: center;
  gap: 16px;
  padding-inline: 8px;
}

.hero__caption {
  max-width: 30ch;
  text-align: center;
  font-size: var(--t-xs);
  line-height: 1.75;
  color: var(--ink-2);
}

/* 规格条：一条横线压住，列间细竖线分——像刊物页脚 */
.specs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 0;
  margin: clamp(32px, 4vw, 52px) 0 0;
  padding-top: 20px;
  border-top: 1px solid var(--line-2);
}

.specs__item {
  padding-inline: 16px;
  border-left: 1px solid var(--line);
}

.specs__item:nth-child(odd) {
  padding-left: 0;
  border-left: none;
}

.specs__value {
  display: flex;
  align-items: baseline;
  gap: 5px;
}

.specs__num {
  font-family: var(--font-mono);
  font-size: 1.35rem;
  font-weight: 600;
  letter-spacing: var(--track-num);
  font-variant-numeric: tabular-nums;
}

.specs__unit {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.specs__label {
  margin: 4px 0 0;
  font-size: var(--t-xs);
  line-height: 1.65;
  color: var(--ink-2);
}

.notice {
  margin-top: 20px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-left: 2px solid var(--amber);
  border-radius: var(--r-sm);
  background: var(--paper-raised);
  font-size: var(--t-sm);
  color: var(--ink-2);
}

/* ---------- 01 机制 ---------- */

.mech {
  display: grid;
  gap: 0;
  border-top: 1px solid var(--line);
}

.mech__item {
  padding: 22px 0;
  border-bottom: 1px solid var(--line);
}

.mech__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.mech__title {
  font-size: var(--t-h3);
}

.mech__how {
  margin-top: 10px;
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink-2);
  max-width: 62ch;
}

/* 后果单独成一行，左侧一道品牌色粗线：读的人一眼分清「怎么做的」和「所以怎样」 */
.mech__then {
  margin-top: 12px;
  padding-left: 13px;
  border-left: 2px solid var(--brand);
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink);
  max-width: 62ch;
}

/* ---------- 02 截图 ---------- */

.shots {
  display: grid;
  gap: clamp(20px, 3vw, 32px);
  grid-template-columns: 1fr;
}

/* ---------- 03 循环 ---------- */

.after-rail {
  margin-top: 22px;
}

/* ---------- 04 安全 ---------- */

.safety {
  position: relative;
  overflow: hidden;
  background: var(--brand-deep);
  color: #eef4f0;
}

.safety__inner {
  position: relative;
  z-index: 1;
}

.safety :deep(.eyebrow),
.safety :deep(.no) {
  color: rgba(238, 244, 240, 0.58);
}

.safety :deep(.sec__title) {
  color: #ffffff;
}

.safety :deep(.lead) {
  color: rgba(238, 244, 240, 0.78);
}

.gates {
  display: grid;
  gap: 2px;
  border-top: 1px solid rgba(238, 244, 240, 0.18);
}

.gate {
  padding: 20px 0;
  border-bottom: 1px solid rgba(238, 244, 240, 0.18);
}

.gate__title {
  font-size: var(--t-h3);
  color: #ffffff;
}

.gate__body {
  margin-top: 8px;
  font-size: var(--t-sm);
  line-height: 1.85;
  color: rgba(238, 244, 240, 0.76);
  max-width: 60ch;
}

.safety__foot {
  margin-top: 24px;
}

.safety :deep(.link-arrow) {
  color: #8bf0cf;
  border-color: rgba(139, 240, 207, 0.35);
}

/* ---------- 05 安装 ---------- */

.install {
  display: grid;
  gap: clamp(24px, 4vw, 48px);
}

.code-block {
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--paper-raised);
}

.code-block__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--paper-sunken);
}

.code-block__name {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.code-block .code {
  border: none;
  border-radius: 0;
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.75;
}

.install__hint {
  margin-top: 12px;
}

.install__hint :deep(a) {
  font-size: var(--t-xs);
}

/* ---------- 06 更新 ---------- */

.entries {
  display: grid;
  gap: 0;
  border-top: 1px solid var(--line);
}

.entry {
  padding: 18px 0;
  border-bottom: 1px solid var(--line);
}

.entry__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.entry__ver {
  font-weight: 600;
  color: var(--brand);
}

.entry__summary {
  margin-top: 8px;
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 68ch;
}

/* ---------- 收尾 ---------- */

.cta {
  border-top: 1px solid var(--line);
}

.cta__title {
  max-width: 18ch;
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

.cta__meta {
  margin-top: 14px;
}

/* ---------- 断点 ---------- */

@media (min-width: 720px) {
  .specs {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .specs__item:nth-child(odd) {
    padding-left: 16px;
    border-left: 1px solid var(--line);
  }

  .specs__item:first-child {
    padding-left: 0;
    border-left: none;
  }

  .gate {
    display: grid;
    grid-template-columns: minmax(0, 0.42fr) minmax(0, 0.58fr);
    gap: 24px;
    align-items: baseline;
  }

  .gate__body {
    margin-top: 0;
  }

  .shots {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (min-width: 900px) {
  .hero__inner {
    grid-template-columns: minmax(0, 1.06fr) minmax(0, 0.94fr);
    align-items: center;
  }

  .hero__lead {
    max-width: 36ch;
  }

  .mech {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: clamp(32px, 4vw, 56px);
  }

  .install {
    grid-template-columns: minmax(0, 1fr) minmax(0, 0.86fr);
    align-items: start;
  }

  .install__code {
    position: sticky;
    top: 84px;
  }
}
</style>
