#!/usr/bin/env python3
"""把 CI 产出的 APK 补挂到 Gitee 发行版（局域网侧执行）。

为什么不在 CI 里传：GitHub runner 出口到 Gitee 实测只有 15.7KB/s，71MB 需要约 76 分钟，
40 分钟上限永远传不完；同一条链路从局域网侧实测 95KB/s，约 13 分钟，快 6 倍。
CI 只负责建发行版并在 body 里写 GitHub 直链，附件由本脚本补传。

本脚本由 Windows 计划任务每 30 分钟调用一次（见 docs/release-pipeline.md），
因此必须满足：幂等、单实例、失败可定位。

用法：
    setx GITEE_TOKEN "…"            # 只需设置一次（用户级），然后重开终端
    python tool/gitee_attach.py                 # 自动找最新的带 APK 的发行版
    python tool/gitee_attach.py --tag v0.2.9112-dev
    python tool/gitee_attach.py --apk .\\HappyAgent-0.2.9112-dev.apk --tag v0.2.9112-dev

令牌只从环境变量 GITEE_TOKEN 读，不落盘、不回显、没有配置文件兜底。

退出码（计划任务与排查看这个）：
    0  成功（含「已挂过、字节数一致，跳过」）
    2  未设置 GITEE_TOKEN，本次跳过
    3  上一轮还在传（单实例锁未拿到），本次跳过
    4  GitHub / Gitee 接口调用失败
    5  服务端字节数校验不通过
"""
import os
import sys
import time


def _boot(msg):
    """启动面包屑：只用内建模块直写日志，绝不经过 logging。

    计划任务里出现过 Python 卡在 import 阶段、连一行日志都没有的情况，
    没有这行就分不清「任务没跑」和「跑了但卡在启动」。所以它必须排在
    所有重依赖（logging / subprocess / urllib）之前，且本身不能失败退出。
    """
    try:
        state = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "hpa")
        os.makedirs(state, exist_ok=True)
        with open(os.path.join(state, "gitee-attach.log"), "a", encoding="utf-8") as f:
            f.write("%s BOOT %s pid=%d argv=%s\n" % (
                time.strftime("%Y-%m-%d %H:%M:%S"), msg, os.getpid(), sys.argv[1:]))
    except Exception:
        pass


_boot("开始")

import argparse
import json
import logging
import logging.handlers
import re
import subprocess
import urllib.error
import urllib.request

_boot("依赖加载完成")

GH_REPO = "xkx121029/Phtomt"
GITEE_API = "https://gitee.com/api/v5/repos/xkx1029/Phtomt"
# CI 产出的标签形态：v0.2.9119-beta / v0.2.9119-dev
CHANNEL_TAG = re.compile(r"^v\d+\.\d+\.\d+-(dev|beta)$")
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".gitee-cache")

# 状态目录：日志与单实例锁都放这里（不落在仓库里，避免被提交）
STATE_DIR = os.path.join(
    os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "hpa")
LOG_PATH = os.path.join(STATE_DIR, "gitee-attach.log")
LOCK_PATH = os.path.join(STATE_DIR, "gitee-attach.lock")

EXIT_OK = 0
EXIT_TOKEN = 2
EXIT_BUSY = 3
EXIT_API = 4
EXIT_VERIFY = 5

log = logging.getLogger("gitee-attach")


class Fail(Exception):
    """带退出码的失败：由 main() 统一记录并返回对应码。"""

    def __init__(self, message, code):
        super().__init__(message)
        self.code = code


def setup_logging():
    os.makedirs(STATE_DIR, exist_ok=True)
    log.setLevel(logging.INFO)
    # 按天滚动，保留 14 天；编码固定 utf-8，避免中文在日志里变问号
    fh = logging.handlers.TimedRotatingFileHandler(
        LOG_PATH, when="midnight", backupCount=14, encoding="utf-8")
    fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(fh)
    # pythonw 下 sys.stdout 为 None，不能无条件加控制台输出
    if sys.stdout is not None:
        sh = logging.StreamHandler(sys.stdout)
        sh.setFormatter(logging.Formatter("%(message)s"))
        log.addHandler(sh)


def acquire_lock():
    """单实例锁：拿到返回文件句柄（进程退出自动释放），拿不到返回 None。

    用 msvcrt 建议锁而不是「锁文件是否存在」：进程被强杀时内核会释放锁，
    不会留下需要人工清理的僵尸锁。
    """
    os.makedirs(STATE_DIR, exist_ok=True)
    f = open(LOCK_PATH, "a+")
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        f.close()
        return None
    return f


