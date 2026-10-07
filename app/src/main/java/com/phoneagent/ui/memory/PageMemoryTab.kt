package com.phoneagent.ui.memory

import androidx.compose.foundation.background
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
import com.phoneagent.ui.theme.appBorder
import com.phoneagent.ui.icons.AppIcons

/**
 * 「页面记忆」分栏：任务执行时自动沉淀的页面热点与跳转路径。
 * 按应用分组展示页面卡片（标题/类型/来过次数/热点控件），页面下挂出边路径流。
 * 单条删除走两步确认（点一次染红，再点才删），与任务记忆一致；不用系统弹窗。
 */
@Composable
internal fun PageMemoryList(
    entries: List<PageMemoryEntry>,
    edges: List<PagePathEdge>,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
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
                )
            }
        }
    }
}

/** 单条页面记忆卡片：标题 + 类型/次数，热点控件 chips，出边路径流 */
@Composable
private fun PageMemoryCard(
    page: PageMemoryEntry,
    outEdges: List<PagePathEdge>,
    titleOf: Map<String, String>,
    armed: Boolean,
    onArmedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(AppRadii.Tile))
            .appBorder(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppRadii.Tile))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    page.title.ifBlank { "未命名页面" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(pageLabelOfType(page.pageType))
                        append(" · 来过 ${page.visitCount} 次")
                        if (page.hotspots.isNotEmpty()) append(" · ${page.hotspots.size} 个已知控件")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

        // 热点控件 chips：只展示文字标签（执行时才在屏幕上按 ratio 画圈），每行最多 3 个
        if (page.hotspots.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            page.hotspots.take(6).chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { hs ->
                        Text(
                            hs.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadii.Chip))
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (page.hotspots.size > 6) {
                Text(
                    "等 ${page.hotspots.size} 个控件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 出边路径流：这一页 —「动作」→ 下一页
        if (outEdges.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
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
                        "${e.actionLabel} → ${titleOf[e.toFp]?.ifBlank { null } ?: "其他页面"}（走过 ${e.count} 次）",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
