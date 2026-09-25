package com.phoneagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.theme.AppRadii

/**
 * 标定预览框的颜色。
 *
 * 这里是全项目唯一一处**刻意不用语义色令牌**的界面色：它是一把「标尺」，
 * 需要在任意壁纸、任意主题下都刺眼可见，跟页面其余部分不是一套语言。
 * 收进具名常量只为不留魔法数字，不参与主题色板。
 */
private val CalibrationGuideBorder = Color(0xFFFFD600)

/** 视觉效果页 */
@Composable
internal fun SettingsVisual(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(bottom = LocalBottomNavClearance.current),
    ) {
        SettingsTopBar("视觉效果", onBack)
        Spacer(Modifier.height(16.dp))

        GroupCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                ToggleRow("点击光标", "执行任务时显示圆润指针，指示 AI 正在点哪里", st.cursorOverlayEnabled) {
                    st.cursorOverlayEnabled = it
                    save()
                }
                if (st.cursorOverlayEnabled) {
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        "光标先到位再点击",
                        "开启后每步会等光标飞到目标再点击，更直观但每步会慢一些",
                        st.cursorClickSync,
                    ) {
                        st.cursorClickSync = it
                        save()
                    }
                }
                Spacer(Modifier.height(12.dp))

                ToggleRow("启用跑马光效", "任务执行时显示屏幕边缘彩色光效", st.edgeLightingEnabled) {
                    st.edgeLightingEnabled = it
                    save()
                }
                Spacer(Modifier.height(8.dp))

                ExpandableCard(
                    title = "光效标定",
                    enabled = st.edgeLightingEnabled,
                    expanded = st.calibrationExpanded,
                    onExpandedChange = { st.calibrationExpanded = it },
                ) {
                    Text(
                        "标定屏幕四边的内缩和圆角，使光效完美贴合设备屏幕。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    // 预览框
                    Text(
                        "实时预览",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(AppRadii.Item),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .padding(8.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                shape = RoundedCornerShape(st.cornerRadius.dp),
                                color = Color.Transparent,
                                border = BorderStroke(2.dp, CalibrationGuideBorder),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        start = st.edgeInsetLeft.dp,
                                        end = st.edgeInsetRight.dp,
                                        top = st.edgeInsetTop.dp,
                                        bottom = st.edgeInsetBottom.dp,
                                    ),
                            ) {}
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // 四边内缩
                    Text(
                        "屏幕四边内缩（dp）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    CalibrationSlider("顶部", st.edgeInsetTop, 0f..30f) {
                        st.edgeInsetTop = it
                        save()
                    }
                    CalibrationSlider("底部", st.edgeInsetBottom, 0f..30f) {
                        st.edgeInsetBottom = it
                        save()
                    }
                    CalibrationSlider("左侧", st.edgeInsetLeft, 0f..30f) {
                        st.edgeInsetLeft = it
                        save()
                    }
                    CalibrationSlider("右侧", st.edgeInsetRight, 0f..30f) {
                        st.edgeInsetRight = it
                        save()
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "圆角半径",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    CalibrationSlider("圆角", st.cornerRadius, 0f..60f) {
                        st.cornerRadius = it
                        save()
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "光带粗细",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    CalibrationSlider("粗细", st.edgeLightingWidth, 5f..50f) {
                        st.edgeLightingWidth = it
                        save()
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "悬浮窗跑马灯",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "调节悬浮窗底部跑马灯。跑马灯浮在屏幕底边之上，长宽随文字自适应：宽度超出一屏就滚动，内边距决定面板厚度。底色可以跟随任务状态，也可以自己配渐变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "跟随状态变色",
                    "跑马灯底色由当前阶段决定：观察蓝 / 思考紫 / 执行粉 / 完成绿 / 出错红。不用自己配色，一眼就能看出 AI 在干什么",
                    st.marqueeAutoColor,
                ) {
                    st.marqueeAutoColor = it
                    save()
                }
                Spacer(Modifier.height(10.dp))
                CalibrationSlider("内边距", st.marqueeHeight, 4f..28f) {
                    st.marqueeHeight = it
                    save()
                }
                Spacer(Modifier.height(14.dp))
                if (st.marqueeAutoColor) {
                    MarqueePhasePreview(st.marqueeHeight)
                } else {
                    Text(
                        "颜色",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    MarqueeColorPicker(st.marqueeColors, st.marqueeHeight) {
                        st.marqueeColors = it
                        save()
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}