package com.phoneagent.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.overlay.OverlayDisplaySettings
import com.phoneagent.ui.components.AppSurfaceMuted
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing

/**
 * 标定预览框的颜色。
 *
 * 这里是全项目唯一一处**刻意不用语义色令牌**的界面色：它是一把「标尺」，
 * 需要在任意壁纸、任意主题下都刺眼可见，跟页面其余部分不是一套语言。
 * 收进具名常量只为不留魔法数字，不参与主题色板。
 */
private val CalibrationGuideBorder = Color(0xFFFFD600)

/**
 * 界面效果页：运行时视觉反馈的**精调项**（点击光标 / 边缘光效标定 / 悬浮窗跑马灯样式）。
 * 显示与不显示的开关统一放在设置首页「常用」，本页不再重复。
 */
@Composable
internal fun SettingsVisual(
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
    /** 由「外观与导航」内嵌渲染时为 true：标题已由外层给，这里不再重复画一遍 */
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // 这一步只读开关状态用于决定标定预览要不要起；开关本身已统一收在设置首页「常用」面板，
    // 本页不再提供第二处入口（同一开关只允许一处）。
    val edgeLightingOn = remember { OverlayDisplaySettings.edgeLightingEnabled(context) }

    // 标定面板展开时起全屏预览；开关关掉就立刻收窗。
    // 这段原先挂在设置页根节点上，现在挪到本页：预览服务只服务这一个分区，
    // 离开分区（或关掉开关）就该一并停掉，不该让根节点替它兜底。
    LaunchedEffect(st.calibrationExpanded, edgeLightingOn) {
        if (st.calibrationExpanded && edgeLightingOn && activity != null) {
            EdgeLightingService.startPreview(
                context = activity,
                top = st.edgeInsetTop,
                bottom = st.edgeInsetBottom,
                left = st.edgeInsetLeft,
                right = st.edgeInsetRight,
                radius = st.cornerRadius,
                width = st.edgeLightingWidth,
            )
        } else if (activity != null) {
            EdgeLightingService.stopPreview(activity)
        }
    }
    LaunchedEffect(
        st.edgeInsetTop, st.edgeInsetBottom, st.edgeInsetLeft, st.edgeInsetRight,
        st.cornerRadius, st.edgeLightingWidth,
    ) {
        if (st.calibrationExpanded && edgeLightingOn && activity != null) {
            EdgeLightingService.updateCalibration(
                context = activity,
                top = st.edgeInsetTop,
                bottom = st.edgeInsetBottom,
                left = st.edgeInsetLeft,
                right = st.edgeInsetRight,
                radius = st.cornerRadius,
                width = st.edgeLightingWidth,
            )
        }
    }
    // 切走分区时组合被回收：预览不会自己停，必须在这里补一刀
    DisposableEffect(Unit) {
        onDispose { activity?.let { EdgeLightingService.stopPreview(it) } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        if (!embedded) {
            SettingsPageHeader("界面效果", onBack, subtitle = "光标、光效标定与跑马灯样式；显示开关在设置首页「常用」")
            Spacer(Modifier.height(AppSpacing.Page))
        }

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

        Spacer(Modifier.height(AppSpacing.Lg))

        // ---- 边缘光效 ----
        SettingsSection(
            title = "边缘光效",
            subtitle = "任务执行时的屏幕边缘绿色光带",
            tone = SettingsTone.Brand,
        ) {
            // 边缘光效的开关已移到设置首页「常用」面板（与跑马灯、任务卡状态行同组）；
            // 本页只保留标定——标定是低频精调，值得独立一页，开关不是。
            Column(Modifier.padding(start = AppSpacing.Lg, end = AppSpacing.Lg, top = AppSpacing.Md, bottom = AppSpacing.Md)) {
                ExpandableCard(
                    title = "光效标定",
                    enabled = edgeLightingOn,
                    expanded = st.calibrationExpanded,
                    onExpandedChange = { st.calibrationExpanded = it },
                ) {
                    Text(
                        "标定屏幕四边的内缩和圆角，使光效完美贴合设备屏幕。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.Md))

                    // 预览框
                    Text(
                        "实时预览",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.Sm))
                    Surface(
                        shape = RoundedCornerShape(AppRadii.Item),
                        color = AppSurfaceMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .padding(AppSpacing.Sm),
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
                    Spacer(Modifier.height(AppSpacing.Lg))

                    // 四边内缩
                    Text(
                        "屏幕四边内缩（dp）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.Sm))
                    // 拖动只改本地编辑态，松手才落盘（避免拖一次写几十次 DataStore）
                    CalibrationSlider("顶部", st.edgeInsetTop, 0f..30f, onChange = { st.edgeInsetTop = it }, onCommit = save)
                    CalibrationSlider("底部", st.edgeInsetBottom, 0f..30f, onChange = { st.edgeInsetBottom = it }, onCommit = save)
                    CalibrationSlider("左侧", st.edgeInsetLeft, 0f..30f, onChange = { st.edgeInsetLeft = it }, onCommit = save)
                    CalibrationSlider("右侧", st.edgeInsetRight, 0f..30f, onChange = { st.edgeInsetRight = it }, onCommit = save)

                    Spacer(Modifier.height(AppSpacing.Md))

                    Text(
                        "圆角半径",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.Sm))
                    CalibrationSlider("圆角", st.cornerRadius, 0f..60f, onChange = { st.cornerRadius = it }, onCommit = save)

                    Spacer(Modifier.height(AppSpacing.Md))

                    Text(
                        "光带粗细",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.Sm))
                    CalibrationSlider("粗细", st.edgeLightingWidth, 5f..80f, onChange = { st.edgeLightingWidth = it }, onCommit = save)
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        // ---- 悬浮窗跑马灯 ----
        SettingsSection(
            title = "悬浮窗跑马灯",
            subtitle = if (st.floatingWindowEnabled) {
                "跑马灯浮在屏幕底边之上，长宽随文字自适应：宽度超出一屏就滚动，内边距决定面板厚度"
            } else {
                "当前悬浮窗已关闭，以下样式不会显示；重新开启上方总开关即可恢复"
            },
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
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                CalibrationSlider("内边距", st.marqueeHeight, 4f..28f, onChange = { st.marqueeHeight = it }, onCommit = save)
                Spacer(Modifier.height(AppSpacing.Lg))
                if (st.marqueeAutoColor) {
                    MarqueePhasePreview(st.marqueeHeight)
                } else {
                    Text(
                        "颜色",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.Sm))
                    MarqueeColorPicker(st.marqueeColors, st.marqueeHeight) {
                        st.marqueeColors = it
                        save()
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Xl))
    }
}
