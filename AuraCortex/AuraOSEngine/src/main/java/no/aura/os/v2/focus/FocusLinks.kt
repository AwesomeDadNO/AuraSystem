@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Deterministic TV focus traversal. Instead of relying on spatial nearest-neighbour search (which let DPAD_DOWN fall into the left rail),
 * rows and hero actions declare explicit up/down targets. The side rail is entered only with DPAD_LEFT from the first item of a row/hero.
 */
val LocalRailRequester = compositionLocalOf<FocusRequester?> { null }

/** Marks the first item of a row: DPAD_LEFT there enters the rail. Other items keep ordinary left/right movement. */
@Composable
fun Modifier.leftToRail(isFirst: Boolean): Modifier {
    val rail = LocalRailRequester.current
    return if (isFirst && rail != null) focusProperties { left = rail } else this
}

/** Explicit vertical links, inherited by every focusable descendant (cards in a row, hero buttons). */
fun Modifier.verticalLinks(up: FocusRequester = FocusRequester.Default, down: FocusRequester = FocusRequester.Default): Modifier =
    focusProperties { this.up = up; this.down = down }

/** Pure description of a vertical page layout, used to compute and test the up/down chain. */
object FocusChain {
    /** Row [index] of [rowCount] rows: -1 = the entry above (hero/primary action), and null = stay (nothing below the last row). */
    fun up(index: Int): Int = index - 1
    fun down(index: Int, rowCount: Int): Int? = if (index + 1 < rowCount) index + 1 else null
}
