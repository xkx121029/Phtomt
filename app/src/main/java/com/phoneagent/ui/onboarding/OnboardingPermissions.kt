package com.phoneagent.ui.onboarding

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
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

    // 可选权限（如使用情况访问权）默认不开启，不计入进度：
    // 分母只数「必做」组（[RequiredKinds]），与顶部文案、分组标题同一口径
    val progress = requiredProgress(permissions)
    // 无障碍没开且具备受限条件（Android 13+ 侧载安装的组件）：开关多半是灰的，点不动
    val a11yBlocked = remember(permissions) {
        permissions.isNotEmpty() && a11yMayBeRestricted(
            sdkInt = Build.VERSION.SDK_INT,
            installingPackage = ProviderManager.installingPackageOf(context, ProviderManager.PACKAGE),
            providerInstalled = ProviderManager.isInstalled(context),
            accessibilityGranted = permissions.any {
                it.kind == PermissionKind.ACCESSIBILITY && it.granted
            },
        )
    }
    // 提示卡钉在页面顶部而不是列表末尾：用户从系统页返回时滚动位置在哪都能一眼看到
    val blocker: (@Composable ColumnScope.() -> Unit)? = if (a11yBlocked) {
        { RestrictedSettingBanner(onOpenAppInfo = { openProviderAppInfo(context) }) }
    } else {
        null
    }

    OnboardingPage(
        banner = blocker,
        content = {
            OnboardingPageHeader(
                icon = AppIcons.Guard,
                title = "开启所需权限",
                body = "必做只有「无障碍」——它让我能看屏幕、能点按。其余按需开启，随时可以回来补。",
            )
            Spacer(Modifier.height(AppSpacing.Lg))
            // 首帧权限列表还没读出来时不显示「0 / 0」，避免闪一下空进度再跳成真实值
            if (progress.total > 0) {
                ReadyBadge(progress.granted, progress.total, progress.allGranted)
                Spacer(Modifier.height(AppSpacing.Lg))
            }
            PermissionGroups.forEach { group ->
                PermissionGroupBlock(
                    group = group,
                    items = permissions.filter { it.kind in group.kinds },
                    onOpen = { kind -> vm.openPermissionSettings(context, kind) },
                )
                // 解法就地展开在「必做」下面：原先放在所有分组之后，用户滚不到也不会滚
                if (group.required && a11yBlocked) {
                    RestrictedSettingSteps(onOpenAppInfo = { openProviderAppInfo(context) })
                }
            }
        },
        footer = {
            PrimaryOnboardingButton(
                label = if (progress.allGranted) "无障碍已就绪，下一步" else "下一步",
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
    GroupHeader(group)
    Spacer(Modifier.height(AppSpacing.Sm))
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Md)) {
        items.forEach { p ->
            OnboardingPermissionRow(
                title = p.title,
                description = p.description,
                icon = permissionIcon(p.kind),
                granted = p.granted,
                onClick = { onOpen(p.kind) },
                required = group.required,
            )
        }
    }
    Spacer(Modifier.height(AppSpacing.Lg))
}

/**
 * 分组小标题：必做用主色，其余用次要色；非必做组右侧标「按需开启」而不是给分母，
 * 这样「已就绪 1 / 1」这个分母不会和列表里的项数打架。
 */
@Composable
private fun GroupHeader(group: PermissionGroup) {
    val required = group.required
    val color = if (required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        Text(
            group.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            group.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!required) {
            StatusPill(
                text = "按需开启",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 打开服务组件的系统「应用信息」页：「允许受限制的设置」就在这一页的右上角菜单里 */
private fun openProviderAppInfo(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:${ProviderManager.PACKAGE}"),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * 阻断式提示卡：钉在权限页顶部。
 *
 * 用户从系统无障碍页返回时往往停在列表中间，原先放在列表末尾的说明等于不存在——
 * 所以这张卡跟着页面顶部走，只报「为什么会这样 + 去哪儿点」，步骤写在无障碍行下方。
 */
@Composable
private fun RestrictedSettingBanner(onOpenAppInfo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    ) {
        Text(
            "无障碍开关是灰的、点不动？",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(AppSpacing.Xxs))
        Text(
            "这是 Android 13 起对侧载应用的限制，不是应用坏了。按下方「必做」里的三步解除即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton(onClick = onOpenAppInfo, modifier = Modifier.padding(top = AppSpacing.Xs)) {
            Text("打开服务组件应用信息")
        }
    }
}

/**
 * 「受限制的设置」三步解法：就地展开在「必做」组下方，而不是藏在所有分组之后。
 * 系统页是英文时用户看到的是 `Allow restricted settings`，两条文案都写上。
 */
@Composable
private fun RestrictedSettingSteps(onOpenAppInfo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
            .padding(AppSpacing.Md),
    ) {
        Text(
            "开关灰置时的三步解法",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        listOf(
            "1. 点下面的按钮，打开服务组件的「应用信息」页",
            "2. 右上角菜单 ⋮ →「允许受限制的设置」（英文为 Allow restricted settings）",
            "3. 回到本页 → 无障碍 → 开启「Happy Agent 自动操作」；返回本页会自动刷新状态",
        ).forEach { step ->
            Text(
                step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(AppSpacing.Xs))
        }
        TextButton(onClick = onOpenAppInfo, modifier = Modifier.padding(top = AppSpacing.Xs)) {
            Text("打开服务组件应用信息")
        }
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
    required: Boolean = false,
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
                modifier = Modifier.padding(start = AppSpacing.Md, top = AppSpacing.Md, bottom = AppSpacing.Md),
            )
            Spacer(Modifier.width(AppSpacing.Md))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = AppSpacing.Md),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = permissionPillText(granted = granted, required = required),
                color = color,
                modifier = Modifier.padding(end = AppSpacing.Md),
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
    PermissionKind.NOTIFICATIONS -> AppIcons.Notifications
    PermissionKind.USAGE_ACCESS -> AppIcons.Insights
    PermissionKind.INSTALL_PACKAGES -> AppIcons.FileDownload
    PermissionKind.ALL_FILES -> AppIcons.Folder
    PermissionKind.DND_ACCESS -> AppIcons.NotifOff
    PermissionKind.MICROPHONE -> AppIcons.Mic
}
