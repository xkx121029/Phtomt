#!/bin/sh
# 两份模块清单的机器强制一致 + 「新模块必须有测试兜底」的自动发现。
#
# 为什么需要它（不是理论风险）：
#   - 单测任务串写在 .github/workflows/android-ci.yml 的 "Unit tests + coverage gates" 里，
#     用例下限写在 tool/check-test-results.sh 的 EXPECTED 表里，两份都是手写清单，
#     全靠人记着同时改。
#   - 今天真发生过一次：新模块 :ui:design 加进去了，两处都改对纯属运气。
#   - 更早发生过两次模块没进 workflow、测试任务 NO-SOURCE 空跑而 CI 全绿
#     （:core:log 的 4 条测试、:core:perf 第一版 0 条）。
#
# 本脚本做三件事，任一不满足都 exit 1：
#   a. workflow 里的测试任务 与 EXPECTED 表逐条比对 —— 多一条少一条都红；
#   b. settings.gradle.kts 里 include 了、且有 src/test 目录，却不在清单里 —— 红
#      （这类模块的测试在 CI 上永远不会执行，而且不会报错）；
#   c. 清单里有、settings.gradle.kts 里已经没有的模块 —— 红（删模块后的残留）。
#
# 只用 sh + sed/awk/grep，不引依赖。CI 里放在 "Assert unit tests actually ran" 之前跑：
# 先证明清单一致，再看用例数——清单不一致时那一步的数字没有意义。
#
# 本地提交前：
#   sh tool/check-module-lists.sh

set -eu

cd "$(git rev-parse --show-toplevel)"

WORKFLOW=.github/workflows/android-ci.yml
SETTINGS=settings.gradle.kts
RESULTS_SCRIPT=tool/check-test-results.sh
STEP_NAME='Unit tests + coverage gates'

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

fail=0
# GitHub Actions 的注解标题固定为「模块清单不一致」，本地跑时这行就是普通文本。
error() {
    echo "::error title=模块清单不一致::$1"
    fail=1
}

# ---------------------------------------------------------------------------
# 1. settings.gradle.kts：模块路径 ↔ 目录
#    projectDir 覆盖必须认：:core:text 的目录是 core/text，映射不是简单去掉冒号。
# ---------------------------------------------------------------------------
sed -E 's/^[[:space:]]*project\("([^"]+)"\)\.projectDir[[:space:]]*=[[:space:]]*file\("([^"]+)"\).*/DIR\t\1\t\2/' \
    "$SETTINGS" > "$TMP/settings.norm"

awk '
    {
        line = $0
        sub(/\/\/.*/, "", line)
        if (line ~ /^DIR\t/) {
            split(line, a, "\t")
            dir[a[2]] = a[3]
            next
        }
        if (line ~ /^[[:space:]]*include\(/) {
            while (match(line, /"[^"]+"/)) {
                m = substr(line, RSTART + 1, RLENGTH - 2)
                line = substr(line, RSTART + RLENGTH)
                if (!(m in seen)) {
                    seen[m] = 1
                    order[++n] = m
                }
            }
        }
    }
    END {
        for (i = 1; i <= n; i++) {
            m = order[i]
            d = (m in dir) ? dir[m] : substr(m, 2)
            gsub(/:/, "/", d)
            print m "\t" d
        }
    }
' "$TMP/settings.norm" > "$TMP/modules.txt"

if [ ! -s "$TMP/modules.txt" ]; then
    error "没能从 $SETTINGS 里抽到任何 include(...)，脚本已失效，请先修解析。"
    echo "模块清单校验失败。"
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. EXPECTED 表 → `:模块路径|测试任务`
#    表里写的是目录形式（core/text），按 settings 的映射换成模块路径再比。
# ---------------------------------------------------------------------------
sed -n "/^EXPECTED='$/,/^'$/p" "$RESULTS_SCRIPT" \
    | grep -E '^[^#|]+\|[^|]+\|[0-9]+$' > "$TMP/table.rows"

if [ ! -s "$TMP/table.rows" ]; then
    error "没能从 $RESULTS_SCRIPT 里读到 EXPECTED 表，脚本已失效，请先修解析。"
    echo "模块清单校验失败。"
    exit 1
fi

: > "$TMP/expected.pairs"
: > "$TMP/table.unknown.txt"
while IFS='|' read -r table_dir task _min; do
    mod=$(awk -F'\t' -v d="$table_dir" '$2 == d { print $1; exit }' "$TMP/modules.txt")
    if [ -z "$mod" ]; then
        # 模块已从 settings 删掉：照原样（目录形式转回模块形式）记进比对集合，
        # 免得 workflow 那边把它重复报成「跑了但表里没有」——同一个根因只报一次。
        printf '%s|%s\n' "$table_dir" "$task" >> "$TMP/table.unknown.txt"
        printf ':%s|%s\n' "$(printf '%s' "$table_dir" | tr '/' ':')" "$task" >> "$TMP/expected.pairs"
        continue
    fi
    printf '%s|%s\n' "$mod" "$task" >> "$TMP/expected.pairs"
done < "$TMP/table.rows"
sort -u -o "$TMP/expected.pairs" "$TMP/expected.pairs"

if [ -s "$TMP/table.unknown.txt" ]; then
    while IFS='|' read -r table_dir task; do
        error "EXPECTED 表里的模块 $table_dir 在 $SETTINGS 里已经不存在（任务 $task）。
           删模块时请同步删掉 $RESULTS_SCRIPT 里的这一行。"
    done < "$TMP/table.unknown.txt"
fi

# ---------------------------------------------------------------------------
# 3. workflow 单测任务串 → `:模块路径|测试任务`
#    只取任务名为 test / testXxxUnitTest 的 token：同一步里还有覆盖率任务
#    （:app:checkPureLogicCoverage 等），它们不属于这份清单。
# ---------------------------------------------------------------------------
awk -v want="$STEP_NAME" '
    /^[[:space:]]*- name:/ { in_step = (index($0, want) > 0); in_run = 0; next }
    in_step && /^[[:space:]]*run:[[:space:]]*\|[[:space:]]*$/ { in_run = 1; next }
    in_run && /^[[:space:]]*$/ { next }
    in_run && /^ {8}[^ ]/ { in_run = 0; next }
    in_run { print }
' "$WORKFLOW" > "$TMP/step-run.txt"

if [ ! -s "$TMP/step-run.txt" ]; then
    error "在 $WORKFLOW 里找不到步骤「$STEP_NAME」的 run: 块，脚本已失效，请先修解析。"
    echo "模块清单校验失败。"
    exit 1
fi

grep -oE ':[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)+' "$TMP/step-run.txt" \
    | grep -E ':(test|test[A-Za-z]*UnitTest)$' \
    | awk -F: '{
        task = $NF
        mod = $0
        sub(/:[^:]*$/, "", mod)
        print mod "|" task
    }' \
    | sort -u > "$TMP/workflow.pairs"

