package no.aura.os.v2.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AuraColors.BrandOrange, secondary = AuraColors.OrangeAmber, onPrimary = AuraColors.OnPrimaryAction,
            background = AuraColors.Background, onBackground = AuraColors.TextPrimary,
            surface = AuraColors.Surface, onSurface = AuraColors.TextPrimary,
            surfaceVariant = AuraColors.SurfaceElevated, onSurfaceVariant = AuraColors.TextSecondary,
            outline = AuraColors.Border, error = AuraColors.AlertRed,
        ),
        content = content,
    )
}
