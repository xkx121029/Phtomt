#!/bin/sh
# 单测「真的跑了」兜底：测试任务 NO-SOURCE / 空跑时 exit code 仍是 0，
# CI 看起来全绿，实际一条用例都没执行。本脚本按模块核对结果 XML 与用例数。
#
# 为什么需要它（不是理论风险）：
#   - :core:log 的 4 条测试一开始留在 :app 里，:core:log:testDebugUnitTest 是 NO-SOURCE，
#     那段时间 CI 对「日志门面有测试」这件事一无所知；
#   - :core:perf 第一版同样是 0 条（后来补了 4 条才有）。
# NO-SOURCE 的判定发生在任务执行之前，没有任何报告产物、也没有任何非零退出码，
# 唯一能观察到它的东西就是「结果目录里没有 XML」。
#
# 用法（顺序不能换，否则会被上一次的旧 XML 骗成绿的）：
#   sh tool/check-test-results.sh --clean   # 跑 gradle 测试任务之前清结果
#   ./gradlew ...:testDebugUnitTest ...
#   sh tool/check-test-results.sh           # 断言每个模块都产出了用例
#
# --clean 是必需的：Test 任务 UP-TO-DATE 或 NO-SOURCE 时不会碰结果目录，
# 上一次的 XML 会原地留着，光数文件就成了「假绿」。先清再跑，缺产物才等于没执行。
#
# 下限口径：**等于当前实际用例数**，与 detekt baseline「只减不增」同一套思路——
# 有意删测试时请同步改这里的数字，让减少这件事被迫被人看见一次。
#
# 新建模块时必须在这里加一行（module|测试任务|用例下限），
# 任务名按插件区分：kotlin("jvm") 模块是 test，com.android.library 是 testDebugUnitTest。
# 用 `./gradlew :<模块>:tasks --dry-run` 核对，写成不存在的任务会在配置期就失败。

set -eu

cd "$(git rev-parse --show-toplevel)"

# module|test task|min test cases
EXPECTED='
app|testDebugUnitTest|1499
core/text|test|42
core/log|testDebugUnitTest|4
domain|test|50
core/security|testDebugUnitTest|20
core/perf|testDebugUnitTest|4
data|testDebugUnitTest|75
ui/design|testDebugUnitTest|14
'

MODE=${1:-check}

# 统计一个结果目录里的用例总数（累加每个 <testsuite tests="N">）。
# 目录不存在或没有 XML 时输出空串，交给调用方区分「目录缺」和「0 条用例」。
count_cases() {
    dir=$1
    [ -d "$dir" ] || { printf ''; return 0; }
    set -- "$dir"/*.xml
    # 没有匹配时 glob 原样返回，用它判断「一个 XML 都没有」
    [ -f "$1" ] || { printf ''; return 0; }
    grep -ho 'tests="[0-9]*"' "$dir"/*.xml \
        | sed 's/[^0-9]//g' \
        | awk '{ n += $1 } END { printf "%d", n }'
}

fail=0
printf '%-16s %-22s %10s %10s\n' MODULE TASK CASES MIN

for row in $EXPECTED; do
    module=$(printf '%s' "$row" | cut -d'|' -f1)
    task=$(printf '%s' "$row" | cut -d'|' -f2)
    min=$(printf '%s' "$row" | cut -d'|' -f3)
    dir="$module/build/test-results/$task"

    if [ "$MODE" = "--clean" ]; then
        # 只清结果目录本身，不动 build/ 下别的东西（并发构建时别把别人正在用的产物删了）
        rm -rf "$dir"
        printf '%-16s %-22s %10s\n' "$module" "$task" cleaned
        continue
    fi

    cases=$(count_cases "$dir")
    printf '%-16s %-22s %10s %10s\n' "$module" "$task" "${cases:-0}" "$min"

    if [ -z "$cases" ]; then
        echo "::error title=模块单测没跑::$module:$task 没有产出结果 XML（$dir）。" \
            "任务大概率是 NO-SOURCE——0 条测试、退出码 0，CI 会静默绿。"
        fail=1
        continue
    fi

    if [ "$cases" -lt "$min" ]; then
        echo "::error title=模块单测变少::$module:$task 只执行了 $cases 条用例，下限是 $min。" \
            "如果是有意删测试，请同步改 tool/check-test-results.sh 里的数字。"
        fail=1
    fi
done

if [ "$MODE" = "--clean" ]; then
    echo "已清空上述模块的 test-results（接下来跑 gradle 测试任务）"
    exit 0
fi

if [ "$fail" -ne 0 ]; then
    echo "单测兜底失败：有模块没在执行用例，或用例数掉到下限以下。"
    exit 1
fi

echo "单测兜底通过：每个模块都产出了结果，且用例数不低于下限。"
