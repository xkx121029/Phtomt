package com.phoneagent.ui.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.EaseOut
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.sin

/** 松手后的去向：原地松手直接发送；上滑至左区松手进入预览编辑；上滑至右区松手取消 */
enum class VoiceFinishMode { SEND, PREVIEW }

/** 录音态悬停分区 */
private enum class Zone { NONE, PREVIEW, CANCEL }

private enum class RecState { IDLE, RECORDING }

// ===== 调参区（改这里即可，规格出处见交互说明）=====
private const val LONG_PRESS_MS = 250L      // 长按判定：按下持续此时长进入录音，提前抬起算普通点击（唤键盘）
private const val TOO_SHORT_MS = 800L       // 误触阈值：录音短于此判「说话时间太短」
private val SLIDE_TRIGGER_DP = 60.dp        // 上滑触发分区的垂直阈值（dy = startY - y）
private val ZONE_OFFSET_DP = 112.dp         // 左右分区中心相对气泡中心的水平偏移
private const val SILENCE_LEVEL = 0.08f     // 静音电平阈值（监听态判定）
private const val SILENCE_MS = 300L         // 静音持续时长（纳秒比较用，单位 ms）

private val BubbleDiameter = 88.dp          // 气泡本体直径
private val BubbleGapAboveField = 24.dp     // 气泡底部距输入框顶部的间隙
private val RingMin = 44.dp                 // 外圈环最小半径
private val RingMax = 62.dp                 // 外圈环最大半径（随音量）
private val PressCircleDiameter = 150.dp    // 按压处深色径向渐变圆直径
private val TipGapAboveBubble = 12.dp       // 提示胶囊距气泡顶部间隙

private val CancelRed = Color(0xFFE65C53)   // 取消态主色（规格指定）
private val TipBgNeutral = Color(0xFF454545) // 提示胶囊中性底色（规格指定）
private val BubbleCenterNeutral = Color(0xFF8E939B)
private val BubbleEdgeNeutral = Color(0xFF53575E)
private val BubbleCenterCancel = Color(0xFFEF7A72)
private val BubbleEdgeCancel = Color(0xFFC94740)
private val BubbleCenterPreview = Color(0xFF7FA0E8)
private val BubbleEdgePreview = Color(0xFF46689F)

/** Popup 定位到窗口 (0,0) 并铺满：独立 window 层级，键盘弹出/列表滚动不会裁剪或抖动浮层 */
private object FullWindowPopupProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * 语音录入手势容器：包裹任意内容，提供「长按 250ms 录音 → 音量气泡 → 上滑预览/取消 → 松手分发」交互。
 *
 * 解耦点：
 * - 音频采集与识别全部在 [VoiceRecordEngine] 实现里，本组件不出现 AudioRecord / ASR / 网络；
 * - [onFinishRecord] 只拿到 (filePath, durationMs, mode)，识别文本经 engine.finalText 异步到达，
 *   由调用方决定「自动发送」还是「填入输入框预览编辑」；
 * - 手势规则：[voiceEnabled] 为 false 时手势层不存在（键盘拉起后长按让位系统文本选择）。
 *
 * 其余调参常量见文件顶部「调参区」。
 */
