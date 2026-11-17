package no.aura.os.v2.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Typography tokens. Hero title: system serif (permitted equivalent of the mockup serif). Everything else: system sans. */
object AuraTypography {
    /** Units are 1920x1080 canvas sp (see [DesignCanvas]); sizes measured on the approved mockups. */
    val HeroTitle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 86.sp, lineHeight = 88.sp, letterSpacing = 0.01.em, color = androidx.compose.ui.graphics.Color.White, shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha=.85f), androidx.compose.ui.geometry.Offset(2f,3f),8f))
    /** Small "THE" line above a hero title. */
    val HeroTitlePrefix = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 0.1.em, color = androidx.compose.ui.graphics.Color.White, shadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color.Black.copy(alpha=.85f), androidx.compose.ui.geometry.Offset(2f,3f),8f))
    val PageTitle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, color = AuraColors.TextPrimary)
    val SectionTitle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, color = AuraColors.TextPrimary)
    val SectionLabel = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, color = AuraColors.AccentBlue)
    /** AURA section label: bold sans, compact. */
    val BrandLabel = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = 0.06.em, color = AuraColors.TextPrimary)
    val CardTitle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, color = AuraColors.TextPrimary)
    val Body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 29.sp, color = AuraColors.TextPrimary)
    val Metadata = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, color = AuraColors.TextSecondary)
    val Label = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, color = AuraColors.TextSecondary)
    val Nav = TextStyle(fontWeight = FontWeight.Normal, fontSize = 20.sp, color = AuraColors.TextPrimary)
    val Button = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, color = AuraColors.TextPrimary)
    /** Wordmark "AURA" in the top navigation. */
    val Brand = TextStyle(fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = 0.04.em, color = AuraColors.TextPrimary)
    /** Title set inside cards (letter-spaced serif caps, as in the mockups). */
    val ArtTitle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 20.sp, letterSpacing = 0.14.em, color = AuraColors.TextPrimary)
    val ArtTitlePrefix = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 0.14.em, color = AuraColors.TextPrimary)
}
