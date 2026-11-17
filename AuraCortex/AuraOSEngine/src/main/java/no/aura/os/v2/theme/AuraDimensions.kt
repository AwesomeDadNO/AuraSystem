package no.aura.os.v2.theme

import androidx.compose.ui.unit.dp

/** 8 dp spacing grid and the radius scale. One radius per component type, everywhere. */
object AuraDimensions {
    val Space4 = 4.dp; val Space8 = 8.dp; val Space12 = 12.dp; val Space16 = 16.dp
    val Space24 = 24.dp; val Space32 = 32.dp; val Space40 = 40.dp

    val RadiusBadge = 4.dp
    val RadiusButton = 10.dp
    val RadiusCard = 12.dp
    val RadiusLargeCard = 16.dp
    val RadiusContainer = 24.dp

    val PageMargin = Space40
    val TopNavHeight = 80.dp
    val RailWidth = 94.dp
    val CardGap = Space16
    val FocusBorder = 2.dp
    const val FocusScale = 1.04f
}
