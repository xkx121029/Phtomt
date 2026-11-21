package com.phoneagent.ui.vm

import com.phoneagent.engine.PromptLang
import com.phoneagent.feature.test.TestConfig
import com.phoneagent.feature.test.TestEngine
import com.phoneagent.feature.test.TestPreset
import com.phoneagent.feature.test.TestRunSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AI 标准化测试域：状态与动作全部来自 [TestEngine]，这里只把注入对象和协程作用域收拢在一处。
 *
 * [TestEngine] 本身是既有单例（构造注入），delegate 不新建任何状态，也不改运行语义。
 */
class TestDelegate(
    private val testEngine: TestEngine,
    private val scope: CoroutineScope,
) {

    val testConfig: StateFlow<TestConfig> = testEngine.config
    val testRunning: StateFlow<Boolean> = testEngine.running
    val testSummary: StateFlow<TestRunSummary> = testEngine.summary
    val testStreamText: StateFlow<String> = testEngine.streamText
    val testLanguage: StateFlow<PromptLang> = testEngine.testLanguage
    val testCategory: StateFlow<String?> = testEngine.testCategory

    fun updateTestConfig(baseUrl: String, model: String, apiKey: String) =
        testEngine.updateConfig(baseUrl, model, apiKey)

    fun setTestLanguage(lang: PromptLang) = testEngine.setTestLanguage(lang)
    fun setTestCategory(category: String?) = testEngine.setTestCategory(category)
    fun getLanguageLabel(lang: PromptLang): String = testEngine.getLanguageLabel(lang)

    fun runTest(preset: TestPreset) {
        scope.launch { testEngine.runPreset(preset) }
    }

    fun resetTest() = testEngine.reset()
}
