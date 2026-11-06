#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验品牌标记「亮格」在各处实现里的几何是否同源。

标记「亮格」有 5 份实现，各自嵌在不同的坐标系里：

  Compose     BrandMark.kt                      画布即外框，u = size / 15.3
  SVG         website/public/logo-mark.svg      24 视图，整组再缩 0.94 留边
  SVG         website/public/favicon.svg        同上，仅瓦片圆角不同
  VD          ic_launcher_foreground.xml        108 视口，24 网格放大到 68.4 居中
  VD          ic_launcher_monochrome.xml        同上，纯白

绝对数值天然不同（这是刻意的：自适应图标要落进 66dp 安全圆，网页图标要留边），
能比较的只有**相对外框的比例**——格宽、缝宽、圆角、描边、四格位置，以及哪一格实心。

改标记最典型的失误是只改了两三处，剩下的悄悄漂移，而且要到桌面上才看得出来。
这个脚本把五份文件读成同一套归一化坐标逐项比对，任何一项超出容差就以非零码退出。

用法：
    python tool/verify_brand_mark.py
    python tool/verify_brand_mark.py -v     # 额外打印每格明细
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

COMPOSE = ROOT / "app/src/main/java/com/phoneagent/ui/components/BrandMark.kt"
COLOR_KT = ROOT / "app/src/main/java/com/phoneagent/ui/theme/Color.kt"
RES_COLORS = ROOT / "app/src/main/res/values/colors.xml"
VD_FOREGROUND = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
VD_MONOCHROME = ROOT / "app/src/main/res/drawable/ic_launcher_monochrome.xml"
SVG_MARK = ROOT / "website/public/logo-mark.svg"
SVG_FAVICON = ROOT / "website/public/favicon.svg"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

#: 归一化到「外框宽度 = 1」之后的容许偏差。2e-3 意味着 24 网格上约 0.03 单位，
#: 比两位小数的取整误差（约 5e-4）宽、比任何真实几何改动窄一个量级。
TOL = 2e-3

#: 格位判定阈值：相邻两列外框左上角相差一个栅距（0.588），取一半即可分类
PITCH_SPLIT = 0.294

FIELDS = (
    ("x0", "左上 x"),
    ("y0", "左上 y"),
    ("w", "格宽"),
    ("h", "格高"),
    ("radius", "圆角"),
    ("stroke", "描边"),
)


class Cell:
    """一格的归一化几何（外框坐标，单位 = 外框宽度）。"""

    __slots__ = ("i", "j", "x0", "y0", "w", "h", "radius", "stroke", "lit")

    def __init__(self, x0, y0, w, h, radius, stroke, lit, i=-1, j=-1):
        self.x0, self.y0, self.w, self.h = x0, y0, w, h
        self.radius, self.stroke, self.lit = radius, stroke, lit
        self.i, self.j = i, j

    @property
    def center(self):
        return self.x0 + self.w / 2, self.y0 + self.h / 2


# ---------------------------------------------------------------- 规范来源

def _kotlin_const(name: str) -> float:
    text = COMPOSE.read_text(encoding="utf-8")
    m = re.search(rf"const val {name}\s*=\s*([0-9.]+)f?", text)
    if not m:
        raise SystemExit(f"BrandMark.kt 里找不到常量 {name}")
    return float(m.group(1))


def _kotlin_int(name: str) -> int:
    text = COMPOSE.read_text(encoding="utf-8")
    m = re.search(rf"const val {name}\s*=\s*(-?\d+)", text)
    if not m:
        raise SystemExit(f"BrandMark.kt 里找不到常量 {name}")
    return int(m.group(1))


