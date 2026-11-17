package no.aura.os.v2.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.theme.AuraColors

/**
 * Renders [Artwork]: bundled drawable by name (Demo Mode) -> neutral gradient -> neutral surface. No generated silhouettes.
 * The bundled drawables are tiny interim colour fields (see references/README.md), upscaled with bilinear filtering.
 */
@Composable
fun ArtworkImage(art: Artwork, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    val id = remember(art.asset) { art.asset?.let { ctx.resources.getIdentifier(it, "drawable", ctx.packageName) } ?: 0 }
    when {
        art.imageUrl != null -> RemoteArtwork(art.imageUrl, modifier, alignment, contentScale)
        art.asset?.startsWith("category:") == true -> no.aura.os.v2.images.BundledCategoryBackdrop(art.asset.removePrefix("category:"), modifier, alignment)
        id != 0 -> Image(painterResource(id), null, modifier.fillMaxSize(), alignment = alignment, contentScale = contentScale)
        art.demo != null -> Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(art.demo.top), Color(art.demo.bottom)))))
        else -> Box(modifier.fillMaxSize().background(AuraColors.Surface))
    }
}

/**
 * Real artwork (Real Mode). Draws at once if the bitmap is already in memory, otherwise a neutral surface until it arrives; a failed or
 * missing image stays a neutral surface. Never substitutes a generated or demo picture for real content.
 */
@Composable
private fun RemoteArtwork(url: String, modifier: Modifier, alignment: Alignment, contentScale: ContentScale) {
    val loader = no.aura.os.v2.images.LocalAuraImageLoader.current
    var bitmap by remember(url, loader) { mutableStateOf(loader?.peek(url)) }
    // Keyed by url: leaving composition (scrolled away) cancels the request.
    LaunchedEffect(url, loader) { if (bitmap == null && loader != null) bitmap = loader.load(url) }
    val b = bitmap
    if (b != null) Image(b, null, modifier.fillMaxSize(), alignment = alignment, contentScale = contentScale)
    else Box(modifier.fillMaxSize().background(AuraColors.Surface))
}
