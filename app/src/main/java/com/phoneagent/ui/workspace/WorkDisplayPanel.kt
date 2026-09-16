package com.phoneagent.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.animateListItem
import com.phoneagent.ui.components.liquidGlass
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.DurationNormal
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.contentSpringSpec
import com.phoneagent.feature.workspace.WorkDisplay
import com.phoneagent.feature.workspace.WorkFile
import com.phoneagent.feature.workspace.WorkLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 编辑完文件后向用户展示内容的浮层面板。
 * 覆盖在工作区页面上，白色液态玻璃 + 滚动正文 + 复制/关闭。
 */
@Composable
internal fun WorkDisplayPanel(display: WorkDisplay, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 面板入场动画：淡入 + 弹簧轻微缩放（尊重“减少动画”时仅淡入）
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val showAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(DurationNormal, easing = EaseOut),
        label = "panel-alpha",
    )
    val showScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.94f,
        animationSpec = contentSpringSpec(),
        label = "panel-scale",
    )

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f * showAlpha))
            .clickable(enabled = true, onClick = onDismiss, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
    ) {
        Surface(
            shape = RoundedCornerShape(AppRadii.Card),
            color = Color.Transparent,
            modifier = Modifier
                .alpha(showAlpha)
                .graphicsLayer { scaleX = showScale; scaleY = showScale }
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 20.dp)
                .liquidGlass(alpha = 0.95f, cornerRadius = AppRadii.Card),
        ) {
            Column(modifier = Modifier.heightIn(max = 520.dp)) {
                // 头部：文件名 + 关闭
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        display.fileName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
                // 正文
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        display.content.ifBlank { "（空文档）" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                // 底部操作
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = android.content.ClipData.newPlainText(display.fileName, display.content)
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(clip)
                        },
                        shape = RoundedCornerShape(AppRadii.Inline),
                    ) {
                        Text("复制全文")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(AppRadii.Inline),
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}