def canonical():
    cell = _kotlin_const("CELL")
    gap = _kotlin_const("GAP")
    radius = _kotlin_const("RADIUS")
    stroke = _kotlin_const("STROKE")
    lit_i = _kotlin_int("LIT_I")
    lit_j = _kotlin_int("LIT_J")

    frame = 2 * cell + gap
    # FRAME 若写成字面量就有第二处真相，顺手核一下；写成表达式（2 * CELL + GAP）则跳过
    m = re.search(r"const val FRAME\s*=\s*([^\n]+)", COMPOSE.read_text(encoding="utf-8"))
    literal = re.fullmatch(r"([0-9.]+)f?\s*", m.group(1)) if m else None
    if literal and abs(float(literal.group(1)) - frame) > 1e-6:
        raise SystemExit(
            f"BrandMark.kt 的 FRAME = {literal.group(1)} 与 2*CELL+GAP = {frame:.2f} 不一致"
        )

    cells = []
    for j in (0, 1):
        for i in (0, 1):
            lit = i == lit_i and j == lit_j
            cells.append(
                Cell(
                    x0=(cell + gap) * i / frame,
                    y0=(cell + gap) * j / frame,
                    w=cell / frame,
                    h=cell / frame,
                    # 描边格的圆角是「中线半径」，实心格是外轮廓半径
                    radius=(radius if lit else radius - stroke / 2) / frame,
                    stroke=0.0 if lit else stroke / frame,
                    lit=lit,
                    i=i,
                    j=j,
                )
            )
    return cells, {
        "cell": cell,
        "gap": gap,
        "radius": radius,
        "stroke": stroke,
        "frame": frame,
        "lit": (lit_i, lit_j),
    }


# ---------------------------------------------------------------- 读三处实现

def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _norm_cells(raw, frame_w):
    """raw: [(x0, y0, w, h, radius, stroke, lit)]，平移到外框原点后按外框宽度归一化。"""
    ox = min(c[0] for c in raw)
    oy = min(c[1] for c in raw)
    out = []
    for x0, y0, w, h, radius, stroke, lit in raw:
        nx, ny = (x0 - ox) / frame_w, (y0 - oy) / frame_w
        cx, cy = nx + w / frame_w / 2, ny + h / frame_w / 2
        out.append(
            Cell(
                x0=nx,
                y0=ny,
                w=w / frame_w,
                h=h / frame_w,
                radius=radius / frame_w,
                stroke=stroke / frame_w,
                lit=lit,
                i=0 if cx < PITCH_SPLIT else 1,
                j=0 if cy < PITCH_SPLIT else 1,
            )
        )
    return out


def svg_cells(path: Path):
    root = ET.parse(path).getroot()
    vb = [float(v) for v in root.get("viewBox", "0 0 24 24").split()]
    tile_w, tile_h = vb[2], vb[3]

    glyph_rects = []
    tile_fill = None

    def walk(el, inherited):
        nonlocal tile_fill
        attrs = dict(inherited)
        for key in ("fill", "stroke", "stroke-width"):
            if el.get(key) is not None:
                attrs[key] = el.get(key)
        if _local(el.tag) == "rect":
            w = float(el.get("width", 0))
            h = float(el.get("height", 0))
            if abs(w - tile_w) < 1e-9 and abs(h - tile_h) < 1e-9:
                tile_fill = attrs.get("fill")
            else:
                glyph_rects.append((el, attrs))
        for child in el:
            walk(child, attrs)

    walk(root, {})

    raw, colors = [], set()
    for el, attrs in glyph_rects:
        x = float(el.get("x", 0))
        y = float(el.get("y", 0))
        w = float(el.get("width", 0))
        h = float(el.get("height", 0))
        rx = float(el.get("rx", 0))
        stroke = attrs.get("stroke", "none")
        fill = attrs.get("fill", "none")
        colors.add((fill or "").lower())
        colors.add((stroke or "").lower())
        if stroke not in ("none", None):
            sw = float(attrs.get("stroke-width", 1.0))
            raw.append((x - sw / 2, y - sw / 2, w + sw, h + sw, rx, sw, False))
        else:
            raw.append((x, y, w, h, rx, 0.0, True))

    if not raw:
        raise SystemExit(f"{path} 里没有找到标记方格")
    frame_w = max(c[0] + c[2] for c in raw) - min(c[0] for c in raw)
    return _norm_cells(raw, frame_w), {"tile_fill": tile_fill, "colors": colors}


TOKEN = re.compile(r"[A-Za-z]|-?\d+(?:\.\d+)?|\.\d+")


