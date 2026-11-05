#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Happy Phone Agent debug 版日志接收端（电脑侧）。

设备侧（debug 包）在回环地址 127.0.0.1:19090 上等待连接，通过 ADB 端口转发暴露给电脑：

    adb forward tcp:19090 tcp:19090
    python tool/hpa_log_receiver.py

USB 与无线调试都用上面同一条命令（无线调试只是把 adb 换成 `adb -s <ip:port>`）。

脚本做的事：
- 解析设备推来的行协议（hello / log / hist_end / bye），按序号校验连续性；
- 发现缺口立即发 `nak <expect>` 要求从缺口重传，每条按序到达都回 `ack`（累计确认）；
- 历史日志写入 `<out>/hpa_logs_<时间>.jsonl`（按 seq 顺序，即时间戳正序）；
- 实时日志额外统计端到端延迟（用 hello 里的设备时刻校准两端时钟差）；
- 断线自动重连（设备端会从 seq=1 重发完整历史），直到 Ctrl+C、设备 `bye` 或
  `--exit-after-history` 满足。

退出码：0 = 历史完整且无缺口；2 = 历史不完整或存在未补齐的缺口；1 = 连接失败。
"""

import argparse
import json
import os
import socket
import sys
import time

PORT_DEFAULT = 19090
READ_TIMEOUT = 1.0


def now_ms():
    return int(time.time() * 1000)


def send_line(sock, obj):
    sock.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))


def file_stamp():
    return time.strftime("%Y%m%d_%H%M%S")


class Stats:
    """一次连接的统计（重连时新建）。"""

    def __init__(self, hello, offset_ms):
        self.hello = hello
        self.offset_ms = offset_ms
        self.expected = 1
        self.history_count = 0
        self.live_count = 0
        self.duplicates = 0
        self.gaps = 0
        # 序号超前到达的记录先缓存，等缺口补齐后按 seq 顺序写出，避免丢数据
        self.pending = {}
        self.history_end_seen = False
        self.history_contiguous = False
        self.history_total = int(hello.get("total", 0))
        self.started_at = now_ms()
        self.history_done_at = None
        self.live_latencies = []
        self.live_late = 0


def write_record(handle, obj, stats, recv_ms, verbose):
    record = {
        "seq": obj.get("seq"),
        "phase": obj.get("phase"),
        "ts": obj.get("ts"),
        "lvl": obj.get("lvl"),
        "msg": obj.get("msg"),
        "detail": obj.get("detail"),
        "taskId": obj.get("taskId"),
        "taskName": obj.get("taskName"),
        "recv_ms": recv_ms,
    }
    if obj.get("phase") == "live":
        latency = recv_ms - stats.offset_ms - int(obj.get("ts", recv_ms))
        record["latency_ms"] = latency
        stats.live_latencies.append(latency)
        stats.live_count += 1
        if latency > 100:
            stats.live_late += 1
        if verbose:
            print(f"[实时 +{latency}ms] {obj.get('lvl')}: {obj.get('msg')}", flush=True)
    else:
        stats.history_count += 1
    handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def handle_line(line, sock, handle, stats, verbose):
    """处理一行；返回 False 表示会话结束（收到 bye）。"""
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        print(f"[warn] 无法解析的行：{line[:200]}", flush=True)
        return True

    kind = obj.get("t")
    if kind == "bye":
        print(
            f"[设备] 主动结束：已发送 {obj.get('sent')} 条，已确认 {obj.get('acked')} 条",
            flush=True,
        )
        return False
    if kind == "hist_end":
        stats.history_end_seen = True
        stats.history_done_at = now_ms()
        total = int(obj.get("total", 0))
        # seq 连续 + 条数吻合才算完整；hist_end 一定先于实时日志，此刻 expected 应为 total+1
        stats.history_contiguous = (
            stats.history_count == total and stats.expected == total + 1
        )
        used = (stats.history_done_at - stats.started_at) / 1000.0
        mark = "完成" if stats.history_contiguous else "异常"
        print(
            f"=== 历史日志传输{mark}：{stats.history_count}/{stats.history_total} 条，"
            f"用时 {used:.2f}s，缺口 {stats.gaps} 次，重复 {stats.duplicates} 条 ===",
            flush=True,
        )
        if not stats.history_contiguous:
            print("[warn] 历史条数或序号校验不一致，见下方汇总", flush=True)
        return True
    if kind != "log":
        return True

    seq = int(obj.get("seq", 0))
    is_live = obj.get("phase") == "live"
    if seq < stats.expected:
        stats.duplicates += 1
        # 已确认过的包又重发了一次：继续确认，让设备侧窗口前移
        send_line(sock, {"t": "ack", "seq": stats.expected - 1})
        return True
    if seq > stats.expected:
        stats.gaps += 1
        stats.pending[seq] = obj
        print(
            f"[缺口] 期望 seq={stats.expected}，实收 seq={seq}（{obj.get('phase')}）→ 请求重传",
            flush=True,
        )
        send_line(sock, {"t": "nak", "expect": stats.expected})
        return True

    write_record(handle, obj, stats, now_ms(), verbose)
    stats.expected = seq + 1
    send_line(sock, {"t": "ack", "seq": seq})
    # 缺口补齐后把之前缓存的后续记录按序补写
    while stats.expected in stats.pending:
        queued = stats.pending.pop(stats.expected)
        write_record(handle, queued, stats, now_ms(), verbose)
        stats.expected += 1
        send_line(sock, {"t": "ack", "seq": stats.expected - 1})
    if not is_live and stats.history_count % 500 == 0:
        print(
            f"[历史] {stats.history_count}/{stats.history_total} 条 "
            f"（seq={seq}，缺口 {stats.gaps}，重复 {stats.duplicates}）",
            flush=True,
        )
    return True


def run_session(host, port, out_dir, verbose, exit_after_history):
    sock = socket.create_connection((host, port), timeout=3)
    sock.settimeout(READ_TIMEOUT)
    sock.sendall(b"")  # 触发出站连接真正建立

    buffered = ""
    hello = None
    while hello is None:
        data = sock.recv(65536)
        if not data:
            raise ConnectionError("连接被设备端关闭")
        buffered += data.decode("utf-8", errors="replace")
        while "\n" in buffered:
            line, buffered = buffered.split("\n", 1)
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if obj.get("t") == "hello":
                hello = obj
                break
            print(f"[设备] 忽略非 hello 首帧：{line[:160]}", flush=True)

    offset_ms = now_ms() - int(hello.get("at", now_ms()))
    print(
        f"[连接] 设备 {hello.get('device')}（sdk {hello.get('sdk')}），"
        f"会话 {hello.get('sid')}，历史 {hello.get('total')} 条，"
        f"时钟偏差约 {offset_ms}ms",
        flush=True,
    )

    stats = Stats(hello, offset_ms)
    path = os.path.join(out_dir, f"hpa_logs_{file_stamp()}.jsonl")
    os.makedirs(out_dir, exist_ok=True)
    print(f"[输出] {path}", flush=True)

    alive = True
    ended_by_bye = False
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        try:
            while alive:
                # 先把 hello 之后可能已到达的行处理完
                while "\n" in buffered:
                    line, buffered = buffered.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    if not handle_line(line, sock, handle, stats, verbose):
                        alive = False
                        ended_by_bye = True
                        break
                if exit_after_history and stats.history_end_seen:
                    break
                if not alive:
                    break
                try:
                    data = sock.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    print("[连接] 设备端关闭连接", flush=True)
                    break
                buffered += data.decode("utf-8", errors="replace")
                # 防止设备异常持续发送导致这里无限堆积：单次最多消化 5000 行
                for _ in range(5000):
                    if "\n" not in buffered:
                        break
                    line, buffered = buffered.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    if not handle_line(line, sock, handle, stats, verbose):
                        alive = False
                        ended_by_bye = True
                        break
        finally:
            try:
                send_line(sock, {"t": "bye"})
            except OSError:
                pass
            sock.close()

    return stats, path, ended_by_bye


def print_summary(stats, path, reconnects):
    total = stats.history_total
    history_ok = stats.history_end_seen and stats.history_contiguous
    lat = stats.live_latencies
    print("", flush=True)
    print("=== 汇总 ===", flush=True)
    print(f"历史：{stats.history_count}/{total} 条，序号连续：{'是' if history_ok else '否'}", flush=True)
    if stats.live_latencies:
        print(
            f"实时：{stats.live_count} 条，平均延迟 {sum(lat) // len(lat)}ms，"
            f"最大 {max(lat)}ms，超 100ms {stats.live_late} 条",
            flush=True,
        )
    else:
        print("实时：0 条（连接期间设备没有新日志）", flush=True)
    print(
        f"缺口 {stats.gaps} 次，重复 {stats.duplicates} 条，重连 {reconnects} 次",
        flush=True,
    )
    print(f"日志文件：{path}", flush=True)
    if history_ok:
        print("=== 传输完成 ===", flush=True)
    else:
        print("=== 传输未完整 ===", flush=True)
    return 0 if history_ok else 2


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(
        description="Happy Phone Agent debug 日志接收端",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="先执行：adb forward tcp:19090 tcp:19090",
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=PORT_DEFAULT)
    parser.add_argument("--out", default="received_logs", help="日志输出目录（默认 received_logs）")
    parser.add_argument("--verbose", action="store_true", help="逐条打印实时日志")
    parser.add_argument("--no-reconnect", action="store_true", help="断线后不重连")
    parser.add_argument(
        "--exit-after-history",
        action="store_true",
        help="历史传完立即退出（自动化验证用，不等实时流）",
    )
    args = parser.parse_args()

    reconnects = 0
    last_stats, last_path = None, ""
    while True:
        try:
            stats, path, ended_by_bye = run_session(
                args.host, args.port, args.out, args.verbose, args.exit_after_history
            )
        except (OSError, ConnectionError) as e:
            print(f"[错误] 连接 {args.host}:{args.port} 失败：{e}", flush=True)
            if last_stats is None:
                return 1
            break
        except KeyboardInterrupt:
            print("\n[中断] 用户结束", flush=True)
            break
        last_stats, last_path = stats, path
        if ended_by_bye or args.exit_after_history:
            break
        if args.no_reconnect:
            break
        reconnects += 1
        print(f"[重连] 第 {reconnects} 次重连，等待 0.5s…", flush=True)
        time.sleep(0.5)

    if last_stats is None:
        return 1
    return print_summary(last_stats, last_path, reconnects)


if __name__ == "__main__":
    sys.exit(main())
