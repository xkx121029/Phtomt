package com.phoneagent.feature.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 数字助理角色助手：把本应用设为系统默认助理（ROLE_ASSISTANT）。
 *
 * 经 RoleManager 系统授权框完成（用户确认，不静默改设置）；API 29 起提供，
 * 低版本返回 null 由调用方引导走系统设置。角色查询/请求细节收口在这里，
 * 设置页只调两个方法，不触碰 RoleManager 细节。
 */
object AssistantRole {

    /** RoleManager 自 API 29 起提供；低版本直接走"未持有"分支 */
    private const val ROLE_API = 29

    /** 当前是否已是默认数字助理（API < 29 一律 false，走系统设置手动指定） */
    fun isDefaultAssistant(context: Context): Boolean {
        val rm = if (Build.VERSION.SDK_INT >= ROLE_API) {
            context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        } else {
            null
        }
        return rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }

    /**
     * 发起角色请求（系统授权框）。返回可 launch 的 Intent；本应用已是助理或
     * 系统拒绝授予该角色（机型定制）时返回 null。
     */
    fun requestRole(activity: Activity?): Intent? {
        val rm = if (Build.VERSION.SDK_INT >= ROLE_API) {
            activity?.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        } else {
            null
        }
        val intent = rm
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !it.isRoleHeld(RoleManager.ROLE_ASSISTANT) }
            ?.let { role -> runCatching { role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }.getOrNull() }
        return intent
    }

    /**
     * 兜底跳转：系统授权框拿不到（API < 29 / 机型不支持该角色 / createRequestRoleIntent 抛异常）时，
     * 引导用户到系统「数字助理应用」设置页手动指定；连该页也没有的 ROM 再回退到系统设置首页。
     * 返回是否成功拉起了任意一页。
     */
    fun openAssistantSettings(context: Context): Boolean =
        listOf(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        ).any { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.isSuccess
        }
}
