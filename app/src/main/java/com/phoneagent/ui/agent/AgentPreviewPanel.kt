package com.phoneagent.ui.agent

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.device.screen.ScreenSharingService
import com.phoneagent.ui.components.OverlayScrim
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.emptyStateTextColor
import com.phoneagent.ui.theme.phoneCameraHoleColor
import com.phoneagent.ui.theme.phoneShellBorderColor
import com.phoneagent.ui.theme.phoneShellColor
import com.phoneagent.ui.theme.runningIndicatorColor
import kotlinx.coroutines.delay

/**
 * 内嵌运行画面浮层（由原 PhonePreviewArea 迁移而来）。
 *
 * 必须挂在与 LazyColumn **同层**的 Box 里，不能放进列表项：
 * 内部是 500ms 轮询取帧，列表回收/重排会反复重启轮询并泄漏帧。
 */
@Composable
internal fun AgentPreviewPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { ScreenSharingService.instance?.previewFrame() }
                .getOrNull()?.let { frame = it }
            delay(500)
        }
    }

    // 点击空白处收起；面板自身吞掉点击，避免误关。
    // 压暗底走全项目共用的 OverlayScrim，不在这里自己调黑度（见 Components.kt）
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.Lg)
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(AppRadii.Overlay))
                .background(colors.surfaceRaised)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(AppSpacing.Lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(runningIndicatorColor()),
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = "AI 实时操作画面",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                PressableScale(onClick = onDismiss) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "收起运行画面",
                        tint = colors.onSurfaceRaised,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Md))

            val shellShape = RoundedCornerShape(AppRadii.Overlay)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 200.dp)
                    .aspectRatio(9f / 16f)
                    .clip(shellShape)
                    .background(phoneShellColor())
                    .border(2.dp, phoneShellBorderColor(), shellShape),
            ) {
                val shot = frame
                if (shot != null && !shot.isRecycled) {
                    Image(
                        bitmap = shot.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(7.dp)
                            .clip(RoundedCornerShape(AppRadii.Bubble)),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = "暂无画面",
                            style = MaterialTheme.typography.titleSmall,
                            color = emptyStateTextColor(),
                        )
                        Spacer(Modifier.height(AppSpacing.Xs))
                        Text(
                            text = "开启屏幕共享授权后\n可实时观看 AI 操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = emptyStateTextColor(),
                            modifier = Modifier.padding(horizontal = AppSpacing.Lg),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = AppSpacing.Sm)
                        .size(6.dp)
                        .clip(RoundedCornerShape(AppRadii.Chip))
                        .background(phoneCameraHoleColor(active = frame != null)),
                )
            }

            Spacer(Modifier.height(AppSpacing.Md))
            Text(
                text = "点击空白处收起",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceRaised,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}