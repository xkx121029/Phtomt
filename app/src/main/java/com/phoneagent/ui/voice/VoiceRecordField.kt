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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.EaseOut
import kotlinx.coroutines.isActive
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

private val GlowCoreBlue = Color(0xFF5B8DEF)    // 光晕核心蓝（参考图取色）
private val GlowPreviewGreen = Color(0xFF3FBB8F) // 预览态光晕
private val CancelRed = Color(0xFFE65C53)        // 取消态光晕

private val DotsCount = 36                // 声波圆点数量
private val DotsSpacing = 5.dp            // 圆点间距
private val DotsYFromBottom = 44.dp       // 圆点条距屏幕底部
private val TipYFromBottom = 96.dp        // 提示文字距屏幕底部

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

/**
 * 录音浮层：全屏透明 Popup，参考图样式 ——
 * 底部锚定的大半径蓝色光晕（随音量呼吸、分区变色）+ 居中白色提示文字 + 底部圆点声波条。
 */
@Composable
private fun RecordingOverlay(
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
        // 入场：整体淡入 160ms（光晕从底部"升起"的观感由渐变本身给出，不做位移）
        val enterAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            enterAlpha.animateTo(1f, tween(160, easing = EaseOut))
        }
        // 分区着色：0 中性 / 1 取消 / -1 预览，120ms 渐变（禁止瞬间跳变）
        val zoneTint by animateFloatAsState(
            targetValue = when (hoverZone) {
                Zone.CANCEL -> 1f
                Zone.PREVIEW -> -1f
                Zone.NONE -> 0f
            },
            animationSpec = tween(120),
            label = "zoneTint",
        )

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = enterAlpha.value },
        ) {
            // 底部光晕：圆心沉到屏幕底边之外，径向渐变三段过渡；音量驱动轻微呼吸
            Canvas(Modifier.fillMaxSize()) {
                val baseR = size.width * 0.72f
                val breathe = 1f + level * 0.06f
                val core = when {
                    zoneTint > 0.5f -> CancelRed
                    zoneTint < -0.5f -> GlowPreviewGreen
                    else -> GlowCoreBlue
                }
                val center = Offset(size.width / 2, size.height + baseR * 0.45f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            core.copy(alpha = 0.95f),
                            core.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = baseR * breathe,
                    ),
                    radius = baseR * breathe,
                    center = center,
                )
            }

            // 圆点声波条：中央驻波，半径随音量起伏；静音时压平成静止小点
            Canvas(Modifier.fillMaxSize()) {
                val spacing = DotsSpacing.toPx()
                val totalW = (DotsCount - 1) * spacing
                val startX = (size.width - totalW) / 2
                val y = size.height - DotsYFromBottom.toPx()
                val baseR = 1.6.dp.toPx()
                val phase = frameNanos / 1_000_000_000f
                val mid = (DotsCount - 1) / 2f
                for (i in 0 until DotsCount) {
                    val dist = (i - mid) / mid // 0 中央 → 1 端点
                    val falloff = 1f - dist * 0.75f
                    val wave = if (silent) 0f
                    else 0.5f + 0.5f * sin(i * 0.55f - phase * 7f)
                    val r = baseR * (0.55f + 1.6f * falloff * (0.25f + 0.75f * level) * wave)
                    drawCircle(
                        color = Color.White.copy(alpha = (0.35f + 0.65f * falloff).coerceAtMost(1f)),
                        radius = r,
                        center = Offset(startX + i * spacing, y),
                    )
                }
            }

            // 提示文字：白色居中，位于声波条上方
            val tipText = when {
                hoverZone == Zone.CANCEL -> "松开手指，取消发送"
                hoverZone == Zone.PREVIEW -> "松手后可编辑文字"
                slideActive -> "左滑预览 · 右滑取消"
                silent -> "没听到声音"
                else -> "松手发送 · 上滑预览或取消"
            }
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = tipText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = TipYFromBottom),
                )
            }
        }
    }
}
