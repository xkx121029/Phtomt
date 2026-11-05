"""容器内冒烟：验证归因分类与 snapshot 新字段。"""

from hpa_training.config import RuntimeState, classify_error

cases = [
    "429 Too Many Requests",
    "TimeoutError: llm call failed",
    "judge_error: bad json",
    "TypeError: boom",
    None,
]
print("classify:", [classify_error(c) for c in cases])

s = RuntimeState()
s.record_episode("429 rate limit")
s.record_episode("TimeoutError")
s.record_episode(None)
snap = s.snapshot()
print("state:", {k: snap[k] for k in ("error_kind", "quota_limited", "error_rate_recent", "recent_episodes", "guard")})
