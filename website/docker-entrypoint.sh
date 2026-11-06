#!/bin/sh
set -e

# 数据目录通常挂着卷。空卷（首次启动）时把镜像自带的种子内容铺进去，
# 已有文件一律不覆盖——在线改过的内容比镜像里的旧版本更权威。
# 安装包不在这个流程里：它们存放在 GitHub / Gitee 的 Release 上。
seed() {
  src="$1"
  dst="$2"
  [ -d "$src" ] || return 0
  mkdir -p "$dst"
  for file in "$src"/*; do
    [ -e "$file" ] || continue
    name=$(basename "$file")
    # -r：种子里有子目录（如 agent-docs），普通 cp 遇到目录会报错并让 set -e 直接退出
    [ -e "$dst/$name" ] || cp -r "$file" "$dst/$name"
  done
}

seed /opt/seed/data "$DATA_DIR"

exec "$@"
