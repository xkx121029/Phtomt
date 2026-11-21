package com.phoneagent.ui.vm

import android.content.Context
import com.phoneagent.feature.skill.Skill
import com.phoneagent.feature.skill.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ACA 技能目录扫描结果的有效期：切 Tab 回来的重复触发走缓存，不重复扫盘 */
private const val ACA_SKILLS_TTL_MS = 60_000L

/**
 * 本次扫描是否应当跳过：正在扫描（重入），或 TTL 内刚扫过且未强制刷新。
 *
 * 抽成纯函数只为能在 JVM 单测里钉住 TTL / 重入两条判定——它不碰 Context、不扫盘。
 * [scannedAtMs] 为 0 表示从未扫过，永远不命中缓存；[force] 为 true 时无视 TTL。
 */
internal fun acaScanSkipped(
    scanning: Boolean,
    nowMs: Long,
    scannedAtMs: Long,
    force: Boolean,
    ttlMs: Long = ACA_SKILLS_TTL_MS,
): Boolean = scanning || (!force && scannedAtMs != 0L && nowMs - scannedAtMs < ttlMs)

/**
 * 技能域：内置技能库与 ACA（编码 agent）技能目录。
 *
 * 协程全部跑在传进来的 [scope]（即 `viewModelScope`），扫描任务的可取消性沿用原实现：
 * [acaScanJob] 仍由本类持有，重入仍被 [_acaScanning] 拦下。
 */
@Suppress("TooManyFunctions")
class SkillDelegate(
    private val skillRegistry: SkillRegistry,
    private val scope: CoroutineScope,
) {

    // ---- Skill 列表快照（UI 观察；每次变更后刷新） ----
    private val _skills = MutableStateFlow<List<Skill>>(skillRegistry.all())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    fun skillAll(): List<Skill> = skillRegistry.all()

    internal fun refreshSkills() { _skills.value = skillRegistry.all() }

    // ---- ACA（编码 agent）可用技能：流式扫描 filesDir/skills，逐条刷入列表 ----
    private val _acaSkills = MutableStateFlow<List<com.phoneagent.coding.skill.AcaSkillInfo>>(emptyList())
    val acaSkills: StateFlow<List<com.phoneagent.coding.skill.AcaSkillInfo>> = _acaSkills.asStateFlow()

    /** 扫描进行中（true 时主页显示"正在扫描"占位） */
    private val _acaScanning = MutableStateFlow(false)
    val acaScanning: StateFlow<Boolean> = _acaScanning.asStateFlow()

    /** 损坏包的「目录名：原因」列表，主页以弱化文本提示 */
    private val _acaSkillErrors = MutableStateFlow<List<String>>(emptyList())
    val acaSkillErrors: StateFlow<List<String>> = _acaSkillErrors.asStateFlow()

    private var acaScanJob: Job? = null

    /** 上次扫描完成的时间；0 表示从未扫过 */
    private var acaScannedAt = 0L

    /**
     * 重新扫描 ACA 技能目录。流式：每发现一个有效包就立刻追加进 [_acaSkills]，
     * 配合主页的级联入场动画形成逐条流出的效果；扫描期间重入直接忽略。
     *
     * 60 秒内已经扫过且没有强制刷新时直接复用上次结果：主页的 LaunchedEffect
     * 每次切回 Tab 都会调用一次，而扫盘是真 IO，没必要为同一份目录反复跑。
     *
     * @param force 目录刚被改动（装上/删掉技能包）时传 true，跳过缓存
     */
    fun refreshAcaSkills(context: Context, force: Boolean = false) {
        if (acaScanSkipped(_acaScanning.value, System.currentTimeMillis(), acaScannedAt, force)) return
        acaScanJob?.cancel()
        acaScanJob = scope.launch {
            _acaScanning.value = true
            try {
                _acaSkills.value = emptyList()
                _acaSkillErrors.value = emptyList()
                // 扫盘在 Default 线程，回调里只做状态追加（StateFlow 写是线程安全的）
                val errors = withContext(Dispatchers.Default) {
                    com.phoneagent.coding.skill.AcaSkillCatalog.streamScan(context.applicationContext) { info ->
                        _acaSkills.value = _acaSkills.value + info
                    }
                }
                _acaSkillErrors.value = errors.map { (dir, err) -> "$dir：$err" }
                acaScannedAt = System.currentTimeMillis()
            } finally {
                // 无论成功、失败还是被取消，都必须复位；否则标志永久为 true，后续扫描会被永久跳过
                _acaScanning.value = false
            }
        }
    }

    /** 在新增时自动分配一个未占用的 id */
    fun nextSkillId(prefix: String = "skill_user"): String {
        var i = 1
        while (skillRegistry.byId("${prefix}_$i") != null) i++
        return "${prefix}_$i"
    }

    fun addSkill(skill: Skill) {
        scope.launch { skillRegistry.add(skill); refreshSkills() }
    }

    fun updateSkill(skill: Skill) {
        scope.launch { skillRegistry.edit(skill); refreshSkills() }
    }

    fun removeSkill(id: String) {
        scope.launch { skillRegistry.remove(id); refreshSkills() }
    }

    /**
     * 批量删除：解析与删除在 Default 线程执行（含持久化 IO），结果回主线程后再刷新快照。
     * @param onResult 主线程回调，参数为成功删除的数量
     */
    fun removeSkills(ids: Set<String>, onResult: (Int) -> Unit = {}) {
        scope.launch {
            val n = withContext(Dispatchers.Default) {
                skillRegistry.removeAll(ids)
            }
            refreshSkills()
            onResult(n)
        }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        scope.launch { skillRegistry.setEnabled(id, enabled); refreshSkills() }
    }

    /**
     * 导入技能清单（JSON 文本）：JSON 解析在 Default 线程执行，结果回主线程刷新快照。
     * @param onResult 主线程回调，参数为导入数量（解析失败按 0 处理，不抛到主线程）
     */
    fun importSkills(text: String, onResult: (Int) -> Unit = {}) {
        scope.launch {
            val imported = withContext(Dispatchers.Default) {
                runCatching { skillRegistry.importJson(text).imported }.getOrDefault(0)
            }
            refreshSkills()
            onResult(imported)
        }
    }

    fun exportSkills(): String = skillRegistry.exportJson()
}
