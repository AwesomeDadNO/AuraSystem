package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/** Truthful Real Mode page for "loading", "nothing to show" and "failed": never fabricated titles. [onRetry] null = no action (loading). */
@Composable
fun RealStatusPage(title: String, body: String?, entry: FocusRequester, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(start = 137.dp, top = 80.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
        Text(title, style = AuraTypography.PageTitle)
        body?.let { Text(it, style = AuraTypography.Body, color = AuraColors.TextSecondary, modifier = Modifier.padding(top = 12.dp).widthIn(max = 760.dp)) }
        if (onRetry != null) {
            val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
            Text("Retry", style = AuraTypography.Button, color = AuraColors.OnPrimaryAction,
                modifier = Modifier.padding(top = 24.dp).focusRequester(entry).auraFocusable(shape, onClick = onRetry).clip(shape).background(AuraColors.PrimaryAction).padding(horizontal = 28.dp, vertical = 12.dp))
        }
    }
}
