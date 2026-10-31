package com.phoneagent.provider.a11y

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 系统语音助手探测器：为 assistant 意图找到本机可拉起的语音助手。
 *
 * **为什么放在 provider-api**：这套探测原先在主应用与 Provider 各有一份实现，
 * 两份要分别维护（选包规则、厂商兜底名单、唤醒顺序上限），任何一边改了名单另一边就悄悄落后，
 * 表现为"主应用算出来的助手"和"Provider 真正拉起的助手"不是同一个。
 * 探测结果必须是同一份事实，因此实现只留这里一份，两端各自依赖 provider-api 共用。
 *
 * 探测顺序（命中即返回）：
 * 1. [Intent.ACTION_VOICE_COMMAND]（语音指令入口，绝大多数厂商助手都会注册）
 * 2. [Intent.ACTION_ASSIST]（默认助理入口）
 * 3. 常见厂商助手包名兜底（小爱/小布/Jovi/小艺/荣耀 YOYO/Google 助理）
 *
 * 挑选逻辑抽成纯函数（[pickHandler] / [vendorFallback]）以便 JVM 单测。
 */
class VoiceAssistantDetector(private val context: Context) {

    /** 助手被拉起的途径（用于结果描述与通道降级判断） */
    enum class Via(val label: String) {
        VOICE_COMMAND("语音指令"),
        ASSIST("默认助理"),
        VENDOR("厂商助手"),
    }

    /** 探测到的助手目标；packageName 可为空（系统入口未钉包名时交给系统默认） */
    data class Target(val via: Via, val packageName: String?, val activity: String?)

    /** 首选助手目标（= [probeAll] 的第一个；供只需单一目标的调用方使用） */
    fun probe(): Target? = probeAll().firstOrNull()

    /**
     * 全部可用助手入口（按唤醒尝试顺序）：语音指令入口 → 默认助理入口 → 已安装的厂商助手。
     * 同包名去重（系统入口命中的往往就是厂商助手本体），上限 [MAX_WAKE_CANDIDATES] 个，
     * 避免多厂商兜底把串行唤醒拖成慢链。
     */
    fun probeAll(): List<Target> {
        val pm = context.packageManager
        // 系统设置里的「默认助理」是最权威的依据：写死的 component 不用猜，排最前
        val configured = configuredAssistant()
        return orderedTargets(
            voiceCommand = probeAction(pm, Intent.ACTION_VOICE_COMMAND)
                ?.let { (pkg, activity) -> Target(Via.VOICE_COMMAND, pkg, activity) },
            assist = probeAction(pm, Intent.ACTION_ASSIST)
                ?.let { (pkg, activity) -> Target(Via.ASSIST, pkg, activity) },
            installedVendors = VENDOR_ASSISTANTS.filter { pm.getLaunchIntentForPackage(it) != null },
        ).let { list ->
            if (configured == null) list else {
                // 已配置的默认助理去重后置顶；它就是系统长按电源键真正会拉起的那个
                (listOf(Target(Via.ASSIST, configured, null)) + list).distinctBy { it.packageName }
                    .take(MAX_WAKE_CANDIDATES)
            }
        }
    }

    /**
     * 读系统设置里登记的默认助理组件（Settings.Secure）。
     * 读操作不需要 WRITE_SECURE_SETTINGS；值为 "pkg/cls" 或 "pkg" 形态，取包名部分。
     * assistant（默认助理）与 voice_interaction_service（语音交互服务）都查——
     * 部分厂商只写后者。
     */
    private fun configuredAssistant(): String? {
        val raw = android.provider.Settings.Secure.getString(context.contentResolver, "assistant")
            ?: android.provider.Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            ?: return null
        val pkg = raw.substringBefore('/').trim()
        return pkg.ifEmpty { null }
    }

    /** 探测单个 action 入口：返回 (包名, activity) 或 null（挑不出系统应用优先的候选时视为未命中） */
    private fun probeAction(pm: PackageManager, action: String): Pair<String?, String?>? {
        val resolves = runCatching {
            pm.queryIntentActivities(Intent(action), 0)
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
        return picked to activity
    }

    companion object {
        /** 唤醒候选上限：入口再多也只串行尝试前几个，失败要快 */
        const val MAX_WAKE_CANDIDATES = 3

        /** 常见厂商语音助手包名（兜底顺序即优先级，用户已确认以系统探测为主） */
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
