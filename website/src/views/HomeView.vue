<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import SectionHead from '../components/SectionHead.vue'
import ReadoutPanel from '../components/ReadoutPanel.vue'
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
 */
const primaryUrl = computed(() => {
  const cached = cachedDownloadSource(latest.value?.version)
  if (cached?.url) return cached.url
  return latest.value ? downloadUrl(latest.value, site.value) : '/download'
})

/** 首屏读数：值来自应用代码（AgentIntent / ActionPolicy / site.json），不随文案变 */
const SPECS = [
  { value: '65', unit: '个', label: '语义意图，模型只描述做什么' },
  { value: '3', unit: '条', label: '执行通道，能力装在独立 Provider' },
  { value: '3B', unit: '端侧', label: '视觉模型，高频页面不出设备' },
  { value: '0', unit: 'Root', label: '无障碍起步，系统命令用无线调试' }
]

/** 首屏读数面板：引擎里真实存在的五个阶段 */
const READOUT = [
  { key: 'observe', text: '无障碍取回 37 个可交互元素' },
  { key: 'annotate', text: '命中 search_box / send_btn / ad_skip' },
  { key: 'intent', text: 'settings_op · brightness = 128' },
  { key: 'translate', text: 'settings put system screen_brightness' },
  { key: 'verify', text: '页面指纹已变化，记为已生效' }
]

const MECHANISMS = [
  {
    key: 'intent',
    title: '模型只说做什么',
    how: '模型输出的是 65 个语义意图之一（tap / input / scroll_to / settings_op…），选通道、算坐标、拼命令全部在端侧完成。',
    then: '换模型、换分辨率、换语言都不用重写口令；模型也没有能力递给你一条命令。'
  },
  {
    key: 'provider',
    title: '高权限能力不在主应用里',
    how: '无障碍、内置 Shizuku、Termux 各自封装在签名一致的 Provider APK 中，主应用经 AIDL 调用。',
    then: '一条通道失败会降级到另一条；卸载主应用不会在系统里留下一个常驻的高权限服务。'
  },
  {
    key: 'verify',
    title: '每个动作都带验证',
    how: '动作前后各取一次页面指纹，不一致才算生效。「已送达但未确认」是独立状态。',
    then: '长任务不靠「我以为点到了」往前推，没确认的步骤会被看见、被重试。'
  },
  {
    key: 'vision',
    title: '看图也分本地和云端',
    how: '元素树可读（控件数 > 3）时优先端侧 3B 框选；稀疏页面切云端视觉描述，两条都不通时由 ML Kit 中文 OCR 兜底。',
    then: '高频页面不烧云端额度，游戏和 WebView 这类自绘界面也不至于看不见。'
  },
  {
    key: 'safety',
    title: '三档动作模式与安全闸',
    how: '保守 / 均衡 / 自由三档决定可用意图（35 + 21 + 5 条分档）。敏感页切只读，手机号、身份证号、卡号先脱敏，不可逆动作必弹确认。',
    then: '越权动作根本不在可选集里，而不是弹个提示希望你自觉。'
  },
  {
    key: 'coding',
    title: '手机上就能编译',
    how: 'PRoot + Alpine + JDK + Gradle + Android SDK 装在应用私有目录，编码 Agent 直接在设备上跑 assembleDebug。',
    then: '没有电脑也能改自己的 App，改完立刻装回去验证。'
  }
]

const LOOP = [
  { title: '观察', line: '无障碍取回当前屏幕的全部可交互元素', desc: '元素树带着 id、文本、类名、可点击性和坐标，是后续所有判断的唯一事实来源。' },
  { title: '标注', line: '给常见控件分配语义 ID', desc: '模型拿到的是「搜索框 / 发送按钮 / 跳过广告」这样的结构表，不是一张要它自己猜的图。' },
  { title: '决策', line: '端侧先处理高频场景，其余交给你的模型', desc: '弹窗、加载、广告这类重复场景本地决断，省掉一次往返。' },
  { title: '执行', line: '通道按能力降级，动作前后比对页面指纹', desc: '能点就点，点不到先滚动查找，仍找不到就中止并说明原因。' },
  { title: '验证', line: '指纹变了才算生效，没变就记一笔未确认', desc: '每一步的结论都带着证据进任务记忆，中断后可以从检查点续跑。' }
]

