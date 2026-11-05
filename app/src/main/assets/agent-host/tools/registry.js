// Tool registry. Tools: { name, description, parameters (JSON Schema), risk: 'read'|'write'|'exec', execute(args, ctx) }

export class ToolRegistry {
  #tools = new Map();

  register(tool) {
    if (!tool?.name || !tool?.description || typeof tool?.execute !== 'function') {
      throw new Error('tool must have name, description and execute()');
    }
    if (!tool.parameters || tool.parameters.type !== 'object') {
      throw new Error(`tool ${tool.name}: parameters must be a JSON Schema of type object`);
    }
    this.#tools.set(tool.name, tool);
    return this;
  }

  get(name) {
    return this.#tools.get(name) ?? null;
  }

  has(name) {
    return this.#tools.has(name);
  }

  /** 移除一个工具（MCP server 断开时用）。返回是否真的移除过。 */
  unregister(name) {
    return this.#tools.delete(name);
  }

  /** 移除某个来源（如 mcp:serverName）注册的全部工具。 */
  unregisterBySource(source) {
    let n = 0;
    for (const [name, tool] of this.#tools) {
      if (tool._source === source) {
        this.#tools.delete(name);
        n += 1;
      }
    }
    return n;
  }

  list() {
    return [...this.#tools.values()];
  }

  schemas() {
    return this.list().map((t) => ({ name: t.name, description: t.description, parameters: t.parameters }));
  }

  async execute(name, args, ctx) {
    const tool = this.#tools.get(name);
    if (!tool) return { ok: false, output: `error: unknown tool "${name}"` };
    try {
      const output = await tool.execute(args ?? {}, ctx);
      return { ok: true, output: output == null ? '(ok)' : String(output) };
    } catch (err) {
      return { ok: false, output: `error: ${err.message}` };
    }
  }
}
