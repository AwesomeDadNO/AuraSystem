package no.aura.os.v2.images

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.aura.os.v2.cards.CategoryArtwork
import no.aura.os.v2.theme.AuraColors

private object BackdropCache { val images = android.util.LruCache<String, ImageBitmap>(2) }
@Composable
fun BundledCategoryBackdrop(name: String, modifier: Modifier, alignment: Alignment) {
    val assets = LocalContext.current.applicationContext.assets
    val allowed = name in CategoryArtwork.names
    val image by produceState<ImageBitmap?>(null, name, assets) {
        value = null
        if (allowed) value = synchronized(BackdropCache.images) { BackdropCache.images.get(name) } ?: withContext(Dispatchers.IO) {
            runCatching { assets.open("category_art/category-$name.webp").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } }.getOrNull()
                ?.also { synchronized(BackdropCache.images) { BackdropCache.images.put(name, it) } }
        }
    }
    image?.let { Image(it, null, modifier.fillMaxSize(), alignment = alignment, contentScale = ContentScale.Crop) }
        ?: Box(modifier.fillMaxSize().background(AuraColors.Surface))
}
