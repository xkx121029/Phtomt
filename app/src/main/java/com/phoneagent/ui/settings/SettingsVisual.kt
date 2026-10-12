package com.phoneagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.components.AppSurfaceMuted
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.theme.AppRadii

/**
 * 标定预览框的颜色。
 *
 * 这里是全项目唯一一处**刻意不用语义色令牌**的界面色：它是一把「标尺」，
 * 需要在任意壁纸、任意主题下都刺眼可见，跟页面其余部分不是一套语言。
 * 收进具名常量只为不留魔法数字，不参与主题色板。
 */
private val CalibrationGuideBorder = Color(0xFFFFD600)

/** 界面效果页：运行时视觉反馈（点击光标 / 边缘光效 / 悬浮窗跑马灯） */
@Composable
internal fun SettingsVisual(st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("界面效果", onBack, subtitle = "执行过程中的视觉反馈，只影响观感")
        Spacer(Modifier.height(20.dp))

        // ---- 点击光标 ----
        SettingsSection(
            title = "点击光标",
            subtitle = "指示 AI 正在点哪里",
            tone = SettingsTone.Brand,
        ) {
            SettingsToggleRow(
                title = "点击光标",
                subtitle = "执行任务时显示圆润指针，指示 AI 正在点哪里",
                value = st.cursorOverlayEnabled,
            ) {
                st.cursorOverlayEnabled = it
                save()
            }
            if (st.cursorOverlayEnabled) {
                SettingsDivider(inset = false)
                SettingsToggleRow(
                    title = "光标先到位再点击",
                    subtitle = "开启后每步会等光标飞到目标再点击，更直观但每步会慢一些",
                    value = st.cursorClickSync,
                ) {
                    st.cursorClickSync = it
                    save()
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 边缘光效 ----
        SettingsSection(
            title = "边缘光效",
            subtitle = "任务执行时的屏幕边缘彩色光效",
            tone = SettingsTone.Brand,
        ) {
            SettingsToggleRow(
                title = "启用跑马光效",
                subtitle = "任务执行时显示屏幕边缘彩色光效",
                value = st.edgeLightingEnabled,
            ) {
                st.edgeLightingEnabled = it
                save()
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
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
                        color = AppSurfaceMuted,
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
                    // 拖动只改本地编辑态，松手才落盘（避免拖一次写几十次 DataStore）
                    CalibrationSlider("顶部", st.edgeInsetTop, 0f..30f, onChange = { st.edgeInsetTop = it }, onCommit = save)
                    CalibrationSlider("底部", st.edgeInsetBottom, 0f..30f, onChange = { st.edgeInsetBottom = it }, onCommit = save)
                    CalibrationSlider("左侧", st.edgeInsetLeft, 0f..30f, onChange = { st.edgeInsetLeft = it }, onCommit = save)
                    CalibrationSlider("右侧", st.edgeInsetRight, 0f..30f, onChange = { st.edgeInsetRight = it }, onCommit = save)

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "圆角半径",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    CalibrationSlider("圆角", st.cornerRadius, 0f..60f, onChange = { st.cornerRadius = it }, onCommit = save)

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "光带粗细",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    CalibrationSlider("粗细", st.edgeLightingWidth, 5f..50f, onChange = { st.edgeLightingWidth = it }, onCommit = save)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 悬浮窗跑马灯 ----
        SettingsSection(
            title = "悬浮窗跑马灯",
            subtitle = "跑马灯浮在屏幕底边之上，长宽随文字自适应：宽度超出一屏就滚动，内边距决定面板厚度",
            tone = SettingsTone.Brand,
        ) {
            SettingsToggleRow(
                title = "跟随状态变色",
                subtitle = "底色由当前阶段决定：观察蓝 / 思考紫 / 执行粉 / 完成绿 / 出错红，不用自己配色",
                value = st.marqueeAutoColor,
            ) {
                st.marqueeAutoColor = it
                save()
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                CalibrationSlider("内边距", st.marqueeHeight, 4f..28f, onChange = { st.marqueeHeight = it }, onCommit = save)
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