package com.phoneagent.engine

import com.phoneagent.engine.execution.ActionMode
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词等价性金样本：把重构前 [AgentPrompts] 全部公开入口的输出按"节"冻结成 SHA-256 指纹。
 *
 * 本类同时是"导出工具"与"等价性断言"：金样本由此前版本导出后提交进仓库，
 * 之后每次重构都必须让它继续通过。
 *
 * 用途：动态提示词系统把大段正文拆成区块后，用同一份指纹逐节比对，
 * 证明"拆分没改动任何文案"（阶段 1 的安全网）。指纹相等即逐字节相等。
 *
 * 金样本路径：`app/src/test/resources/prompt_golden/snapshot.sha256`（提交进仓库）
 */
class PromptSnapshotTest {

    /**
     * 等价性断言：当前 [AgentPrompts] 每一节的输出指纹必须与金样本完全一致。
     * 任何一处文案被改写、增删空行、或占位符渲染失败，都会在这里以"节名"直接指出。
     */
    @Test
    fun `拆分后提示词与金样本逐字节一致`() {
        val golden = File(repoRoot(), "app/src/test/resources/prompt_golden/snapshot.sha256")
        assertTrue("金样本缺失：${golden.absolutePath}", golden.isFile)

        val expected = golden.readLines().filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split('\t')
                parts[0] to (parts[1] to parts[2].toInt())
            }
        val actual = PromptSnapshot.fingerprints().lines().filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split('\t')
                parts[0] to (parts[1] to parts[2].toInt())
            }

        assertEquals("节的集合发生变化", expected.keys, actual.keys)
        val changed = expected.keys.filter { expected[it] != actual[it] }
        assertTrue(
            "以下提示词节的输出与重构前不一致：" +
                changed.joinToString("、") { name ->
                    val e = expected.getValue(name)
                    val a = actual.getValue(name)
                    "$name(字符数 ${e.second}→${a.second})"
                },
            changed.isEmpty(),
        )
    }

    /** 定位仓库根：从工作目录向上找到含 `app/src/main/java/com/phoneagent` 的目录 */
    internal companion object {
        fun repoRoot(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "app/src/main/java/com/phoneagent").isDirectory) return dir
                dir = dir.parentFile
            }
            error("未找到仓库根目录（工作目录=${File("").absolutePath}）")
        }

        fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 金样本组装器：把所有组合按固定节名逐节取指纹。
 *
 * 只调用 [AgentPrompts] 的公开函数；重构后签名不变，因此同一份代码在重构前后
 * 必须产出逐字节相同的文本（指纹相等）。
 */
object PromptSnapshot {

    /** 返回形如 `节名\tsha256\t字符数` 的清单，按节名排序 */
    fun fingerprints(): String {
        val map = LinkedHashMap<String, String>()
        collect(map)
        return map.entries
            .sortedBy { it.key }
            .joinToString("\n") { (name, body) ->
                "$name\t${PromptSnapshotTest.sha256(body)}\t${body.length}"
            } + "\n"
    }

