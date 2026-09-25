package com.phoneagent.engine

import com.phoneagent.engine.execution.ActionMode
import java.io.File
import org.junit.Test

/**
 * 提示词快照：把重构前 [AgentPrompts] 全部公开入口的输出冻结成金样本文件。
 *
 * 用途：动态提示词系统把大段正文拆成区块后，用同一份金样本逐字节比对，
 * 证明"拆分没改动任何文案"（阶段 1 的安全网）。
 *
 * 生成方式：`.\gradlew.bat :app:testDebugUnitTest --tests "*PromptSnapshotTest*"`
 * 产出路径：`app/src/test/resources/prompt_golden/snapshot.txt`（提交进仓库）
 */
class PromptSnapshotTest {

    @Test
    fun `导出提示词金样本`() {
        val target = File(repoRoot(), "app/src/test/resources/prompt_golden/snapshot.txt")
        target.parentFile?.mkdirs()
        target.writeText(PromptSnapshot.compose())
        println("prompt golden written: ${target.absolutePath} (${target.length()} bytes)")
    }

    /** 定位仓库根：从工作目录向上找到同时含 `app/src/main/java/com/phoneagent` 的目录 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/phoneagent").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未找到仓库根目录（工作目录=${File("").absolutePath}）")
    }
}

/**
 * 金样本组装器：所有组合用固定标记分隔，保证可复现。
 *
 * 只调用 [AgentPrompts] 的公开函数，重构后签名不变，因此同一份代码在重构前后
 * 必须产出逐字节相同的文本。
 */
object PromptSnapshot {

    private const val SEP = "\n=====@=====\n"

