package com.phoneagent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.theme.AppTheme

/**
 * 品牌标记「亮格」：2×2 圆角方格，右上一格为实心——桌面四格里正在工作的那一格。
 *
 * 几何与三处资产同源，改动需三处同步：
 * - 安卓自适应图标 `res/drawable/ic_launcher_foreground.xml`
 * - 官网 `website/public/logo-mark.svg` / `favicon.svg`
 * - 本文件
 *
 * 比例来自 24 网格：格 6.3、缝 2.7、圆角 2.0、描边 1.55，外框宽 15.3。
 * 画布取外框尺寸（不是 24 网格），所以 [size] 就是标记本身的宽度，由调用方决定占位比例。
 */
private const val CELL = 6.3f
private const val GAP = 2.7f
private const val RADIUS = 2.0f
private const val STROKE = 1.55f
private const val FRAME = 2 * CELL + GAP

/** 实心格位于右上（i = 1, j = 0），与图标资产一致 */
private const val LIT_I = 1
private const val LIT_J = 0

/**
 * @param size 标记外框宽度（含描边）
 * @param tint 单色填充/描边色；深锚底上用 [com.phoneagent.ui.theme.BrandMarkTint]
 */
@Composable
fun BrandMark(
    size: Dp = 24.dp,
    tint: Color = AppTheme.colors.brand,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / FRAME
        val sw = STROKE * u
        val box = CELL * u
        val inset = sw / 2f

        for (i in 0..1) {
            for (j in 0..1) {
                val left = i * (CELL + GAP) * u
                val top = j * (CELL + GAP) * u
                if (i == LIT_I && j == LIT_J) {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(left, top),
                        size = Size(box, box),
                        cornerRadius = CornerRadius(RADIUS * u),
                    )
                } else {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(left + inset, top + inset),
                        size = Size(box - sw, box - sw),
                        cornerRadius = CornerRadius(RADIUS * u - inset),
                        style = Stroke(width = sw),
                    )
                }
            }
        }
    }
}