    private fun collect(map: MutableMap<String, String>) {
        PromptLang.entries.forEach { lang ->
            listOf(false, true).forEach { vision ->
                listOf(false, true).forEach { shizuku ->
                    ActionMode.entries.forEach { mode ->
                        map["system/${lang}/${vision}/${shizuku}/${mode}"] =
                            AgentPrompts.system(lang, "", vision, shizuku, actionMode = mode)
                    }
                }
                map["capabilities/${lang}/${vision}"] = AgentPrompts.capabilitiesLang(lang, vision)
            }
            ActionMode.entries.forEach { mode ->
                map["actionMode/${lang}/${mode}"] = AgentPrompts.actionModeSection(lang, mode)
            }
            listOf(false, true).forEach { hasServer ->
                listOf(emptyList(), listOf("filesystem_read | 读文件 | 读 | path")).forEach { mcp ->
                    listOf(emptyList(), listOf("打开应用", "点一下")).forEach { disabled ->
                        map["skills/${lang}/${hasServer}/${mcp.size}/${disabled.size}"] =
                            AgentPrompts.skillSection(lang, mcp, disabled, hasServer)
                    }
                }
            }
            map["systemWithSkills/${lang}"] = AgentPrompts.system(
                lang, "", hasVision = true, shizukuAvailable = true,
                skills = AgentPrompts.skillSection(lang, emptyList(), emptyList(), false),
            )
            map["systemCustom/${lang}"] =
                AgentPrompts.system(lang, "自定义系统提示", hasVision = false, shizukuAvailable = false)

            map["planning/${lang}"] =
                AgentPrompts.planning(lang, "帮我在美团点一份黄焖鸡米饭", "用户常在美团点餐", "美团、微信、支付宝")
            map["planningEmptyProfile/${lang}"] = AgentPrompts.planning(lang, "查一下明天天气", "", "")
            map["batchPlanning/${lang}"] =
                AgentPrompts.batchPlanning(lang, "打开微信给张三发消息，然后查一下明天天气", "微信、天气")
            map["replan/${lang}"] =
                AgentPrompts.replan(lang, "帮我在美团点一份黄焖鸡米饭", "找不到搜索框", "1. 打开美团 ✅\n2. 点击搜索 ❌")

            map["decision/${lang}"] = AgentPrompts.decision(
                lang, "帮我在美团点一份黄焖鸡米饭", 2, 5, "在搜索框输入关键词",
                "已打开美团首页", 0, "美团首页",
            )
            map["decisionWithMemory/${lang}"] = AgentPrompts.decision(
                lang, "帮我在美团点一份黄焖鸡米饭", 1, 5, "打开美团",
                "", 2, "桌面", memory = "- 用户常在美团点黄焖鸡米饭\n- 用户偏好少辣",
            )

            map["review/${lang}"] = AgentPrompts.reviewSystem(lang)
            map["verify/${lang}"] = AgentPrompts.verify(lang, "点击搜索框", "键盘弹出")
            map["memoryDistill/${lang}"] =
                AgentPrompts.memoryDistill(lang, "帮我在美团点一份黄焖鸡米饭", "已完成", "打开美团 → 搜索 → 下单")
            map["memoryDistillEmptySteps/${lang}"] = AgentPrompts.memoryDistill(lang, "打个招呼", "已完成", "")
            map["takeoverRecovery/${lang}"] =
                AgentPrompts.takeoverRecovery(lang, "帮我在美团点一份黄焖鸡米饭", "点击搜索", "1. 打开美团\n2. 点击搜索")
            map["takeoverRecoveryNoPlan/${lang}"] =
                AgentPrompts.takeoverRecovery(lang, "帮我在美团点一份黄焖鸡米饭", "点击搜索", "")
            map["userGuidance/${lang}"] =
                AgentPrompts.userGuidance(lang, "先点右上角的三条杠", "帮我在美团点一份黄焖鸡米饭", "点击搜索", "上一步失败")
            map["userGuidanceNoFailure/${lang}"] =
                AgentPrompts.userGuidance(lang, "先点右上角的三条杠", "帮我在美团点一份黄焖鸡米饭", "点击搜索", "")

            map["environment/${lang}"] = AgentPrompts.environment(
                lang,
                EnvFacts(
                    dateTime = "2026-09-20 周六 15:04",
                    network = "Wi-Fi",
                    battery = "62%（充电中）",
                    foreground = "微信(com.tencent.mm)",
                    installedCount = 87,
                ),
            )
            map["environmentSparse/${lang}"] =
                AgentPrompts.environment(lang, EnvFacts(dateTime = "2026-09-20 周六 15:04"))

            listOf(false, true).forEach { followUp ->
                listOf(
                    emptyList(),
                    listOf(
                        PreviousTask("帮我在美团点一份黄焖鸡米饭", "已完成", "已下单一份黄焖鸡米饭"),
                        PreviousTask("查一下明天天气", "已完成"),
                    ),
                ).forEachIndexed { i, previous ->
                    map["sessionContext/${lang}/${i}/${followUp}"] =
                        AgentPrompts.sessionContext(lang, previous, followUp)
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
                    map["situational/${lang}/${termux}/${i}"] =
                        AgentPrompts.situationalExtras(lang, task, termux)
                }
            }
        }
    }
}