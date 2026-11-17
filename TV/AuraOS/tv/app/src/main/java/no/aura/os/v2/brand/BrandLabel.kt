package no.aura.os.v2.brand

import androidx.compose.runtime.Immutable

/** Two-part AURA section label: [primary] is rendered white, [accent] orange. Orange is branding only; interaction stays blue. */
@Immutable data class BrandLabelSpec(val primary: String, val accent: String) {
    val text: String get() = "$primary $accent"
}

/** The approved label texts. Every branded hero/section label must come from here so styling stays in one component ([AuraBrandLabel]). */
object BrandLabels {
    val RECOMMENDED = BrandLabelSpec("AURA", "RECOMMENDED")      // Home, Movies, TV Shows
    val SPORTS_CENTER = BrandLabelSpec("AURA", "SPORTS CENTER")
    val LIVE_ON_AURA = BrandLabelSpec("LIVE ON", "AURA")
    val AURABOX = BrandLabelSpec("AURA", "BOX")
    val AURAAMP = BrandLabelSpec("AURA", "AMP")
    val AURATUBE = BrandLabelSpec("AURA", "TUBE")
}
