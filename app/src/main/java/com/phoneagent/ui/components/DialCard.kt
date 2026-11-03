package com.phoneagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.phoneagent.core.ai.CatalogModel
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.approvalModeLabel
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.DurationFast
import com.phoneagent.ui.theme.EaseOut
import com.phoneagent.ui.theme.appBorder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 转盘里的一档选项 */
data class DialOption(val id: String, val label: String)

/**
 * 模型转盘候选：模型库名单 + 当前手填/不在库里的模型。
 * 当前值一定在轮上，否则转盘会停在"选项里没有的一档"上，看起来像没生效。
 */
fun modelDialOptions(catalog: List<CatalogModel>, current: String): List<DialOption> {
    val names = catalog.map { it.name }.filter { it.isNotBlank() }.distinct()
    val all = if (current.isNotBlank() && current !in names) listOf(current) + names else names
    return all.map { DialOption(id = it, label = it) }
}

/** 授权等级三档：文案复用设置页的 [approvalModeLabel]，两页与设置页只留一个口径 */
fun approvalDialOptions(): List<DialOption> =
    listOf("ask", "risky", "bypass").map { DialOption(id = it, label = approvalModeLabel(it)) }

/** 转盘可见三行：上一档 / 当前档 / 下一档，小型卡片里刚好够"能转、能看清" */
private val WheelItemHeight = 30.dp
private const val WheelRows = 3

/** 松手后按速度惯性多滑的时长（秒）：太小像滑块，太大会甩过头 */
private const val FlingProjectionSecs = 0.15f

/**
 * 配置卡片：标题 + 当前值，默认折叠；点头部才铺开内嵌转盘，Agent 页与编码页共用。
 *
 * 为什么默认折叠：转盘要占三行高（90dp），两张并排长期挂在输入框上方会把对话区
 * 挤掉一大截；而"当前选了哪个"一行字就够看。展开是随手一步，不牺牲任何可选项。
 *
 * 为什么用转盘而不是分段按钮：
 * - 配置项从输入区主位拉出来后，必须能被一眼识别成"可转轮"而不是"可点按钮"；
 * - 转轴平行于屏幕（rotationX），上下拨动时选项像滚筒一样翻过去，手感与选择动作一致。
 */