    fun compose(): String {
        val sb = StringBuilder()
        PromptLang.entries.forEach { lang ->
            listOf(false, true).forEach { vision ->
                listOf(false, true).forEach { shizuku ->
                    ActionMode.entries.forEach { mode ->
                        section(sb, "system/${lang}/${vision}/${shizuku}/${mode}") {
                            AgentPrompts.system(lang, "", vision, shizuku, actionMode = mode)
                        }
                    }
                }
                section(sb, "capabilities/${lang}/${vision}") {
                    AgentPrompts.capabilitiesLang(lang, vision)
                }
            }
            ActionMode.entries.forEach { mode ->
                section(sb, "actionMode/${lang}/${mode}") {
                    AgentPrompts.actionModeSection(lang, mode)
                }
            }
            listOf(false, true).forEach { hasServer ->
                listOf(emptyList(), listOf("filesystem_read | 读文件 | 读 | path")).forEach { mcp ->
                    listOf(emptyList(), listOf("打开应用", "点一下")).forEach { disabled ->
                        section(sb, "skills/${lang}/${hasServer}/${mcp.size}/${disabled.size}") {
                            AgentPrompts.skillSection(lang, mcp, disabled, hasServer)
                        }
                    }
                }
            }
            section(sb, "systemWithSkills/${lang}") {
                AgentPrompts.system(
                    lang, "", hasVision = true, shizukuAvailable = true,
                    skills = AgentPrompts.skillSection(lang, emptyList(), emptyList(), false),
                )
            }
            section(sb, "systemCustom/${lang}") {
                AgentPrompts.system(lang, "自定义系统提示", hasVision = false, shizukuAvailable = false)
            }

            section(sb, "planning/${lang}") {
                AgentPrompts.planning(lang, "帮我在美团点一份黄焖鸡米饭", "用户常在美团点餐", "美团、微信、支付宝")
            }
            section(sb, "planningEmptyProfile/${lang}") {
                AgentPrompts.planning(lang, "查一下明天天气", "", "")
            }
            section(sb, "batchPlanning/${lang}") {
                AgentPrompts.batchPlanning(lang, "打开微信给张三发消息，然后查一下明天天气", "微信、天气")
            }
            section(sb, "replan/${lang}") {
                AgentPrompts.replan(lang, "帮我在美团点一份黄焖鸡米饭", "找不到搜索框", "1. 打开美团 ✅\n2. 点击搜索 ❌")
            }

            section(sb, "decision/${lang}") {
                AgentPrompts.decision(
                    lang, "帮我在美团点一份黄焖鸡米饭", 2, 5, "在搜索框输入关键词",
                    "已打开美团首页", 0, "美团首页",
                )
            }
            section(sb, "decisionWithMemory/${lang}") {
                AgentPrompts.decision(
                    lang, "帮我在美团点一份黄焖鸡米饭", 1, 5, "打开美团",
                    "", 2, "桌面", memory = "- 用户常在美团点黄焖鸡米饭\n- 用户偏好少辣",
                )
            }

            section(sb, "review/${lang}") { AgentPrompts.reviewSystem(lang) }
            section(sb, "verify/${lang}") {
                AgentPrompts.verify(lang, "点击搜索框", "键盘弹出")
            }
            section(sb, "memoryDistill/${lang}") {
                AgentPrompts.memoryDistill(lang, "帮我在美团点一份黄焖鸡米饭", "已完成", "打开美团 → 搜索 → 下单")
            }
            section(sb, "memoryDistillEmptySteps/${lang}") {
                AgentPrompts.memoryDistill(lang, "打个招呼", "已完成", "")
            }
            section(sb, "takeoverRecovery/${lang}") {
                AgentPrompts.takeoverRecovery(lang, "帮我在美团点一份黄焖鸡米饭", "点击搜索", "1. 打开美团\n2. 点击搜索")
            }
            section(sb, "takeoverRecoveryNoPlan/${lang}") {
                AgentPrompts.takeoverRecovery(lang, "帮我在美团点一份黄焖鸡米饭", "点击搜索", "")
            }
            section(sb, "userGuidance/${lang}") {
                AgentPrompts.userGuidance(lang, "先点右上角的三条杠", "帮我在美团点一份黄焖鸡米饭", "点击搜索", "上一步失败")
            }
            section(sb, "userGuidanceNoFailure/${lang}") {
                AgentPrompts.userGuidance(lang, "先点右上角的三条杠", "帮我在美团点一份黄焖鸡米饭", "点击搜索", "")
            }

            section(sb, "environment/${lang}") {
                AgentPrompts.environment(
                    lang,
                    EnvFacts(
                        dateTime = "2026-09-20 周六 15:04",
                        network = "Wi-Fi",
                        battery = "62%（充电中）",
                        foreground = "微信(com.tencent.mm)",
                        installedCount = 87,
                    ),
                )
            }
            section(sb, "environmentSparse/${lang}") {
                AgentPrompts.environment(lang, EnvFacts(dateTime = "2026-09-20 周六 15:04"))
            }
            listOf(false, true).forEach { followUp ->
                listOf(
                    emptyList(),
                    listOf(
                        PreviousTask("帮我在美团点一份黄焖鸡米饭", "已完成", "已下单一份黄焖鸡米饭"),
                        PreviousTask("查一下明天天气", "已完成"),
                    ),
                ).forEachIndexed { i, previous ->
                    section(sb, "sessionContext/${lang}/${i}/${followUp}") {
                        AgentPrompts.sessionContext(lang, previous, followUp)
                    }
                }
            }

            listOf(false, true).forEach { termux ->
                listOf(
                    "帮我写一份周报",
                    "打开这个文件 /sdcard/Download/季度汇报.ppt",
                    "查一下今天的汇率",
                    "打开美团 App",
                    "帮我把这段话记下来",
                ).forEachIndexed { i, task ->
                    section(sb, "situational/${lang}/${termux}/${i}") {
                        AgentPrompts.situationalExtras(lang, task, termux)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun section(sb: StringBuilder, name: String, body: () -> String) {
        sb.append("----- ").append(name).append(SEP).append(body()).append(SEP)
    }
}