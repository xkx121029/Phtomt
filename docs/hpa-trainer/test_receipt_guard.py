from types import SimpleNamespace
from bench_env.runner.base import Evaluator
from bench_env.env.base import ActionType
from bench_env.task.judge import JudgeResult


def case(name, answers, submitted=True, expected=100023.46, answer=None):
    ev = Evaluator(judge_mode="state", eval_mode="grounded")
    task = SimpleNamespace(answer_fields=["x"], get_expected_response=lambda ji: [expected])
    ji = SimpleNamespace(
        apps={"answer_sheet": {"answers": answers, "submitted": submitted}},
        answer=answer,
    )
    ex = SimpleNamespace(stop_reason=ActionType.COMPLETE)
    res = JudgeResult(success=True, clean=True, progress=1.0, issues=[], warnings=[])
    out = ev._apply_completion_receipt_guard(task, ji, ex, res)
    reasons = [i.get("reason") for i in out.issues]
    print(f"[{name}] success={out.success} progress={out.progress:.3f} reasons={reasons}")


case("guard_on_wrong_number", {"0": "21000"})
case("guard_on_truncated_claim", {"0": "100,023"})
case("ok_exact_number", {"0": "100023.46"})
case("ok_with_commas", {"0": "100,023.46"})
case("no_receipt", {"0": "100023.46"}, submitted=False)
case("no_claim", {}, answer=None)