@Composable
fun ConfigDialCard(
    title: String,
    icon: ImageVector,
    options: List<DialOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(AppRadii.Item)
    val buzz = rememberHapticClick()
    val density = LocalDensity.current
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    // 弹层的宽度跟随卡片本身；卡片随窗口收放，取实测像素而不是写死
    var cardWidthPx by remember { mutableIntStateOf(0) }
    val expandable = options.size > 1
    val currentLabel = options.firstOrNull { it.id == selectedId }?.label ?: selectedId

    Column(
        modifier = modifier
            .onGloballyPositioned { cardWidthPx = it.size.width }
            .clip(shape)
            .background(colors.surfaceRaised)
            .appBorder(1.dp, colors.outlineSoft, shape)
            .alpha(if (enabled) 1f else 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadii.Inline))
                .clickable(enabled = expandable && enabled) {
                    buzz()
                    expanded = !expanded
                }
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onSurfaceRaised,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(AppSpacing.Xs))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(AppSpacing.Xs))
            // 折叠露当前值、展开交给转盘去显示：同一行不做两份重复信息
            if (!expanded) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.brand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
                if (!note.isNullOrBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.brand,
                        maxLines = 1,
                    )
                }
            }
            if (expandable) {
                Spacer(Modifier.width(AppSpacing.Xs))
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = if (expanded) "收起选项" else "展开选项",
                    tint = colors.onSurfaceRaised,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        // 展开的转盘走弹层：它浮在输入框上方的空白处，不参与这一行的测量，
        // 所以一张卡展开时另一张既不会被顶起、也不会跟着浮上去——两张卡各自独立
        if (expanded && expandable && enabled) {
            val gapPx = with(density) { AppSpacing.Sm.toPx().roundToInt() }
            val positionProvider = remember(gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                            .coerceIn(0, maxX)
                        // 卡片紧贴输入框，只能向上弹：向下会盖住输入框
                        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceIn(0, maxY)
                        return IntOffset(x, y)
                    }
                }
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                val enterState = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = enterState,
                    // 只做透明度 + 位移，不做高度展开：弹层是"贴着卡片上沿"定位的，
                    // 高度从 0 长到满时定位会跟着重算——画面先是出现在下方，再整体跳上去
                    enter = fadeIn(tween(DurationFast, easing = EaseOut)) +
                        slideInVertically(tween(DurationFast, easing = EaseOut)) { it / 3 },
                ) {
                    val popupWidth =
                        if (cardWidthPx > 0) with(density) { cardWidthPx.toDp() } else 160.dp
                    Box(
                        modifier = Modifier
                            .width(popupWidth)
                            .shadow(12.dp, shape)
                            .clip(shape)
                            .background(colors.surfaceRaised)
                            .appBorder(1.dp, colors.outlineSoft, shape)
                            .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                    ) {
                        WheelPicker(
                            options = options,
                            selectedId = selectedId,
                            onSelect = onSelect,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 转盘本体：竖直方向拖动 = 转动，松手吸附到最近一档。
 *
 * 不接 LazyColumn：配置项最多十几条，直接按"离当前滚动值的距离"摆三五行，
 * 既没有列表测量开销，也不会因为 item 回收导致中间高亮行闪烁。
 */
@Composable
private fun WheelPicker(
    options: List<DialOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val itemPx = with(density) { WheelItemHeight.toPx() }
    val selectedIndex = options.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    // 滚动位置用普通浮点状态而不是 Animatable：Animatable 只能在协程里改值，拖动时就得
    // 每帧 launch 一个 snapTo，多个协程互相抢帧——画面上就是"一跳一跳"的来源。
    // 这里拖动直接同步改值，动画只负责松手后的收尾（吸附 + 惯性）
    var offset by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // 收尾动画的目标，用来判断"当前动画去的就是新选中项"，避免重复启动把它掐断
    var animTarget by remember { mutableFloatStateOf(Float.NaN) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * 收尾动画入口：从当前位置滑到 [target]，可带初速度做惯性。
     * 必须是非 suspend 函数——手势在 `awaitEachGesture` 的受限作用域里，不能直接调 suspend。
     */
    fun settle(target: Float, initialVelocity: Float = 0f) {
        animJob?.cancel()
        animTarget = target
        animJob = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            ) { value, _ -> offset = value }
        }
    }

    // 外部改了选中项（模型库刷新、别处切换）：转盘跟上，不打断正在进行的拖动
    LaunchedEffect(selectedIndex, options.size) {
        // 松手/点选时已把目标交给动画，别在这里用零初速重起一次（那会把惯性丢掉）
        if (dragging || (animJob?.isActive == true && abs(animTarget - selectedIndex) < 0.01f)) {
            return@LaunchedEffect
        }
        if (abs(offset - selectedIndex) > 0.01f) settle(selectedIndex.toFloat())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WheelItemHeight * WheelRows)
            .clip(RoundedCornerShape(AppRadii.Inline))
            .pointerInput(options, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var started = false
                    var moved = 0f
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.changedToUpIgnoreConsumed()) break
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        tracker.addPointerInputChange(change)
                        if (!started) {
                            moved += dy
                            if (abs(moved) > viewConfiguration.touchSlop) {
                                started = true
                                dragging = true
                                // 接管前先停掉上一次吸附/惯性动画，避免两个动画抢同一个值
                                animJob?.cancel()
                            }
                        }
                        if (started) {
                            change.consume()
                            // 拖动期间同步改值，跟手就是"手指到哪滚到哪"，不必再套动画
                            offset = (offset - dy / itemPx)
                                .coerceIn(0f, options.lastIndex.toFloat())
                        }
                    }
                    if (!started) return@awaitEachGesture
                    dragging = false
                    // 松手按速度再滑一段并吸附到最近一档：轻甩能越一两档，才像轮盘而不是滑块
                    val velocity = tracker.calculateVelocity().y
                    val projected = (offset - velocity / itemPx * FlingProjectionSecs)
                        .roundToInt().coerceIn(0, options.lastIndex)
                    settle(
                        target = projected.toFloat(),
                        // 速度折算成"档/秒"并夹住上限，手速再快也不会把整轮甩飞
                        initialVelocity = (-velocity / itemPx).coerceIn(-24f, 24f),
                    )
                    if (options[projected].id != selectedId) onSelect(options[projected].id)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 中轴高亮带：与相邻选项区分"当前档"，比只靠字号更稳
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(AppRadii.Inline))
                .background(colors.brandContainer.copy(alpha = 0.45f)),
        )

        val camera = 12f * density.density
        val first = floor(offset).toInt() - 2
        for (i in first..(first + 4)) {
            if (i !in options.indices) continue
            val distance = i - offset
            // 焦点连续衰减到 0，而不是在 1.55 处硬切：硬切会让上下两档在滚动中突然消失，
            // 看起来就是一顿一顿的
            val focus = ((1.6f - abs(distance)) / 1.6f).coerceIn(0f, 1f)
            if (focus <= 0f) continue
            Text(
                // 字号全程不变，只靠缩放/透明度/颜色表达焦点；原先在焦点过半时切换字号，
                // 每滚过一行文字就"弹"一下
                text = options[i].label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = lerp(colors.onSurfaceRaised, colors.onBrandContainer, focus),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Xs)
                    .offset(y = WheelItemHeight * distance)
                    .graphicsLayer {
                        rotationX = (distance * 44f).coerceIn(-78f, 78f)
                        val s = 0.78f + 0.22f * focus
                        scaleX = s
                        scaleY = s
                        cameraDistance = camera
                    }
                    .alpha(focus.pow(0.6f))
                    .clickable(enabled = enabled && i != selectedIndex) {
                        settle(i.toFloat())
                        onSelect(options[i].id)
                    },
            )
        }
    }
}
