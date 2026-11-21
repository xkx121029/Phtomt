package com.phoneagent.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success

/**
 * 引导第 3 步：安装服务组件。
 *
 * 为什么要在向导里做：无障碍、内置 Shizuku、Termux 三条执行通道都跑在独立应用
 * `com.phoneagent.provider` 里。组件没装时，系统无障碍列表里根本没有可开启的条目，
 * 用户到了下一步点「去授权」只会跳进一个空列表——这是旧向导最容易卡死的地方。
 *
 * 安装要用户在系统安装器里确认，回来必须重读状态，因此挂在 ON_RESUME 上刷新。
 * 允许跳过：不装组件也能用对话能力，只是不能替你操作手机。
 */
@Composable
internal fun OnboardingPreparePage(onNext: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var installedVersion by remember { mutableStateOf(ProviderManager.installedVersionName(context)) }
    var bundledVersion by remember { mutableStateOf(ProviderManager.bundledVersion(context)) }
    var needsInstall by remember { mutableStateOf(ProviderManager.needsInstall(context)) }
    var installError by remember { mutableStateOf<String?>(null) }
    // 点过安装、但被「允许安装未知应用」拦住：返回本页时自动续装，不再要求用户点第二次
    var awaitingInstallPermission by remember { mutableStateOf(false) }
    // 允许后返回仍未授权：按钮改成「继续安装」，说清还差这一步
    var installPermissionMissing by remember { mutableStateOf(false) }

    // 从系统安装器返回时窗口回前台，立刻重读版本，装完这一步就地变成「已就绪」
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installedVersion = ProviderManager.installedVersionName(context)
                bundledVersion = ProviderManager.bundledVersion(context)
                needsInstall = ProviderManager.needsInstall(context)
                installPermissionMissing = false
                if (awaitingInstallPermission) {
                    awaitingInstallPermission = false
                    if (ProviderManager.canInstallPackages(context)) {
                        launchInstall(context) { installError = it }
                    } else {
                        installPermissionMissing = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingPage(
        content = {
            OnboardingPageHeader(
                icon = AppIcons.Guard,
                title = "先装一个服务组件",
                body = "替我操作手机的能力跑在独立组件里：装上之后，即使主程序更新或重启，授权也不会掉。",
            )
            Spacer(Modifier.height(AppSpacing.Lg))
            ProviderStatusCard(
                installedVersion = installedVersion,
                bundledVersion = bundledVersion,
                needsInstall = needsInstall,
            )
            if (needsInstall) {
                Spacer(Modifier.height(AppSpacing.Md))
                InstallPermissionNotice(continuing = installPermissionMissing)
            }
            installError?.let { message ->
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        },
        footer = {
            PrepareFooter(
                needsInstall = needsInstall,
                installed = installedVersion != null,
                continuing = installPermissionMissing,
                onInstall = {
                    awaitingInstallPermission = true
                    installError = null
                    launchInstall(context) {
                        installError = it
                        awaitingInstallPermission = false
                        installPermissionMissing = true
                    }
                },
                onNext = onNext,
            )
        },
    )
}

/**
 * 拉起安装：区分「已跳去授权页」与「真的失败」。
 *
 * [ProviderManager.install] 在没有「安装未知应用」授权时会自己打开系统授权页并返回
 * `Failed`——那不是错误，所以这里只看授权状态，不把它的文案当报错抛给用户。
 */
private fun launchInstall(context: android.content.Context, onFailed: (String) -> Unit) {
    when (val r = ProviderManager.install(context)) {
        is ProviderManager.InstallLaunch.Failed ->
            if (ProviderManager.canInstallPackages(context)) onFailed("拉起安装器失败：${r.reason}") else Unit
        else -> Unit
    }
}

/**
 * 安装前预告：用户点下去就会被丢到系统的「安装未知应用」页，先讲清下一步是什么，
 * 免得以为跳错了页面；允许后返回本页会自动继续安装。
 */
@Composable
private fun InstallPermissionNotice(continuing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadii.Card))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
            .padding(AppSpacing.Md),
    ) {
        Text(
            if (continuing) {
                "还没允许「安装未知应用」"
            } else {
                "接下来会跳到系统设置"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.height(AppSpacing.Xxs))
        Text(
            if (continuing) {
                "点「继续安装」会再次打开那个页面，打开「允许来自此来源的应用」后返回本页即可。"
            } else {
                "系统会让你允许「安装未知应用」（英文为 Install unknown apps），允许后返回本页会自动继续安装，" +
                    "不用再点一次。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** 底部操作区：需要装就给安装按钮（附跳过），已就绪直接进下一步 */
@Composable
private fun PrepareFooter(
    needsInstall: Boolean,
    installed: Boolean,
    continuing: Boolean,
    onInstall: () -> Unit,
    onNext: () -> Unit,
) {
    if (needsInstall) {
        PrimaryOnboardingButton(
            label = when {
                continuing -> "继续安装"
                installed -> "更新服务组件"
                else -> "安装服务组件"
            },
            onClick = onInstall,
        )
        Spacer(Modifier.height(AppSpacing.Sm))
        TextButton(onClick = onNext) { Text("先跳过，稍后安装") }
    } else {
        PrimaryOnboardingButton(label = "下一步", onClick = onNext)
        Spacer(Modifier.height(AppSpacing.Sm))
        Text(
            "组件已就绪",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 组件状态卡：结论 + 「当前 / 随包」版本。
 *
 * 不显示「已安装」就结束：安装 ≠ 绑定 ≠ 无障碍已开启，旧的通道卡踩过这个坑——
 * 用户看到「已安装」以为能用。这里只报版本事实，可用性交给下一步的权限清单验证。
 */
@Composable
private fun ProviderStatusCard(
    installedVersion: String?,
    bundledVersion: String?,
    needsInstall: Boolean,
) {
    val ready = !needsInstall && installedVersion != null
    val color = if (ready) Success else MaterialTheme.colorScheme.primary
    AppItemCard(containerColor = color.copy(alpha = 0.08f)) {
        AppIconTile(
            icon = if (ready) AppIcons.CheckCircle else AppIcons.FileDownload,
            tint = color,
            background = color.copy(alpha = 0.16f),
            modifier = Modifier.padding(start = AppSpacing.Md, top = AppSpacing.Md, bottom = AppSpacing.Md),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
        ) {
            Text(
                when {
                    installedVersion == null -> "服务组件未安装"
                    needsInstall -> "服务组件需要更新"
                    else -> "服务组件已就绪"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(AppSpacing.Xxs))
            Text(
                buildString {
                    append("当前 ")
                    append(installedVersion ?: "未安装")
                    if (bundledVersion != null) append(" · 随包 $bundledVersion")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