const INSTALL = [
  { title: '放行未知来源', desc: '当前提供 Debug 构建，系统会拦截。在「设置 → 应用 → 特殊权限」里为浏览器或文件管理器放行。' },
  { title: '核对 SHA-256', desc: '下载页每个版本都给出校验值，装之前自己比一遍：sha256sum 或 Get-FileHash。' },
  { title: '授予无障碍权限', desc: '它是 Agent 的手和眼，必须给。悬浮窗与通知可选，不给也能跑，只是看不到实时进度。' },
  { title: '填一个模型端点', desc: '任意 OpenAI 兼容端点都行：自建 Ollama、LM Studio、one-api。测试连接通过后才允许保存。' }
]

const SCREENS = [
  {
    src: './shots/home-agent.webp',
    alt: 'Happy Agent 应用首页：顶部是任务输入框，下方是最近的任务',
    meta: '首页／v0.2／1080×2400',
    caption: '一句人话进输入框，下面是它跑过的任务。没有表单，也没有步骤向导。'
  },
  {
    src: './shots/task-card.webp',
    alt: '任务执行中的任务卡：当前阶段、已完成步骤与可展开的单步详情',
    meta: '执行中／1080×2400',
    caption: '每一步都写着它看到了什么、做了什么、下一步准备做什么，随时可以插话改方向。'
  },
  {
    src: './shots/workbench.webp',
    alt: '深色主题下的 Agent 页：任务进度与对话并列',
    meta: '深色／1080×2400',
    caption: '同一套语义色令牌的另一面：宽屏上进度与对话并排，窄屏收成单列。'
  }
]

