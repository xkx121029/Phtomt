package com.phoneagent.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.AppItemCard
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.StatusPill
import com.phoneagent.ui.home.BrandTile
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.model.PermissionKind
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.DurationSlow
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.appBorder
import com.phoneagent.ui.theme.appBorderStroke
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.contentSpringSpec
import com.phoneagent.ui.theme.motionSettings
import com.phoneagent.ui.theme.staggerDelayMs
import kotlinx.coroutines.delay

/**
 * 首次进入的新手引导。
 *
 * 视觉完全走全项目统一令牌：品牌渐变瓦片（与页眉 BrandTile 同源）、M3 Expressive 圆角、
 * 主题感知色；动效遵守 emilkowalski/skills 规范与用户偏好——**只有自下而上的入场**，
 * 页与页之间也是"下一页从下方浮入 + 淡入"，没有任何水平滑动与缩放。
 *
 * 结构：上中下三段。中段是当前步骤（图标瓦片 + 标题 + 正文），底部是进度点与主按钮，
 * 右上角提供"跳过"。完成或跳过都会回调 [onFinish]。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }
    val motion = motionSettings()
    // 系统返回手势 = 上一步；第一步返回即结束引导
    BackHandler(enabled = true) {
        if (step > 0) step-- else onFinish()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // 跳过：右上角弱化入口，只在不给用户添负担的位置出现
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onFinish) {
                Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.weight(1f))

        // 步骤内容：换页时下一页自下而上浮入（与全项目"只做自下而上位移"同一条规矩）
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (
                    fadeIn(tween(DurationSlow, easing = EaseOut)) +
                        slideInVertically(
                            animationSpec = contentSpringSpec(),
                            initialOffsetY = { if (motion.reduceMotion) 0 else it / 4 },
                        )
                    ) togetherWith fadeOut(tween(DurationFast))
            },
            label = "onboarding-step",
        ) { index ->
            val info = steps[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 品牌渐变瓦片：全项目唯一的品牌渐变（页眉 BrandTile 同源），首步放品牌名
                if (index == 0) {
                    StaggerIn(0) {
                        BrandTile(size = 72.dp)
                    }
                    Spacer(Modifier.height(AppSpacing.Lg))
                } else {
                    StepIconTile(info.icon, motion.reduceMotion)
                }
                StaggerIn(1) {
                    Text(
                        info.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(AppSpacing.Md))
                StaggerIn(2) {
                    Text(
                        info.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 进度点 + 主按钮：固定在底部，不随步骤内容动
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = AppSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDots(
                count = steps.size,
                current = step,
                modifier = Modifier.padding(bottom = AppSpacing.Lg),
            )
            Button(
                onClick = {
                    if (step < steps.lastIndex) step++ else onFinish()
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    if (step < steps.lastIndex) "继续" else "开始使用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 引导步骤文案与配图 */
private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val steps = listOf(
    OnboardingStep(
        AppIcons.AutoAwesome,
        "你好，我是 Happy Agent",
        "一句话说出需求，我来替你操作手机：自己看屏幕、自己点按输入，一步步把事情做完。",
    ),
    OnboardingStep(
        AppIcons.TouchApp,
        "每个动作都看得见",
        "执行时屏幕上会标出点击位置，进度实时播报；需要确认时先来问你，不乱动你的手机。",
    ),
    OnboardingStep(
        AppIcons.Remember,
        "越用越懂你",
        "自动记住常用页面与操作路径，学会的技能可以复用；网页操作与文档产出直接交付结果。",
    ),
    OnboardingStep(
        AppIcons.Launch,
        "最后一步：交给我",
        "接下来把权限逐项点亮，我就能替你看、替你点；AI 模型到「设置」里配好即可。",
    ),
)

/** 步骤配图瓦片：品牌色低饱和底 + 主题图标，与列表条目的图标瓦片同一套语言 */
@Composable
private fun StepIconTile(icon: ImageVector, reduceMotion: Boolean) {
    val colors = AppTheme.colors
    StaggerIn(0) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(AppRadii.Hero))
                .background(colors.brand.copy(alpha = 0.14f))
                .appBorder(1.dp, colors.brand.copy(alpha = 0.25f), RoundedCornerShape(AppRadii.Hero)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 进度点：当前步收成品牌色胶囊，其余是静默圆点，颜色与宽度用同一根曲线过渡 */
@Composable
private fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 18.dp else 6.dp,
                animationSpec = contentSpringSpec(),
                label = "dot-width",
            )
            Surface(
                shape = CircleShape,
                color = if (active) {
                    AppTheme.colors.brand
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(width = width, height = 6.dp),
            ) {}
        }
    }
}

