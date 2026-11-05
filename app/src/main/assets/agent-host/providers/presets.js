// 区域/厂商端点预设表（能力 #4）。
//
// Operit 有 43 个专用 Provider 文件；对 HACA 来说绝大部分只是“baseUrl + 默认
// 模型 + 几个特有 header”的差异，用一个数据表表达就够了——加端点不改契约，
// 也不用为每个厂商复制一整个类。真正协议不同的（Ollama / Anthropic）单独成类。

import { OpenAICompatibleProvider } from './openai-compatible.js';
import { AnthropicProvider } from './anthropic.js';
import { OllamaProvider } from './ollama.js';

/**
 * @typedef {Object} EndpointPreset
 * @property {string} id
 * @property {string} label
 * @property {'openai'|'ollama'|'anthropic'} kind   用哪个 Provider 实现
 * @property {string} baseUrl
 * @property {string} defaultModel
 * @property {string[]} [models]                    常见模型 id，供设置页下拉
 * @property {boolean} [needsKey]                   是否必须填 key（本地服务可空）
 * @property {Record<string,string>} [extraHeaders] 厂商特有 header
 * @property {string} [note]
 */

/** @type {EndpointPreset[]} */
export const ENDPOINT_PRESETS = [
  {
    id: 'openai',
    label: 'OpenAI',
    kind: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    defaultModel: 'gpt-5-codex',
    models: ['gpt-5-codex', 'gpt-5', 'gpt-5-mini', 'o4-mini'],
    needsKey: true,
  },
  {
    id: 'anthropic',
    label: 'Anthropic',
    kind: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    defaultModel: 'claude-sonnet-4-5',
    models: ['claude-sonnet-4-5', 'claude-opus-4-1', 'claude-haiku-4-5'],
    needsKey: true,
  },
  {
    id: 'xai',
    label: 'xAI (Grok)',
    kind: 'openai',
    baseUrl: 'https://api.x.ai/v1',
    defaultModel: 'grok-4',
    models: ['grok-4', 'grok-4-fast', 'grok-code-fast-1'],
    needsKey: true,
  },
  {
    id: 'moonshot',
    label: 'Moonshot / Kimi',
    kind: 'openai',
    baseUrl: 'https://api.moonshot.cn/v1',
    defaultModel: 'kimi-k2-0905-preview',
    models: ['kimi-k2-0905-preview', 'kimi-k2-turbo-preview', 'moonshot-v1-128k'],
    needsKey: true,
  },
  {
    id: 'moonshot-intl',
    label: 'Moonshot (international)',
    kind: 'openai',
    baseUrl: 'https://api.moonshot.ai/v1',
    defaultModel: 'kimi-k2-0905-preview',
    needsKey: true,
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    kind: 'openai',
    baseUrl: 'https://api.deepseek.com/v1',
    defaultModel: 'deepseek-chat',
    models: ['deepseek-chat', 'deepseek-reasoner'],
    needsKey: true,
  },
  {
    id: 'zhipu',
    label: '智谱 GLM',
    kind: 'openai',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    defaultModel: 'glm-4.6',
    models: ['glm-4.6', 'glm-4.5-air', 'glm-4-plus'],
    needsKey: true,
  },
  {
    id: 'dashscope',
    label: '阿里百炼 Qwen',
    kind: 'openai',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    defaultModel: 'qwen3-coder-plus',
    models: ['qwen3-coder-plus', 'qwen3-max', 'qwen-plus'],
    needsKey: true,
  },
  {
    id: 'volcengine',
    label: '火山方舟 Doubao',
    kind: 'openai',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    defaultModel: 'doubao-seed-code',
    needsKey: true,
  },
  {
    id: 'minimax',
    label: 'MiniMax',
    kind: 'openai',
    baseUrl: 'https://api.minimaxi.com/v1',
    defaultModel: 'MiniMax-M2',
    needsKey: true,
  },
  {
    id: 'fourrouter',
    label: 'FourRouter',
    kind: 'openai',
    baseUrl: 'https://fourrouter.ai/v1',
    defaultModel: 'gpt-5-codex',
    needsKey: true,
    note: '聚合网关，模型 id 跟随上游',
  },
  {
    id: 'openrouter',
    label: 'OpenRouter',
    kind: 'openai',
    baseUrl: 'https://openrouter.ai/api/v1',
    defaultModel: 'anthropic/claude-sonnet-4.5',
    needsKey: true,
    extraHeaders: { 'x-title': 'happy_ACA' },
  },
  {
    id: 'groq',
    label: 'Groq',
    kind: 'openai',
    baseUrl: 'https://api.groq.com/openai/v1',
    defaultModel: 'llama-3.3-70b-versatile',
    needsKey: true,
  },
  {
    id: 'together',
    label: 'Together AI',
    kind: 'openai',
    baseUrl: 'https://api.together.xyz/v1',
    defaultModel: 'Qwen/Qwen3-Coder-480B-A35B-Instruct',
    needsKey: true,
  },
  {
    id: 'mistral',
    label: 'Mistral',
    kind: 'openai',
    baseUrl: 'https://api.mistral.ai/v1',
    defaultModel: 'codestral-latest',
    needsKey: true,
  },
  {
    id: 'gemini',
    label: 'Google Gemini (OpenAI 兼容)',
    kind: 'openai',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    defaultModel: 'gemini-2.5-pro',
    needsKey: true,
  },
  {
    id: 'ollama',
    label: 'Ollama (本地)',
    kind: 'ollama',
    baseUrl: 'http://127.0.0.1:11434',
    defaultModel: 'qwen2.5-coder:7b',
    models: ['qwen2.5-coder:7b', 'qwen2.5-coder:14b', 'deepseek-coder-v2:16b', 'llama3.1:8b'],
    needsKey: false,
    note: '端侧/本机推理，无 key，probe() 会列已安装模型',
  },
  {
    id: 'lmstudio',
    label: 'LM Studio (本地)',
    kind: 'openai',
    baseUrl: 'http://127.0.0.1:1234/v1',
    defaultModel: 'qwen2.5-coder',
    needsKey: false,
  },
  {
    id: 'vllm',
    label: 'vLLM (本地/自建)',
    kind: 'openai',
    baseUrl: 'http://127.0.0.1:8000/v1',
    defaultModel: 'Qwen/Qwen3-Coder-30B',
    needsKey: false,
  },
  {
    id: 'custom',
    label: '自定义 OpenAI 兼容端点',
    kind: 'openai',
    baseUrl: '',
    defaultModel: '',
    needsKey: true,
    note: '任何 OpenAI /chat/completions 兼容服务',
  },
];

