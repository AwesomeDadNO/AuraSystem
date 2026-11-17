package no.aura.os.v2.overlay

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import no.aura.os.v2.theme.DesignCanvas
import androidx.compose.runtime.CompositionLocalProvider
import no.aura.os.v2.images.LocalAuraImageLoader

/**
 * Every AuraOS dialog goes through here. A Compose [Dialog] is its own window with its own root, and that root provides the PLATFORM
 * density again: without [DesignCanvas] inside it, "canvas units" silently become device dp (2 px each on a 1080p xhdpi Google TV), so
 * everything in the dialog is drawn at twice its designed size. [fullScreen] dialogs (title details, video player) take the whole display.
 */
@Composable
fun CanvasDialog(onDismissRequest: () -> Unit, fullScreen: Boolean = false, dismissOnClickOutside: Boolean = true, content: @Composable () -> Unit) {
    val properties = if (fullScreen) DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = dismissOnClickOutside) else DialogProperties(dismissOnClickOutside = dismissOnClickOutside)
    // Capture the loader in the calling composition and explicitly re-provide it in the Dialog's window.
    val imageLoader = LocalAuraImageLoader.current
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalAuraImageLoader provides imageLoader) { DesignCanvas(content) }
    }
}