def _path_bbox_and_radius(d: str):
    """只认这些图标用到的绝对命令 M/H/V/A/Z；出现别的命令直接报错，免得静默量错。"""
    tokens = TOKEN.findall(d)
    pts = []
    cx = cy = 0.0
    cmd = None
    arc_rx = None
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        if tok.isalpha():
            cmd = tok
            i += 1
            continue
        if cmd == "M":
            cx, cy = float(tokens[i]), float(tokens[i + 1])
            pts.append((cx, cy))
            i += 2
            cmd = "L"
        elif cmd == "L":
            cx, cy = float(tokens[i]), float(tokens[i + 1])
            pts.append((cx, cy))
            i += 2
        elif cmd == "H":
            cx = float(tokens[i])
            pts.append((cx, cy))
            i += 1
        elif cmd == "V":
            cy = float(tokens[i])
            pts.append((cx, cy))
            i += 1
        elif cmd == "A":
            rx, ry = float(tokens[i]), float(tokens[i + 1])
            x, y = float(tokens[i + 5]), float(tokens[i + 6])
            if arc_rx is None:
                arc_rx = (rx, ry)
            cx, cy = x, y
            pts.append((cx, cy))
            i += 7
        else:
            raise SystemExit(f"pathData 里出现了本脚本不认识的命令 {cmd!r}：{d[:40]}…")
    if not pts:
        raise SystemExit(f"pathData 解析不出坐标：{d[:40]}…")
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys), arc_rx


def vd_cells(path: Path):
    root = ET.parse(path).getroot()
    raw, colors = [], set()
    for el in root.iter():
        if _local(el.tag) != "path":
            continue
        d = el.get(ANDROID_NS + "pathData")
        if not d:
            continue
        x1, y1, x2, y2, arc = _path_bbox_and_radius(d)
        stroke_color = el.get(ANDROID_NS + "strokeColor")
        fill_color = el.get(ANDROID_NS + "fillColor")
        colors.add((stroke_color or fill_color or "").lower())
        if stroke_color:
            sw = float(el.get(ANDROID_NS + "strokeWidth", 0))
            if not arc:
                raise SystemExit(f"{path.name}：描边格缺圆角圆弧，量不出中线半径")
            # 路径是中线轮廓，外框要向外扩半个描边
            raw.append((x1 - sw / 2, y1 - sw / 2,
                        x2 - x1 + sw, y2 - y1 + sw, arc[0], sw, False))
        else:
            raw.append((x1, y1, x2 - x1, y2 - y1,
                        arc[0] if arc else 0.0, 0.0, True))
    if not raw:
        raise SystemExit(f"{path} 里没有找到标记方格")
    frame_w = max(c[0] + c[2] for c in raw) - min(c[0] for c in raw)
    return _norm_cells(raw, frame_w), {"colors": colors}


# ---------------------------------------------------------------- 比对

def compare(label: str, cells, spec):
    """返回 (问题列表, 最大偏差)。"""
    problems = []
    worst = 0.0

    lit = [c for c in cells if c.lit]
    if len(lit) != 1:
        problems.append(f"实心格数量为 {len(lit)}，应恰好 1 个")
        return problems, worst

    by_pos = {(c.i, c.j): c for c in cells}
    if len(by_pos) != len(cells):
        problems.append(f"四格的格位有重复（解析出 {len(cells)} 格但只落在 {len(by_pos)} 个格位）")

    for want in spec:
        got = by_pos.get((want.i, want.j))
        name = f"格({want.i},{want.j})"
        if got is None:
            problems.append(f"{name} 缺失")
            continue
        if want.lit != got.lit:
            problems.append(
                f"{name} 实心状态不一致：规范是{'实心' if want.lit else '描边'}"
                f"，此文件是{'实心' if got.lit else '描边'}"
            )
        for attr, cn in FIELDS:
            w, g = getattr(want, attr), getattr(got, attr)
            diff = abs(w - g)
            worst = max(worst, diff)
            if diff > TOL:
                problems.append(
                    f"{name} {cn}：期望 {w:.6f}，实际 {g:.6f}（差 {g - w:+.6f}）"
                )
    return problems, worst


def _hex(value: str | None) -> str | None:
    if not value:
        return None
    v = value.strip().lower()
    m = re.fullmatch(r"#([0-9a-f]{6})", v)
    if m:
        return "#" + m.group(1)
    m = re.fullmatch(r"0xff([0-9a-f]{6})", v)
    if m:
        return "#" + m.group(1)
    return v