def gh_json(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "hpa-gitee-attach",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode())
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        raise Fail("读取 GitHub 发行版失败：%s" % e, EXIT_API)


def gitee(method, path, token, data=None, attempts=3):
    """调 Gitee OpenAPI。跨洋链路会偶发 curl 56/reset，无人值守跑必须自带重试。

    注意：失败时 curl 有时返回空 stderr（rc 非 0 但没有错误文本），
    所以日志里要把 rc 一起打出来，否则只能看到「调用失败：」这种空消息。
    """
    url = GITEE_API + path
    cmd = ["curl.exe" if os.name == "nt" else "curl", "-sS", "--max-time", "120", "-X", method, url]
    if data is not None:
        cmd += ["--data-urlencode", "access_token=" + token]
        for k, v in data.items():
            cmd += ["--data-urlencode", "%s=%s" % (k, v)]
    last = ""
    for attempt in range(1, attempts + 1):
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if out.returncode == 0:
            try:
                return json.loads(out.stdout)
            except json.JSONDecodeError:
                last = "返回非 JSON：%s" % out.stdout[:200]
        else:
            last = "rc=%d %s" % (out.returncode, (out.stderr or "").strip()[:200])
        log.warning("Gitee %s %s 第 %d 次失败：%s", method, path.split("?")[0], attempt, last)
        if attempt < attempts:
            time.sleep(5 * attempt)
    raise Fail("Gitee API 调用失败（已重试 %d 次）：%s" % (attempts, last), EXIT_API)


def pick_release(tag):
    # per_page 要给够：GitHub 这个接口实测是「按 created_at 升序」返回的（老的在前面），
    # 直接取 rels[0] 会挑到几个月前的旧版本——第一版计划任务就跑出过这个事故。
    rels = gh_json("https://api.github.com/repos/%s/releases?per_page=50" % GH_REPO)
    if tag:
        for r in rels:
            if r["tag_name"] == tag:
                return r
        raise Fail("GitHub 上找不到 tag=%s" % tag, EXIT_API)
    # 自己按创建时间倒序排一遍，不依赖接口返回顺序
    rels.sort(key=lambda r: r.get("created_at") or "", reverse=True)
    # 只认 CI 出的 vX.Y.Z-dev/beta 标签：官网同步那几个 v0.2.200009x 的旧包也是「带 APK 的最新发行版」，
    # 不加这一层过滤会把旧包挂到新发行版上，然后一直认为「已经处理完了」。
    for r in rels:
        if CHANNEL_TAG.match(r["tag_name"]) and any(a["name"].endswith(".apk") for a in r.get("assets") or []):
            return r
    # 标签命名变了也不至于停止工作：退回「最新的带 APK 发行版」
    for r in rels:
        if any(a["name"].endswith(".apk") for a in r.get("assets") or []):
            return r
    raise Fail("GitHub 上没有任何带 APK 的发行版", EXIT_API)


def download(rel, asset):
    os.makedirs(CACHE_DIR, exist_ok=True)
    path = os.path.join(CACHE_DIR, asset["name"])
    if os.path.exists(path) and os.path.getsize(path) == asset["size"]:
        log.info("复用已下载的 APK：%s（%d B）", path, asset["size"])
        return path
    log.info("下载 %s（%d B）...", asset["name"], asset["size"])
    curl = "curl.exe" if os.name == "nt" else "curl"
    # 不能走 github.com/releases/download/<tag>/<file>：本机实测该直链 (56) Connection was reset，
    # 一会儿通一会儿断，不能托付给无人值守的计划任务。改走 api.github.com 的资产端点
    # （会 302 到对象存储），实测 206 分片可用。
    url = "https://api.github.com/repos/%s/releases/assets/%d" % (GH_REPO, asset["id"])
    # GitHub 侧 71MB 跨洋下载会偶发 reset：断点续传 + 重试，避免重下整包
    p = subprocess.run([
        curl, "-sS", "-L", "-C", "-", "--retry", "6", "--retry-delay", "3",
        "--retry-all-errors", "--connect-timeout", "30", "--max-time", "1800",
        "-H", "Accept: application/octet-stream", "-H", "User-Agent: hpa-gitee-attach",
        "-o", path, url,
    ], capture_output=True, text=True)
    if p.returncode != 0:
        raise Fail("下载失败（curl %d）：%s" % (p.returncode, (p.stderr or "").strip()[:200]), EXIT_API)
    actual = os.path.getsize(path)
    if actual != asset["size"]:
        raise Fail("下载不完整：%d / %d B" % (actual, asset["size"]), EXIT_VERIFY)
    return path


