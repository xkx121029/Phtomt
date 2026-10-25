package com.phoneagent.coding.skill

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/** 一条 ACA（编码 agent）可用技能的展示信息 */
data class AcaSkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val tools: List<String>,
)

/**
 * ACA 技能目录：与 [com.phoneagent.coding.agent.LocalAgent] 注入的 HAPPY_ACA_SKILLS_DIR
 * 指向同一根目录。skill.json 是唯一契约（见 agent-host/skills.js loadSkillManifest），
 * 主页只做展示，不必为列表启动 node 子进程——直接扫盘即可，字段与 host 侧一一对应。
 */
object AcaSkillCatalog {

    /** skill 包根目录：LocalAgent 注入 env 时也用这一份路径，两边永不失配 */
    fun skillsDir(context: Context): File = File(context.filesDir, "skills")

    /**
     * 流式扫描：每发现一个有效 skill 包就回调一次 [onItem]（两次回调间隔
     * [STREAM_STEP_MS]，配合主页的级联入场形成逐条流出的观感）。
     * 返回损坏包的错误列表（目录名 → 原因），坏包只跳过不阻断。
     */
    suspend fun streamScan(
        context: Context,
        onItem: suspend (AcaSkillInfo) -> Unit,
    ): List<Pair<String, String>> {
        val errors = mutableListOf<Pair<String, String>>()
        val dirs = skillsDir(context).listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return errors
        for (dir in dirs) {
            val info = parseManifest(dir)
            if (info == null) {
                errors += dir.name to (manifestError(dir) ?: "skill.json 缺失")
            } else {
                onItem(info)
                delay(STREAM_STEP_MS)
            }
        }
        return errors
    }

    // ---------- 内部：解析 ----------

    /** 解析单个 skill 包目录；结构无效返回 null（原因见 [manifestError]） */
    private fun parseManifest(dir: File): AcaSkillInfo? {
        val manifest = runCatching { JSONObject(dir.resolve("skill.json").readText()) }.getOrNull() ?: return null
        val id = manifest.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val tools = manifest.optJSONArray("tools")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } ?: emptyList()
        return AcaSkillInfo(
            id = id,
            name = manifest.optString("name", id),
            description = manifest.optString("description"),
            version = manifest.optString("version", "0.0.0"),
            tools = tools,
        )
    }

    /** 复读一次 skill.json 的解析异常文本，给 UI 一条看得懂的失败原因 */
    private fun manifestError(dir: File): String? {
        val f = dir.resolve("skill.json")
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }
            .exceptionOrNull()?.message?.let { "skill.json 无效：$it" }
    }

    private const val STREAM_STEP_MS = 120L
}
