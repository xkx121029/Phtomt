#!/bin/sh
set -e

# 数据目录与 APK 目录通常挂着卷。空卷（首次启动）时把镜像自带的种子内容铺进去，
# 已有文件一律不覆盖——用户上传的包和在线改过的内容比镜像里的旧版本更权威。
seed() {
  src="$1"
  dst="$2"
  [ -d "$src" ] || return 0
  mkdir -p "$dst"
  for file in "$src"/*; do
    [ -e "$file" ] || continue
    name=$(basename "$file")
    [ -e "$dst/$name" ] || cp "$file" "$dst/$name"
  done
}

seed /opt/seed/data "$DATA_DIR"
seed /opt/seed/apk "$APK_DIR"

exec "$@"
