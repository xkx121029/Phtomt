#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把训练机生成的执行文档同步到官网站点（App 云端执行文档）。

流程：
  1. 从 hpa-trainer 9817 拉 /api/taskdocs 与每篇 /api/taskdoc/{id}/md；
  2. 给正文补上站点要求的 front-matter（packages / apps / keywords / intents / priority）；
  3. scp 到 NAS 中转目录，再 docker cp 进 phtomt-site 的 data/agent-docs；
  4. 用站点 API 复核 count 与 id 列表。

可重复执行：同名文件覆盖，站点按 mtime 自动刷新，不需要重建镜像。
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

TRAINER = "http://192.168.2.16:9817"
SITE = "http://192.168.2.16:5180"
NAS = "nas"
REMOTE_STAGE = "/tmp/hpa_agent_docs"
SITE_DATA = "/app/server/data/agent-docs"

# 套件 -> App 匹配信息（App 端匹配：packages 40 分 / apps 40 分 / keywords 8 分 / intents 6 分）
SUITE_META = {
    "alipay": {"packages": ["com.eg.android.AlipayGphone"], "apps": ["支付宝", "Alipay"]},
    "bilibili": {"packages": ["tv.danmaku.bili"], "apps": ["B站", "哔哩哔哩", "bilibili"]},
    "wechat_reading": {"packages": ["com.tencent.weread"], "apps": ["微信读书"]},
    "wechat": {"packages": ["com.tencent.mm"], "apps": ["微信", "WeChat"]},
    # 系统设置/账号类没有唯一包名，只按文本匹配，避免误注入
    "account": {"packages": [], "apps": ["设置", "12306"]},
    "crossapp_commerce": {"packages": [], "apps": ["淘宝", "京东", "支付宝", "微信"]},
}

# 只在这些动作词真实出现在任务名里时才写进 intents，避免大范围误命中
ACTION_WORDS = (
    "开启", "关闭", "查看", "打开", "发送", "添加", "修改", "设置", "查询", "计算",
    "绑定", "退出", "刷新", "收藏", "关注", "点赞", "投币", "播放", "搜索", "删除",
    "编辑", "保存", "复制", "移动", "重命名", "领取", "充值", "转账", "付款", "收款",
    "登录", "注册", "找回", "重置", "更换", "解绑", "分享", "下载", "上传", "签到",
)

PRIORITY_BY_DIFFICULTY = {"L1": 70, "L2": 55, "L3": 40}


def fetch_text(url: str) -> str:
    with urllib.request.urlopen(url, timeout=20) as resp:
        return resp.read().decode("utf-8")


def fetch_json(url: str):
    return json.loads(fetch_text(url))


def unique(items) -> list[str]:
    out: list[str] = []
    for item in items:
        text = str(item or "").strip()
        if text and text not in out:
            out.append(text)
    return out


def build_front_matter(doc: dict, md: str, version: str) -> str:
    task_id = str(doc.get("task_id") or doc.get("id") or "doc")
    suite = str(doc.get("suite") or "general")
    task_name = str(doc.get("task_name") or task_id)
    difficulty = str(doc.get("difficulty") or "")
    meta = SUITE_META.get(suite, {"packages": [], "apps": []})

    # 路由里出现过的 app 也作为匹配线索（跨应用任务尤其需要）
    route_apps: list[str] = []
    route = doc.get("route") or {}
    if isinstance(route, dict) and route.get("app"):
        route_apps.append(str(route["app"]))

    keywords = unique([task_name, *meta["apps"]])
    intents = [w for w in ACTION_WORDS if w in task_name]
    packages = unique([*meta["packages"]])
    apps = unique([*meta["apps"], *route_apps])
    tags = unique([suite, difficulty, *[str(c) for c in (doc.get("capabilities") or [])], "trainer"])

    steps = doc.get("steps") or []
    summary = f"{task_name}（{suite} · {difficulty or '未分级'} · 训练机实测 {len(steps)} 步）"

    def fmt_list(items: list[str]) -> str:
        # 站点是极简 front-matter：单行 key: value，遇到英文逗号会切成数组
        return "[" + ", ".join(i.replace(",", "，") for i in items) + "]"

    lines = [
        "---",
        f"title: {task_name}",
        f"summary: {summary}",
        f"version: {version}",
        f"priority: {PRIORITY_BY_DIFFICULTY.get(difficulty, 50)}",
        f"packages: {fmt_list(packages)}",
        f"apps: {fmt_list(apps)}",
        f"keywords: {fmt_list(keywords)}",
        f"intents: {fmt_list(intents)}",
        f"tags: {fmt_list(tags)}",
        "---",
        "",
    ]
    return "\n".join(lines) + md.strip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="只生成文件，不上传")
    args = parser.parse_args()

    health = fetch_json(f"{TRAINER}/api/health").get("state") or {}
    version = f"trainer-g{health.get('generation', '?')}-pv{health.get('policy_version', '?')}"

    index = fetch_json(f"{TRAINER}/api/taskdocs")
    docs = index.get("docs") if isinstance(index, dict) else index
    if not isinstance(docs, list):
        print(f"unexpected /api/taskdocs shape: {type(docs)}")
        return 2

    staging = Path(tempfile.mkdtemp(prefix="hpa_agent_docs_"))
    written: list[str] = []
    for item in docs:
        task_id = str(item.get("task_id") or item.get("id") or "").strip()
        if not task_id or not re.match(r"^[A-Za-z0-9._-]+$", task_id):
            print(f"skip invalid id: {task_id!r}")
            continue
        detail = fetch_json(f"{TRAINER}/api/taskdoc/{task_id}")
        md = fetch_text(f"{TRAINER}/api/taskdoc/{task_id}/md")
        content = build_front_matter(detail, md, version)
        (staging / f"{task_id}.md").write_text(content, encoding="utf-8")
        written.append(task_id)
        print(f"built {task_id}.md ({len(content)} chars)")

    if not written:
        print("no docs to upload")
        return 1

    if args.dry_run:
        print(f"dry-run staging: {staging}")
        return 0

    subprocess.run(["ssh", NAS, f"rm -rf {REMOTE_STAGE} && mkdir -p {REMOTE_STAGE}"], check=True)
    for name in written:
        subprocess.run(["scp", "-O", str(staging / f"{name}.md"), f"{NAS}:{REMOTE_STAGE}/{name}.md"], check=True)
    subprocess.run(["ssh", NAS, f"docker cp {REMOTE_STAGE}/. phtomt-site:{SITE_DATA}/"], check=True)

    live = fetch_json(f"{SITE}/api/agent-docs/index.json")
    live_ids = [d["id"] for d in live.get("docs", [])]
    missing = [i for i in written if i not in live_ids]
    print(f"site count={live.get('count')} version={live.get('version')}")
    print("uploaded:", ", ".join(written))
    if missing:
        print("MISSING on site:", ", ".join(missing))
        return 1
    shutil.rmtree(staging, ignore_errors=True)
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
