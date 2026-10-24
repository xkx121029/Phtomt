#!/usr/bin/env python3
"""
校验 small-icu 产物并替换 jniLibs 里的三个 libicu*.so。

校验逻辑：把 app/src/main/jniLibs/arm64-v8a 下全部 so 的导出符号求并集，
确认每个未定义符号都能被（导出并集 ∪ 系统库白名单）满足——特别覆盖
libnode.so 对 ICU 符号的依赖，防止替换出缺符号的坏包。全部通过才落盘。

用法：python tool/copy_small_icu.py
前置：bash tool/build_icu_small.sh（产物在 tool/icu_small_out/）
"""

import os
import shutil
import sys

import lief

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNILIBS = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")
SRC = os.path.join(ROOT, "tool", "icu_small_out")

# Android 系统库由 linker 提供，不参与本地符号满足（与 bundle_runtime.py 同一白名单）
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so",
    "libc++abi.so", "libunwind.so", "libqdmetadata.so", "libvndksupport.so",
}
ICU_LIBS = ["libicudata.so", "libicui18n.so", "libicuuc.so"]


def main():
    if not all(os.path.exists(os.path.join(SRC, f)) for f in ICU_LIBS):
        sys.exit(f"missing artifacts in {SRC}; run tool/build_icu_small.sh first")

    # 新库的导出符号并入集合并参与校验
    new_exports = set()
    for f in ICU_LIBS:
        binary = lief.parse(os.path.join(SRC, f))
        if binary is None:
            sys.exit(f"cannot parse {f}")
        new_exports |= {s.name for s in binary.dynamic_symbols if s.exported}

    # jniLibs 现有全部 so 的导出/未定义符号
    all_exports = set(new_exports)
    all_undefs = set()
    for name in os.listdir(JNILIBS):
        if not name.endswith(".so"):
            continue
        binary = lief.parse(os.path.join(JNILIBS, name))
        all_exports |= {s.name for s in binary.dynamic_symbols if s.exported}
        all_undefs |= {s.name for s in binary.dynamic_symbols if s.imported}

    missing = sorted(all_undefs - all_exports)
    if missing:
        sys.exit("symbol check FAILED, unresolved: " + ", ".join(missing[:20]))
    print(f"[small-icu] symbol check OK ({len(all_undefs)} undefined symbols all resolved)")

    for f in ICU_LIBS:
        old = os.path.join(JNILIBS, f)
        new = os.path.join(SRC, f)
        print(f"[small-icu] {f}: {os.path.getsize(old) / 1e6:.1f} MB -> {os.path.getsize(new) / 1e6:.1f} MB")
        shutil.copy2(new, old)
    print("[small-icu] replaced.")


if __name__ == "__main__":
    main()
