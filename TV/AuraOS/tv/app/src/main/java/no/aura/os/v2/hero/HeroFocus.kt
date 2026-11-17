package no.aura.os.v2.hero

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Shared blue focus for outlined hero/episode actions. */
@Composable
internal fun Modifier.heroFocusable(shape: Shape, onFocused: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val highlighted = focused || pressed
    LaunchedEffect(focused) { if (focused) onFocused?.invoke() }
    return this
        .border(if (highlighted) 2.dp else 1.dp, if (highlighted) no.aura.os.v2.theme.AuraColors.Focus else Color.White.copy(alpha = 0.65f), shape)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}
