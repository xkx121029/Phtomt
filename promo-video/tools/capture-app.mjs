/**
 * 从 promo/app.html（1:1 Compose UI 复刻）批量采集屏幕纹理。
 *
 * 输出：
 *   public/textures/screens/<id>.png   #screen 区域，deviceScaleFactor=3 → 1236×2676
 *   public/textures/layout.json        # 各状态关键元素在 412×892 逻辑屏内的 bbox
 *
 * 用法: node tools/capture-app.mjs
 */
import { chromium } from 'playwright-core';
import fs from 'node:fs/promises';
import path from 'node:path';

const APP = 'file:///D:/xkx/xkx_appproj/happy_phone%20agent/promo/app.html';
const ROOT = path.resolve('.');
const OUT = path.join(ROOT, 'public/textures/screens');
const DPR = 4;
const SCREEN = { w: 412, h: 892 };

/** 每个状态是一次 evaluate：修改 S → 渲染 → 可选点击。 */
const STATES = [
  {
    id: 'home',
    run: () => {
      S.theme = 'dark';
      S.palette = 'ink_green';
      S.perms = { a11y: true, overlay: true, autostart: true, apps: true };
      S.agent.items = [];
      setTab('home');
    },
  },
  {
    id: 'home-ochre',
    run: () => {
      S.theme = 'dark'; S.palette = 'ochre'; setTab('home');
    },
  },
  {
    id: 'agent-empty',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      S.agent.items = []; S.agent.plan = null; S.agent.running = false;
      setTab('agent');
    },
  },
  {
    id: 'agent-plan',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      const a = S.agent;
      a.items = [
        { kind: 'user', text: '帮我把手机字体调大一点，我眼睛有点累' },
        {
          kind: 'stream',
          text: '好的。我先读取当前字体设置，再逐步调大字号，最后确认页面已经变大。',
          typing: false,
        },
      ];
      a.task = '帮我把手机字体调大一点';
      a.planning = false;
      a.running = false;
      a.step = 0;
      a.total = 3;
      a.confidence = 0.86;
      a.phase = 'PLANNING';
      a.plan = {
        confidence: 0.86,
        seconds: 18,
        steps: [
          { t: '打开系统设置', i: '读取设置入口控件，进入显示与亮度' },
          { t: '进入字体大小', i: '定位「字体与显示大小」并点开预览页' },
          { t: '调到最大档并确认', i: '拖到最右，比对页面指纹确认真生效' },
        ],
      };
      setTab('agent');
    },
  },
  {
    id: 'agent-run',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      const a = S.agent;
      a.items = [
        { kind: 'user', text: '帮我把手机字体调大一点，我眼睛有点累' },
        { kind: 'approved' },
        { kind: 'tool', idx: 1, title: '打开系统设置', detail: '无障碍服务读出 46 个可点控件', state: 'done' },
        { kind: 'tool', idx: 2, title: '进入字体与显示大小', detail: '点击「显示与亮度」→「字体大小」', state: 'done' },
        { kind: 'tool', idx: 3, title: '把字号调到最大档', detail: '滑杆拖至最右，预览区同步变大', state: 'run' },
      ];
      a.task = '帮我把手机字体调大一点';
      a.planning = false; a.plan = null;
      a.running = true; a.paused = false;
      a.step = 3; a.total = 3;
      a.confidence = 0.91;
      a.phase = 'ACTING';
      a.tokens = 18420;
      setTab('agent');
    },
  },
  {
    id: 'agent-done',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      const a = S.agent;
      a.items = [
        { kind: 'user', text: '帮我把手机字体调大一点，我眼睛有点累' },
        { kind: 'approved' },
        { kind: 'tool', idx: 1, title: '打开系统设置', detail: '无障碍服务读出 46 个可点控件', state: 'done' },
        { kind: 'tool', idx: 2, title: '进入字体与显示大小', detail: '点击「显示与亮度」→「字体大小」', state: 'done' },
        { kind: 'tool', idx: 3, title: '把字号调到最大档', detail: '滑杆拖至最右，预览区同步变大', state: 'done' },
        {
          kind: 'done', steps: 3, tokens: '28.6k', avg: '1.7s',
          note: '字体已调到最大档，桌面与设置页都已生效。',
        },
      ];
      a.task = '帮我把手机字体调大一点';
      a.planning = false; a.plan = null;
      a.running = false; a.step = 3; a.total = 3;
      a.phase = 'DONE'; a.confidence = 0.94;
      setTab('agent');
    },
  },
  {
    id: 'memory',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      seedMemoryPages();
      S.memory.tab = 'graph';
      S.memory.anomalies = [
        { anomalyType: '控件被广告遮挡', appPackage: 'com.tencent.mm' },
        { anomalyType: '页面未加载完就点击', appPackage: 'com.android.settings' },
        { anomalyType: '输入法抢焦点', appPackage: 'com.android.deskclock' },
      ];
      S.memory.profiles = [
        { key: '常用字号', value: '最大档' },
        { key: '习惯时间', value: '早 7:30 闹钟' },
        { key: '偏好通道', value: '无障碍优先' },
      ];
      S.memory.taskMemories = [
        {
          id: 1, taskName: '把手机字体调到最大', label: '已验证', pill: 'ok',
          steps: 4, goal: '让系统字号与界面元素同时变大',
          requirements: ['不要动到无障碍服务本身'],
          methods: ['设置 → 显示与亮度 → 字体大小 → 拖到最右'],
        },
        {
          id: 2, taskName: '整理本月账单', label: '已验证', pill: 'ok',
          steps: 6, goal: '把本月订单按类别汇总成一条清单',
          requirements: [],
          methods: ['读取订单列表 → 按商户归类 → 生成摘要卡片'],
        },
      ];
      setTab('memory');
    },
  },
  {
    id: 'memory-pages',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      seedMemoryPages();
      S.memory.tab = 'pages';
      setTab('memory');
    },
  },
  {
    id: 'coding',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      S.coding.workspace = 'happy-agent';
      S.coding.path = '~/workspace/happy-agent';
      S.coding.conn = 'connected';
      S.coding.busy = false;
      S.coding.active = 1;
      S.coding.sessions = [{
        id: 1, title: '给设置页加一个开关',
        messages: [
          { role: 'me', text: '给设置页加一个「禁止自动跳转」开关，默认关闭。' },
          {
            role: 'ai',
            text: '改三处：设置项模型、界面开关、执行前判定。先加字段。',
            code:
              'data class MotionPrefs(\n' +
              '    val blockAutoJump: Boolean = false,\n' +
              ')\n\n' +
              '// SettingsRepository.kt\n' +
              'val blockAutoJump = prefs.blockAutoJump\n' +
              '    .stateIn(scope, SharingStarted.Eagerly, false)',
            file: 'MotionPrefs.kt', lang: 'Kotlin',
          },
          { role: 'ai', text: '已写入 3 个文件，编译通过。' },
        ],
      }];
      setTab('coding');
    },
  },
  {
    id: 'chat',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      S.chat = [
        { role: 'me', text: '这个 App 到底怎么接管手机的？' },
        {
          role: 'ai',
          text:
            '它不靠脚本。无障碍服务把当前页面读成一棵控件树，我看到的是「第 3 个可点击、文字是字体大小」，' +
            '再由端侧转译层算出该点哪里。AI 只描述意图，坐标和通道都在本地决定。',
        },
      ];
      setTab('chat');
    },
  },
  {
    id: 'settings',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      setTab('settings');
    },
  },
  {
    id: 'theme-page',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      openPage('theme');
    },
  },
];

