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
      /* 对齐真机当前状态：任务记忆 13、异常 0、画像 0（统计卡 13/0/0） */
      S.memory.anomalies = [];
      S.memory.profiles = [];
      S.memory.taskMemories = [
        { id: 1, taskName: '设明早上七点的闹钟', label: '进行中', pill: 'warn',
          steps: 0, goal: '设明早上七点的闹钟', requirements: [], methods: [] },
        { id: 2, taskName: '设明早上七点的闹钟', label: '未完成', pill: 'err',
          steps: 0, goal: '设明早上七点的闹钟', requirements: [], methods: [] },
        ...[
          ['把屏幕亮度调到最高', '设置 → 显示与亮度 → 亮度拖到最右'],
          ['打开时钟，设一个 10 分钟后的闹钟', '时钟 → 添加闹钟 → 设为 10 分钟后'],
          ['把手机字体调到最大', '设置 → 显示与亮度 → 字体大小 → 拖到最右'],
          ['打开相册，看看最近拍的照片', '相册 → 照片 → 最近项目'],
          ['打开无线局域网设置', '设置 → 网络和互联网 → WLAN'],
          ['查看电池电量', '设置 → 电池'],
          ['打开蓝牙', '设置 → 已配对的设备 → 蓝牙开关'],
          ['把媒体音量调到 50%', '音量键 → 滑杆拖到一半'],
          ['打开飞行模式再关闭', '设置 → 网络和互联网 → 飞行模式'],
          ['截一张当前屏幕', '电源键 + 音量下键'],
          ['打开计算器', '桌面 → 计算器'],
        ].map(([taskName, method], i) => ({
          id: i + 3, taskName, label: '已验证', pill: 'ok',
          steps: 2, goal: taskName, requirements: [], methods: [method],
        })),
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
  /* 三个「设备内编码」示例：同一套真机 UI，不同语言 / 不同任务，供 S7 能力墙轮播 */
  {
    id: 'coding-python',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      S.coding.workspace = 'happy-agent';
      S.coding.path = '~/workspace/happy-agent';
      S.coding.conn = 'connected';
      S.coding.busy = false;
      S.coding.active = 1;
      S.coding.sessions = [{
        id: 1, title: '整理监控录像',
        messages: [
          { role: 'me', text: '把 /clips 下昨天的录像按小时归档，空文件删掉。' },
          {
            role: 'ai',
            text: '先按修改时间分组，再跳过 0 字节文件，最后打印归档结果。',
            code:
              'from pathlib import Path\n' +
              'import shutil, datetime\n\n' +
              'for clip in Path("/clips").glob("*.mp4"):\n' +
              '    if clip.stat().st_size == 0:\n' +
              '        clip.unlink(); continue\n' +
              '    hour = datetime.fromtimestamp(clip.stat().st_mtime).hour\n' +
              '    dest = Path(f"/archive/{hour:02d}")\n' +
              '    dest.mkdir(parents=True, exist_ok=True)\n' +
              '    shutil.move(str(clip), str(dest / clip.name))',
            file: 'archive_clips.py', lang: 'Python',
          },
          { role: 'ai', text: '已归档 37 个文件，跳过 2 个空文件。' },
        ],
      }];
      setTab('coding');
    },
  },
  {
    id: 'coding-canvas',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      S.coding.workspace = 'happy-agent';
      S.coding.path = '~/workspace/happy-agent';
      S.coding.conn = 'connected';
      S.coding.busy = false;
      S.coding.active = 1;
      S.coding.sessions = [{
        id: 1, title: '官网几何加载动画',
        messages: [
          { role: 'me', text: '给官网写一个立体几何的加载动画，别用第三方库。' },
          {
            role: 'ai',
            text: '用 Canvas 画等距立方体，按时间驱动旋转和明暗，设备像素比单独处理。',
            code:
              'const dpr = window.devicePixelRatio || 1\n' +
              'canvas.width = W * dpr; c.scale(dpr, dpr)\n\n' +
              'function frame(t) {\n' +
              '  c.clearRect(0, 0, W, H)\n' +
              '  const a = t / 1600, s = Math.cos(a) * 18\n' +
              '  isoCube(c, W / 2, H / 2 + Math.sin(a) * 8, s)\n' +
              '  requestAnimationFrame(frame)\n' +
              '}\n' +
              'requestAnimationFrame(frame)',
            file: 'geometry.ts', lang: 'TypeScript',
          },
          { role: 'ai', text: '已写入 geometry.ts，60fps 下无重排。' },
        ],
      }];
      setTab('coding');
    },
  },
  {
    id: 'coding-compose',
    run: () => {
      S.theme = 'dark'; S.palette = 'ink_green';
      S.coding.workspace = 'happy-agent';
      S.coding.path = '~/workspace/happy-agent';
      S.coding.conn = 'connected';
      S.coding.busy = false;
      S.coding.active = 1;
      S.coding.sessions = [{
        id: 1, title: '设置页呼吸指示灯',
        messages: [
          { role: 'me', text: '任务运行时在状态条加一个会呼吸的绿点，别太闪。' },
          {
            role: 'ai',
            text: '用 infiniteTransition 驱动 alpha，减少动效偏好下静态常亮。',
            code:
              '@Composable\n' +
              'fun PulseDot(reduceMotion: Boolean) {\n' +
              '  val alpha by animateFloat(\n' +
              '    target = 1f, tween(1100, RepeatMode.Reverse)\n' +
              '  )\n' +
              '  Box(Modifier.size(8.dp)\n' +
              '    .clip(CircleShape)\n' +
              '    .background(Brand.copy(alpha = alpha))\n' +
              '  )\n' +
              '}',
            file: 'PulseDot.kt', lang: 'Kotlin',
          },
          { role: 'ai', text: '已接入 HomeStatusStrip，构建通过。' },
        ],
      }];
      setTab('coding');
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

// 按实测 bbox 裁出三个编码示例的「提问 + 代码卡」特写（供 S7b 几何场景卡片使用）
for (const id of ['coding-python', 'coding-canvas', 'coding-compose']) {
  const st = layout[id];
  const me = st?.['.chat-me']?.[0];
  const code = st?.['.code-card']?.[0];
  if (!me || !code) {
    console.log(`card crop ${id}: skip (missing bbox)`);
    continue;
  }
  const x = 12;
  const y = Math.max(96, Math.round(me.y - 14));
  const w = 388;
  const h = Math.min(SCREEN.h - y, Math.round(code.y + code.h - y + 72));
  const kx = Math.round(x * DPR);
  const ky = Math.round(y * DPR);
  const kw = Math.round(w * DPR);
  const kh = Math.round(h * DPR);
  execFileSync('ffmpeg', [
    '-y', '-v', 'error',
    '-i', path.join(OUT, `${id}.png`),
    '-vf', `crop=${kw}:${kh}:${kx}:${ky}`,
    path.join(OUT, `${id}-card.png`),
  ]);
  console.log(`card crop ${id} ${kw}x${kh} @ ${kx},${ky}`);
}
