package no.aura.os.v2.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions

/**
 * The single v2 focus treatment: sharp blue border, gentle scale and ONE elevation shadow tinted blue (a soft glow on API 28+, no blur layers).
 * The border is only composed while focused, so unfocused items carry no extra drawing cost.
 */
@Composable
fun Modifier.auraFocusable(
    shape: Shape,
    scale: Float = AuraDimensions.FocusScale,
    elevation: Dp = 8.dp,
    showOutline: Boolean = true,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) scale else 1f, tween(if (no.aura.os.v2.theme.LocalReduceMotion.current) 0 else 140), label = "focusScale")
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) onFocused?.invoke() }
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (focused) Modifier.shadow(elevation, shape, clip = false, ambientColor = AuraColors.Focus.copy(alpha = 0.35f), spotColor = AuraColors.Focus.copy(alpha = 0.55f)) else Modifier)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
        // drawn AFTER the inner content (clip/background/artwork applied by the caller), so the border is never covered
        .drawWithContent {
            // Content is ALWAYS drawn; only the outline is conditional on valid geometry.
            drawContent()
            val strokePx = AuraDimensions.FocusBorder.toPx()
            val inset = FocusOutline.insetFor(size.width, size.height, strokePx)
            if (focused && showOutline && inset != null) {
                inset(inset) {
                    // `size` here is the already-inset scope size (> 0 by construction), so the outline matches the stroke's centre line.
                    drawOutline(shape.createOutline(size, layoutDirection, this), AuraColors.Focus, style = Stroke(strokePx))
                }
            }
        }
}

/** Pure geometry guard for the focus outline (unit-tested). */
object FocusOutline {
    /** Inset (half the stroke) for a component of [width] x [height], or null when no valid outline can be drawn (zero/NaN/infinite/smaller than the stroke). */
    fun insetFor(width: Float, height: Float, strokePx: Float): Float? {
        if (!width.isFinite() || !height.isFinite() || !strokePx.isFinite() || strokePx <= 0f) return null
        return if (width > strokePx && height > strokePx) strokePx / 2f else null
    }
}
