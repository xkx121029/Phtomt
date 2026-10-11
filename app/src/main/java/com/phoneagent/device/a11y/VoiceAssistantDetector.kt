package com.phoneagent.device.a11y

import android.content.Context
import android.content.Intent
import com.phoneagent.engine.execution.pickPreferredPackage

/**
 * 系统语音助手探测器：为 assistant 意图找到本机可拉起的语音助手。
 *
 * 探测顺序（命中即返回）：
 * 1. [Intent.ACTION_VOICE_COMMAND]（语音指令入口，绝大多数厂商助手都会注册）
 * 2. [Intent.ACTION_ASSIST]（默认助理入口）
 * 3. 常见厂商助手包名兜底（小爱/小布/Jovi/小艺/荣耀 YOYO/Google 助理）
 *
 * 探测挑选逻辑抽成纯函数（[pickHandler] / [vendorFallback]）以便 JVM 单测，
 * 与 OpenTarget.kt 的做法一致。
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

    fun probe(): Target? {
        val pm = context.packageManager
        // 1) 语音指令入口
        probeAction(pm, Intent.ACTION_VOICE_COMMAND)?.let { (pkg, activity) ->
            return Target(Via.VOICE_COMMAND, pkg, activity)
        }
        // 2) 默认助理入口
        probeAction(pm, Intent.ACTION_ASSIST)?.let { (pkg, activity) ->
            return Target(Via.ASSIST, pkg, activity)
        }
        // 3) 厂商助手包名兜底
        val installed = VENDOR_ASSISTANTS.filter { pm.getLaunchIntentForPackage(it) != null }.toSet()
        vendorFallback(installed)?.let { return Target(Via.VENDOR, it, null) }
        return null
    }

    /** 探测单个 action 入口：返回 (包名, activity) 或 null（挑不出系统应用优先的候选时视为未命中） */
    private fun probeAction(pm: android.content.pm.PackageManager, action: String): Pair<String?, String?>? {
        val resolves = runCatching {
            pm.queryIntentActivities(Intent(action), 0)
        }.getOrDefault(emptyList())
        if (resolves.isEmpty()) return null
        val candidates = resolves.mapNotNull {
            val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
            val system = it.activityInfo?.applicationInfo
                ?.let { ai -> ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 } == true
            pkg to system
        }.distinctBy { it.first }
        if (candidates.isEmpty()) return null
        val picked = pickHandler(candidates) ?: return null
        val activity = resolves.firstOrNull { it.activityInfo?.packageName == picked }
            ?.activityInfo?.name
        return picked to activity
    }

    companion object {
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
         * 纯函数：从 action 候选中挑包名（系统应用优先，无系统应用取第一个）。
         * 直接复用 AppNameResolver 的 [pickPreferredPackage] 语义，保证两处行为一致。
         */
        internal fun pickHandler(candidates: List<Pair<String, Boolean>>): String? =
            pickPreferredPackage(candidates)

        /** 纯函数：厂商兜底——按 [VENDOR_ASSISTANTS] 顺序返回第一个已安装的包名 */
        internal fun vendorFallback(installed: Set<String>): String? =
            VENDOR_ASSISTANTS.firstOrNull { it in installed }
    }
}
