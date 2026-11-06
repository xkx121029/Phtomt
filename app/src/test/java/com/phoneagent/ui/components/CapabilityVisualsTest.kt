package com.phoneagent.ui.components

import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.domain.model.CapabilityRegistry
import com.phoneagent.domain.model.IntentType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：能力端点的界面身份不许缺。
 *
 * 之前 UI 侧各自维护中文名与图标，新能力（定闹钟、剪贴板、日历…）进来后没跟上，
 * 卡片标题退回「未知操作」且图标是通用机器人。能力注册表 [CapabilityRegistry] 是唯一事实源，
 * 这里遍历它和 [IntentType.ALL]，缺一项就红，不再靠人肉发现。
 */
class CapabilityVisualsTest {

    /** 中文名必须真的含汉字，避免「其他操作」「未知命令」这类兜底混过去 */
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun `能力注册表每一项都有中文名与图标`() {
        val problems = mutableListOf<String>()
        CapabilityRegistry.all.forEach { spec ->
            val name = CapabilityVisuals.nameOf(spec.intent)
            if (name.isBlank() || !cjk.containsMatchIn(name)) {
                problems += "「${spec.intent}」缺少中文名，实际为「$name」"
            }
            if (CapabilityVisuals.iconOrNull(spec.intent) == null) {
                problems += "「${spec.intent}」缺少图标"
            }
            // 动作动词同样不能退回英文意图名
            val verb = HumanTranslator.actionVerb(spec.intent)
            if (!cjk.containsMatchIn(verb)) {
                problems += "「${spec.intent}」的动作动词仍是英文：$verb"
            }
        }
        assertTrue("能力端点的界面身份有缺口：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `IntentType 全集都有中文名与图标`() {
        val problems = mutableListOf<String>()
        IntentType.ALL.forEach { intent ->
            val name = CapabilityVisuals.nameOf(intent)
            if (name.isBlank() || !cjk.containsMatchIn(name)) {
                problems += "「$intent」缺少中文名，实际为「$name」"
            }
            if (CapabilityVisuals.iconOrNull(intent) == null) {
                problems += "「$intent」缺少图标"
            }
        }
        assertTrue("意图全集的界面身份有缺口：\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
