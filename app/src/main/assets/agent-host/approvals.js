// Approval policy: decides whether a tool call may execute.
//   mode 'ask'    -> every call goes through onRequest
//   mode 'risky'  -> read-only tools auto-approved, write/exec go through onRequest
//   mode 'bypass' -> nothing is asked (dangerous; for demos and CI)

export const APPROVAL_MODES = ['ask', 'risky', 'bypass'];

export class ApprovalPolicy {
  constructor({ mode = 'risky', onRequest } = {}) {
    if (!APPROVAL_MODES.includes(mode)) throw new Error(`unknown approval mode: ${mode}`);
    this.mode = mode;
    this.onRequest = onRequest;
  }

  async check(tool, args, ctx) {
    if (this.mode === 'bypass') return true;
    if (this.mode === 'risky' && tool.risk === 'read') return true;
    if (typeof this.onRequest !== 'function') {
      throw new Error(`approval required for ${tool.name} but no onRequest handler is configured (mode=${this.mode})`);
    }
    return Boolean(await this.onRequest({ tool: tool.name, risk: tool.risk, args }, ctx));
  }
}
