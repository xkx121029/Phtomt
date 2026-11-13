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
 *
 * 并发/失败安全：以前的实现是「先 rm -rf 目标目录，再逐个文件拷进去」。
 * 同一工作区并行跑两个构建（本项目经常多线同时构建）时，一个构建会把另一个
 * 正在拷贝的目录删掉，最终 assets 里可能是半个 host；拷贝中途失败同理。
 * 现在改成：拷到 app/build 下的 staging 目录 → 内容指纹 → 加锁原子替换 → 清理备份。
 */

import fs from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const hacaRoot = process.env.HACA_ROOT || path.resolve(root, '..', 'happy_ACA');
const srcDir = path.join(hacaRoot, 'packages', 'agent-core', 'src');
const outDir = path.join(root, 'app', 'src', 'main', 'assets', 'agent-host');

// 暂存/备份/锁都放在 build 目录：不会被 mergeAssets 当成资源打包，且与 assets 同盘，
// 重命名是原子的（换到系统临时目录可能跨盘，rename 会直接失败）。
const workDir = path.join(root, 'app', 'build', 'tmp', 'bundle-host');
const staging = path.join(workDir, `staging-${process.pid}`);
const backup = path.join(workDir, `backup-${process.pid}`);
const lockDir = path.join(workDir, 'lock');

if (!fs.existsSync(path.join(srcDir, 'index.js'))) {
  console.error(`[bundle-host] happy_ACA agent-core not found at ${srcDir}`);
  console.error('[bundle-host] set HACA_ROOT or clone happy_ACA next to this project; skipping sync.');
  process.exit(1);
}

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

function acquireLock() {
  for (let i = 0; i < 300; i++) {
    try {
      fs.mkdirSync(lockDir);
      return true;
    } catch {
      // 上一次构建被强杀会留下陈旧锁：超过 2 分钟视为无效
      try {
        if (Date.now() - fs.statSync(lockDir).mtimeMs > 120_000) {
          fs.rmdirSync(lockDir);
          continue;
        }
      } catch {
        /* 锁刚被释放，下一轮重试即可 */
      }
      sleep(100);
    }
  }
  return false;
}

function releaseLock() {
  try {
    fs.rmdirSync(lockDir);
  } catch {
    /* 已被清理 */
  }
}

function copy(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const s = path.join(from, entry.name);
    const d = path.join(to, entry.name);
    if (entry.isDirectory()) copy(s, d);
    else fs.copyFileSync(s, d);
  }
}

function collectFiles(dir, base = dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) collectFiles(p, base, acc);
    else acc.push(path.relative(base, p));
  }
  return acc;
}

/**
 * 目录内容的指纹（排序遍历，结果与文件系统返回顺序无关）。
 * 写入前后各算一次并比对：能抓住「其它进程同时在改 assets/agent-host」这类
 * 只在并发下出现、平时完全看不见的半成品。
 */
function fingerprintOf(dir, exclude = new Set()) {
  const digest = createHash('sha256');
  // 必须与历史算法完全一致（每个目录内按名字排序、深度优先），否则指纹会无故变化，
  // 已装机的设备会被判定为「host 有更新」而重新解压一次。
  //
  // 排序用 JS 默认的码元比较（与构建期 Java 的 String 排序一致），不能用
  // localeCompare：后者受运行环境影响，同样的目录可能排出不同顺序。
  // 逐字节哈希前剔除 CR：Windows 工作区是 CRLF、CI 是 LF，不去掉的话
  // 同一份提交会在两边算出不同指纹（mergeDebugAssets 的闸门正因此失败过）。
  const walk = (d) => {
    const entries = fs.readdirSync(d, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      if (exclude.has(entry.name)) continue;
      const p = path.join(d, entry.name);
      if (entry.isDirectory()) walk(p);
      else {
        digest.update(path.relative(dir, p).replaceAll('\\', '/'));
        const bytes = fs.readFileSync(p);
        digest.update(bytes.filter((b) => b !== 0x0d));
      }
    }
  };
  walk(dir);
  return digest.digest('hex').slice(0, 16);
}

function runCatchingRead(file) {
  try {
    return fs.readFileSync(file, 'utf8').trim();
  } catch {
    return '';
  }
}

fs.mkdirSync(workDir, { recursive: true });
fs.rmSync(staging, { recursive: true, force: true });
fs.rmSync(backup, { recursive: true, force: true });