/** 需要记录的 bbox（相对 #screen 左上，单位=逻辑 CSS px） */
const PROBES = [
  '#hdr', '#hdrInner', '#nav', '#composer', '#dock', '.agent-list',
  '.strip', '.plan-panel', '.plan-head', '.plan-steps', '.pstep', '.plan-actions',
  '.tool', '.done-card', '.bubble', '.ai-bubble', '.hero-tile', '.suggest',
  '.nav-item', '.rail', '.sec-box', '.srow', '.gnode', '.groot',
  '.chat-me', '.chat-ai', '.code-card', '.ws-head', '.pg-card', '.tmem',
  '.mem-stats', '.seg', '.seg button', '.card',
];

const shots = [
  { id: 'home', selector: '#view', tag: 'view' },
  { id: 'agent', selector: '#view', tag: 'view' },
];

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const ctx = await browser.newContext({
  viewport: { width: 1200, height: 1600 },
  deviceScaleFactor: DPR,
  colorScheme: 'dark',
  reducedMotion: 'reduce',
});
const page = await ctx.newPage();
await page.goto(APP, { waitUntil: 'load' });

// 关掉所有过渡/动画，保证截图确定性
await page.addStyleTag({
  content: `*,*::before,*::after{animation:none!important;transition:none!important}`,
});
await page.evaluate(() => document.fonts.ready);
await new Promise((r) => setTimeout(r, 800));

await fs.mkdir(OUT, { recursive: true });
const layout = {};

for (const st of STATES) {
  await page.evaluate(st.run);
  await page.evaluate(() => {
    applyChrome();
    render();
    document.querySelectorAll('.toast').forEach((n) => (n.style.display = 'none'));
    document.querySelectorAll('.anim').forEach((n) => n.classList.remove('anim'));
  });
  await new Promise((r) => setTimeout(r, 420));

  const screen = page.locator('#screen');
  await screen.screenshot({ path: path.join(OUT, `${st.id}.png`) });

  layout[st.id] = await page.evaluate((probes) => {
    const s = document.querySelector('#screen').getBoundingClientRect();
    const out = {};
    for (const sel of probes) {
      out[sel] = Array.from(document.querySelectorAll(sel)).map((el) => {
        const r = el.getBoundingClientRect();
        return {
          x: +(r.left - s.left).toFixed(1),
          y: +(r.top - s.top).toFixed(1),
          w: +r.width.toFixed(1),
          h: +r.height.toFixed(1),
        };
      });
    }
    return out;
  }, PROBES);

  const box = await screen.boundingBox();
  console.log(
    `${st.id.padEnd(14)} screen=${Math.round(box.width)}x${Math.round(box.height)} -> png ${Math.round(box.width * DPR)}x${Math.round(box.height * DPR)}`
  );
}

await fs.writeFile(
  path.join(ROOT, 'public/textures/layout.json'),
  JSON.stringify({ screen: SCREEN, dpr: DPR, states: layout }, null, 1)
);
console.log('layout written');
await browser.close();

// 按实测 bbox 裁出计划面板特写（供 S3 前景卡使用）
const panel = layout['agent-plan']['.plan-panel'][0];
const pad = 2;
const cx = Math.round((panel.x - pad) * DPR);
const cy = Math.round((panel.y - pad) * DPR);
const cw = Math.round((panel.w + pad * 2) * DPR);
const ch = Math.round((panel.h + pad * 2) * DPR);
const { execFileSync } = await import('node:child_process');
execFileSync('ffmpeg', [
  '-y', '-v', 'error',
  '-i', path.join(OUT, 'agent-plan.png'),
  '-vf', `crop=${cw}:${ch}:${cx}:${cy}`,
  path.join(OUT, 'agent-plan-panel.png'),
]);
console.log(`panel crop ${cw}x${ch} @ ${cx},${cy} → agent-plan-panel.png`);
