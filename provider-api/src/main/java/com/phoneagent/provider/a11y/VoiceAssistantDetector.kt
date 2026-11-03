package com.phoneagent.provider.a11y

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * 系统语音助手探测器：在**拉起之前**就回答"本机能不能用、用哪一个"。
 *
 * **为什么放在 provider-api**：这套探测原先在主应用与 Provider 各有一份实现，
 * 两份要分别维护（选包规则、厂商兜底名单、唤醒顺序上限），任何一边改了名单另一边就悄悄落后，
 * 表现为"主应用算出来的助手"和"Provider 真正拉起的助手"不是同一个。
 * 探测结果必须是同一份事实，因此实现只留这里一份，两端各自依赖 provider-api 共用。
 *
 * 正式探测（命中即视为"有可用助理"，按权威度排序，只取第一个）：
 * 1. [android.app.role.RoleManager.ROLE_ASSISTANT] 当前持有者（API 29+，最权威：长按电源键拉的就是它）；
 * 2. `Settings.Secure.assistant`（部分 ROM 在这里登记默认助理组件）；
 * 3. `Settings.Secure.voice_interaction_service`（VoiceInteractionService 组件，部分 ROM 只写这里）；
 * 4. [Intent.ACTION_ASSIST] 的默认处理者；
 * 5. [Intent.ACTION_VOICE_COMMAND] 的处理者。
 *
 * 厂商包名兜底（[VENDOR_ASSISTANTS]）**只在正式探测全部为空**时启用，且不当主路径。
 * 结果带 TTL 缓存（[PROBE_TTL_MS]），避免同一次任务里每步都重扫一遍 PackageManager；
 * 拉起失败时调用 [invalidate] 让下一次重新探测。
 *
 * 挑选逻辑抽成纯函数（[pickHandler] / [vendorFallback] / [orderedTargets]）以便 JVM 单测。
 */
class VoiceAssistantDetector(private val context: Context) {

    /** 助手被拉起/被发现的方式（用于结果描述与通道降级判断） */
    enum class Via(val label: String) {
        /** RoleManager.ROLE_ASSISTANT 持有者 */
        ROLE("系统助理角色"),

        /** Settings.Secure 里登记的默认助理 */
        SETTINGS("系统默认助理"),

        /** Settings.Secure 里登记的 VoiceInteractionService */
        VOICE_INTERACTION("语音交互服务"),

        /** ACTION_ASSIST 的默认处理者 */
        ASSIST("默认助理入口"),

        /** ACTION_VOICE_COMMAND 的处理者 */
        VOICE_COMMAND("语音指令入口"),

        /** 正式探测全空后的厂商包名兜底 */
        VENDOR("厂商助手"),
    }

    /** 探测到的助手目标；packageName 可为空（系统入口未钉包名时交给系统默认） */
    data class Target(val via: Via, val packageName: String?, val activity: String?)

    /** 首选助手目标（= [probeAll] 的第一个；拉起层只认这一个，不再连环试错） */
    fun probe(): Target? = probeAll().firstOrNull()

    /** 全部可用助手入口（按权威度排序，最多 [MAX_WAKE_CANDIDATES] 个）；带 TTL 缓存 */
    fun probeAll(): List<Target> = cache.get()

    /** 拉起失败后调用：下次 [probe]/[probeAll] 重新扫一遍（用户可能刚在系统设置里改了默认助理） */
    fun invalidate() = cache.invalidate()

