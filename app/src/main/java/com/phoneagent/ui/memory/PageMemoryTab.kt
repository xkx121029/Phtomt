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
 * 按应用分组，每页一张「虚拟画面」卡：左侧线框缩略图（AI 眼里的手机画面），
 * 右侧标题/来过次数/出边路径流；点卡片打开下钻面板看完整画面与可跳控件。
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
    // 单条删除的两步确认：第一次点只把该行图标染红（armed），再点才真删；
    // 3 秒不点自动复位。armedId 记录当前处于确认态的行，同一时间只有一行
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
            Spacer(Modifier.height(4.dp))
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
            }
            Spacer(Modifier.height(6.dp))

            val titleOf = pages.associate { it.fingerprint to it.title }
            pages.forEach { page ->
                PageMemoryCard(
                    page = page,
                    outEdges = edges.filter { it.appPackage == pkg && it.fromFp == page.fingerprint },
                    titleOf = titleOf,
                    armed = armedId == page.id,
                    onArmedChange = { armedId = if (it) page.id else null },
                    onDelete = { armedId = null; onDelete(page.id) },
                    onOpen = { onOpenPage(page) },
                )
            }
        }
    }
}

/** 单页画廊卡：左侧虚拟画面缩略图，右侧标题/次数/出边路径流，点卡片下钻 */
@Composable
private fun PageMemoryCard(
    page: PageMemoryEntry,
    outEdges: List<PagePathEdge>,
    titleOf: Map<String, String>,
    armed: Boolean,
    onArmedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .appBorder(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppRadii.Tile))
            .clickable(onClick = onOpen)
            .padding(12.dp),
    ) {
        Row {
            // 左：虚拟画面缩略图（真实手机比例的线框重建，点卡片放大下钻）
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .clip(RoundedCornerShape(AppRadii.Tile))
                    .background(AppTheme.colors.surfaceSunken),
            ) {
                VirtualScreenCanvas(
                    shapes = page.shapes,
                    modifier = Modifier.width(92.dp),
                )
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        page.title.ifBlank { "未命名页面" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PressableScale(onClick = { onArmedChange(!armed) }) {
                        Icon(
                            AppIcons.DeleteOutline,
                            // 视觉确认态：图标染红 + 读屏文案改为「再点确认删除」
                            contentDescription = if (armed) "再点确认删除" else "删除",
                            tint = if (armed) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(6.dp).size(16.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        append(pageLabelOfType(page.pageType))
                        append(" · 来过 ${page.visitCount} 次")
                        val controlCount = if (page.shapes.isNotEmpty()) page.shapes.size else page.hotspots.size
                        if (controlCount > 0) append(" · $controlCount 个控件")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 出边路径流：这一页 —「动作」→ 下一页（带出发控件 id，与 AI 注入口径一致）
                if (outEdges.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    outEdges.forEach { e ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 3.dp),
                        ) {
                            Icon(
                                AppIcons.ChevronRight,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                buildString {
                                    append(e.actionLabel)
                                    if (e.fromControl.isNotBlank()) append("（id=${e.fromControl}）")
                                    append(" → ")
                                    append(titleOf[e.toFp]?.ifBlank { null } ?: "其他页面")
                                    append("（走过 ${e.count} 次）")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点画面可放大查看形态",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** pageType 的中文标签（与 PageAnnotator 的页面类型保持一致的展示口径） */
private fun pageLabelOfType(type: String): String = when (type) {
    "generic" -> "普通页面"
    "unknown" -> "未知页面"
    else -> type
}