try {
  copy(srcDir, staging);
  // The stdio entry stays host-stdio.js (do NOT overwrite index.js: index.js is the
  // library barrel that host-stdio.js imports from — overwriting it would make the
  // entry import itself and lose every re-export). RuntimeBootstrap runs host-stdio.js.

  // Offline demo scenarios (real tool loops, no API key needed).
  const scenariosSrc = path.join(hacaRoot, 'packages', 'agent-core', 'scenarios');
  const scenariosOut = path.join(staging, 'scenarios');
  if (fs.existsSync(scenariosSrc)) {
    fs.mkdirSync(scenariosOut, { recursive: true });
    for (const name of fs.readdirSync(scenariosSrc)) {
      if (name.endsWith('.json')) fs.copyFileSync(path.join(scenariosSrc, name), path.join(scenariosOut, name));
    }
  }

  // 内容指纹写进 assets。RuntimeBootstrap 拿它和已解压的那份比：
  // 一致就复用，不一致才重新解压。以前靠手工 bump HOST_VERSION 常量，
  // 忘了 bump 的后果是「装了新 APK，设备还在跑旧 host」——静默、且极难查。
  const fingerprint = fingerprintOf(staging, new Set(['VERSION']));
  fs.writeFileSync(path.join(staging, 'VERSION'), fingerprint + '\n', 'utf8');

  if (!acquireLock()) {
    console.error(
      `[bundle-host] 等待同步锁超时（30s）：另一个构建正在同步 ${path.relative(root, outDir)}。\n` +
        `若确认没有其它构建在跑，可删除 ${path.relative(root, lockDir)} 后重试。`,
    );
    fs.rmSync(staging, { recursive: true, force: true });
    process.exit(1);
  }
  try {
    // 原子替换：旧目录先挪走，新目录顶上；失败则把旧目录还原，
    // 保证任何时刻 assets/agent-host 要么是完整的旧版本、要么是完整的新版本。
    //
    // Windows 例外：目录里任何一个文件被别的进程打开（并行的 mergeAssets 正在读、
    // 杀软/索引器在扫），rename 会以 EPERM/EBUSY 失败。此时不能直接让构建失败——
    // 退回「就地覆盖」：先覆盖写新文件，再删掉新版本里不存在的旧文件。
    // 覆盖期间目录仍然是"旧版本 + 部分新文件"，但每个文件都是完整可读的，
    // 且最终内容收敛到新版本；比整次构建失败更可接受。
    const hadOld = fs.existsSync(outDir);
    let swapped = false;
    let lastErr = null;
    // 目标目录被并行构建短暂占用是常态（mergeAssets 正在读），重试往往第二、三次就成功
    for (let i = 0; i < 10 && !swapped; i++) {
      try {
        if (hadOld && fs.existsSync(outDir) && !fs.existsSync(backup)) fs.renameSync(outDir, backup);
        fs.renameSync(staging, outDir);
        swapped = true;
      } catch (e) {
        lastErr = e;
        if (hadOld && fs.existsSync(backup) && !fs.existsSync(outDir)) {
          try {
            fs.renameSync(backup, outDir);
          } catch {
            /* 还原失败时保留 backup 目录，至少没有丢文件 */
          }
        }
        if (i < 9) sleep(200);
      }
    }
    if (!swapped) {
      console.warn(`[bundle-host] 原子替换失败(${lastErr?.code})，改为就地覆盖`);
      copy(staging, outDir);
      const keep = new Set(collectFiles(staging));
      for (const rel of collectFiles(outDir)) {
        if (!keep.has(rel)) fs.rmSync(path.join(outDir, rel), { force: true });
      }
    }
  } finally {
    releaseLock();
  }
  fs.rmSync(staging, { recursive: true, force: true });
  fs.rmSync(backup, { recursive: true, force: true });

  // 写完自检：目标目录的内容指纹必须与 staging 一致，VERSION 文件也必须指向它。
  // 覆盖路径（rename 被占用时）存在"旧文件 + 新文件"并存的短暂窗口，如果那一刻
  // 有别的进程把目录写坏，这一步会立刻暴露，而不是把一个半新半旧的 host 打进 APK。
  const written = fingerprintOf(outDir, new Set(['VERSION']));
  const versionOnDisk = runCatchingRead(path.join(outDir, 'VERSION'));
  if (written !== fingerprint || versionOnDisk !== fingerprint) {
    throw new Error(
      `[bundle-host] 写完校验失败：期望指纹 ${fingerprint}，实际 ${written}，VERSION=${versionOnDisk || '(缺失)'}；` +
        '可能有其它进程同时在改 assets/agent-host',
    );
  }

  const count = (dir) =>
    fs.readdirSync(dir, { withFileTypes: true }).reduce((n, e) => n + (e.isDirectory() ? count(path.join(dir, e.name)) : 1), 0);
  console.log(`[bundle-host] ${count(outDir)} files -> ${path.relative(root, outDir)}  (fingerprint ${fingerprint})`);
} catch (e) {
  fs.rmSync(staging, { recursive: true, force: true });
  throw e;
}