@Composable
fun VoiceRecordGestureBox(
    engine: VoiceRecordEngine,
    voiceEnabled: Boolean,
    /** 短按（未达长按阈值抬起）回调：通常用于唤出键盘 */
    onTap: () -> Unit,
    onFinishRecord: (filePath: String?, durationMs: Long, mode: VoiceFinishMode) -> Unit,
    modifier: Modifier = Modifier,
    onStartRecord: () -> Unit = {},
    onCancelRecord: () -> Unit = {},
    onRecordTooShort: () -> Unit = {},
    onStartFailed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var recState by remember { mutableStateOf(RecState.IDLE) }
    var hoverZone by remember { mutableStateOf(Zone.NONE) }
    var slideActive by remember { mutableStateOf(false) }
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    var pressPoint by remember { mutableStateOf(Offset.Zero) } // 窗口坐标（root 坐标）
    val haptic = LocalHapticFeedback.current
    val currentEngine by rememberUpdatedState(engine)

    // 一帧循环驱动所有动画（规格第五节第 5 条）：音量低通、静音监听态、波浪相位
    var smoothedLevel by remember { mutableFloatStateOf(0f) }
    var silent by remember { mutableStateOf(false) }
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recState) {
        if (recState == RecState.IDLE) {
            smoothedLevel = 0f
            silent = false
            return@LaunchedEffect
        }
        var smoothed = 0f
        var silenceStartNanos = -1L
        while (isActive) {
            withFrameNanos { now ->
                // 规格第五节第 3 条：一阶低通，不做必然抖动
                smoothed = smoothed * 0.7f + currentEngine.level.value * 0.3f
                smoothedLevel = smoothed
                frameNanos = now
                if (smoothed < SILENCE_LEVEL) {
                    if (silenceStartNanos < 0) silenceStartNanos = now
                    silent = now - silenceStartNanos > SILENCE_MS * 1_000_000
                } else {
                    silenceStartNanos = -1L
                    silent = false
                }
            }
        }
    }

    // 异常路径：录音中来电/切后台/旋转屏幕导致组合销毁 → 静默丢弃（规格第五节.3）
    val stateNow by rememberUpdatedState(recState)
    DisposableEffect(Unit) {
        onDispose { if (stateNow != RecState.IDLE) currentEngine.cancel() }
    }

    Box(
        modifier = modifier.onGloballyPositioned { fieldBounds = it.boundsInRoot() },
    ) {
        content()

        // 长按手势拦截层：仅 voiceEnabled 时存在 —— 键盘拉起后长按走系统文本选择（交互确认项）
        if (voiceEnabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressPoint = down.position + fieldBounds.topLeft
                            // 250ms 内抬起 → 普通点击，唤出键盘；超时未抬起 → 进入录音；
                            // 期间事件被系统消费（来电/弹窗）→ 视为取消
                            var tap = false
                            var lost = false
                            withTimeoutOrNull(LONG_PRESS_MS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val c = event.changes.firstOrNull { it.id == down.id } ?: continue
                                    if (c.changedToUp()) {
                                        tap = true
                                        break
                                    }
                                    if (c.isConsumed) {
                                        lost = true
                                        break
                                    }
                                }
                            }
                            if (tap) onTap()
                            if (tap || lost) return@awaitEachGesture
                            if (!currentEngine.start()) {
                                onStartFailed() // 权限/识别服务不可用，由调用方引导授权
                                return@awaitEachGesture
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hoverZone = Zone.NONE
                            slideActive = false
                            recState = RecState.RECORDING
                            onStartRecord()

                            val startY = down.position.y
                            var hovered = Zone.NONE
                            var slid = false
                            var fingerUp = false
                            while (true) {
                                val event = awaitPointerEvent()
                                // 只追踪发起按压的那根手指，忽略多指干扰
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    fingerUp = change.changedToUp()
                                    change.consume()
                                    break
                                }
                                val dy = startY - change.position.y
                                if (dy > SLIDE_TRIGGER_DP.toPx()) {
                                    slid = true
                                    val dx = change.position.x - down.position.x
                                    val zoneOffsetPx = ZONE_OFFSET_DP.toPx()
                                    hovered = when {
                                        dx < -zoneOffsetPx * 0.5f -> Zone.PREVIEW
                                        dx > zoneOffsetPx * 0.5f -> Zone.CANCEL
                                        else -> Zone.NONE
                                    }
                                } else {
                                    slid = false
                                    hovered = Zone.NONE
                                }
                                hoverZone = hovered
                                slideActive = slid
                                change.consume()
                            }

                            // 松手 → 按当前悬停分区决定去向；浮层随 recState 复位 0ms 消失
                            val zone = hovered
                            val wasSlid = slid
                            hoverZone = Zone.NONE
                            slideActive = false
                            when {
                                !fingerUp -> { // ACTION_CANCEL（来电/切后台等）→ 静默丢弃
                                    currentEngine.cancel()
                                }
                                zone == Zone.CANCEL -> {
                                    currentEngine.cancel()
                                    recState = RecState.IDLE
                                    onCancelRecord()
                                    return@awaitEachGesture
                                }
                                else -> {
                                    val capture = currentEngine.stop()
                                    recState = RecState.IDLE
                                    if (capture == null) return@awaitEachGesture
                                    if (capture.durationMs < TOO_SHORT_MS) {
                                        onRecordTooShort()
                                    } else {
                                        val mode = if (wasSlid && zone == Zone.PREVIEW) {
                                            VoiceFinishMode.PREVIEW
                                        } else {
                                            VoiceFinishMode.SEND
                                        }
                                        onFinishRecord(capture.filePath, capture.durationMs, mode)
                                    }
                                }
                            }
                            recState = RecState.IDLE
                        }
                    },
            )
        }
    }

    if (recState == RecState.RECORDING) {
        RecordingOverlay(
            anchor = fieldBounds,
            pressPoint = pressPoint,
            level = smoothedLevel,
            silent = silent,
            frameNanos = frameNanos,
            slideActive = slideActive,
            hoverZone = hoverZone,
        )
    }
}

