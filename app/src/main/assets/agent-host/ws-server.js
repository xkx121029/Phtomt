// Minimal RFC 6455 WebSocket server (text frames only) — enough to bridge the
// Android app to the agent router with zero dependencies.

import http from 'node:http';
import crypto from 'node:crypto';

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

export function acceptKey(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

export function encodeFrame(payload, opcode = 1) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8');
  const len = data.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  header[0] = 0x80 | opcode;
  return Buffer.concat([header, data]);
}

export function decodeFrames(buf) {
  const frames = [];
  let off = 0;
  while (true) {
    if (off + 2 > buf.length) break;
    const opcode = buf[off] & 0x0f;
    const masked = (buf[off + 1] & 0x80) !== 0;
    let len = buf[off + 1] & 0x7f;
    let p = off + 2;
    if (len === 126) {
      if (p + 2 > buf.length) break;
      len = buf.readUInt16BE(p);
      p += 2;
    } else if (len === 127) {
      if (p + 8 > buf.length) break;
      len = Number(buf.readBigUInt64BE(p));
      p += 8;
    }
    let mask = null;
    if (masked) {
      if (p + 4 > buf.length) break;
      mask = buf.subarray(p, p + 4);
      p += 4;
    }
    if (p + len > buf.length) break;
    let payload = buf.subarray(p, p + len);
    if (mask) {
      const out = Buffer.allocUnsafe(len);
      for (let i = 0; i < len; i++) out[i] = payload[i] ^ mask[i % 4];
      payload = out;
    }
    frames.push({ opcode, payload });
    off = p + len;
  }
  return { frames, rest: buf.subarray(off) };
}

export class WsConnection {
  constructor(socket) {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.onMessage = null;
    this.onClose = null;
    socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      const { frames, rest } = decodeFrames(this.buffer);
      this.buffer = rest;
      for (const f of frames) {
        if (f.opcode === 8) return this.close();
        if (f.opcode === 9) {
          this.socket.write(encodeFrame(f.payload, 10)); // pong
          continue;
        }
        if (f.opcode === 1 || f.opcode === 2) this.onMessage?.(f.payload.toString('utf8'));
      }
    });
    const bye = () => this.onClose?.();
    socket.on('close', bye);
    socket.on('error', bye);
  }

  send(obj) {
    if (this.socket.destroyed) return;
    this.socket.write(encodeFrame(typeof obj === 'string' ? obj : JSON.stringify(obj)));
  }

  close() {
    try {
      this.socket.write(encodeFrame(Buffer.alloc(0), 8));
    } catch {
      /* already gone */
    }
    this.socket.end();
    this.onClose?.();
  }
}

export class WsJsonServer {
  constructor({ port, host = '0.0.0.0', path = '/', onConnection }) {
    this.port = port;
    this.host = host;
    this.path = path;
    this.server = http.createServer((req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('happy_ACA agent endpoint (WebSocket, one JSON object per message)\n');
    });
    this.server.on('upgrade', (req, socket) => {
      const urlPath = (req.url || '/').split('?')[0];
      const key = req.headers['sec-websocket-key'];
      if (urlPath !== this.path || !key) {
        socket.destroy();
        return;
      }
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`,
      );
      onConnection?.(new WsConnection(socket));
    });
  }

  listen() {
    return new Promise((resolve) => this.server.listen(this.port, this.host, resolve));
  }

  close() {
    this.server.close();
  }
}
