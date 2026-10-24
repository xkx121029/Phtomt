#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# small-icu 交叉编译：为 node 运行时生成轻量 ICU（仅 en locale，数据 ~1-2MB）
#
# 为什么：Termux 的 nodejs-lts 链接 full-ICU，libicudata.so 高达 31.6MB（打包进
# APK 约 10MB+）。agent-core 不需要完整国际化，用 upstream icu4c 78.x 以
# ICU_DATA_FILTER_FILE 只保留 en locale 重建三个 libicu*.so，ABI（soname 大版本
# 78）与 termux 版一致，node 无需重编。实测可省 ~30MB raw。
#
# 运行方式（Windows 宿主，经 Docker）：
#   docker run --rm -v "<NDK路径>:/ndk:ro" -v "<项目>/tool:/src" ubuntu:24.04 bash /src/build_icu_small.sh
# 产物：/src/icu_small_out/{libicudata.so,libicui18n.so,libicuuc.so}
# 之后用 tool/copy_small_icu.py 校验符号并替换 jniLibs 里的三个文件。
# ---------------------------------------------------------------------------
set -euo pipefail

ICU_MAJOR=78
OUT=/src/icu_small_out
NDK_BIN=/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq build-essential curl ca-certificates python3 patchelf jq >/dev/null

mkdir -p /work
cd /work

# ---- 源码：优先宿主预下载的归档（tool/icu4c-src.tgz，容器内可能被网络环境拦 TLS）----
if [ -f /src/icu4c-src.tgz ]; then
  echo "[icu] using pre-downloaded /src/icu4c-src.tgz"
  mkdir -p icu_x && tar xf /src/icu4c-src.tgz -C icu_x
  ICU_SRC=$(dirname "$(find icu_x -maxdepth 4 -type d -name source | head -1)")
else
  echo "[icu] resolving latest release-${ICU_MAJOR}.x tag"
  TAG=$(curl -s "https://api.github.com/repos/unicode-org/icu/tags?per_page=100" \
    | jq -r "[.[].name | select(startswith(\"release-${ICU_MAJOR}.\")) and (contains(\"rc\")|not)] | sort_by(split(\".\")[2] | tonumber) | last")
  echo "[icu] tag = $TAG"
  mkdir -p icu_x && tar xf /src/icu4c-src.tgz -C icu_x 2>/dev/null || \
    curl -sfL -o icu.tgz "https://codeload.github.com/unicode-org/icu/tar.gz/refs/tags/${TAG}" && true
  if [ ! -d icu_x/icu4c ] && [ -f icu.tgz ]; then tar xf icu.tgz -C icu_x; fi
  ICU_SRC=$(dirname "$(find icu_x -maxdepth 4 -type d -name source | head -1)")
fi
echo "[icu] source dir: $ICU_SRC"

# ---- locale 过滤：只留 en（等价 node --with-intl=small-icu）----
cat > filter.json <<'EOF'
{
  "localeFilter": {
    "filterType": "language",
    "includelist": ["en"]
  }
}
EOF

# ---- 宿主构建（提供交叉数据装配用的工具）----
mkdir -p host && cd host
"$ICU_SRC/runConfigureICU" Linux/gcc --disable-tests --disable-samples --disable-extras >/dev/null
make -j"$(nproc)" >/dev/null
cd /work

# ---- arm64 Android 交叉构建 ----
export CC=${NDK_BIN}/aarch64-linux-android26-clang
export CXX=${NDK_BIN}/aarch64-linux-android26-clang++
export ICU_DATA_FILTER_FILE=/work/filter.json
mkdir -p cross && cd cross
"$ICU_SRC/configure" \
  --host=aarch64-linux-android \
  --with-cross-build=/work/host \
  --with-data-packaging=library \
  --disable-tools --disable-extras --disable-tests --disable-samples \
  --enable-shared --disable-static \
  --prefix=/work/install >/dev/null
make -j"$(nproc)" >/dev/null
make install >/dev/null
cd /work

# ---- 裁剪 + 改名对齐 jniLibs 约定（lib*.so，soname 与 node 的 DT_NEEDED 一致）----
mkdir -p "$OUT"
STRIP=${NDK_BIN}/llvm-strip
for lib in libicudata libicui18n libicuuc; do
  src=$(ls /work/install/lib/${lib}.so.* | sort -V | tail -1)
  $STRIP --strip-unneeded "$src" -o "/work/${lib}.so"
  patchelf --set-soname "${lib}.so" "/work/${lib}.so"
  # 上游的 DT_NEEDED 是 libicuuc.so.78；jniLibs 集合里已统一为裸名
  patchelf --replace-needed "libicuuc.so.${ICU_MAJOR}" "libicuuc.so" "/work/${lib}.so" 2>/dev/null || true
  cp "/work/${lib}.so" "$OUT/"
done

echo "[icu] done:"
ls -la "$OUT"