def run(args, token):
    rel = pick_release(args.tag)
    tag = rel["tag_name"]
    asset = next(a for a in rel["assets"] if a["name"].endswith(".apk"))

    apk = os.path.abspath(args.apk) if args.apk else download(rel, asset)
    size = os.path.getsize(apk)
    name = asset["name"]

    body = "**版本**：%s\n**APK**：https://github.com/%s/releases/download/%s/%s\n" % (
        tag, GH_REPO, tag, name)
    existing = gitee("GET", "/releases/tags/%s?access_token=%s" % (tag, token), token)
    if isinstance(existing, dict) and existing.get("id"):
        rid = existing["id"]
    else:
        created = gitee("POST", "/releases", token, {
            "tag_name": tag,
            "name": "Happy Phone Agent %s" % tag,
            "body": body,
            "target_commitish": "nightly",
            "prerelease": "true",
        })
        rid = created.get("id")
        if not rid:
            raise Fail("Gitee 发行版创建失败：%s" % created, EXIT_API)

    files = gitee("GET", "/releases/%s/attach_files?access_token=%s" % (rid, token), token)
    if any(f.get("name") == name and f.get("size") == size for f in files):
        log.info("跳过：%s 已挂载且字节数一致（%d B）", name, size)
        return

    log.info("上传：Gitee 发行版 %s ← %s（%d B）", tag, name, size)
    started = time.time()
    curl = "curl.exe" if os.name == "nt" else "curl"
    # 上传本身也要重试一次：实测出现过 curl 正常退出但没落盘的情况（响应体为空），
    # 无人值守时不能指望下一轮 30 分钟后才发现。
    for attempt in (1, 2):
        out = subprocess.run([
            curl, "-sS", "--http1.1", "--connect-timeout", "30", "--max-time", "3600",
            "-X", "POST", "%s/releases/%s/attach_files" % (GITEE_API, rid),
            "-F", "access_token=" + token, "-F", "file=@" + apk,
            "-w", "\n_HTTP=%{http_code} _TIME=%{time_total}s _SIZE=%{size_upload}B _SPEED=%{speed_upload}B/s\n",
        ], capture_output=True, text=True, encoding="utf-8", errors="replace")
        tail = (out.stdout or "")[-260:].strip()
        log.info("curl 返回（第 %d 次，rc=%d）：%s", attempt, out.returncode, tail)
        if out.returncode == 0 and ("_HTTP=201" in tail or "_HTTP=200" in tail):
            break
        log.warning("上传未成功：%s", (out.stderr or "").strip()[:200])
        time.sleep(10)

    files = gitee("GET", "/releases/%s/attach_files?access_token=%s" % (rid, token), token)
    got = next((f.get("size") for f in files if f.get("name") == name), None)
    if got != size:
        raise Fail("Gitee 附件校验失败：服务端 %s B，期望 %d B（GitHub Release 不受影响）"
                   % (got, size), EXIT_VERIFY)
    log.info("完成：%s = %d B，耗时 %.0fs", name, got, time.time() - started)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag")
    ap.add_argument("--apk")
    args = ap.parse_args()

    setup_logging()
    # 计划任务每 30 分钟一轮，靠日志里这条头把「某轮为什么没动作」看明白
    log.info("---- 开始：pid=%d tag=%s apk=%s ----", os.getpid(), args.tag or "(自动)", args.apk or "(下载)")
    _boot("日志系统就绪")

    token = os.environ.get("GITEE_TOKEN")
    if not token:
        log.error("未设置环境变量 GITEE_TOKEN，本次跳过。"
                  "设置方式：setx GITEE_TOKEN \"…\"（用户级），然后重开终端。")
        return EXIT_TOKEN
    _boot("令牌已就绪")

    lock = acquire_lock()
    if lock is None:
        log.warning("上一轮仍在传输（锁被占用），本次跳过")
        return EXIT_BUSY
    _boot("单实例锁已获取")

    try:
        run(args, token)
        return EXIT_OK
    except Fail as e:
        log.error("%s（退出码 %d）", e, e.code)
        return e.code
    except Exception as e:  # 兜底：任何未预期异常都要进日志，否则计划任务里看不到原因
        log.exception("未预期异常：%s", e)
        return EXIT_API


if __name__ == "__main__":
    sys.exit(main())
