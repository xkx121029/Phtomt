#!/usr/bin/env python3
"""
PhoneAgent coding runtime bundler: Termux Node.js -> main app jniLibs.

Why this exists: since Android 10 an app may only exec native code from its
native library directory, and PackageManager only extracts files named lib*.so
from an APK. But Termux's libraries are named libz.so.1, libcrypto.so.3,
libicuuc.so.78, ... (none match lib*.so). So we take the nodejs-lts .deb,
extract node + its dependency closure, and rename every reference to Android's
lib*.so convention, rewriting DT_NEEDED, SONAME *and* the VERNEED table
(version requirements reference libraries by name; skipping them yields
"cannot find libcrypto.so.3 from verneed"). The main app APK ships libnode.so
in its jniLibs (useLegacyPackaging=true so the libs land on real disk); the
agent host execs it from the app's own nativeLibraryDir with LD_LIBRARY_PATH
pointing there.
(Ported from happy_ACA tool/bundle_runtime.py; see HACA docs/ARCHITECTURE.md section 5.)

Usage:
    python tool/bundle_runtime.py                  # arm64-v8a only
    python tool/bundle_runtime.py --abis aarch64   # subset
    python tool/bundle_runtime.py --clean          # wipe outputs first

Output: app/src/main/jniLibs/<android-abi>/lib*.so
Cache:  build/termux-cache/ (downloaded .debs; safe to delete)

NOTE: termux node links full-ICU (libicudata.so ~31.6MB). After bundling, run
tool/build_icu_small.sh + tool/copy_small_icu.py to replace the three
libicu*.so with en-only small-icu builds (same ABI, saves ~30MB raw).

Requires: python + lief (pip install lief)
"""

import argparse
import gzip
import io
import os
import re
import sys
import tarfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
DIST = "stable"

# android ABI -> termux arch
ABI_MAP = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86_64": "x86_64",
    "x86": "i686",
}

# Packages we need beyond node itself (shared-library deps of node).
NEEDED_PACKAGES = ["nodejs-lts", "libc++", "openssl", "c-ares", "libicu", "libsqlite", "zlib"]

# Libraries provided by Android's bionic/system: never ship, never rename.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so",
    "libc++abi.so", "libunwind.so", "libqdmetadata.so", "libvndksupport.so",
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 输出到主包 app 模块的 jniLibs（运行时随主 APK 一起打包，不再单独分发）
JNILIBS = os.path.join(ROOT, "app", "src", "main", "jniLibs")
CACHE = os.path.join(ROOT, "build", "termux-cache")


def log(msg):
    print(f"[bundle] {msg}", flush=True)


# ---------- apt repo access ----------