const QUICK_START = `# 1. 装好后授予无障碍与悬浮窗权限
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
    <!-- 首屏 -->
    <section class="hero">
      <div class="page hero__in">
        <div class="hero__text">
          <h1 v-reveal class="display hero__claim">一句话，<br />手机自己把事做完。</h1>
          <p v-reveal="40" class="hero__sub">
            你说「把屏幕亮度调到一半」，它<b>读当前页面</b>、拆出四步、用系统命令真的改掉，
            再把每一步的指纹变化记下来。<b>不是告诉你去哪点，是替你做。</b>
          </p>
          <div v-reveal="70" class="hero__acts">
            <a
              class="btn btn--primary btn--lg"
              :href="primaryUrl"
              :target="latest && !latest.apk ? '_blank' : undefined"
              :rel="latest && !latest.apk ? 'noopener noreferrer' : undefined"
            >
              下载 APK
              <span v-if="latest?.apk" class="mono hero__size">{{ latest.apk.sizeText }}</span>
            </a>
            <RouterLink to="/how-it-works" class="btn btn--lg">看它怎么跑</RouterLink>
          </div>
          <p v-if="latest" v-reveal="90" class="mono hero__ver">
            v{{ String(latest.version).replace(/^v/i, '') }}，{{ channelLabel(latest.channel) }}，
            {{ formatDate(latest.date) }}<template v-if="latest.channel !== 'stable'">，暂无稳定版</template>
          </p>
        </div>

        <div v-reveal="60" class="hero__panel">
          <ReadoutPanel
            src="./shots/home-agent.webp"
            alt="Happy Agent 首页截图：一个自然语言输入框，下面是最近的任务"
            :steps="READOUT"
            :foot="{ left: '4 步，0 步未确认', right: 'verified' }"
            screen="1080 × 2400"
          />
        </div>
      </div>

      <div class="page">
        <dl v-reveal="120" class="specs">
          <div v-for="spec in SPECS" :key="spec.label" class="specs__item">
            <dt class="specs__v">
              <span class="specs__n">{{ spec.value }}</span>
              <span class="specs__u">{{ spec.unit }}</span>
            </dt>
            <dd class="specs__l">{{ spec.label }}</dd>
          </div>
        </dl>
      </div>
    </section>

    <p v-if="failed" class="page notice">
      内容接口暂不可用，页面显示的是构建期快照。若为本地开发，请确认 <code>npm run dev:api</code> 已启动。
    </p>

    <!-- 大字声明 -->
    <section class="say">
      <div class="page say__in">
        <h2 v-reveal class="say__title">它不是把点击<br />录下来重放。</h2>
        <div class="say__body">
          <p v-reveal="40">
            录制回放在页面改版的那一刻就失效了。这里每一步都以「当前屏幕上真实存在什么」为唯一依据：
            目标控件不在当前页面就不允许点击，找不到先滚动，仍找不到就中止并说明卡在哪。
          </p>
          <p v-reveal="80" class="muted">
            所以它没有「训练过的界面」，只有读一次屏幕、做一次判断的能力。
          </p>
        </div>
      </div>
    </section>

    <!-- 六条机制 -->
    <section class="section">
      <div class="page">
        <SectionHead
          label="机制"
          title="凭什么它能自己把事做完"
          lead="六件事决定了它和「录一遍点击再重放」的区别。每条都写清机制，以及这个机制换来了什么。"
        >
          <RouterLink to="/features" class="btn btn--sm">全部 {{ featureCount || '' }} 项能力</RouterLink>
          <RouterLink to="/how-it-works" class="btn btn--sm">架构与循环</RouterLink>
        </SectionHead>

        <div class="mech">
          <article v-for="(item, index) in MECHANISMS" :key="item.key" v-reveal="index * 30" class="mech__item">
            <p class="mono mech__key">{{ item.key }}</p>
            <div class="mech__body">
              <h3 class="mech__title">{{ item.title }}</h3>
              <p class="mech__how">{{ item.how }}</p>
              <p class="mech__then">{{ item.then }}</p>
            </div>
          </article>
        </div>
      </div>
    </section>

    <!-- 图版 -->
    <section class="section band">
      <div class="page">
        <SectionHead
          label="实机"
          title="长这样"
          lead="三张都取自当前版本的应用，不是设计稿。控制台式的明细没有藏起来——它想让你看清每一步在做什么。"
        />
        <div class="plates">
          <ShotFrame v-reveal class="plates__lead" v-bind="SCREENS[0]" />
          <ShotFrame v-reveal="60" v-bind="SCREENS[1]" />
          <ShotFrame v-reveal="90" v-bind="SCREENS[2]" />
        </div>
      </div>
    </section>

    <!-- 主循环 -->
    <section class="section">
      <div class="page">
        <SectionHead
          label="主循环"
          title="一轮五步，每轮都回到屏幕本身"
          lead="没有「先把全部规划好再执行」这一步：每一步都以当前屏幕上真实存在什么为依据，页面变了它也跟得上。"
        />
        <StepRail :items="LOOP" dense />
        <p class="after-rail">
          <RouterLink to="/how-it-works" class="link-arrow">看八层代码结构与设计原则</RouterLink>
        </p>
      </div>
    </section>

    <!-- 安全 -->
    <section class="section safe">
      <div class="page">
        <SectionHead
          label="安全"
          title="把手机交出去之前，先看清闸门在哪"
          lead="一个能替你点屏幕的程序，安全不能只是「我们会小心的」。下面三条硬编码在动作下发之前。"
        />
        <div class="gates">
          <div v-reveal class="gate">
            <p class="mono gate__k">只读</p>
            <div>
              <h3 class="gate__t">敏感页面切只读</h3>
              <p class="gate__d">识别到支付、个人信息这类页面时整体只读：能看，不能点、不能提交。验证码与支付确认一律停下来等人。</p>
            </div>
          </div>
          <div v-reveal="40" class="gate">
            <p class="mono gate__k">脱敏</p>
            <div>
              <h3 class="gate__t">进模型前先脱敏</h3>
              <p class="gate__d">11 位手机号、18 位身份证号、银行卡号在写入上下文之前替换掉，模型看到的是掩码。</p>
            </div>
          </div>
          <div v-reveal="80" class="gate">
            <p class="mono gate__k">确认</p>
            <div>
              <h3 class="gate__t">不可逆动作二次确认</h3>
              <p class="gate__d">删除、清通知这类回不去的操作自动带确认，弹卡由人决定；自由模式下 AI 自写命令才放行。</p>
            </div>
          </div>
        </div>
        <p class="safe__foot">
          <RouterLink to="/docs/safety" class="link-arrow">安全与隐私的完整说明</RouterLink>
        </p>
      </div>
    </section>

    <!-- 安装 -->
    <section class="section">
      <div class="page">
        <SectionHead
          label="上手"
          title="四步装好，五分钟跑通第一个任务"
          lead="模型可以完全跑在你自己的机器上。地址不写协议会自动补全，内网 http 服务直接可用。"
        >
          <RouterLink to="/download" class="btn btn--sm">下载页：版本与校验值</RouterLink>
        </SectionHead>

        <div class="install">
          <StepRail :items="INSTALL" />
          <div class="install__side">
            <div class="codeblock">
              <div class="codeblock__bar">
                <span class="mono">快速开始</span>
                <CopyButton :text="QUICK_START" />
              </div>
              <pre class="code code--dark"><code>{{ QUICK_START }}</code></pre>
            </div>
            <p class="hint">
              更细的填法见
              <RouterLink to="/docs/getting-started" class="link-arrow">快速开始</RouterLink>
              与
              <RouterLink to="/docs/execution-channels" class="link-arrow">执行通道</RouterLink>。
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- 更新 -->
    <section v-if="entries.length" class="section band">
      <div class="page">
        <SectionHead label="更新" title="最近改了什么">
          <RouterLink to="/changelog" class="btn btn--sm">全部记录</RouterLink>
        </SectionHead>

        <div class="entries">
          <article v-for="(entry, index) in entries" :key="entry.version" v-reveal="index * 40" class="entry">
            <div class="entry__line">
              <RouterLink :to="`/changelog/${entry.version}`" class="mono entry__ver">{{ entry.version }}</RouterLink>
              <span class="mono entry__date">{{ formatDate(entry.date) }}</span>
              <span v-for="sec in entry.sections || []" :key="sec.type" :class="['tag', `tag--${sectionTone(sec.type)}`]">
                {{ sec.type }} {{ sec.items?.length || 0 }}
              </span>
            </div>
            <p v-if="entry.summary" class="entry__sum">{{ entry.summary }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 收尾 -->
    <section class="section cta">
      <div class="page cta__in">
        <h2 v-reveal class="h2 cta__t">装到自己的手机上试试</h2>
        <p v-reveal="40" class="lead cta__l">
          当前提供 Debug 构建，用于内测与自用。每个版本的 SHA-256 都写在下载页上，装之前可以自行核对。
        </p>
        <div v-reveal="60" class="cta__acts">
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
        <p v-if="latest" v-reveal="80" class="mono cta__meta">
          {{ channelLabel(latest.channel) }}，{{ formatDate(latest.date) }}（{{ relativeTime(latest.date) }}）
          <template v-if="releases.length > 1">，共 {{ releases.length }} 个可下载版本</template>
        </p>
        <p v-if="site.releaseSource?.degraded" class="hint">
          版本数据为本地兜底，可能不是最新；GitHub 恢复后会自动刷新。
        </p>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ---------- 首屏 ---------- */

.hero {
  padding-block: clamp(26px, 4vw, 56px) 0;
}

.hero__in {
  display: grid;
  gap: clamp(30px, 4vw, 48px);
  align-items: center;
}

.hero__claim {
  max-width: 12em;
}

.hero__sub {
  margin-top: 22px;
  max-width: 26em;
  font-size: var(--t-body);
  line-height: 1.9;
  color: var(--ink-2);
}

.hero__sub b {
  color: var(--ink);
  font-weight: 600;
}

.hero__acts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 30px;
}

.hero__size {
  font-size: 0.78em;
  opacity: 0.7;
}

.hero__ver {
  margin-top: 14px;
  font-size: 0.875rem;
  color: var(--ink-2);
}

.hero__panel {
  min-width: 0;
}

/* 硬指标：一行读数列，用线分开，不做卡片 */
.specs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0;
  margin: clamp(28px, 3.6vw, 44px) 0 0;
  border-top: 1px solid var(--line);
}

.specs__item {
  padding: 16px 16px 16px 0;
  border-bottom: 1px solid var(--line-soft);
}

.specs__v {
  display: flex;
  align-items: baseline;
  gap: 5px;
  margin: 0;
}

.specs__n {
  font-family: var(--font-mono);
  font-size: 1.5rem;
  font-weight: 500;
  letter-spacing: -0.02em;
}

.specs__u {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.specs__l {
  margin: 4px 0 0;
  font-size: 0.875rem;
  line-height: 1.6;
  color: var(--ink-2);
}

.notice {
  margin-top: 20px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-left: 2px solid var(--warn);
  border-radius: var(--r-sm);
  background: var(--panel);
  font-size: var(--t-sm);
  color: var(--ink-2);
}

/* ---------- 大字声明 ---------- */

.say {
  padding-block: clamp(48px, 6vw, 84px);
  border-top: 1px solid var(--line);
}

.say__in {
  display: grid;
  gap: clamp(20px, 3vw, 48px);
}

.say__title {
  font-size: clamp(1.9rem, 1.3rem + 2.4vw, 3.1rem);
  font-weight: 650;
  line-height: 1.24;
  letter-spacing: -0.012em;
}

.say__body {
  max-width: 34em;
  font-size: var(--t-body);
  line-height: 1.9;
  color: var(--ink-2);
}

.say__body p + p {
  margin-top: 14px;
}

/* ---------- 六条机制 ---------- */

.mech {
  border-top: 1px solid var(--line);
}

.mech__item {
  display: grid;
  grid-template-columns: 5.6rem minmax(0, 1fr);
  gap: 16px;
  padding: 20px 0;
  border-bottom: 1px solid var(--line-soft);
}

.mech__key {
  font-size: var(--t-xs);
  color: var(--signal);
  padding-top: 3px;
}

.mech__title {
  font-size: var(--t-h3);
}

.mech__how {
  margin-top: 7px;
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink-2);
  max-width: 46em;
}

/* 后果单独一行，左侧一道信号色细线：读的人一眼分清「怎么做的」和「所以怎样」 */
.mech__then {
  margin-top: 9px;
  padding-left: 11px;
  border-left: 2px solid var(--signal-line);
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink);
  max-width: 46em;
}

/* ---------- 图版 ---------- */

.plates {
  display: grid;
  gap: clamp(18px, 2.6vw, 30px);
  grid-template-columns: minmax(0, 1fr);
}

/* ---------- 循环 ---------- */

.after-rail {
  margin-top: 20px;
}

/* ---------- 安全 ---------- */

.safe {
  background: var(--panel);
  border-block: 1px solid var(--line);
}

.gates {
  border-top: 1px solid var(--line-soft);
}

.gate {
  display: grid;
  grid-template-columns: 4.6rem minmax(0, 1fr);
  gap: 16px;
  padding: 18px 0;
  border-bottom: 1px solid var(--line-soft);
}

.gate__k {
  font-size: var(--t-xs);
  color: var(--signal);
  padding-top: 3px;
}

.gate__t {
  font-size: var(--t-h3);
}

.gate__d {
  margin-top: 6px;
  font-size: var(--t-sm);
  line-height: 1.85;
  color: var(--ink-2);
  max-width: 46em;
}

.safe__foot {
  margin-top: 22px;
}

/* ---------- 安装 ---------- */

.install {
  display: grid;
  gap: clamp(24px, 3.6vw, 44px);
}

.codeblock {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
  background: var(--panel);
}

.codeblock__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line-soft);
  background: var(--panel-2);
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.codeblock .code {
  border: none;
  border-radius: 0;
  margin: 0;
  font-size: 0.85rem;
}

.install__side .hint {
  margin-top: 12px;
}

.install__side :deep(.link-arrow) {
  font-size: var(--t-xs);
}

/* ---------- 更新 ---------- */

.entries {
  border-top: 1px solid var(--line);
}

.entry {
  padding: 16px 0;
  border-bottom: 1px solid var(--line-soft);
}

.entry__line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.entry__ver {
  font-size: var(--t-sm);
  color: var(--signal);
}

.entry__date {
  font-size: var(--t-xs);
  color: var(--ink-3);
}

.entry__sum {
  margin-top: 7px;
  font-size: var(--t-sm);
  line-height: 1.8;
  color: var(--ink-2);
  max-width: 48em;
}

/* ---------- 收尾 ---------- */

.cta {
  border-top: 1px solid var(--line);
}

.cta__t {
  max-width: 16em;
}

.cta__l {
  margin-top: 12px;
  max-width: 32em;
}

.cta__acts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}

.cta__meta {
  margin-top: 12px;
  font-size: 0.875rem;
  color: var(--ink-2);
}

/* ---------- 断点 ---------- */

@media (min-width: 720px) {
  .specs {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .specs__item {
    padding: 16px 16px 16px 20px;
    border-left: 1px solid var(--line-soft);
  }

  .specs__item:first-child {
    padding-left: 0;
    border-left: none;
  }

  .plates {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .say__in {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    align-items: end;
  }
}

@media (min-width: 900px) {
  .hero__in {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.02fr);
  }

  .mech {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: clamp(28px, 4vw, 56px);
  }

  .install {
    grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
    align-items: start;
  }

  .install__side {
    position: sticky;
    top: 84px;
  }
}

/* 栅格子项默认 min-width:auto，一列长文本会把单列轨道撑到比容器还宽，
   375px 下直接顶出横向滚动条。显式归零后由父级轨道决定宽度。 */
.hero__in > *,
.mech > *,
.install > *,
.gates > *,
.specs > *,
.plates > *,
.entries > *,
.say__in > * {
  min-width: 0;
}
</style>
