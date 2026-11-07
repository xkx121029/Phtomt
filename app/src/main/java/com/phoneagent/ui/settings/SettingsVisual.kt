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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.overlay.OverlayDisplaySettings
import com.phoneagent.overlay.StepTextPlacement
import com.phoneagent.ui.components.AppSurfaceMuted
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/**
 * 标定预览框的颜色。
 *
 * 这里是全项目唯一一处**刻意不用语义色令牌**的界面色：它是一把「标尺」，
 * 需要在任意壁纸、任意主题下都刺眼可见，跟页面其余部分不是一套语言。
 * 收进具名常量只为不留魔法数字，不参与主题色板。
 */
private val CalibrationGuideBorder = Color(0xFFFFD600)

/**
 * 界面效果页：运行时视觉反馈的显示开关与**精调项**。
 *
 * 跑马灯 / 任务卡状态行 / 边缘光效的显隐开关就在它们各自的分区里，
 * 与它们控制的样式放在一起；设置首页「常用」只保留「浮层显示」这一个总开关。
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
    // 边缘光效的开关与样式同页：开关决定要不要预览，标定决定长什么样
    var edgeLightingOn by remember { mutableStateOf(OverlayDisplaySettings.edgeLightingEnabled(context)) }
    // 两处逐步文字是同一个设置（stepPlacement）的展开形态：勾两处 = both，只勾一处 = 对应值
    val initialPlacement = remember { OverlayDisplaySettings.stepTextPlacement(context) }
    var marqueeOn by remember { mutableStateOf(initialPlacement != StepTextPlacement.CARD) }
    var cardOn by remember { mutableStateOf(initialPlacement != StepTextPlacement.MARQUEE) }
    var stepTextHint by remember { mutableStateOf(false) }

    fun updateStepText(nextMarquee: Boolean, nextCard: Boolean) {
        if (!nextMarquee && !nextCard) {
            // 两处都取消会让描述无处可显：保持原勾选并就地提示，不静默变成空状态
            stepTextHint = true
            return
        }
        val next = when {
            nextMarquee && nextCard -> StepTextPlacement.BOTH
            nextMarquee -> StepTextPlacement.MARQUEE
            else -> StepTextPlacement.CARD
        }
        marqueeOn = nextMarquee
        cardOn = nextCard
        OverlayDisplaySettings.setStepTextPlacement(context, next)
    }
    LaunchedEffect(stepTextHint) {
        if (stepTextHint) {
            delay(2_500)
            stepTextHint = false
        }
    }

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
            SettingsPageHeader("界面效果", onBack, subtitle = "光标、边缘光效与跑马灯的显示和样式")
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
            SettingsToggleRow(
                title = "显示边缘光效",
                subtitle = "任务执行时在屏幕边缘显示绿色光带；关闭就不建窗口、不占触摸，任务照常执行",
                value = edgeLightingOn,
            ) { next ->
                edgeLightingOn = next
                OverlayDisplaySettings.setEdgeLightingEnabled(context, next)
            }
            SettingsDivider(inset = false)
            // 标定是低频精调，跟在开关下面：开了才需要调，关着也能改样式
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
                "当前悬浮窗已关闭，以下样式不会显示；到设置首页「常用」重新开启「浮层显示」即可恢复"
            },
            tone = SettingsTone.Brand,
        ) {
            // 两处逐步文字：同一个设置的展开形态，至少保留一处
            SettingsCheckRow(
                title = "顶部跑马灯",
                subtitle = "描述在顶部光带滚动；不勾就整条不显示",
                checked = marqueeOn,
            ) { next ->
                updateStepText(nextMarquee = next, nextCard = cardOn)
            }
            SettingsDivider(inset = false)
            SettingsCheckRow(
                title = "任务卡状态行",
                subtitle = "描述出现在任务卡里；不勾就只留「第 n/m 步」",
                checked = cardOn,
            ) { next ->
                updateStepText(nextMarquee = marqueeOn, nextCard = next)
            }
            if (stepTextHint) {
                Text(
                    "至少保留一处显示",
                    modifier = Modifier.padding(start = AppSpacing.Page, end = AppSpacing.Page, top = AppSpacing.Xxs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SettingsDivider(inset = false)
            // 文案颜色以 `FloatingUi.phaseTone` 为准，改色时同步这里
            SettingsToggleRow(
                title = "跟随状态变色",
                subtitle = "底色由当前阶段决定：观察雾蓝 / 思考墨绿 / 执行品红 / 完成绿 / 出错红 / 等待琥珀，不用自己配色",
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