def fetch(url, dest=None):
    req = urllib.request.Request(url, headers={"User-Agent": "phoneagent-runtime-bundler/0.1"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if dest:
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(data)
    return data


def package_index(arch):
    url = f"{REPO}/dists/{DIST}/main/binary-{arch}/Packages.gz"
    data = gzip.decompress(fetch(url))
    stanzas = {}
    for stanza in data.decode("utf-8", "replace").split("\n\n"):
        m = re.match(r"Package:\s*(\S+)", stanza)
        if m:
            stanzas[m.group(1)] = stanza
    return stanzas


def package_filename(stanzas, pkg):
    stanza = stanzas.get(pkg)
    if not stanza:
        raise SystemExit(f"package {pkg} not found in index")
    return re.search(r"Filename:\s*(\S+)", stanza).group(1)


# ---------- .deb (ar) + data.tar.xz ----------

def read_ar(data):
    if data[:8] != b"!<arch>\n":
        raise SystemExit("not an ar archive")
    entries = {}
    off = 8
    while off + 60 <= len(data):
        header = data[off : off + 60]
        name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        body = data[off + 60 : off + 60 + size]
        entries[name] = body
        off += 60 + size + (size % 2)
    return entries


def extract_data_tar(deb_bytes):
    """Extract wanted files from a .deb into {relpath: bytes}.

    Termux packages install under data/data/com.termux/files/usr/...
    """
    ar = read_ar(deb_bytes)
    data_member = next(v for k, v in ar.items() if k.startswith("data.tar"))
    out = {}
    with tarfile.open(fileobj=io.BytesIO(data_member), mode="r:*") as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            name = member.name.lstrip("./")
            if "/usr/bin/" in name or "/usr/lib/" in name:
                f = tar.extractfile(member)
                if f:
                    out[name] = f.read()
    return out


# ---------- ELF handling (lief) ----------

def load_elf(path):
    import lief

    return lief.parse(path)


def needed_libs(binary):
    import lief

    names = []
    for entry in binary.dynamic_entries:
        if type(entry).__name__ in ("DynamicEntryLibrary", "DynamicSharedObject"):
            names.append(entry.name)
    return names


def soname_of(binary):
    for entry in binary.dynamic_entries:
        if type(entry).__name__ == "DynamicSharedObject":
            return entry.name
    return None


# ---------- main logic ----------

def bundle_abi(android_abi, keep_cache):
    arch = ABI_MAP[android_abi]
    log(f"=== {android_abi} (termux {arch}) ===")

    stanzas = package_index(arch)
    files = {}  # basename -> bytes  (candidate ELF files)

    for pkg in NEEDED_PACKAGES:
        fn = package_filename(stanzas, pkg)
        url = f"{REPO}/{fn}"
        cache_path = os.path.join(CACHE, arch, os.path.basename(fn))
        if os.path.exists(cache_path):
            with open(cache_path, "rb") as f:
                deb = f.read()
        else:
            log(f"  download {pkg} ({os.path.basename(fn)})")
            deb = fetch(url, cache_path)
        extracted = extract_data_tar(deb)
        for rel, data in extracted.items():
            base = os.path.basename(rel)
            if ".so" in base:
                files[base] = data
        node_key = next((k for k in extracted if k.endswith("/usr/bin/node")), None)
        if pkg == "nodejs-lts":
            if node_key is None:
                raise SystemExit("nodejs-lts .deb has no usr/bin/node")
            files["node"] = extracted[node_key]

    if "node" not in files:
        raise SystemExit("node binary missing")

    # Work out the transitive closure of libraries node actually needs.
    # Termux real files carry versioned suffixes (libicuuc.so.78.3, libz.so.1.3.2,
    # libsqlite3.54.0.so) while DT_NEEDED uses the short soname (libicuuc.so.78),
    # so resolve short names to real files with a versioned-suffix pattern.
    import lief
    import tempfile

    tmp = tempfile.mkdtemp(prefix="phoneagent-bundle-")

    def parse(base):
        p = os.path.join(tmp, re.sub(r"[^A-Za-z0-9.]", "_", base))
        if not os.path.exists(p):
            with open(p, "wb") as f:
                f.write(files[base])
        return load_elf(p)

    def resolve(needed):
        if needed in files:
            return needed
        idx = needed.find(".so")
        stem = needed[:idx] if idx >= 0 else needed
        # stem, optional version groups, .so, optional version groups:
        #   libicuuc.so.78   -> libicuuc.so.78.3
        #   libz.so.1        -> libz.so.1.3.2
        #   libsqlite3.so    -> libsqlite3.54.0.so
        pat = re.compile("^" + re.escape(stem) + r"(\.[0-9]+)*\.so(\.[0-9]+)*$")
        cands = sorted((b for b in files if pat.match(b)), key=len)
        return cands[0] if cands else None

    ship = set()
    ref = {}  # shipped base name -> the DT_NEEDED string dependents use for it
    unresolved = set()

    def visit(base, needed_as=None):
        if base in ship or base not in files:
            return
        ship.add(base)
        if needed_as is not None:
            ref[base] = needed_as
        for lib in needed_libs(parse(base)):
            if lib in SYSTEM_LIBS:
                continue
            actual = resolve(lib)
            if actual is None:
                unresolved.add(lib)
                continue
            visit(actual, lib)

    visit("node", "node")

    if unresolved:
        raise SystemExit(
            "unresolved DT_NEEDED libraries (not in downloaded packages, not system): "
            + ", ".join(sorted(unresolved))
        )

    # Android only packages lib*.so files, so every shipped library must be
    # renamed to the lib<stem>.so form; node becomes libnode.so.
    def lib_name(base):
        if base == "node":
            return "libnode.so"
        idx = base.find(".so")
        return base[: idx + 3] if idx >= 0 else "lib" + re.sub(r"[^A-Za-z0-9.]", "_", base) + ".so"

    # Map every name a dependent might use (DT_NEEDED / VERNEED / the library's
    # own SONAME) to the new lib*.so filename, so all three tables agree and the
    # linker resolves each dependency by exact filename.
    rename = {}
    for base in ship:
        new = lib_name(base)
        for key in {ref.get(base, base), soname_of(parse(base)) or base}:
            rename[key] = new

    seen = {}
    for base in ship:
        fn = lib_name(base)
        if fn in seen:
            raise SystemExit(f"filename collision: {seen[fn]} and {base} -> {fn}")
        seen[fn] = base

    def patch(binary):
        """Rewrite DT_NEEDED, SONAME and VERNEED library names via `rename`."""
        for entry in binary.dynamic_entries:
            tname = type(entry).__name__
            if tname in ("DynamicEntryLibrary", "DynamicSharedObject") and entry.name in rename:
                entry.name = rename[entry.name]
        for vr in binary.symbols_version_requirement:
            if vr.name in rename:
                vr.name = rename[vr.name]

    out_dir = os.path.join(JNILIBS, android_abi)
    os.makedirs(out_dir, exist_ok=True)
    total = 0
    for base in sorted(ship):
        binary = parse(base)
        patch(binary)
        dest = os.path.join(out_dir, lib_name(base))
        binary.write(dest)
        os.chmod(dest, 0o755)
        total += os.path.getsize(dest)

    # Sanity: after rewriting, every DT_NEEDED/SONAME/VERNEED reference must be
    # either a system lib or a file we actually shipped.
    shipped = set(seen)
    for base in sorted(ship):
        check = load_elf(os.path.join(out_dir, lib_name(base)))
        refs = [e.name for e in check.dynamic_entries
                if type(e).__name__ in ("DynamicEntryLibrary", "DynamicSharedObject")]
        refs += [vr.name for vr in check.symbols_version_requirement]
        bad = [r for r in refs if r not in SYSTEM_LIBS and r not in shipped]
        if bad:
            raise SystemExit(f"{lib_name(base)} still references missing libs: {bad}")

    log(f"  shipped {len(ship)} libs, {total / 1024 / 1024:.1f} MB -> {out_dir}")
    for base in sorted(ship):
        log(f"    {base}  ->  {lib_name(base)}")
    return len(ship), total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--abis", nargs="*", default=["arm64-v8a"], choices=list(ABI_MAP))
    ap.add_argument("--clean", action="store_true")
    args = ap.parse_args()

    if args.clean:
        import shutil

        for abi in args.abis:
            shutil.rmtree(os.path.join(JNILIBS, abi), ignore_errors=True)

    grand = 0
    for abi in args.abis:
        _, size = bundle_abi(abi, keep_cache=True)
        grand += size
    log(f"done: {grand / 1024 / 1024:.1f} MB total across {len(args.abis)} ABI(s)")
    log("NOTE: jniLibs is a build input, not committed. Re-run after SDK changes.")


if __name__ == "__main__":
    main()
