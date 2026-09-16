package com.phoneagent.ui.debug.panels

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.domain.model.AgentLog
import com.phoneagent.domain.model.AgentMetrics
import com.phoneagent.domain.model.ConversationMessage
import com.phoneagent.core.text.HumanTranslator
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.AppTopBar
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.Success
import com.phoneagent.ui.theme.Warning
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun StepShotPanel(shot: com.phoneagent.domain.model.StepShot) {
    if (shot.step == 0 && shot.screenshot == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadii.Item),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "最新一步 · 步骤 ${shot.step}${if (shot.verified) " · 已确认 ✅" else " · 待确认"}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (shot.screenshot != null && shot.annotatedScreenshot != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "← 原图 / 识别图 →（左右拖动对比）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                DragCompare(shot.screenshot!!, shot.annotatedScreenshot!!)
            } else if (shot.screenshot != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = shot.screenshot!!.asImageBitmap(),
                    contentDescription = "原截图",
                    modifier = Modifier
                        .width(160.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(AppRadii.Chip)),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                shot.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DragCompare(bmpA: android.graphics.Bitmap, bmpB: android.graphics.Bitmap) {
    var frac by remember { mutableStateOf(0.5f) }
    val aspect = if (bmpA.height > 0) bmpA.width.toFloat() / bmpA.height.toFloat() else 1f
    val imgA = bmpA.asImageBitmap()
    val imgB = bmpB.asImageBitmap()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(AppRadii.Item))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val w = this.size.width.toFloat()
                    if (w > 0) frac = (frac + dragAmount / w).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.toInt()
            val h = size.height.toInt()
            val cutW = (frac * w).toInt().coerceAtLeast(1)
            // 左区：bmpA 左侧 frac 区域（保持原图比例，不拉伸）
            drawImage(
                image = imgA,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize((imgA.width * frac).toInt().coerceAtLeast(1), imgA.height),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(cutW, h),
            )
            // 右区：bmpB 右侧 1-frac 区域
            val srcLeft = (imgB.width * frac).toInt().coerceIn(0, (imgB.width - 1).coerceAtLeast(0))
            drawImage(
                image = imgB,
                srcOffset = IntOffset(srcLeft, 0),
                srcSize = IntSize((imgB.width - srcLeft).coerceAtLeast(1), imgB.height),
                dstOffset = IntOffset(cutW, 0),
                dstSize = IntSize((w - cutW).coerceAtLeast(1), h),
            )
            val cut = frac * size.width
            drawLine(Color.White, Offset(cut, 0f), Offset(cut, size.height), strokeWidth = 3f)
            drawCircle(Color.White, radius = 10f, center = Offset(cut, size.height / 2f))
        }
    }
}