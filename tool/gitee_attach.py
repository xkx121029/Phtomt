#!/usr/bin/env python3
"""把 CI 产出的 APK 补挂到 Gitee 发行版（局域网侧执行）。

为什么不在 CI 里传：GitHub runner 出口到 Gitee 实测只有 15.7KB/s，71MB 需要约 76 分钟，
40 分钟上限永远传不完；同一条链路从局域网侧实测 97KB/s，约 12 分钟，快 6 倍。
CI 只负责建发行版并在 body 里写 GitHub 直链，附件由本脚本补传。

用法：
    GITEE_TOKEN=xxx python tool/gitee_attach.py                # 自动找最新的 dev/beta 发行版
    GITEE_TOKEN=xxx python tool/gitee_attach.py --tag v0.2.9112-dev
    GITEE_TOKEN=xxx python tool/gitee_attach.py --apk .\\HappyAgent-0.2.9112-dev.apk --tag v0.2.9112-dev

令牌只从环境变量读，不写文件、不回显。
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request

GH_REPO = "xkx121029/Phtomt"
GITEE_API = "https://gitee.com/api/v5/repos/xkx1029/Phtomt"
CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".gitee-cache")


def gh_json(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "hpa-gitee-attach",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def gitee(method, path, token, data=None):
    url = GITEE_API + path
    cmd = ["curl.exe" if os.name == "nt" else "curl", "-sS", "--max-time", "120", "-X", method, url]
    if data is not None:
        cmd += ["--data-urlencode", "access_token=" + token]
        for k, v in data.items():
            cmd += ["--data-urlencode", "%s=%s" % (k, v)]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        sys.exit("Gitee API 调用失败：%s" % out.stderr.strip())
    try:
        return json.loads(out.stdout)
    except json.JSONDecodeError:
        sys.exit("Gitee API 返回非 JSON：%s" % out.stdout[:200])


def pick_release(tag):
    rels = gh_json("https://api.github.com/repos/%s/releases?per_page=10" % GH_REPO)
    if tag:
        for r in rels:
            if r["tag_name"] == tag:
                return r
        sys.exit("GitHub 上找不到 tag=%s" % tag)
    return rels[0]


def download(rel, asset):
    os.makedirs(CACHE_DIR, exist_ok=True)
    path = os.path.join(CACHE_DIR, asset["name"])
    if os.path.exists(path) and os.path.getsize(path) == asset["size"]:
        print("复用已下载的 APK：%s（%d B）" % (path, asset["size"]))
        return path
    print("下载 %s（%d B）..." % (asset["name"], asset["size"]))
    curl = "curl.exe" if os.name == "nt" else "curl"
    subprocess.run([curl, "-sS", "-L", "--max-time", "1800", "-o", path, asset["browser_download_url"]], check=True)
    actual = os.path.getsize(path)
    if actual != asset["size"]:
        sys.exit("下载不完整：%d / %d B" % (actual, asset["size"]))
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag")
    ap.add_argument("--apk")
    args = ap.parse_args()

    token = os.environ.get("GITEE_TOKEN")
    if not token:
        sys.exit("缺少环境变量 GITEE_TOKEN")

    rel = pick_release(args.tag)
    tag = rel["tag_name"]
    asset = next((a for a in rel["assets"] if a["name"].endswith(".apk")), None)
    if not asset:
        sys.exit("GitHub 发行版 %s 没有 APK 附件" % tag)

    apk = os.path.abspath(args.apk) if args.apk else download(rel, asset)
    size = os.path.getsize(apk)
    name = asset["name"]
    print("目标：Gitee 发行版 %s ← %s（%d B）" % (tag, name, size))

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
            sys.exit("Gitee 发行版创建失败：%s" % created)
    print("Gitee 发行版 id=%s" % rid)

    files = gitee("GET", "/releases/%s/attach_files?access_token=%s" % (rid, token), token)
    if any(f.get("name") == name and f.get("size") == size for f in files):
        print("附件已存在且字节数一致，跳过")
        return

    curl = "curl.exe" if os.name == "nt" else "curl"
    out = subprocess.run([
        curl, "-sS", "--http1.1", "--connect-timeout", "30", "--max-time", "3600",
        "-X", "POST", "%s/releases/%s/attach_files" % (GITEE_API, rid),
        "-F", "access_token=" + token, "-F", "file=@" + apk,
        "-w", "\n_TIME=%{time_total}s _SIZE=%{size_upload}B _SPEED=%{speed_upload}B/s\n",
    ], capture_output=True, text=True, encoding="utf-8")
    tail = out.stdout[-300:]
    print(tail.strip())

    files = gitee("GET", "/releases/%s/attach_files?access_token=%s" % (rid, token), token)
    got = next((f.get("size") for f in files if f.get("name") == name), None)
    if got != size:
        sys.exit("Gitee 附件校验失败：服务端 %s B，期望 %d B（GitHub Release 不受影响）" % (got, size))
    print("✅ 已落盘：%s = %d B" % (name, got))


if __name__ == "__main__":
    main()
