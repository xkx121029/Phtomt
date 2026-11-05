// 原子文件写：先写同目录临时文件，再 rename 覆盖目标。
//
// 为什么需要：各个持久化点（会话 JSONL、用量账本、模型档位、记忆图谱、
// 工作流定义、MCP 配置）以前都是直接 fs.writeFileSync。进程在写一半被
// 杀掉（OOM、掉电、系统回收）会留下半截 JSON——下次 #load 解析失败，
// catch 分支「fresh start」会把**全部**历史静默清空。rename 在同一文件
// 系统上是原子的，要么旧文件要么新文件，不会出现中间态。

import fs from 'node:fs';
import path from 'node:path';

export function atomicWriteFileSync(file, data) {
  const abs = path.resolve(file);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  const tmp = `${abs}.tmp-${process.pid}-${Date.now().toString(36)}`;
  fs.writeFileSync(tmp, data);
  try {
    fs.renameSync(tmp, abs);
  } catch (err) {
    // rename 失败（跨设备/权限）：清掉临时文件，别在磁盘上留垃圾
    try {
      fs.rmSync(tmp, { force: true });
    } catch {
      /* ignore */
    }
    throw err;
  }
}
