package no.aura.os.v2.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** The approved mockups are laid out on a 1920x1080 canvas. All v2 dp/sp values are canvas units; this maps them to the real screen. */
object DesignCanvasMath {
    const val WIDTH = 1920f
    const val HEIGHT = 1080f
    /** Pixels per canvas unit for a screen that is [widthPx] wide (1.0 on a 1920 px screen, 2.0 on 3840 px). */
    fun density(widthPx: Float): Float = if (widthPx.isFinite() && widthPx > 0f) widthPx / WIDTH else 1f
}

/**
 * Makes 1dp equal one 1920x1080 design pixel on any TV resolution/density, so dimensions measured on the mockups transfer 1:1.
 *
 * The scale is derived from the display's pixel width, NOT from the ambient [LocalDensity]: every Compose window (the activity, each Dialog)
 * re-provides the platform density at its root, so a dialog does not inherit this scale and must apply it again (see CanvasDialog). Reading
 * pixels makes that re-application idempotent.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current                       // also recomposes this on a configuration change
    val px = LocalContext.current.resources.displayMetrics.widthPixels.toFloat()
    val d = DesignCanvasMath.density(if (px > 0f) px else cfg.screenWidthDp * LocalDensity.current.density)
    val density = remember(d) { Density(density = d, fontScale = 1f) }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