/**
 * 语音输入输入框：[VoiceRecordGestureBox] 的输入框特例，自带 BasicTextField。
 * 手势规则：键盘收起时长按=录音、短按=唤出键盘；键盘拉起后语音逻辑完全不生效。
 */
@Composable
fun VoiceRecordField(
    engine: VoiceRecordEngine,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onFinishRecord: (filePath: String?, durationMs: Long, mode: VoiceFinishMode) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "按住说话",
    onStartRecord: () -> Unit = {},
    onCancelRecord: () -> Unit = {},
    onRecordTooShort: () -> Unit = {},
    onStartFailed: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    VoiceRecordGestureBox(
        engine = engine,
        voiceEnabled = !focused,
        onTap = { focusRequester.requestFocus() },
        onFinishRecord = onFinishRecord,
        modifier = modifier,
        onStartRecord = onStartRecord,
        onCancelRecord = onCancelRecord,
        onRecordTooShort = onRecordTooShort,
        onStartFailed = onStartFailed,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
            ),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(AppRadii.Inline))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(Modifier.padding(AppSpacing.Md)) {
                        if (value.isEmpty()) {
                            Text(
                                text = if (focused) placeholder else "$placeholder · 长按语音",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                }
            },
        )
    }
}

/** 录音浮层：全屏透明 Popup，按压深色圆 + 音量气泡 + 提示胶囊 + 左右分区 */
@Composable
private fun RecordingOverlay(
    anchor: Rect,
    pressPoint: Offset,
    level: Float,
    silent: Boolean,
    frameNanos: Long,
    slideActive: Boolean,
    hoverZone: Zone,
) {
    Popup(
        popupPositionProvider = FullWindowPopupProvider,
        properties = PopupProperties(focusable = false),
    ) {
        val density = LocalDensity.current
        val primary = MaterialTheme.colorScheme.primary
        // 入场：opacity 0→1 + scale 0.82→1.0，160ms（规格第一节.1）
        val enterScale = remember { Animatable(0.82f) }
        val enterAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            enterScale.animateTo(1f, tween(160, easing = EaseOut))
        }
        LaunchedEffect(Unit) {
            enterAlpha.animateTo(1f, tween(160, easing = EaseOut))
        }
        // 状态切换 120ms 渐变（规格第一节.2：禁止瞬间跳变）
        val zoneTint by animateFloatAsState(
            targetValue = when (hoverZone) {
                Zone.CANCEL -> 1f
                Zone.PREVIEW -> -1f
                Zone.NONE -> 0f
            },
            animationSpec = tween(120),
            label = "zoneTint",
        )
        val zonesAlpha by animateFloatAsState(
            targetValue = if (slideActive) 1f else 0f,
            animationSpec = tween(120),
            label = "zonesAlpha",
        )

        val bubbleCenter = with(density) {
            Offset(
                anchor.center.x,
                anchor.top - (BubbleGapAboveField + BubbleDiameter / 2).toPx(),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = enterAlpha.value
                    scaleX = enterScale.value
                    scaleY = enterScale.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        bubbleCenter.x / size.width,
                        bubbleCenter.y / size.height,
                    )
                },
        ) {
            // 按压区域：150dp 径向渐变深色圆，随浮层淡入淡出，无位移、无系统 Ripple
            Canvas(Modifier.fillMaxSize()) {
                val r = PressCircleDiameter.toPx() / 2
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x33000000), Color.Transparent),
                        center = pressPoint,
                        radius = r,
                    ),
                    radius = r,
                    center = pressPoint,
                )
            }

            // 气泡：外圈描边环（44~62dp，透明度 0.2~0.55）+ 内圆径向渐变（缩放 0.95~1.15）
            Canvas(Modifier.fillMaxSize()) {
                val baseR = (BubbleDiameter / 2).toPx()
                val ringR = lerp(RingMin, RingMax, level).toPx()
                val ringAlpha = androidx.compose.ui.util.lerp(0.2f, 0.55f, level)
                val innerScale = androidx.compose.ui.util.lerp(0.95f, 1.15f, level)
                val centerColor = when {
                    zoneTint > 0.5f -> BubbleCenterCancel
                    zoneTint < -0.5f -> BubbleCenterPreview
                    else -> BubbleCenterNeutral
                }
                val edgeColor = when {
                    zoneTint > 0.5f -> BubbleEdgeCancel
                    zoneTint < -0.5f -> BubbleEdgePreview
                    else -> BubbleEdgeNeutral
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(centerColor, edgeColor),
                        center = bubbleCenter,
                        radius = baseR * innerScale,
                    ),
                    radius = baseR * innerScale,
                    center = bubbleCenter,
                )
                drawCircle(
                    color = Color.White.copy(alpha = ringAlpha),
                    radius = ringR,
                    center = bubbleCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                )
            }

            // 麦克风图标
            Icon(
                imageVector = AppIcons.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .offset {
                        val icon = 26.dp.toPx()
                        IntOffset(
                            (bubbleCenter.x - icon / 2).roundToInt(),
                            (bubbleCenter.y - icon / 2).roundToInt(),
                        )
                    }
                    .size(26.dp),
            )

            // 监听态：静音持续 300ms 后，小波浪从左到右循环移动（规格第五节.4）
            if (silent) {
                Canvas(
                    Modifier
                        .offset {
                            IntOffset(
                                (bubbleCenter.x - 32.dp.toPx()).roundToInt(),
                                (bubbleCenter.y - 34.dp.toPx()).roundToInt(),
                            )
                        }
                        .size(64.dp, 10.dp),
                ) {
                    val phase = frameNanos / 1_000_000_000f * 4f
                    val step = 3.dp.toPx()
                    var x = 0f
                    val path = androidx.compose.ui.graphics.Path()
                    while (x <= size.width) {
                        val y = size.height / 2 + sin(x / 9.dp.toPx() + phase * 6f) * size.height / 2.4f
                        if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += step
                    }
                    drawPath(
                        path,
                        color = Color.White.copy(alpha = 0.85f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // 提示胶囊：气泡上方 12dp，深色底 #454545，取消态 #E65C53
            val tipText = when (hoverZone) {
                Zone.CANCEL -> "松开手指，取消发送"
                Zone.PREVIEW -> "松手后可编辑文字"
                Zone.NONE -> if (silent) "没听到声音" else "松手发送 · 上滑预览或取消"
            }
            val tipBg = when {
                hoverZone == Zone.CANCEL -> CancelRed
                hoverZone == Zone.PREVIEW -> primary
                else -> TipBgNeutral
            }
            Box(
                Modifier
                    .offset {
                        val tipY = bubbleCenter.y -
                            (BubbleDiameter / 2 + TipGapAboveBubble + 24.dp).toPx()
                        IntOffset(
                            (bubbleCenter.x - 88.dp.toPx()).roundToInt(),
                            tipY.roundToInt(),
                        )
                    }
                    .size(176.dp, 24.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(tipBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tipText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }

            // 上滑后的左右分区：左「预览编辑」右「取消发送」，悬停高亮
            val zoneOffsetPx = with(density) { ZONE_OFFSET_DP.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = zonesAlpha },
            ) {
                ZonePill(
                    label = "预览编辑",
                    center = Offset(bubbleCenter.x - zoneOffsetPx, bubbleCenter.y),
                    selected = hoverZone == Zone.PREVIEW,
                    selectedColor = primary,
                )
                ZonePill(
                    label = "取消发送",
                    center = Offset(bubbleCenter.x + zoneOffsetPx, bubbleCenter.y),
                    selected = hoverZone == Zone.CANCEL,
                    selectedColor = CancelRed,
                )
            }
        }
    }
}

@Composable
private fun ZonePill(
    label: String,
    center: Offset,
    selected: Boolean,
    selectedColor: Color,
) {
    Box(
        Modifier
            .offset {
                IntOffset(
                    (center.x - 48.dp.toPx()).roundToInt(),
                    (center.y - 20.dp.toPx()).roundToInt(),
                )
            }
            .size(96.dp, 40.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(if (selected) selectedColor else Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
