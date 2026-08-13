package com.phoneagent.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.phoneagent.ui.theme.SpringConfigs

/**
 * Button press feedback — scale(0.97) on press with critically-damped spring.
 *
 * Design principles (emilkowalski/skills):
 * 1. "Buttons must feel responsive — add transform: scale(0.97) on :active.
 *    This gives instant feedback, making the UI feel like it is truly listening."
 * 2. "Respond on pointer-down, not on release." — haptic feedback fires
 *    the instant the button is pressed, not on click/release.
 * 3. No bounce on button press — bounce is reserved exclusively for
 *    momentum-driven gestures (flick, drag release).
 */@Composable
fun PressableScale(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onPress: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Fire haptic on press-down, not on release
    LaunchedEffect(pressed) {
        if (pressed) onPress()
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = SpringConfigs.ButtonDampingRatio,
            stiffness = SpringConfigs.ButtonStiffness,
        ),
        label = "press",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        content = { content() },
    )
}

/**
 * Status overview card: icon + title + subtitle + state color.
 *
 * Uses tonal elevation to create depth without heavy shadows —
 * Material 3 surfaces build hierarchy through color, not shadows.
 */
@Composable
fun StatusCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color,
    iconBackground: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(
                alpha = 0.9f,
                cornerRadius = 20.dp,
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = iconBackground,
                    modifier = Modifier.matchParentSize(),
                ) {}
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.invoke()
        }
    }
}

/**
 * Section header with restrained typography.
 *
 * Design principle: "Typography is size-specific — never one tracking value
 * for all sizes. Large text wants negative tracking; small text wants
 * slightly positive tracking for legibility."
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 20.dp, bottom = 10.dp),
    )
}