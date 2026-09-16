package com.phoneagent.feature.test

/**
 * 智能体 AI 标准化测试（Prompt 回归）的数据模型。
 * 参照《HPS ai标准化测试.md》：预设方案 → 用例 → 逐条校验 → 结果汇总。
 *
 * 判分口径对齐「转译层」（[com.phoneagent.engine.execution.IntentTranslator]）的输入契约：
 * AI 只输出 `intent` 意图（禁止旧的 type/action 字段），目标用嵌套 `target:{by,value}` 描述
 * （by = id | text | hint | coordinate，禁止旧的 method 字段），不可逆操作用 `needs_confirmation`。
 */

/** 测试分组（对应标准化文档的 A~E 组） */
enum class TestGroup(val label: String) {
    FORMAT("格式检查"),
    TARGETING("寻址方式"),
    DECISION("决策逻辑"),
    MERGE("动作合并"),
    REGRESSION("回归基准"),
    REAL("真实环境"),
}

/** 测试配置（模型接入） */
data class TestConfig(
    val baseUrl: String = "https://api.agnes-ai.cn/v1",
    val model: String = "agnes-2.5-flash",
    val apiKey: String = "",
)

/** 单条测试用例 */
data class TestCase(
    val id: String,
    val name: String,
    /** 英文场景/user 输入（页面描述 + 任务） */
    val userPrompt: String,
    /** 页面来源：accessibility 或 screenshot */
    val source: String = "accessibility",
    /** 期望的意图（命中任一即通过） */
    val expectedIntent: Set<String> = emptySet(),
    /** 禁止出现的意图 */
    val forbiddenIntent: Set<String> = emptySet(),
    /** 期望 target.by（组B：id / text / hint / coordinate） */
    val expectedBy: String? = null,
    /** 不应使用的 target.by（组B） */
    val forbiddenBy: String? = null,
    /** 动作合并期望：true=必须返回数组，false=禁止返回数组 */
    val expectArray: Boolean? = null,
    /** 是否严格校验统一必填字段（intent / reasoning / expected / confidence），组A 格式检查用 */
    val requireAllFields: Boolean = false,
    /** 是否要求不可逆操作携带 needs_confirmation=true */
    val requireConfirmation: Boolean = false,
    /** 期望 confidence 等附加校验描述（用于展示） */
    val checkHint: String = "",
)

/** 单条用例执行结果 */
data class TestResult(
    val case: TestCase,
    val passed: Boolean,
    val rawOutput: String,
    /** 解析出的意图（数组则取数组第一个） */
    val intentType: String?,
    val errors: List<String>,
    val latencyMs: Long,
)

/** 预设方案：一组用例 */
data class TestPreset(
    val id: String,
    val name: String,
    val group: TestGroup,
    val description: String,
    val cases: List<TestCase>,
)

/** 测试状态 */
enum class TestStatus { IDLE, RUNNING, DONE, ERROR }

/** 一次批量运行的结果汇总 */
data class TestRunSummary(
    val status: TestStatus = TestStatus.IDLE,
    val presetName: String = "",
    val total: Int = 0,
    val passed: Int = 0,
    val results: List<TestResult> = emptyList(),
    val error: String = "",
)