package no.aura.os.v2.shell

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.aura.os.v2.theme.AuraColors

/** Selection remains visible when remote focus leaves, without changing target geometry. */
object NavigationFocusPolicy {
    fun accented(selected: Boolean, focused: Boolean): Boolean = selected || focused
    fun underlineDp(selected: Boolean, focused: Boolean) = if(focused) 3 else if(selected) 2 else 0
}

class NavigationInteraction(val source: MutableInteractionSource, private val focus: State<Boolean>) {
    val focused: Boolean get() = focus.value
}

@Composable
fun rememberNavigationInteraction(): NavigationInteraction {
    val source = remember { MutableInteractionSource() }
    val focus = source.collectIsFocusedAsState()
    return remember(source, focus) { NavigationInteraction(source, focus) }
}

/** Text menus use only a logo-orange underline; icon rails opt out of line decoration. */
fun Modifier.auraNavigationFocusable(
    interaction: NavigationInteraction,
    selected: Boolean = false,
    underline: Boolean = true,
    onClick: () -> Unit,
): Modifier = semantics { this.selected = selected }
    .clickable(interactionSource=interaction.source,indication=null,role=Role.Button,onClick=onClick)
    .drawWithContent {
        drawContent()
        if(underline && (selected || interaction.focused)) {
            val stroke=NavigationFocusPolicy.underlineDp(selected,interaction.focused).dp.toPx()
            drawLine(AuraColors.Focus.copy(alpha=if(interaction.focused) 1f else .8f),
                Offset(6.dp.toPx(),size.height-stroke/2),Offset(size.width-6.dp.toPx(),size.height-stroke/2),strokeWidth=stroke)
        }
    }
