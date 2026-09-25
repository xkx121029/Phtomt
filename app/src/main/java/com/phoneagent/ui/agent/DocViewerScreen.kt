package com.phoneagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.feature.document.DocResult
import com.phoneagent.ui.components.AppIconTile
import com.phoneagent.ui.components.MarkdownPreview
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme

/**
 * AI 回返的 Markdown 全屏阅读页。
 *
 * 覆盖整页（含底部悬浮导航栏）：AI 生成的长文档在任务流里只能限高内滚，
 * 要通读就得有这么一个"整屏只放正文"的地方。出口只有一个——底部大圆角按钮，
 * 与测试/调试等全屏二级页同为一条规矩（不做左上角返回箭头）。
 */
@Composable
fun DocViewerScreen(
    doc: DocResult?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceBase),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.Lg,
                    end = AppSpacing.Lg,
                    top = AppSpacing.Lg,
                    bottom = AppSpacing.Md,
                ),
        ) {
            AppIconTile(
                icon = AppIcons.Description,
                tint = colors.brand,
                background = colors.surfaceSunken,
                tileSize = 36.dp,
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(AppSpacing.Md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 生成的内容",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceRaised,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = doc?.fileName.orEmpty().ifBlank { "全文阅读" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (doc == null) {
            Text(
                text = "这份内容已经关掉了；在任务流里再点一次「全屏」即可重新打开。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceRaised,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xs),
            ) {
                MarkdownPreview(content = doc.content, modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(AppSpacing.Md))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(AppRadii.Hero),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .height(56.dp),
        ) {
            Icon(AppIcons.Home, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(text = "返回主页", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
    }
}