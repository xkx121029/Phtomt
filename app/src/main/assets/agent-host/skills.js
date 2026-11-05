// Skill 包系统（能力 #25，参考 Operit SkillPackage/SkillManager 的形态，独立实现）。
//
// 一个 skill 就是一个目录：
//   <skillsDir>/<skill-id>/
//     skill.json      { id, name, description, version, tools: [...], prompt? }
//     tools/*.mjs     每个工具一个文件，默认导出 { name, description, parameters, execute }
//     prompt.md       可选：激活时注入 system prompt 的说明
//
// 设计要点：
//   1. skill 是「按需激活」的——默认不注册，agent 调用 activate_skill 才注册其工具
//      （避免几十个 skill 的工具把上下文撑爆）
//   2. 工具代码用动态 import() 加载，天然支持 ESM、零解析成本
//   3. skill.json 是唯一契约，新增 skill 不需要改任何代码

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

/** 读取并校验一个 skill 目录。 */
export function loadSkillManifest(dir) {
  const manifestPath = path.join(dir, 'skill.json');
  if (!fs.existsSync(manifestPath)) throw new Error(`no skill.json in ${dir}`);
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (err) {
    throw new Error(`invalid skill.json in ${dir}: ${err.message}`);
  }
  if (!manifest?.id) throw new Error(`skill.json in ${dir} needs an id`);
  return {
    id: String(manifest.id),
    name: manifest.name || manifest.id,
    description: manifest.description || '',
    version: manifest.version || '0.0.0',
    // prompt.md 与 skill.json 里的 prompt 是两种写法：文档类 skill 的说明通常很长，
    // 单独成文件更好编辑。缺任何一边都回落到 null，激活时就不会往 system prompt 里塞空段。
    prompt: manifest.prompt || readPromptFile(dir),
    tools: Array.isArray(manifest.tools) ? manifest.tools : [],
    dir,
  };
}

/** 读 <skill>/prompt.md；没有或读不出来都返回 null（说明文档缺失不该让整个 skill 失效）。 */
function readPromptFile(dir) {
  const promptPath = path.join(dir, 'prompt.md');
  try {
    const text = fs.readFileSync(promptPath, 'utf8').trim();
    return text || null;
  } catch {
    return null;
  }
}

