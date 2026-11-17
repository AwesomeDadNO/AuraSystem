package no.aura.os.v2.cards

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.aura.os.v2.data.CategoryCard
import no.aura.os.v2.theme.*

/** At most six sampled (~418×235) artworks, not twelve full-resolution decodes on the UI thread. */
private object CategoryBitmapCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(6)
    fun get(key: String): ImageBitmap? = synchronized(cache) { cache.get(key) }
    fun put(key: String, value: ImageBitmap) = synchronized(cache) { cache.put(key, value) }
}

@Composable
fun CategoryArtCard(card: CategoryCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val art = remember(card.id) { CategoryArtwork.forId(card.id) }
    val assets = LocalContext.current.applicationContext.assets
    val bitmap by produceState<CategoryArtState>(CategoryArtState(CategoryBitmapCache.get(art.orEmpty())), art, assets) {
        value = CategoryArtState(CategoryBitmapCache.get(art.orEmpty()))
        value = CategoryArtState(art?.let { name -> CategoryBitmapCache.get(name) ?: withContext(Dispatchers.IO) {
            runCatching { assets.open("category_art/category-$name.webp").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() } }.getOrNull()
                ?.also { CategoryBitmapCache.put(name, it) }
        } })
    }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(256.dp, 144.dp).auraFocusable(shape, scale = 1.02f, elevation = 2.dp, onClick = onClick)
        .clip(shape).background(AuraColors.SurfaceElevated).auraGlass(shape)) {
        bitmap.image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(25.dp).height(2.dp).background(AuraColors.Focus))
            Text(card.title, style = AuraTypography.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
private data class CategoryArtState(val image: ImageBitmap?)
