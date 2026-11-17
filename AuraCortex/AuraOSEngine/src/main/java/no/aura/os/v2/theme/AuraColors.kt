package no.aura.os.v2.theme

import androidx.compose.ui.graphics.Color

/** Central colour tokens. Warm logo orange is the approved interaction accent, softened through glass opacity. */
object AuraColors {
    val Background = Color(AuraPalette.BACKGROUND)
    val BackgroundDeep = Color(AuraPalette.BACKGROUND_DEEP)
    val Surface = Color(AuraPalette.SURFACE)
    val SurfaceElevated = Color(AuraPalette.SURFACE_ELEVATED)
    val Border = Color(AuraPalette.BORDER)
    val TextPrimary = Color(AuraPalette.TEXT_PRIMARY)
    val TextSecondary = Color(AuraPalette.TEXT_SECONDARY)
    val TextMuted = Color(AuraPalette.TEXT_MUTED)
    /** Legacy name retained for callers; all app accents now follow mobile orange. */
    val AccentBlue = Color(AuraPalette.BRAND_ORANGE)
    val ProgressBlue = Color(AuraPalette.PROGRESS_BLUE)
    val FocusBlue = Color(AuraPalette.FOCUS_BLUE)
    /** Shared mobile/TV brand and interaction orange. */
    val BrandOrange = Color(AuraPalette.BRAND_ORANGE)
    /** Approved warm focus and playback accent; selected surfaces use restrained opacity. */
    val Focus = BrandOrange
    val Progress = BrandOrange
    val AlertRed = Color(AuraPalette.ALERT_RED)
    val Success = Color(AuraPalette.SUCCESS)

    // Derived (documented in V2_DESIGN_SYSTEM.md, not new hues)
    /** Cream-gold of hero titles, sampled from the approved mockups (not part of the core token list). */
    val HeroTitleTint = Color(0xFFEAD7AC)
    /** Compact player panel, sampled from the mockups. */
    val Panel = Surface
    val OrangeBurnt = BrandOrange
    val OrangeAmber = Color(AuraPalette.ACCENT_WARM)
    val ButtonInactive = Color(AuraPalette.BUTTON_INACTIVE)
    val ButtonIcon = Color(AuraPalette.BUTTON_ICON)
    val ButtonGradient = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFFFFB856), Color(0xFFE75200)))
    val OrangeGradient = ButtonGradient
    val NavPill = BrandOrange.copy(alpha = 0.20f)
    val ButtonInactiveBg = Color(AuraPalette.BUTTON_INACTIVE_BG)
    val ButtonInactiveIcon = Color(AuraPalette.BUTTON_INACTIVE_ICON)
    /** Text on the primary action button. */
    val OnPrimaryAction = Color(0xFFFFFFFF)
    /** Primary action fill. */
    val PrimaryAction = BrandOrange
    /** Card outline at rest. */
    val CardBorder = Border.copy(alpha = 0.6f)
}
