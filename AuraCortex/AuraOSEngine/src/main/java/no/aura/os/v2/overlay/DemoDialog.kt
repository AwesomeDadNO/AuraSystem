package no.aura.os.v2.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/** A clearly labelled demo overlay (simulated player, details, queue, program details ...). */
data class DemoDialog(val title: String, val lines: List<String>, val footer: String? = "Demo Mode · illustrative content") {
    companion object { fun simple(what: String) = DemoDialog("Demo Mode", listOf("$what — simulated in the visual demo.", "No network, accounts or live sources are used.")) }
}

/** Separate dialog window: focus is trapped inside; Close takes initial focus; Back dismisses. */
@Composable
fun DemoDialogHost(dialog: DemoDialog, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    CanvasDialog(onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(AuraDimensions.RadiusContainer)).background(AuraColors.SurfaceElevated).padding(AuraDimensions.Space32).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(AuraDimensions.Space12),
        ) {
            Text(dialog.title, style = AuraTypography.SectionTitle)
            dialog.lines.forEach { Text(it, style = AuraTypography.Body, color = AuraColors.TextSecondary) }
            dialog.footer?.let { Text(it, style = AuraTypography.Label) }
            val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
            Text("Close", style = AuraTypography.Button, color = AuraColors.OnPrimaryAction,
                modifier = Modifier.padding(top = AuraDimensions.Space8).focusRequester(fr).auraFocusable(shape, onClick = onClose).clip(shape).background(AuraColors.PrimaryAction).padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { fr.requestFocus() } }
}
