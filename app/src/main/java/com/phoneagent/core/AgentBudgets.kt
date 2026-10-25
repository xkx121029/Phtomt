package com.phoneagent.core

/**
 * AI 上下文回注预算（全项目唯一数值定义点）：
 * 各工具链路（网页正文 Markdown / 内置浏览器读取 / shell 输出 / 无线 ADB shell 输出）
 * 回注给 AI 的统一字符上限，超出按各自策略截断。
 *
 * 各链路以语义别名引用此值（`HtmlToMarkdown.BUDGET`、`BrowserBridge.MAX_RESULT_CHARS`、
 * `ShellRules.SHELL_OUTPUT_BUDGET`），
 * 调整预算只改 [REINJECT_MAX_CHARS] 这一处，杜绝多定义点漂移。
 * 放在 core 层：它是四条链路共同的最底层依赖，反向引用任何上层都会破坏分层。
 */
object AgentBudgets {
    /** 工具输出回注给 AI 的统一字符上限 */
    const val REINJECT_MAX_CHARS = 4000
}