# 一个测试任务都没抽到，多半是 workflow 写法变了（任务串挪进了变量之类），
# 这时「两边都空」会给出假绿，所以单独判一次。
if [ ! -s "$TMP/workflow.pairs" ]; then
    error "在步骤「$STEP_NAME」里没抽到任何 :<模块>:test* 任务，脚本已失效，请先修解析。"
fi

# a1. 表里有、workflow 没跑 —— 该模块的测试根本不会在 CI 上执行
comm -23 "$TMP/expected.pairs" "$TMP/workflow.pairs" > "$TMP/missing.in.workflow"
# a2. workflow 跑了、表里没记 —— 兜底断言不看它，空跑也发现不了
comm -13 "$TMP/expected.pairs" "$TMP/workflow.pairs" > "$TMP/missing.in.table"

while IFS='|' read -r mod task; do
    error "模块 $mod 的测试任务 $task 在 EXPECTED 表里有，但 $WORKFLOW 的「$STEP_NAME」里没跑。
           请把它加进那一步的 ./gradlew 任务串。"
done < "$TMP/missing.in.workflow"

while IFS='|' read -r mod task; do
    dir=$(awk -F'\t' -v m="$mod" '$1 == m { print $2; exit }' "$TMP/modules.txt")
    error "模块 $mod（目录 ${dir:-?}）的测试任务 $task 在 $WORKFLOW 里跑了，
           但 $RESULTS_SCRIPT 的 EXPECTED 表里没有它，兜底断言不会核对它的用例数（空跑也照样绿）。
           请补一行：${dir:-?}|$task|用例下限"
done < "$TMP/missing.in.table"

# ---------------------------------------------------------------------------
# 4. 自动发现：settings 里的模块有 src/test 却不在清单里
#    这类模块的测试永远不会在 CI 上跑，而且「什么都没执行」不会有任何非零退出码。
#    判据取 src/test 目录本身：一个空的 test 源集正是「看起来有测试」的那种，
#    留在清单外就是下一次 NO-SOURCE 假绿。
# ---------------------------------------------------------------------------
sed 's/|.*$//' "$TMP/expected.pairs" | sort -u > "$TMP/expected.mods"

while IFS='	' read -r mod dir; do
    [ -d "$dir/src/test" ] || continue
    grep -Fxq "$mod" "$TMP/expected.mods" && continue
    error "模块 $mod（$dir）有 src/test 目录，但没有进 CI 单测清单，它的测试在 CI 上永远不会执行。
           请把该模块的测试任务加进 $WORKFLOW 的「$STEP_NAME」，
           并在 $RESULTS_SCRIPT 的 EXPECTED 表补一行：$dir|测试任务|用例下限"
done < "$TMP/modules.txt"

# ---------------------------------------------------------------------------
# 5. 汇总
# ---------------------------------------------------------------------------
total=$(wc -l < "$TMP/modules.txt" | tr -d ' ')
listed=$(wc -l < "$TMP/expected.mods" | tr -d ' ')
tested=$(wc -l < "$TMP/workflow.pairs" | tr -d ' ')

if [ "$fail" -ne 0 ]; then
    echo "模块清单校验失败：workflow / EXPECTED 表 / settings.gradle.kts 三者不一致，或新模块缺测试兜底。"
    exit 1
fi

echo "模块清单校验通过：$tested 个测试任务、$listed 个模块全部对齐（settings 里共 $total 个模块）。"
echo "  1. workflow「$STEP_NAME」的测试任务 == $RESULTS_SCRIPT 的 EXPECTED 表（逐条一致）"
echo "  2. settings.gradle.kts 里带 src/test 的模块，全部在清单内"
echo "  3. 清单里的模块，settings.gradle.kts 里全都还在"
