package com.phoneagent.ui.onboarding

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success

/**
 * 引导第 4 步：开权限。
 *
 * 与旧实现的差别：
 * - **分组**：必做 / 推荐 / 可选。原先 6 项平铺，用户不知道先点哪个；
 *   现在一眼看到「只要开一项就能跑起来」，其余按需。
 * - **不再每秒轮询**。改成页面首次进入刷一次 + 回到前台（ON_RESUME）刷一次——
 *   用户去系统设置授权完回来，状态照样当场点亮，但不会在后台一直空转。
 * - **进度可读**：顶部胶囊直接报「已就绪 n / m」，必做项单独标注未完成。
 */
@Composable
internal fun OnboardingPermissionPage(vm: MainViewModel, onNext: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissions by vm.permissions.collectAsState()

    LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grantedCount = permissions.count { it.granted }
    val allGranted = permissions.isNotEmpty() && grantedCount == permissions.size
    // 无障碍没开且系统是 Android 13+：很可能撞上「受限制的设置」拦路
    val a11yBlocked = Build.VERSION.SDK_INT >= 33 &&
        permissions.any { it.kind == PermissionKind.ACCESSIBILITY && !it.granted }

    OnboardingPage(
        content = {
            OnboardingPageHeader(
                icon = AppIcons.Guard,
                title = "开启所需权限",
                body = "只有「无障碍」是必须的——它让我能看屏幕、能点按。其余按需开启，随时可以回来补。",
            )
            Spacer(Modifier.height(AppSpacing.Lg))
            // 首帧权限列表还没读出来时不显示「0 / 0」，避免闪一下空进度再跳成真实值
            if (permissions.isNotEmpty()) {
                ReadyBadge(grantedCount, permissions.size, allGranted)
                Spacer(Modifier.height(AppSpacing.Lg))
            }
            PermissionGroups.forEach { group ->
                PermissionGroupBlock(
                    group = group,
                    items = permissions.filter { it.kind in group.kinds },
                    onOpen = { kind -> vm.openPermissionSettings(context, kind) },
                )
            }
            // Android 13+ 侧载应用的无障碍开关会被系统标成「受限制的设置」，
            // 点开只有一句「App was denied access」和一个 Close。提前把解法写在原地。
            if (a11yBlocked) RestrictedSettingHint(context)
        },
        footer = {
            PrimaryOnboardingButton(
                label = if (allGranted) "全部就绪，下一步" else "下一步",
                onClick = onNext,
            )
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                "权限随时可以在「设置 → 权限与执行通道」里调整",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 一组权限：小标题 + 该组条目；组内没有条目时整块不渲染 */
@Composable
private fun PermissionGroupBlock(
    group: PermissionGroup,
    items: List<PermissionItem>,
    onOpen: (PermissionKind) -> Unit,
) {
    if (items.isEmpty()) return
    GroupHeader(group.title, group.hint, group.required)
    Spacer(Modifier.height(AppSpacing.Sm))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { p ->
            OnboardingPermissionRow(
                title = p.title,
                description = p.description,
                icon = permissionIcon(p.kind),
                granted = p.granted,
                onClick = { onOpen(p.kind) },
            )
        }
    }
    Spacer(Modifier.height(AppSpacing.Lg))
}

/** 权限分组：必做先出现，其余按「影响面由大到小」排列 */
private data class PermissionGroup(
    val title: String,
    val hint: String,
    val required: Boolean,
    val kinds: List<PermissionKind>,
)

private val PermissionGroups = listOf(
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
        kinds = listOf(PermissionKind.QUERY_ALL_PACKAGES, PermissionKind.NOTIFICATIONS),
    ),
    PermissionGroup(
        title = "可选",
        hint = "体验增强：进度浮窗、设备设置调整、后台更稳",
        required = false,
        kinds = listOf(PermissionKind.OVERLAY, PermissionKind.WRITE_SETTINGS, PermissionKind.AUTOSTART),
    ),
)

/** 分组小标题：必做用主色，其余用次要色，避免三组看起来一样重 */
@Composable
private fun GroupHeader(title: String, hint: String, required: Boolean) {
    val color = if (required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Android 13+ 侧载应用的无障碍开关会被系统标记为「受限制的设置」：
 * 点进去只显示 `App was denied access` 与一个 Close 按钮，开关不可点。
 * 解法在服务组件的应用信息页右上角菜单里，离线用户很难自己找到，这里直接写出来并给跳转。
 */
@Composable
private fun RestrictedSettingHint(context: android.content.Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
            .padding(AppSpacing.Md),
    ) {
        Text(
            "开启时如果看到「App was denied access」：这是 Android 13 起对侧载应用的限制。" +
                "打开服务组件的应用信息，右上角菜单选「允许受限制的设置」，再回来开启即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        TextButton(
            onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ProviderManager.PACKAGE}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        ) { Text("打开服务组件应用信息") }
    }
}

/** 权限行：与「概览」页权限雷达同一套视觉语言，点按跳转系统授权页 */
@Composable
internal fun OnboardingPermissionRow(
    title: String,
    description: String,
    icon: ImageVector,
    granted: Boolean,
    onClick: () -> Unit,
) {
    val color = if (granted) Success else MaterialTheme.colorScheme.primary
    PressableScale(onClick = onClick) {
        AppItemCard(
            containerColor = if (granted) {
                Success.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            },
        ) {
            AppIconTile(
                icon = icon,
                tint = color,
                background = color.copy(alpha = 0.16f),
                tileSize = 44.dp,
                iconSize = 22.dp,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (granted) "完成" else "去授权",
                color = color,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
    }
}

/** 权限条目图标：与「概览」页权限雷达共用同一套映射，两处看到的是同一个图标 */
private fun permissionIcon(kind: PermissionKind): ImageVector = when (kind) {
    PermissionKind.ACCESSIBILITY -> AppIcons.TouchApp
    PermissionKind.OVERLAY -> AppIcons.Camera
    PermissionKind.WRITE_SETTINGS -> AppIcons.Tune
    PermissionKind.AUTOSTART -> AppIcons.Bolt
    PermissionKind.QUERY_ALL_PACKAGES -> AppIcons.Memory
    PermissionKind.NOTIFICATIONS -> AppIcons.Notifications
}
