package com.phoneagent.feature.test

/**
 * 智能体 AI 标准化测试（Prompt 回归）的数据模型。
 * 参照《HPS ai标准化测试.md》：预设方案 → 用例 → 逐条校验 → 结果汇总。
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
    /** 期望动作类型（命中任一即通过） */
    val expectedAction: Set<String> = emptySet(),
    /** 禁止出现的动作类型 */
    val forbiddenAction: Set<String> = emptySet(),
    /** 期望 target.method（组B） */
    val expectedMethod: String? = null,
    /** 不应使用的 target.method（组B） */
    val forbiddenMethod: String? = null,
    /** 动作合并期望：true=必须返回数组，false=禁止返回数组 */
    val expectArray: Boolean? = null,
    /** 期望 confidence 等附加校验描述（用于展示） */
    val checkHint: String = "",
)

/** 单条用例执行结果 */
data class TestResult(
    val case: TestCase,
    val passed: Boolean,
    val rawOutput: String,
    /** 解析出的动作类型（数组则取数组第一个） */
    val actionType: String?,
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