export function presetById(id) {
  return ENDPOINT_PRESETS.find((p) => p.id === id) ?? null;
}

/** 设置页下拉用。 */
export function presetOptions() {
  return ENDPOINT_PRESETS.map((p) => ({ id: p.id, label: p.label, needsKey: Boolean(p.needsKey) }));
}

/**
 * 根据 preset id + 用户填的字段造一个 Provider 实例。
 * kind 决定用哪个实现，端点差异全部收口在这一处。
 */
export function createProviderFromPreset({
  presetId,
  apiKey = '',
  baseUrl = '',
  model = '',
  extraHeaders,
  fetchImpl,
  numCtx,
  keepAlive,
}) {
  const preset = presetById(presetId) || presetById('custom');
  const resolvedBase = baseUrl || preset.baseUrl;
  const resolvedModel = model || preset.defaultModel;
  const headers = { ...(preset.extraHeaders || {}), ...(extraHeaders || {}) };

  if (preset.kind === 'anthropic') {
    if (!apiKey) throw new Error(`${preset.label}: api key is required`);
    return new AnthropicProvider({ apiKey, baseUrl: resolvedBase, model: resolvedModel, fetchImpl });
  }
  if (preset.kind === 'ollama') {
    return new OllamaProvider({ baseUrl: resolvedBase, model: resolvedModel, fetchImpl, numCtx, keepAlive });
  }
  if (preset.needsKey && !apiKey) throw new Error(`${preset.label}: api key is required`);
  return new OpenAICompatibleProvider({
    apiKey,
    baseUrl: resolvedBase,
    model: resolvedModel,
    extraHeaders: headers,
    fetchImpl,
  });
}
