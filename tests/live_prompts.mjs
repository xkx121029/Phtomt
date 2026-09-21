/**
 * 从 Kotlin 源文件提取"当前真实生效"的提示词。
 *
 * 背景：tests/ 下早期脚本内嵌了 v2.0 时代的提示词副本（action/type 字段），
 * 与 AgentPrompts.kt 现行的 intent DSL 已脱节，跑它们验证不了真实提示词。
 * 本模块直接解析 AgentPrompts.kt，保证测试与软件运行时用的是同一份文本。
 *
 * 用法: import { loadPrompts } from './live_prompts.mjs'
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
export const KT_PATH = join(here, '..', 'app', 'src', 'main', 'java', 'com', 'phoneagent', 'engine', 'AgentPrompts.kt');

/** 取第 n（0 起）对三引号 raw string 的内容 */
function rawStrings(src, from, count) {
  const out = [];
  let idx = from;
  for (let i = 0; i < count; i++) {
    const start = src.indexOf('"""', idx);
    if (start < 0) break;
    const end = src.indexOf('"""', start + 3);
    if (end < 0) break;
    out.push(src.slice(start + 3, end));
    idx = end + 3;
  }
  return out;
}

function fnStart(src, name) {
  const m = new RegExp(`fun\\s+${name}\\s*\\(`).exec(src);
  if (!m) throw new Error(`函数未找到: ${name}`);
  return m.index;
}

/** COMMON_CN_APPS 这类拼接常量：抽取后把各段字符串连起来 */
function constText(src, name) {
  const m = new RegExp(`val\\s+${name}\\s*=`).exec(src);
  if (!m) throw new Error(`常量未找到: ${name}`);
  const semi = src.indexOf('"', m.index);
  const end = src.indexOf('\n\n', semi);
  const body = src.slice(semi, end < 0 ? src.length : end);
  return [...body.matchAll(/"([^"]*)"/g)].map((x) => x[1]).join('');
}

/** 运行期插值（$task、${...}）替换成示例值，未登记的用占位符 */
const SAMPLE = {
  task: '在美团点一份黄焖鸡米饭',
  profile: '无',
  installedApps: '微信、美团、支付宝、高德地图',
  stepIndex: '2',
  totalSteps: '5',
  currentStep: '点击搜索框',
  lastStepResult: '✅ 已确认成功',
  consecutiveFailures: '0',
  contextHint: '美团首页',
  blockReason: '找不到搜索框',
  history: '1. 打开美团 ✅\n2. 点击搜索框 ❌',
  outcome: '已完成',
  stepsSummary: '1. 打开美团 ✅ 2. 搜索黄焖鸡米饭 ✅',
  actionDesc: 'tap 搜索框',
  expected: '键盘弹出',
  hint: '点右上角的搜索图标',
  plan: '1. 打开美团 2. 搜索',
  stuckStep: '点击搜索框',
};

function render(text, consts, vars = {}) {
  const v = { ...SAMPLE, ...vars };
  return text
    .replace(/\$([A-Z_][A-Z0-9_]*)/g, (m, name) => (name in consts ? consts[name] : m))
    .replace(/\$\{(?:[^{}]|\{[^{}]*\})*\}/g, (m) => {
      const inner = m.slice(2, -1).trim();
      const key = Object.keys(v).find((k) => inner === k || inner.startsWith(k + '.'));
      if (key) return v[key];
      const quoted = /"([^"]*)"/.exec(inner);
      return quoted ? quoted[1] : `[${inner.split('.')[0]}]`;
    })
    .replace(/\$([A-Za-z_]\w*)/g, (m, name) => (name in v ? v[name] : m));
}

/**
 * 用自定义变量渲染某个提示词函数（lang: 'CN' | 'EN'）。
 * 与软件运行时同一份文本，只是把 $task/$installedApps 等换成测试用例的输入。
 * build('planning', 'CN', { task: '...' })
 */
export function build(name, lang = 'CN', vars = {}) {
  const src = readFileSync(KT_PATH, 'utf8');
  const consts = { COMMON_CN_APPS: constText(src, 'COMMON_CN_APPS') };
  const anchor = name === 'system' ? 'systemCN' : name;
  const raws = rawStrings(src, fnStart(src, anchor), 2);
  const raw = raws[lang === 'EN' ? 1 : 0];
  if (raw === undefined) throw new Error(`提示词未找到: ${name}/${lang}`);
  return render(raw, consts, vars).trim();
}

export function loadPrompts() {
  const src = readFileSync(KT_PATH, 'utf8');
  const consts = { COMMON_CN_APPS: constText(src, 'COMMON_CN_APPS') };

  const pick = (name, count = 2) => rawStrings(src, fnStart(src, name), count).map((t) => render(t, consts).trim());

  const system = rawStrings(src, fnStart(src, 'systemCN'), 2).map((t) => render(t, consts).trim());
  const [planCN, planEN] = pick('planning');
  const [decisionCN, decisionEN] = pick('decision');
  const [replanCN, replanEN] = pick('replan');
  const [batchCN, batchEN] = pick('batchPlanning');
  const [verifyCN, verifyEN] = pick('verify');
  const [distillCN, distillEN] = pick('memoryDistill');
  const [reviewCN, reviewEN] = pick('reviewSystem');

  return {
    source: KT_PATH,
    systemCN: system[0],
    systemEN: system[1],
    planCN, planEN,
    decisionCN, decisionEN,
    replanCN, replanEN,
    batchCN, batchEN,
    verifyCN, verifyEN,
    distillCN, distillEN,
    reviewCN, reviewEN: reviewEN || '',
    consts,
  };
}

if (process.argv[1] && process.argv[1].endsWith('live_prompts.mjs')) {
  const p = loadPrompts();
  console.log('来源:', p.source);
  for (const k of ['systemCN', 'systemEN', 'planCN', 'decisionCN', 'replanCN', 'batchCN']) {
    console.log(`${k}: ${p[k].length} 字符`);
  }
}