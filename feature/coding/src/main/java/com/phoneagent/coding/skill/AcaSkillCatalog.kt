package com.phoneagent.coding.skill

import android.content.Context
import android.util.Log
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
     * 把随包 skill（assets/skills/<name>）落到 [skillsDir]。
     *
     * 随包资产是只读的，host 侧只认磁盘目录，所以首次启动必须复制一份工作副本。
     * 复制以 skill.json 的 version 为准：安装目录里的 `.bundled-version` 记录上次落盘版本，
     * 版本相同就跳过（不覆盖用户改动，也不每次启动重写一遍），随包升级时才重新落盘。
     * 整个流程失败只记日志，不阻断 host 启动。
     */
    fun ensureBundled(context: Context) {
        val bundled = runCatching {
            context.assets.list(BUNDLED_ROOT).orEmpty()
        }.getOrDefault(emptyArray())
        bundled.forEach { name ->
            runCatching { installBundled(context, File(skillsDir(context), name), "$BUNDLED_ROOT/$name") }
                .onFailure { Log.w(TAG, "随包 skill 落盘失败：$name（${it.message}）") }
        }
    }

    /**
     * 流式扫描：每发现一个有效 skill 包就回调一次 [onItem]（两次回调间隔
     * [STREAM_STEP_MS]，配合主页的级联入场形成逐条流出的观感）。
     * 返回损坏包的错误列表（目录名 → 原因），坏包只跳过不阻断。
     */
    suspend fun streamScan(
        context: Context,
        onItem: suspend (AcaSkillInfo) -> Unit,
    ): List<Pair<String, String>> {
        // 列表要看到随包 skill：先落盘再扫描（版本没变时这一步只是读一个标记文件）
        ensureBundled(context)
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

    /** 单个随包 skill：版本未变则跳过，变了就整包覆盖并写新标记 */
    private fun installBundled(context: Context, target: File, assetPath: String) {
        val version = runCatching {
            context.assets.open("$assetPath/skill.json").bufferedReader().use { it.readText() }
                .let { JSONObject(it).optString("version", DEFAULT_VERSION) }
        }.getOrDefault(DEFAULT_VERSION)
        val marker = File(target, MARKER_FILE)
        if (marker.isFile && marker.readText().trim() == version) return
        copyAssetTree(context, assetPath, target)
        marker.writeText(version)
    }

    /**
     * 递归拷贝 asset 目录。`assets.list()` 对目录返回子项、对文件抛异常，
     * 所以用「列不出来就是文件」判断叶子节点，不需要额外的扩展名规则。
     */
    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = runCatching { context.assets.list(assetPath).orEmpty() }.getOrDefault(emptyArray())
        if (children.isNotEmpty()) {
            target.mkdirs()
            children.forEach { child -> copyAssetTree(context, "$assetPath/$child", File(target, child)) }
            return
        }
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private const val STREAM_STEP_MS = 120L
    private const val TAG = "AcaSkillCatalog"

    /** 随包 skill 的资产目录（app/src/main/assets/skills） */
    private const val BUNDLED_ROOT = "skills"

    /** 落盘版本标记文件名：与 skill.json 平级，不进 tools 目录 */
    private const val MARKER_FILE = ".bundled-version"
    private const val DEFAULT_VERSION = "0.0.0"
}
