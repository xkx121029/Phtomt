package com.phoneagent.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.data.store.PageMemoryEntry
import com.phoneagent.data.store.PagePathEdge
import com.phoneagent.ui.components.PressableScale
import com.phoneagent.ui.components.SectionCard
import com.phoneagent.ui.theme.Accent
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorder
import com.phoneagent.ui.icons.AppIcons

/**
 * 「页面记忆」分栏：任务执行时自动沉淀的页面形态与跳转路径。
 * 面向长期累计设计：按应用分组，组内两列缩略图网格（每页一张「虚拟画面」线框），
 * 卡片只保留缩略图 + 标题 + 一行元信息，密度高、翻阅快；点卡片打开下钻面板看完整画面。
 * 同形页面（控件结构相同）在写入侧已归并为一条，列表不会因翻页重复刷屏。
 * 单条删除走两步确认（点一次染红，再点才删），与任务记忆一致；不用系统弹窗。
 */
@Composable
internal fun PageMemoryList(
    entries: List<PageMemoryEntry>,
    edges: List<PagePathEdge>,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
    onOpenPage: (PageMemoryEntry) -> Unit,
) {
    // 单条删除的两步确认：第一次点只把该卡图标染红（armed），再点才真删；
    // 3 秒不点自动复位。armedId 记录当前处于确认态的卡，同一时间只有一张
    var armedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(armedId) {
        if (armedId != null) {
            kotlinx.coroutines.delay(3000)
            armedId = null
        }
    }

    SectionCard(
        title = "页面记忆",
        count = entries.size,
        countColor = Accent,
        onClear = onClear,
    ) {
        if (entries.isEmpty()) {
            Text(
                "暂无页面记忆",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 按应用分组：指纹不含包名，展示时必须先把包名归拢，跨应用的同名页面才不会混在一起
        val byApp = entries.groupBy { it.appPackage }
        byApp.forEach { (pkg, pages) ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    pkg,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${pages.size} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            // 两列网格：长期累计下密度优先，一屏能看更多页面
            pages.chunked(2).forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowItems.forEach { page ->
                        PageMemoryCard(
                            page = page,
                            outEdgeCount = edges.count { it.appPackage == pkg && it.fromFp == page.fingerprint },
                            armed = armedId == page.id,
                            onArmedChange = { armedId = if (it) page.id else null },
                            onDelete = { armedId = null; onDelete(page.id) },
                            onOpen = { onOpenPage(page) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 末行单卡补位，保持两列对齐
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 网格缩略卡：虚拟画面 + 标题 + 一行元信息，点卡下钻，角标两步删除 */
@Composable
private fun PageMemoryCard(
    page: PageMemoryEntry,
    outEdgeCount: Int,
    armed: Boolean,
    onArmedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadii.Tile))
            .appBorder(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppRadii.Tile))
            .clickable(onClick = onOpen)
            .padding(8.dp),
    ) {
        Box {
            VirtualScreenCanvas(
                shapes = page.shapes,
                // 缩略态：禁用画面内点击（下钻交给整卡），改用更方的比例压缩纵向空间
                onTapControl = null,
                screenAspect = 0.78f,
                modifier = Modifier.fillMaxWidth(),
            )
            PressableScale(
                onClick = { onArmedChange(!armed) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                Icon(
                    AppIcons.DeleteOutline,
                    // 视觉确认态：图标染红 + 读屏文案改为「再点确认删除」
                    contentDescription = if (armed) "再点确认删除" else "删除",
                    tint = if (armed) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            AppTheme.colors.surfaceBase.copy(alpha = 0.7f),
                            CircleShape,
                        )
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            page.title.ifBlank { "未命名页面" },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(pageLabelOfType(page.pageType))
                append(" · 来过 ${page.visitCount} 次")
                if (outEdgeCount > 0) append(" · $outEdgeCount 条路径")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** pageType 的中文标签（与 PageAnnotator 的页面类型保持一致的展示口径） */
private fun pageLabelOfType(type: String): String = when (type) {
    "generic" -> "普通页面"
    "unknown" -> "未知页面"
    else -> type
}