def color_checks():
    """同一枚标记在三处用的必须是同一组色，否则桌面上和页面里看起来是两个东西。"""
    out = []
    color_kt = COLOR_KT.read_text(encoding="utf-8")
    anchor = _hex(re.search(r"val BrandAnchor\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)", color_kt).group(1))
    tint = _hex(re.search(r"val BrandMarkTint\s*=\s*Color\((0x[0-9A-Fa-f]{8})\)", color_kt).group(1))

    res = RES_COLORS.read_text(encoding="utf-8")
    res_bg = _hex(re.search(r'name="ic_launcher_background">\s*(#[0-9A-Fa-f]{6})', res).group(1))

    svg_tile = _hex(svg_cells(SVG_MARK)[1]["tile_fill"])

    anchors = [
        ("Color.kt BrandAnchor", anchor),
        ("colors.xml ic_launcher_background", res_bg),
        ("logo-mark.svg 瓦片", svg_tile),
    ]
    if len({v for _, v in anchors if v}) != 1:
        detail = "、".join(f"{n}={v}" for n, v in anchors)
        out.append(f"深锚底色不一致：{detail}")

    fg_colors = {c.lower() for c in vd_colors(VD_FOREGROUND)}
    svg_colors = {c for c in svg_colors_of(SVG_MARK) if c.startswith("#")}
    svg_colors |= {c for c in svg_colors_of(SVG_FAVICON) if c.startswith("#")}
    # SVG 里还有瓦片底色，剔除后再和端侧比
    svg_colors.discard(svg_tile)
    expect = {tint}
    if svg_colors != expect:
        out.append(f"SVG 标记色 ≠ BrandMarkTint {tint}：实际 {sorted(svg_colors)}")
    if fg_colors != expect:
        out.append(f"ic_launcher_foreground 标记色 ≠ BrandMarkTint {tint}：实际 {sorted(fg_colors)}")

    mono = {c.lower() for c in vd_colors(VD_MONOCHROME)}
    if mono != {"#ffffff"}:
        out.append(f"ic_launcher_monochrome 应当是纯白单色，实际 {sorted(mono)}")
    return out


def svg_colors_of(path: Path):
    root = ET.parse(path).getroot()
    found = set()
    for el in root.iter():
        for key in ("fill", "stroke"):
            v = el.get(key)
            if v and v.startswith("#"):
                found.add(v.lower())
    return found


def vd_colors(path: Path):
    root = ET.parse(path).getroot()
    found = set()
    for el in root.iter():
        if _local(el.tag) != "path":
            continue
        for key in ("strokeColor", "fillColor"):
            v = el.get(ANDROID_NS + key)
            if v:
                found.add(v)
    return found


def main(argv):
    verbose = "-v" in argv or "--verbose" in argv

    spec, meta = canonical()
    print("「亮格」几何同源校验")
    print(
        f"  规范来源 BrandMark.kt：CELL={meta['cell']} GAP={meta['gap']} "
        f"RADIUS={meta['radius']} STROKE={meta['stroke']} 外框={meta['frame']:.1f} "
        f"实心格={meta['lit']}"
    )
    lit = next(c for c in spec if c.lit)
    stroked = next(c for c in spec if not c.lit)
    print(
        f"  归一化比例（外框=1）：格 {stroked.w:.4f}  缝 {lit.x0 - stroked.w:.4f}  "
        f"描边 {stroked.stroke:.4f}  描边格圆角 {stroked.radius:.4f}  "
        f"实心格圆角 {lit.radius:.4f}"
    )
    print()

    sources = [
        ("compose  BrandMark.kt", spec),
        ("svg      logo-mark.svg", svg_cells(SVG_MARK)[0]),
        ("svg      favicon.svg", svg_cells(SVG_FAVICON)[0]),
        ("vector   ic_launcher_foreground.xml", vd_cells(VD_FOREGROUND)[0]),
        ("vector   ic_launcher_monochrome.xml", vd_cells(VD_MONOCHROME)[0]),
    ]

    failed = 0
    for label, cells in sources:
        problems, worst = compare(label, cells, spec)
        if problems:
            failed += 1
            print(f"  [!!] {label}")
            for p in problems:
                print(f"       - {p}")
        else:
            print(f"  [OK] {label}   最大偏差 {worst:.6f}（容差 {TOL}）")
        if verbose:
            for c in sorted(cells, key=lambda c: (c.j, c.i)):
                print(
                    f"       格({c.i},{c.j}) x0={c.x0:.4f} y0={c.y0:.4f} "
                    f"w={c.w:.4f} r={c.radius:.4f} sw={c.stroke:.4f} "
                    f"{'实心' if c.lit else '描边'}"
                )

    print()
    color_problems = color_checks()
    if color_problems:
        failed += 1
        print("  [!!] 颜色")
        for p in color_problems:
            print(f"       - {p}")
    else:
        print("  [OK] 颜色   深锚 / 标记色 / 单色层三组一致")

    print()
    if failed:
        print(f"失败：{failed} 处与规范不同源。改标记时请把五份文件一起改。")
        return 1
    print("通过：五份实现的几何与配色同源。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
