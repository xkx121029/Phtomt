#!/bin/sh
# 代码规模统计：文档里出现的「文件数 / 行数 / 单测数」一律以本脚本输出为准。
#
# 口径写死在这里，避免每篇文档各算一套（历史上 335 / 346 / 356 / 560 几个数并存，
# 差距全在「算不算 third_party、算不算未跟踪文件、含不含测试」）。
#
#   - 只统计 git **已跟踪**的 .kt：构建产物、node_modules、未跟踪的临时文件天然不计
#   - app_*  ：只算主应用（app/src/main、app/src/test）。写「app/src/main/java/com/phoneagent/」
#              这类具体路径的文档用这一组
#   - main_* / test_*：五个自研模块（app、provider、provider-api 的 src/main 与 src/test）。
#              写「主代码 / 单测」总量、不点名某个模块的文档用这一组
#   - repo_* ：全仓 tracked .kt，含 env、env-api 与 third_party/ 下 vendor 的 Shizuku 九个模块
#   - 不含 app/src/main/cpp（C/C++ 源码，不是 Kotlin）
#   - 行数 = 换行符个数（wc -l）：末行没有换行的文件会少算 1 行
#   - *_tests = `@Test` 出现次数，带多行参数的写法只算一次
#
# 用法（仓库根目录或任意子目录均可）：
#   sh tool/count-code.sh
# 输出（每行「键 + 制表符 + 数」，便于人读也便于脚本取）：
#   app_main_files  app_main_lines  app_test_files  app_test_lines  app_test_tests
#   main_files  main_lines  test_files  test_lines  test_tests
#   repo_files  repo_lines

set -eu

cd "$(git rev-parse --show-toplevel)"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

git ls-files -z | tr '\0' '\n' \
    | grep '\.kt$' | grep -v '^app/src/main/cpp/' | sort -u > "$tmp/all.txt"

select_scope() {
    grep -E "^($1)/" "$tmp/all.txt" || true
}

select_scope 'app/src/main' > "$tmp/app_main.txt"
select_scope 'app/src/test' > "$tmp/app_test.txt"
select_scope 'app/src/main|provider/src/main|provider-api/src/main' > "$tmp/main.txt"
select_scope 'app/src/test|provider/src/test|provider-api/src/test' > "$tmp/test.txt"

# wc/grep 一次批处理整批文件；wc 每批多打一行 total，用 $2 != "total" 滤掉
count_lines() {
    tr '\n' '\0' < "$1" | xargs -0 -n 100 wc -l | awk '$2 != "total" { s += $1 } END { print s + 0 }'
}

count_tests() {
    tr '\n' '\0' < "$1" | xargs -0 -n 100 grep -ho '@Test' | wc -l | tr -d ' '
}

emit() {
    printf '%s_files\t%s\n' "$1" "$(grep -c . "$2" || true)"
    printf '%s_lines\t%s\n' "$1" "$(count_lines "$2")"
}

emit app_main "$tmp/app_main.txt"
emit app_test "$tmp/app_test.txt"
printf 'app_test_tests\t%s\n' "$(count_tests "$tmp/app_test.txt")"
emit main "$tmp/main.txt"
emit test "$tmp/test.txt"
printf 'test_tests\t%s\n' "$(count_tests "$tmp/test.txt")"
emit repo "$tmp/all.txt"
