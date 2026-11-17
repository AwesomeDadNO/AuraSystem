package no.aura.os.v2.theme

/** Raw ARGB values of the v2 colour tokens (platform-free so they can be unit-tested). Source: docs/V2_DESIGN_SYSTEM.md. */
object AuraPalette {
    const val BACKGROUND = 0xFF1A1D24L
    const val BACKGROUND_DEEP = 0xFF1A1D24L
    const val SURFACE = 0xFF222630L
    const val SURFACE_ELEVATED = 0xFF2B303DL
    const val BORDER = 0xFF3D4454L
    const val TEXT_PRIMARY = 0xFFF5F7FAL
    const val TEXT_SECONDARY = 0xFFA5A6AFL
    const val TEXT_MUTED = 0xFF747782L
    const val ACCENT_BLUE = 0xFF4FC3F7L
    const val PROGRESS_BLUE = 0xFF38A8FFL
    const val FOCUS_BLUE = 0xFF79D5FFL
    /** Orange button gradient start (#ffb856) and end (#e75200). */
    const val BRAND_ORANGE = 0xFFE75200L
    const val ACCENT_WARM = 0xFFFFB856L
    const val ACCENT_DEEP = 0xFFE75200L
    const val BUTTON_INACTIVE = 0xFF2B2E37L
    const val BUTTON_ICON = 0xFFA9A7B2L
    const val ALERT_RED = 0xFFFF6B6BL
    const val SUCCESS = 0xFF45C59AL

    /** Button inactive background (#2b2e37) and icon/text color (#a9a7b2). */
    const val BUTTON_INACTIVE_BG = 0xFF2B2E37L
    const val BUTTON_INACTIVE_ICON = 0xFFA9A7B2L

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double { val c = ((argb shr shift) and 0xFF) / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }
    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Long, b: Long): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }
}
