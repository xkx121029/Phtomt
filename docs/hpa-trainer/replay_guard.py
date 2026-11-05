"""离线回放：把 gen18 真实 episode 的判分输入喂给镜像里的 Evaluator，验证回执守卫可观测。

样本来源：gen18 / alipay.CheckBalance / pv=34，答题卡提交 `2301104.20`，期望 `100023.46`，
模型宣布 COMPLETE，基础判分失败。这里把「基础判分」显式构造为放行，用来观察守卫是否拦下，
并打印 guard 计数在每组用例前后的变化（答题卡层 checks/blocked，消息层 checks_message/blocked_message）。

注意：本脚本只读判分代码、只构造输入，不训练、不改判分行为。
"""

from types import SimpleNamespace

from bench_env.env.base import ActionType
from bench_env.runner.base import Evaluator, guard_stats
from bench_env.task.judge import JudgeResult

EXPECTED = 100023.46
REAL_ANSWER = "2301104.20"     # gen18 真实提交值（模型把读数改写成这个数）
CORRECT_ANSWER = "100023.46"
REWRITTEN_MESSAGE = "理财总资产为2301104.20元，已在答题卡APP中填写答案并成功提交"
CORRECT_MESSAGE = "理财总资产为100023.46元，已在答题卡APP中填写答案并成功提交"
PLAIN_MESSAGE = "已在答题卡APP中填写答案并成功提交，页面显示完成"
UNIT_MESSAGE = "理财总资产为 10.002346万元，已在答题卡APP中填写答案并成功提交"


def run(name: str, answers: dict, submitted: bool = True, base_success: bool = True,
        message: str = "") -> None:
    before = guard_stats()
    ev = Evaluator(judge_mode="state", eval_mode="grounded")
    task = SimpleNamespace(answer_fields=["x"], get_expected_response=lambda ji: [EXPECTED])
    ji = SimpleNamespace(
        apps={"answer_sheet": {"answers": answers, "submitted": submitted}},
        answer=None,
    )
    ex = SimpleNamespace(stop_reason=ActionType.COMPLETE, agent_message=message)
    base = JudgeResult(
        success=base_success,
        clean=True,
        progress=1.0 if base_success else 0.0,
        issues=[],
        warnings=[],
    )
    out = ev._apply_completion_receipt_guard(task, ji, ex, base)
    reasons = [i.get("reason") for i in out.issues]
    after = guard_stats()
    print(
        f"[{name}] 改前(基础判分)=success:{base.success} progress:{base.progress:.2f}"
        f" → 改后(过守卫)=success:{out.success} progress:{out.progress:.3f}"
        f" reasons={reasons}"
        f" | answer checks {before['checks']}->{after['checks']} blocked {before['blocked']}->{after['blocked']}"
        f" | message checks {before['checks_message']}->{after['checks_message']}"
        f" blocked {before['blocked_message']}->{after['blocked_message']}"
    )


print(f"start guard={guard_stats()}")
# ---- 答题卡 / judge_input.answer 层 ----
run("answer_rewritten", {"0": REAL_ANSWER})
run("answer_correct", {"0": CORRECT_ANSWER})
run("no_receipt", {"0": CORRECT_ANSWER}, submitted=False)
# ---- agent_message 层（答题卡都给正确值，只改 message）----
run("msg_rewritten_should_block", {"0": CORRECT_ANSWER}, message=REWRITTEN_MESSAGE)
run("msg_correct_should_pass", {"0": CORRECT_ANSWER}, message=CORRECT_MESSAGE)
run("msg_plain_no_number_should_pass", {"0": CORRECT_ANSWER}, message=PLAIN_MESSAGE)
run("msg_unit_comma_should_pass", {"0": CORRECT_ANSWER}, message=UNIT_MESSAGE)
print(f"end guard={guard_stats()}")
