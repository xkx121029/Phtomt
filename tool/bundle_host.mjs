#!/usr/bin/env node
/**
 * Copies the happy_ACA agent host (zero-dep ESM sources) into this project's
 * Android assets folder, so the APK can extract and run it on-device with the
 * Node runtime shipped in the separate :runtime APK.
 *
 *   node tool/bundle_host.mjs
 *   HACA_ROOT=D:\path\to\happy_ACA node tool/bundle_host.mjs
 *
 * Source of truth is the happy_ACA repo (default: ../happy_ACA relative to this
 * project root). Sync is strictly one-way: never hand-edit the output.
 *
 * Output: app/src/main/assets/agent-host/  (host-stdio.js is the stdio entry)
 * Also wired as a preBuild dependency in app/build.gradle.kts (skipped when the
 * HACA checkout is absent).
 */

import fs from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const hacaRoot = process.env.HACA_ROOT || path.resolve(root, '..', 'happy_ACA');
const srcDir = path.join(hacaRoot, 'packages', 'agent-core', 'src');
const outDir = path.join(root, 'app', 'src', 'main', 'assets', 'agent-host');

if (!fs.existsSync(path.join(srcDir, 'index.js'))) {
  console.error(`[bundle-host] happy_ACA agent-core not found at ${srcDir}`);
  console.error('[bundle-host] set HACA_ROOT or clone happy_ACA next to this project; skipping sync.');
  process.exit(1);
}

fs.rmSync(outDir, { recursive: true, force: true });

const copy = (from, to) => {
  fs.mkdirSync(to, { recursive: true });
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const s = path.join(from, entry.name);
    const d = path.join(to, entry.name);
    if (entry.isDirectory()) copy(s, d);
    else fs.copyFileSync(s, d);
  }
};

copy(srcDir, outDir);
// The stdio entry stays host-stdio.js (do NOT overwrite index.js: index.js is the
// library barrel that host-stdio.js imports from — overwriting it would make the
// entry import itself and lose every re-export). RuntimeBootstrap runs host-stdio.js.

// Offline demo scenarios (real tool loops, no API key needed).
const scenariosSrc = path.join(hacaRoot, 'packages', 'agent-core', 'scenarios');
const scenariosOut = path.join(outDir, 'scenarios');
if (fs.existsSync(scenariosSrc)) {
  fs.mkdirSync(scenariosOut, { recursive: true });
  for (const name of fs.readdirSync(scenariosSrc)) {
    if (name.endsWith('.json')) fs.copyFileSync(path.join(scenariosSrc, name), path.join(scenariosOut, name));
  }
}

const count = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).reduce((n, e) => n + (e.isDirectory() ? count(path.join(dir, e.name)) : 1), 0);

// 内容指纹写进 assets。RuntimeBootstrap 拿它和已解压的那份比：
// 一致就复用，不一致才重新解压。以前靠手工 bump HOST_VERSION 常量，
// 忘了 bump 的后果是「装了新 APK，设备还在跑旧 host」——静默、且极难查。
const digest = createHash('sha256');
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p);
    else {
      digest.update(path.relative(outDir, p).replaceAll('\\', '/'));
      digest.update(fs.readFileSync(p));
    }
  }
};
walk(outDir);
const fingerprint = digest.digest('hex').slice(0, 16);
fs.writeFileSync(path.join(outDir, 'VERSION'), fingerprint + '\n', 'utf8');

console.log(`[bundle-host] ${count(outDir)} files -> ${path.relative(root, outDir)}  (fingerprint ${fingerprint})`);