/**
 * 分段入场：元素自下而上浮入 + 淡入，相邻元素错开 [staggerDelayMs] 形成"级联"。
 * 只在首次进入组合时播一次；减少动画时直接呈现、不做位移。
 */
@Composable
private fun StaggerIn(
    index: Int,
    content: @Composable () -> Unit,
) {
    val motion = motionSettings()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val offsetY = if (motion.reduceMotion) 0 else 1
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(
            tween(DurationSlow, delayMillis = staggerDelayMs(index), easing = EaseOut),
        ) + slideInVertically(
            animationSpec = contentSpringSpec(),
            initialOffsetY = { if (motion.reduceMotion) 0 else it / 3 * offsetY },
        ),
    ) {
        content()
    }
}

/**
 * 开屏引导第二阶段：权限配置（独立整屏，紧接在欢迎步骤之后）。
 *
 * 数据源与授权跳转复用权限雷达的同一套接口（[MainViewModel.permissions] /
 * [MainViewModel.openPermissionSettings]），条目视觉与「概览」页雷达保持一致——
 * 用户在引导里见过的行，进主界面后还是同一行。
 *
 * 状态每秒轮询刷新：用户从系统授权页返回时状态自动点亮，无需手动刷新。
 * 所有权限都是可选的：完成按钮始终可用，稍后可在「概览」页的权限雷达里继续配置。
 */
@Composable
fun OnboardingPermissionScreen(
    vm: MainViewModel,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val permissions by vm.permissions.collectAsState()
    // 进入时刷新一次 + 停留期间轮询：授权页返回后状态自动点亮
    LaunchedEffect(Unit) {
        vm.refreshPermissions(context)
        while (true) {
            delay(1000)
            vm.refreshPermissions(context)
        }
    }
    // 系统返回手势：有上一阶段就先回上一阶段，没有则直接结束引导
    BackHandler(enabled = true) {
        val back = onBack
        if (back != null) back() else onFinish()
    }

    val grantedCount = permissions.count { it.granted }
    val allGranted = permissions.isNotEmpty() && grantedCount == permissions.size

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        // 页首：品牌瓦片 + 标题 + 说明，与欢迎页同一套版式
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StaggerIn(0) { BrandTile(size = 64.dp) }
            Spacer(Modifier.height(AppSpacing.Lg))
            StaggerIn(1) {
                Text(
                    "开启所需权限",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(AppSpacing.Md))
            StaggerIn(2) {
                Text(
                    "每点亮一项，我能替你做的事就多一件；也可以先跳过，稍后在「概览」页继续。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(AppSpacing.Lg))
            StaggerIn(3) { ReadyBadge(grantedCount, permissions.size, allGranted) }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        // 权限条目：与「概览」页权限雷达同一行视觉（图标瓦片 + 状态胶囊），点按跳授权页
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            permissions.forEachIndexed { index, p ->
                StaggerIn(4 + index) {
                    OnboardingPermissionRow(
                        title = p.title,
                        description = p.description,
                        icon = permissionIcon(p.kind),
                        granted = p.granted,
                        onClick = { vm.openPermissionSettings(context, p.kind) },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部：完成按钮始终可用，不强迫授权
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = AppSpacing.Lg, bottom = AppSpacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onFinish,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    if (allGranted) "全部就绪，开始使用" else "完成，先这样",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                "随时可以在「概览」页的权限雷达里继续配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 就绪统计胶囊：全部点亮时换成成功色 + 对勾 */
@Composable
private fun ReadyBadge(granted: Int, total: Int, allGranted: Boolean) {
    val color = if (allGranted) Success else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        border = appBorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            Icon(
                if (allGranted) AppIcons.CheckCircle else AppIcons.Guard,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (allGranted) "全部就绪" else "已就绪 $granted / $total",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

/** 权限行：与权限雷达条目同一套视觉语言，点按跳转系统授权页 */
@Composable
private fun OnboardingPermissionRow(
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
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
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
    PermissionKind.AUTOSTART -> AppIcons.Bolt
    PermissionKind.QUERY_ALL_PACKAGES -> AppIcons.Memory
}