    /** 真正的探测：正式入口优先，全部为空才落厂商兜底 */
    private fun detect(): List<Target> {
        val pm = context.packageManager
        val formal = buildList {
            roleAssistant()?.let { add(it) }
            persistAssistant()?.let { add(it) }
            voiceInteractionService()?.let { add(it) }
            actionTarget(pm, Intent.ACTION_ASSIST, Via.ASSIST)?.let { add(it) }
            actionTarget(pm, Intent.ACTION_VOICE_COMMAND, Via.VOICE_COMMAND)?.let { add(it) }
        }.distinctBy { it.packageName ?: "system:${it.via}" }

        // 正式探测已经有答案：厂商兜底不再插手（避免"其实有默认助理，却被兜底包名抢答"）
        if (formal.isNotEmpty()) return formal.take(MAX_WAKE_CANDIDATES)

        return VENDOR_ASSISTANTS
            .filter { runCatching { pm.getLaunchIntentForPackage(it) != null }.getOrDefault(false) }
            .map { Target(Via.VENDOR, it, null) }
            .take(MAX_WAKE_CANDIDATES)
    }

    /** RoleManager.ROLE_ASSISTANT 当前持有者（API 29+；读取持有者不需要额外权限） */
    private fun roleAssistant(): Target? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(android.app.role.RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) return null
        if (!rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) return null
        // getRoleHolders 在个别 SDK stub 里不可见：走反射取持有者包名，取不到就交给 Settings 那条
        val holder = runCatching {
            rm.javaClass.getMethod("getRoleHolders", String::class.java)
                .invoke(rm, android.app.role.RoleManager.ROLE_ASSISTANT)
                .let { it as? List<*> }
                ?.firstOrNull() as? String
        }.getOrNull()
        return holder?.let { Target(Via.ROLE, it, null) }
    }

    /**
     * 读系统设置里登记的默认助理（Settings.Secure）。
     * 读操作不需要 WRITE_SECURE_SETTINGS；值为 "pkg/cls" 或 "pkg" 形态。
     * 先看 `assistant`（默认助理），再看 `voice_interaction_service`——部分厂商只写后者。
     */
    private fun persistAssistant(): Target? {
        val assistant = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull()
        componentOf(assistant)?.let { return Target(Via.SETTINGS, it.first, it.second) }

        val vis = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull()
        componentOf(vis)?.let { return Target(Via.VOICE_INTERACTION, it.first, it.second) }
        return null
    }

    /** VoiceInteractionService 的注册者（系统应用优先；只探测，不申请角色） */
    private fun voiceInteractionService(): Target? {
        val resolves = runCatching {
            context.packageManager.queryIntentServices(
                Intent(android.service.voice.VoiceInteractionService.SERVICE_INTERFACE), 0,
            )
        }.getOrDefault(emptyList())
        val candidates = resolves.mapNotNull {
            val pkg = it.serviceInfo?.packageName ?: return@mapNotNull null
            val system = it.serviceInfo?.applicationInfo
                ?.let { ai -> ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true
            Triple(pkg, it.serviceInfo?.name, system)
        }.distinctBy { it.first }
        val picked = pickHandler(candidates.map { it.first to it.third }) ?: return null
        val service = candidates.firstOrNull { it.first == picked }
        return Target(Via.VOICE_INTERACTION, picked, service?.second)
    }

    /** 探测单个 action 入口：返回 Target 或 null（挑不出候选时视为未命中） */
    private fun actionTarget(pm: PackageManager, action: String, via: Via): Target? {
        val resolves = runCatching {
            // MATCH_DEFAULT_ONLY 只留"默认可处理"的 Activity，避免把普通注册者误判成助理入口
            pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        if (resolves.isEmpty()) return null
        val candidates = resolves.mapNotNull {
            val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
            val system = it.activityInfo?.applicationInfo
                ?.let { ai -> ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true
            pkg to system
        }.distinctBy { it.first }
        if (candidates.isEmpty()) return null
        val picked = pickHandler(candidates) ?: return null
        val activity = resolves.firstOrNull { it.activityInfo?.packageName == picked }
            ?.activityInfo?.name
        return Target(via, picked, activity)
    }

    /** "pkg/cls" / "pkg/.Cls" / "pkg" → (包名, 类名或 null；类名相对包名时补全) */
    private fun componentOf(raw: String?): Pair<String, String?>? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val pkg = trimmed.substringBefore('/').trim()
        if (pkg.isEmpty()) return null
        val cls = trimmed.substringAfter('/', "").trim()
        val fullClass = when {
            cls.isEmpty() -> null
            cls.startsWith(".") -> pkg + cls
            else -> cls
        }
        return pkg to fullClass
    }

    companion object {
        /** 探测结果缓存时长：同一次任务内的连续动作不该反复扫 PackageManager */
        const val PROBE_TTL_MS = 60_000L

        /** 唤醒候选上限：入口再多也只串行尝试前几个，失败要快 */
        const val MAX_WAKE_CANDIDATES = 3

        /** 常见厂商语音助手包名（**仅**正式探测全空时的兜底，顺序即优先级） */
        val VENDOR_ASSISTANTS = listOf(
            "com.miui.voiceassist",                     // 小米 小爱同学
            "com.heytap.speechassist",                  // OPPO/一加/真我 小布助手
            "com.vivo.voiceassist",                     // vivo/iQOO Jovi / 小 V
            "com.huawei.vassistant",                    // 华为 小艺
            "com.hihonor.vassistant",                   // 荣耀 YOYO 助理
            "com.google.android.googlequicksearchbox",  // Google 助理
        )

        /**
         * 纯函数：把三类入口排成唤醒顺序并去重（同包名只保留靠前的入口）。
         * 系统入口（语音指令/默认助理）在厂商兜底之前——它们本就是厂商助手的正规入口。
         */
        fun orderedTargets(
            voiceCommand: Target?,
            assist: Target?,
            installedVendors: List<String>,
        ): List<Target> = buildList {
            voiceCommand?.let { add(it) }
            assist?.let { add(it) }
            installedVendors.forEach { pkg -> add(Target(Via.VENDOR, pkg, null)) }
        }.distinctBy { it.packageName }.take(MAX_WAKE_CANDIDATES)

        /** 纯函数：从 action 候选中挑包名（系统应用优先，无系统应用取第一个） */
        fun pickHandler(candidates: List<Pair<String, Boolean>>): String? =
            pickPreferredPackage(candidates)

        /** 纯函数：厂商兜底——按 [VENDOR_ASSISTANTS] 顺序返回第一个已安装的包名 */
        fun vendorFallback(installed: Set<String>): String? =
            VENDOR_ASSISTANTS.firstOrNull { it in installed }
    }

    /** 探测结果 TTL 缓存（时钟可注入，便于单测验证过期与失效） */
    private val cache = ProbeCache(PROBE_TTL_MS, { SystemClock.elapsedRealtime() }) { detect() }

    /**
     * 通用 TTL 缓存：同一时刻只缓存一份结果，过期或 [invalidate] 后重新计算。
     * 放在这里是因为"探测缓存"是助手探测的固有语义，测试需要确定性时钟。
     */
    class ProbeCache<T>(
        private val ttlMs: Long,
        private val clock: () -> Long,
        private val fetch: () -> T,
    ) {
        private var value: T? = null
        private var stamp: Long = 0L
        private var valid: Boolean = false

        fun get(): T {
            if (valid && clock() - stamp < ttlMs) return value as T
            val fresh = fetch()
            value = fresh
            stamp = clock()
            valid = true
            return fresh
        }

        fun invalidate() {
            value = null
            valid = false
            stamp = 0L
        }
    }
}

/**
 * 候选挑选：优先系统自带应用，没有系统应用才取第一个候选。
 *
 * 应用名解析（主应用 AppNameResolver）与助手探测都按这条同一规则选包：
 * 同一台机器上往往同时装着系统应用与第三方同类应用，按安装顺序取第一个会让结果随机。
 * 抽成纯函数是为了可单测——调用方都依赖 PackageManager，无法在纯 JVM 里跑。
 */
fun pickPreferredPackage(candidates: List<Pair<String, Boolean>>): String? =
    candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
