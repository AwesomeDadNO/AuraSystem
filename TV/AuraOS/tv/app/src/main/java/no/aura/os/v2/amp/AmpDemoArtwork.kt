package no.aura.os.v2.amp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Photo-only regions in the owner's 1672 × 941 approved v5 atlas. No labels or controls. */
object AmpDemoArtwork {
    private var cached: Map<String, ImageBitmap>? = null
    suspend fun load(context: Context): Map<String, ImageBitmap> = withContext(Dispatchers.IO) {
        synchronized(this@AmpDemoArtwork) {
            cached ?: context.assets.open("auraamp/approved_design_v5.png").use { stream ->
                val source = requireNotNull(BitmapFactory.decodeStream(stream))
                val regions = mapOf(
                    "feature" to intArrayOf(560, 133, 580, 220),
                    "bloom" to intArrayOf(1240, 156, 344, 294),
                    "release0" to intArrayOf(121, 409, 248, 78),
                    "release1" to intArrayOf(393, 409, 236, 78),
                    "release2" to intArrayOf(656, 409, 230, 78),
                    "release3" to intArrayOf(912, 409, 229, 78),
                    "northern" to intArrayOf(147, 772, 60, 35),
                    "quiet" to intArrayOf(147, 822, 60, 35),
                    "golden" to intArrayOf(147, 872, 60, 35),
                    "blue" to intArrayOf(393, 409, 236, 78),
                    "night" to intArrayOf(912, 409, 229, 78),
                    "artist0" to intArrayOf(127, 590, 68, 68),
                    "artist1" to intArrayOf(339, 590, 68, 68),
                    "artist2" to intArrayOf(550, 590, 68, 68),
                    "artist3" to intArrayOf(761, 590, 68, 68),
                    "artist4" to intArrayOf(976, 590, 68, 68),
                )
                regions.mapValues { (_, r) -> Bitmap.createBitmap(source, r[0], r[1], r[2], r[3]).asImageBitmap() }
                    .also { source.recycle(); cached = it }
            }
        }
    }
}
