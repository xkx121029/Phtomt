// Scripted provider for tests and offline demos. No network, no keys.
// Each turn: { events?: [...], text?: string, finishReason?: string }

export class MockProvider {
  constructor(turns = [], { model = 'mock' } = {}) {
    this.turns = [...turns];
    this.calls = [];
    // 账本按 provider.model 过账；没有这个字段用量报表会全是 "unknown"
    this.model = model;
  }

  async *stream({ messages }) {
    this.calls.push(JSON.parse(JSON.stringify(messages)));
    const turn = this.turns.shift() ?? { text: '(mock: no scripted turn left)' };
    const events = turn.events ?? [{ type: 'text', delta: turn.text ?? '' }];
    for (const ev of events) {
      yield ev;
      if (turn.delay) await new Promise((r) => setTimeout(r, turn.delay));
    }
    yield { type: 'finish', reason: turn.finishReason ?? 'stop', usage: { input_tokens: 100, output_tokens: 50 } };
  }
}

// Convenience builders for scripted turns.

export function mockText(text) {
  return { events: [{ type: 'text', delta: text }] };
}

export function mockToolCall(name, args) {
  return {
    events: [{ type: 'tool_call', index: 0, id: `call_${name}`, name, argsDelta: JSON.stringify(args) }],
    finishReason: 'tool_calls',
  };
}

/**
 * Load a scripted scenario from a JSON file: an array of turns in the same
 * shape mockText/mockToolCall produce, but as plain JSON so an on-device host
 * can replay a real tool-calling session without any API key.
 *
 *   [{"name":"read_file","args":{"path":"a.txt"}}, {"text":"done"}]
 * or the verbose form:  [{"events":[...], "finishReason":"..."}]
 */
export function mockScriptFromJSON(json) {
  // 手工编辑的 JSON 在 Windows 上常被写成带 BOM 的 UTF-8；
  // JSON.parse 会直接抛 "Unexpected token"，这里先剥掉 BOM。
  const data = typeof json === 'string' ? JSON.parse(json.replace(/^\uFEFF/, '')) : json;
  if (!Array.isArray(data)) throw new Error('mock script must be a JSON array of turns');
  return data.map((turn, i) => {
    if (turn.text !== undefined || turn.events !== undefined) return turn;
    if (turn.name) {
      return {
        events: [
          {
            type: 'tool_call',
            index: 0,
            id: `call_${i}_${turn.name}`,
            name: turn.name,
            argsDelta: JSON.stringify(turn.args ?? {}),
          },
        ],
        finishReason: 'tool_calls',
      };
    }
    throw new Error(`mock script turn ${i} needs {text}, {events} or {name, args}`);
  });
}