/** 扫描 skills 根目录，返回全部 manifest（损坏的 skill 跳过并记录错误）。 */
export function discoverSkills(skillsRoot) {
  if (!skillsRoot || !fs.existsSync(skillsRoot)) return { skills: [], errors: [] };
  const skills = [];
  const errors = [];
  for (const entry of fs.readdirSync(skillsRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    try {
      skills.push(loadSkillManifest(path.join(skillsRoot, entry.name)));
    } catch (err) {
      errors.push({ dir: entry.name, error: err.message });
    }
  }
  return { skills, errors };
}

/**
 * Skill 管理器：激活 / 停用 skill，把 skill 的工具注册进 ToolRegistry。
 *
 * 激活流程：
 *   1. manifest.tools 里每个文件名 -> <dir>/tools/<file>.mjs 动态 import
 *   2. 默认导出必须是 { name, description, parameters, execute }
 *   3. 工具名加前缀 skill_<skillId>_ 防撞名（重名时跳过并记错误）
 * 停用：把该前缀的工具全部 unregister。
 */
export class SkillManager {
  constructor({ registry, skillsRoot, onEvent } = {}) {
    if (!registry) throw new Error('SkillManager needs a registry');
    this.registry = registry;
    this.skillsRoot = skillsRoot || null;
    this.onEvent = onEvent || (() => {});
    this.active = new Map(); // skillId -> { manifest, tools: string[], activatedAt }
  }

  /** 列出可用 skill（含激活状态）。 */
  list() {
    const { skills, errors } = this.skillsRoot ? discoverSkills(this.skillsRoot) : { skills: [], errors: [] };
    return skills.map((s) => ({
      id: s.id,
      name: s.name,
      description: s.description,
      version: s.version,
      active: this.active.has(s.id),
      tools: s.tools,
      prompt: s.prompt,
    }));
  }

  errors() {
    return this.skillsRoot ? discoverSkills(this.skillsRoot).errors : [];
  }

  async activate(skillId) {
    if (this.active.has(skillId)) return this.active.get(skillId);
    const { skills } = this.skillsRoot ? discoverSkills(this.skillsRoot) : { skills: [] };
    const manifest = skills.find((s) => s.id === skillId);
    if (!manifest) throw new Error(`unknown skill: ${skillId}`);

    const registered = [];
    const failures = [];
    for (const file of manifest.tools) {
      const filePath = path.join(manifest.dir, 'tools', file.endsWith('.mjs') ? file : `${file}.mjs`);
      if (!fs.existsSync(filePath)) {
        failures.push({ file, error: 'tool file not found' });
        continue;
      }
      try {
        const mod = await import(pathToFileURL(filePath).href);
        const tool = mod.default || mod;
        const name = `skill_${skillId}_${tool.name}`;
        if (!tool?.name || typeof tool?.execute !== 'function' || !tool?.parameters) {
          failures.push({ file, error: 'tool must export { name, description, parameters, execute }' });
          continue;
        }
        this.registry.register({
          name,
          description: `[skill:${manifest.name}] ${tool.description}`,
          parameters: tool.parameters,
          risk: tool.risk || 'exec',
          _source: `skill:${skillId}`,
          execute: (args, ctx) => tool.execute(args, { ...ctx, skillId, skillDir: manifest.dir }),
        });
        registered.push(name);
      } catch (err) {
        failures.push({ file, error: err.message });
      }
    }

    const entry = { manifest, tools: registered, failures, activatedAt: new Date().toISOString() };
    this.active.set(skillId, entry);
    this.onEvent({ type: 'skill_activated', skillId, tools: registered, failures });
    return entry;
  }

  deactivate(skillId) {
    const entry = this.active.get(skillId);
    if (!entry) return { deactivated: false };
    for (const name of entry.tools) this.registry.unregister(name);
    this.active.delete(skillId);
    this.onEvent({ type: 'skill_deactivated', skillId });
    return { deactivated: true, tools: entry.tools };
  }

  deactivateAll() {
    for (const id of [...this.active.keys()]) this.deactivate(id);
  }

  /** 已激活 skill 的 prompt 片段，拼进 system prompt 用。 */
  activePrompts() {
    return [...this.active.values()]
      .map((e) => {
        if (!e.manifest.prompt) return '';
        return `## Skill: ${e.manifest.name}\n${e.manifest.prompt}`;
      })
      .filter(Boolean)
      .join('\n\n');
  }
}

/** 内置 skill 工具的工厂：让 agent 自己发现/激活 skill。 */
export function createSkillTools({ skillsRoot, manager }) {
  const mgr = manager || new SkillManager({ registry: null, skillsRoot });

  return [
    {
      name: 'list_skills',
      description:
        'List available skill packages (id, description, tools). Skills are inactive until activated — activate only what the task needs.',
      risk: 'read',
      parameters: { type: 'object', properties: {} },
      execute() {
        const list = mgr.list();
        if (!list.length) return '(no skills installed)';
        return list
          .map((s) => `${s.id}${s.active ? ' [active]' : ''} — ${s.name}\n    ${s.description}\n    tools: ${s.tools.join(', ') || '(none)'}`)
          .join('\n');
      },
    },
    {
      name: 'activate_skill',
      description:
        'Activate a skill package by id, registering its tools as skill_<id>_<tool>. Read list_skills first.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: { skill_id: { type: 'string', description: 'Skill id from list_skills' } },
        required: ['skill_id'],
      },
      async execute({ skill_id }) {
        const entry = await mgr.activate(skill_id);
        const lines = [`activated skill "${skill_id}" (${entry.tools.length} tools)`];
        for (const f of entry.failures) lines.push(`  failed: ${f.file} — ${f.error}`);
        return lines.join('\n');
      },
    },
    {
      name: 'deactivate_skill',
      description: 'Deactivate a skill and unregister its tools. Use when a skill is no longer needed in this session.',
      risk: 'write',
      parameters: {
        type: 'object',
        properties: { skill_id: { type: 'string' } },
        required: ['skill_id'],
      },
      execute({ skill_id }) {
        return JSON.stringify(mgr.deactivate(skill_id));
      },
    },
  ];
}
