package com.phoneagent.ui.onboarding

import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind

/**
 * 权限页的口径：分组、必做进度、「受限制的设置」判定。
 *
 * 这三条只在这里定义一次，页面只负责画——之前的分歧全部来自「同一件事有两套判法」：
 * 进度用 `PermissionItem.optional` 算分母（得 6），分组标题用 [PermissionGroups] 说「必做只有一项」，
 * 行内标签又用 `optional` 判「可选」，于是「推荐」组里出现了「可选」标签。
 *
 * 1. 「必做」= [RequiredKinds]，进度分子分母都只数这些（方案 A：分母越小越可信）；
 * 2. 非必做组统一「按需开启」，不给分母，行标签与组标题同权重，见 [permissionPillText]；
 * 3. Android 13+ 侧载应用的无障碍开关会被系统灰置，判定见 [a11yMayBeRestricted]。
 */
internal data class PermissionGroup(
    val title: String,
    val hint: String,
    val required: Boolean,
    val kinds: List<PermissionKind>,
)

/** 权限分组：必做先出现，其余按「影响面由大到小」排列 */
internal val PermissionGroups = listOf(
    PermissionGroup(
        title = "必做",
        hint = "不开启就只能聊天，不能替你操作手机",
        required = true,
        kinds = listOf(PermissionKind.ACCESSIBILITY),
    ),
    PermissionGroup(
        title = "推荐",
        hint = "影响任务能不能找到并启动目标应用",
        required = false,
        kinds = listOf(
            PermissionKind.NOTIFICATIONS,
            PermissionKind.USAGE_ACCESS,
            PermissionKind.INSTALL_PACKAGES,
        ),
    ),
    PermissionGroup(
        title = "可选",
        hint = "体验增强：进度浮窗、设备设置调整、后台更稳",
        required = false,
        kinds = listOf(
            PermissionKind.OVERLAY,
            PermissionKind.WRITE_SETTINGS,
            PermissionKind.AUTOSTART,
            PermissionKind.ALL_FILES,
            PermissionKind.DND_ACCESS,
            PermissionKind.MICROPHONE,
        ),
    ),
)

/** 「必做」组里的权限项：进度只数这些 */
internal val RequiredKinds: Set<PermissionKind> = PermissionGroups
    .filter { it.required }
    .flatMap { it.kinds }
    .toSet()

/** 必做就绪统计：分子分母都只数 [RequiredKinds]，页面上报「已就绪 1 / 1」 */
internal data class RequiredProgress(val granted: Int, val total: Int) {
    val allGranted: Boolean get() = total > 0 && granted == total
}

internal fun requiredProgress(permissions: List<PermissionItem>): RequiredProgress {
    val required = permissions.filter { it.kind in RequiredKinds }
    return RequiredProgress(granted = required.count { it.granted }, total = required.size)
}

/**
 * 权限行右侧标签。
 *
 * 权重必须与所在分组一致：必做未开是「去授权」，非必做未开一律「按需开启」——
 * 不能因为 `PermissionItem.optional` 为 false 就把「推荐」组里的项标成必做。
 */
internal fun permissionPillText(granted: Boolean, required: Boolean): String = when {
    granted -> "完成"
    required -> "去授权"
    else -> "按需开启"
}

/** 已知能免除「受限制的设置」的安装来源：Play 装的包系统不会灰置无障碍开关 */
private val TrustedInstallers = setOf("com.android.vending")

/**
 * Android 13 起系统才提供「受限制的设置」授权入口；更早的版本没有这道门，不需要提示。
 */
private const val MIN_SDK_FOR_RESTRICTED_SETTINGS = 33

/**
 * 无障碍开关是否可能被系统灰置。
 *
 * Android 13 起，从浏览器/文件管理器等来源侧载安装的应用，其无障碍开关默认灰置，
 * 需要用户在「应用信息 → 右上角菜单 → 允许受限制的设置」里解除。系统没有公开 API
 * 能读到「开关是否灰」，这里用四个可观测量近似，宁可多提示：本站目标用户正是侧载安装。
 *
 * @param installingPackage 服务组件的安装来源包名；null 表示读不到（侧载/未知来源）
 * @param providerInstalled 服务组件是否已安装——没装时无障碍列表里根本没有条目，
 *   那是「先去装组件」的另一条路，不该报受限
 * @param accessibilityGranted 无障碍是否已按引擎口径可用
 */
internal fun a11yMayBeRestricted(
    sdkInt: Int,
    installingPackage: String?,
    providerInstalled: Boolean,
    accessibilityGranted: Boolean,
): Boolean = sdkInt >= MIN_SDK_FOR_RESTRICTED_SETTINGS &&
    providerInstalled &&
    !accessibilityGranted &&
    installingPackage !in TrustedInstallers
