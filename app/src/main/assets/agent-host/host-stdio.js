#!/usr/bin/env node
// Embedded agent host for the Android app (and any stdio embedder).
// Speaks the same JSON-lines protocol as the WebSocket server; config via env:
//   HAPPY_ACA_PROVIDER   openai | anthropic | ollama | mock | profile  (default openai)
//   HAPPY_ACA_API_KEY    provider API key
//   HAPPY_ACA_BASE_URL   endpoint (any OpenAI-compatible gateway, or an Ollama server)
//   HAPPY_ACA_MODEL      model id
//   HAPPY_ACA_WORKSPACE  workspace root (default cwd)
//   HAPPY_ACA_APPROVAL   ask | risky | bypass      (default risky)
//   HAPPY_ACA_TOKEN      optional shared secret; required in every request's params.token
//   HAPPY_ACA_STATE_DIR  enable persistence: sessions, memory, ledger, profiles, workflows, mcp
//   HAPPY_ACA_SKILLS_DIR optional skills root (SKILL.md + tools/*.mjs packages)
//   HAPPY_ACA_EMBED_BASE_URL / HAPPY_ACA_EMBED_API_KEY  optional remote embedding endpoint
//   HAPPY_ACA_MOCK_SCRIPT  scripted mock scenario (one loop, no API key)
//   HAPPY_ACA_NO_HOST_PROBE  set to 1 to skip the startup host_capabilities probe

import readline from 'node:readline';
import fs from 'node:fs';
import path from 'node:path';
import {
  AnthropicProvider,
  HostBridge,
  MockProvider,
  OllamaProvider,
  OpenAICompatibleProvider,
  createRuntime,
  createRouter,
  mockScriptFromJSON,
} from './index.js';

const env = process.env;
const providerKind = env.HAPPY_ACA_PROVIDER || 'openai';
const apiKey = env.HAPPY_ACA_API_KEY || '';
const baseUrl = env.HAPPY_ACA_BASE_URL || 'https://api.openai.com/v1';
const model = env.HAPPY_ACA_MODEL || 'gpt-5-codex';
const workspace = env.HAPPY_ACA_WORKSPACE || process.cwd();
const approvalMode = env.HAPPY_ACA_APPROVAL || 'risky';
const token = env.HAPPY_ACA_TOKEN || '';
const stateDir = env.HAPPY_ACA_STATE_DIR || null;

/** 按 env 造 provider；providerKind=profile 时完全交给模型档位（设置页配的那套）。 */
function buildProvider() {
  if (providerKind === 'profile') return null; // createRuntime 会从 profiles 里取
  if (providerKind === 'anthropic') return new AnthropicProvider({ apiKey, baseUrl, model });
  if (providerKind === 'ollama') {
    return new OllamaProvider({
      baseUrl: env.HAPPY_ACA_BASE_URL || 'http://127.0.0.1:11434',
      model: env.HAPPY_ACA_MODEL || 'llama3.2',
      numCtx: env.HAPPY_ACA_NUM_CTX ? Number(env.HAPPY_ACA_NUM_CTX) : undefined,
      keepAlive: env.HAPPY_ACA_KEEP_ALIVE,
    });
  }
  if (providerKind === 'mock') {
    // Optional scripted scenario so an offline device can exercise the real tool
    // loop (read -> edit -> run) without any API key:
    //   HAPPY_ACA_MOCK_SCRIPT=/path/to/scenario.json
    let turns = [];
    if (env.HAPPY_ACA_MOCK_SCRIPT && fs.existsSync(env.HAPPY_ACA_MOCK_SCRIPT)) {
      // 坏剧本不能让 host 起不来：离线演示的价值就在于「什么都打不开」。
      try {
        turns = mockScriptFromJSON(fs.readFileSync(env.HAPPY_ACA_MOCK_SCRIPT, 'utf8'));
      } catch (err) {
        process.stderr.write(`[happy-aca] ignoring bad mock script: ${err.message}\n`);
        turns = [];
      }
    }
    return new MockProvider(turns);
  }
  return new OpenAICompatibleProvider({ apiKey, baseUrl, model });
}

// 反向调用通道：agent → 宿主应用（浏览器 / 工作区文件 / 编辑器 / 预览 / 工具箱）。
// 只在应用真的实现了这些能力时才有回应；否则 probe() 记为 standalone 并降级。
// token 一并带上：App 侧对每个反向请求校验它，防止管道上的其他方驱动设备能力。
const send = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');
const bridge = new HostBridge({ send, token: token || null });

const runtime = createRuntime({
  provider: buildProvider(),
  workspaceRoot: workspace,
  stateDir,
  approvalMode,
  skillsRoot: env.HAPPY_ACA_SKILLS_DIR || null,
  preferTaskProvider: providerKind === 'profile',
  hostBridge: bridge,
});

const router = createRouter({ agent: runtime.agent, store: runtime.store, runtime, token: token || undefined });
router.addSender(send);

const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
  // 先让桥认领：只有它自己发出去的请求的响应才归它，其余仍走正常 RPC。
  let parsed = null;
  try {
    parsed = line.trim() ? JSON.parse(line) : null;
  } catch {
    /* 交给 router 报解析错误 */
  }
  if (parsed && bridge.handle(parsed)) return;
  router.handleLine(line, send);
});
rl.on('close', () => {
  bridge.fail('host closed the pipe');
  process.exit(0);
});

// 启动后探一次宿主能力，让 shell 通道表在第一次调用工具前就有答案。
if (env.HAPPY_ACA_NO_HOST_PROBE !== '1') {
  bridge.probe().then((caps) => {
    process.stderr.write(
      `[happy-aca] host bridge: platform=${caps.platform} channels=[${caps.channels.join(',')}] ` +
        `capabilities=[${(caps.capabilities || []).join(',')}]\n`,
    );
  });
}

process.stderr.write(
  `[happy-aca] embedded host ready: provider=${providerKind} model=${model} ` +
    `workspace=${workspace} approval=${approvalMode} token=${token ? 'on' : 'off'} ` +
    `sessions=${runtime.store.list().length} tools=${runtime.registered.length}` +
    `${stateDir ? ` state=${path.resolve(stateDir)}` : ''}` +
    `${runtime.failed.length ? ` degraded=${runtime.failed.length}` : ''}\n`,
);
for (const f of runtime.failed) process.stderr.write(`[happy-aca] degraded ${f.component}: ${f.error}\n`);
