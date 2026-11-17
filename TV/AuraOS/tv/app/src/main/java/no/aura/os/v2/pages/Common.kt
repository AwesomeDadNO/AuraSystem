package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/** Standard panel look shared by cast, source, score, program and secondary-page tiles: Surface fill + thin border, radius 12. */
fun Modifier.panel(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AuraDimensions.RadiusCard), fill: Color = AuraColors.Surface): Modifier =
    this.clip(shape).background(fill).border(1.dp, AuraColors.Border.copy(alpha = 0.75f), shape)

/** Small red dot used by LIVE markers. */
@Composable fun LiveDot(size: Dp = 8.dp) { Box(Modifier.size(size).clip(CircleShape).background(AuraColors.AlertRed)) }

/** "LIVE" marker: red dot + label. */
@Composable fun LiveMarker(text: String = "LIVE", fontSize: Int = 15) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot(); Spacer(Modifier.width(6.dp)); Text(text, style = AuraTypography.Label.copy(color = AuraColors.TextPrimary, fontSize = fontSize.sp))
    }
}

/**
 * Neutral placeholder for team crests / logos: a ring with the team code. Real crests need licensed or produced artwork
 * (listed in references/README.md); this avoids inventing look-alike logos.
 */
@Composable
fun CrestBadge(code: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(AuraColors.SurfaceElevated).border(1.5.dp, AuraColors.AccentBlue.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        Text(code.take(3).uppercase(), style = AuraTypography.Label.copy(fontSize = (size.value * 0.30f).sp, color = AuraColors.TextPrimary), maxLines = 1)
    }
}

/** "Demo" tag shown on secondary demonstration views and illustrative data. */
@Composable
fun DemoTag(text: String = "Demo view · illustrative content", modifier: Modifier = Modifier) {
    Text(text, style = AuraTypography.Label, modifier = modifier.clip(RoundedCornerShape(AuraDimensions.RadiusBadge)).background(AuraColors.SurfaceElevated).padding(horizontal = 10.dp, vertical = 4.dp))
}
