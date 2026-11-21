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
import androidx.compose.runtime.DisposableEffect
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
 * 位图所有权：每帧都是调用方独占的拷贝，因此只保留「当前 + 上一帧」两张，
 * 上一帧在下一帧到达时回收；面板收起时两张一起回收。
 */
@Composable
internal fun AgentPreviewPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    // 刚被换下的上一帧。回收推迟一个轮询周期，理由见轮询里的注释。
    var staleFrame by remember { mutableStateOf<Bitmap?>(null) }

    // 500ms 轮询，但只在捕获线程真的产出新帧时才取图、才写 state：
    // 屏幕没动的时候，连那一次整屏 ARGB 拷贝都不做（previewFrame 每次都拷一整屏）。
    LaunchedEffect(Unit) {
        var lastSeq = Long.MIN_VALUE
        var lastService: ScreenSharingService? = null
        while (true) {
            val service = ScreenSharingService.instance
            if (service == null) {
                // 服务断开：重连后 frameSeq 会从 0 重来，旧序号必须丢掉，否则会一直等一个更大的序号
                lastSeq = Long.MIN_VALUE
                lastService = null
            } else {
                if (service !== lastService) {
                    lastSeq = Long.MIN_VALUE
                    lastService = service
                }
                val seq = service.previewFrameSeq()
                if (seq != lastSeq) {
                    val next = runCatching { service.previewFrame() }.getOrNull()
                    if (next != null) {
                        lastSeq = seq
                        // previewFrame 返回的是调用方独占的拷贝（服务自己那份留在锁里），
                        // 本面板拿到的每一帧都没有第二个持有者，可以放心回收。
                        // 但仍推迟一个周期才 recycle：上一帧可能还挂在刚提交的绘制指令上，
                        // 立刻回收会撞 use-after-recycle。代价是常驻最多两帧整屏位图。
                        staleFrame?.recycle()
                        staleFrame = frame
                        frame = next
                    }
                }
            }
            delay(500)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 面板收起时把手上这两帧一起还掉，不留一份悬空的全屏位图
            frame?.recycle()
            staleFrame?.recycle()
            frame = null
            staleFrame = null
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
                .clip(RoundedCornerShape(AppTheme.metrics.overlayRadius))
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
                        .clip(RoundedCornerShape(AppTheme.metrics.chipRadius))
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

            val shellShape = RoundedCornerShape(AppTheme.metrics.overlayRadius)
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
                            .clip(RoundedCornerShape(AppTheme.metrics.tileRadius)),
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
                        .clip(RoundedCornerShape(AppTheme.metrics.chipRadius))
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