package no.aura.os.v2.apps

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppGridGeometry {
    const val COLUMNS = 5
    const val WIDTH = 300f
    const val HEIGHT = 168.75f
    const val GAP = 16f
}

/** Owner-approved custom logo artwork; no match grants installation, account access or entitlement. */
enum class ShortcutBrand(val column: Int, val row: Int) {
    NETFLIX(0,0), DISNEY(1,0), PRIME(2,0), YOUTUBE(3,0), APPLE(4,0), MAX(5,0),
    PARAMOUNT(0,1), SKYSHOWTIME(1,1), DISCOVERY(2,1), DAZN(3,1), TWITCH(4,1), CRUNCHYROLL(5,1),
    RAKUTEN(0,2), MUBI(1,2), PLUTO(2,2), HULU(3,2), PEACOCK(4,2), ROKU(5,2),
    BRITBOX(0,3), STARZ(1,3), MGM(2,3), JELLYFIN(3,3), STRIM(4,3), SF_ANYTIME(5,3),
    TV2(0,4), NRK(1,4), NRK_SUPER(2,4), ALTIBOX(3,4), RIKSTV(4,4), TELIA(5,4),
    TOD(0,5), PLEX(1,5), EMBY(2,5), ALLENTE(3,5), VIAPLAY(4,5), VGTV(5,5);

    companion object {
        /** Exact published/previously established packages only. No label/prefix/substring guessing. */
        fun forPackage(packageName: String): ShortcutBrand? = when (packageName) {
            "com.netflix.ninja", "com.netflix.mediaclient" -> NETFLIX
            "com.disney.disneyplus" -> DISNEY
            "com.amazon.amazonvideo.livingroom", "com.amazon.avod" -> PRIME
            "com.google.android.youtube.tv", "com.google.android.youtube" -> YOUTUBE
            "com.apple.atve.androidtv.appletv" -> APPLE
            "com.wbd.stream", "com.hbo.hbonow" -> MAX
            "com.cbs.ott", "com.cbs.ca" -> PARAMOUNT
            "com.skyshowtime.skyshowtime.google" -> SKYSHOWTIME
            "com.discovery.dplay" -> DISCOVERY
            "com.dazn" -> DAZN
            "tv.twitch.android.app" -> TWITCH
            "com.crunchyroll.crunchyroid" -> CRUNCHYROLL
            "tv.wuaki", "tv.wuaki.apptv" -> RAKUTEN
            "com.mubi" -> MUBI
            "tv.pluto.android" -> PLUTO
            "com.hulu.livingroomplus" -> HULU
            "com.peacocktv.peacockandroid" -> PEACOCK
            "com.roku.web.trc" -> ROKU
            "com.britbox.us" -> BRITBOX
            "com.bydeluxe.d3.android.program.starz" -> STARZ
            "org.jellyfin.androidtv" -> JELLYFIN
            "com.strimmobile" -> STRIM
            "no.tv2.sumo", "no.tv2.tv2play" -> TV2
            "no.nrk.tv", "no.nrk.tvhybrid" -> NRK
            "air.no.nrksuper.app" -> NRK_SUPER
            "no.altibox.tv" -> ALTIBOX
            "no.rikstv.app" -> RIKSTV
            "no.get.play.tv" -> TELIA
            "com.todtv.tod" -> TOD
            "com.plexapp.android" -> PLEX
            "tv.emby.embyatv", "com.mb.android" -> EMBY
            "allente.b2c.tvclient" -> ALLENTE
            "com.viaplay.android" -> VIAPLAY
            else -> null
        }
    }
}

/** One immutable ~6MB decoded atlas shared by all logos. Disk decode stays off the main thread. */
private data class LogoAtlas(val image: ImageBitmap, val bounds: Map<ShortcutBrand, LogoPixelBounds>)

private object LogoAtlasCache {
    @Volatile var atlas: LogoAtlas? = null
    val rows = intArrayOf(108, 248, 382, 511, 680, 795)

    /** Called only from Dispatchers.IO; cache image and its source rectangles atomically. */
    fun decode(assets: android.content.res.AssetManager): LogoAtlas? = synchronized(this) {
        atlas ?: runCatching {
            val bitmap = assets.open("app_logos/approved_atlas.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                })
            } ?: return@runCatching null
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in pixels.indices) {
                if ((pixels[i] ushr 24) <= LogoBoundsPolicy.NOISE_ALPHA) pixels[i] = 0
            }
            val bounds = ShortcutBrand.values().mapNotNull { brand ->
                val cell = LogoPixelBounds(brand.column * bitmap.width / 6, rows[brand.row],
                    (brand.column + 1) * bitmap.width / 6, (rows[brand.row] + 140).coerceAtMost(bitmap.height))
                LogoBoundsPolicy.meaningfulBounds(pixels, bitmap.width, cell)?.let { brand to it }
            }.toMap()
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            LogoAtlas(bitmap.asImageBitmap(), bounds)
        }.getOrNull()?.also { atlas = it }
    }
}

/** Transparent original-proportion wordmark inside caller's landscape slot; adds no extra focus target. */
@Composable
fun ShortcutBadge(packageName: String, label: String, modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = { Text(label, color = Color.White) }) {
    val brand = ShortcutBrand.forPackage(packageName)
    val assets = LocalContext.current.applicationContext.assets
    val atlas by produceState(LogoAtlasCache.atlas, assets, brand != null) {
        if (brand != null && value == null) value = withContext(Dispatchers.IO) {
            LogoAtlasCache.decode(assets)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val sourceBounds = brand?.let { atlas?.bounds?.get(it) }
        if (sourceBounds == null) fallback() else {
            val image = atlas!!.image
            Canvas(Modifier.fillMaxSize()) {
                val source = IntSize(sourceBounds.width, sourceBounds.height)
                val scale = minOf(size.width / source.width, size.height / source.height) * LogoBoundsPolicy.CONTENT_FRACTION
                val width = (source.width * scale).toInt().coerceAtLeast(1)
                val height = (source.height * scale).toInt().coerceAtLeast(1)
                drawImage(image, IntOffset(sourceBounds.left, sourceBounds.top), source,
                    IntOffset(((size.width - width) / 2).toInt(), ((size.height - height) / 2).toInt()), IntSize(width, height), filterQuality = FilterQuality.Medium)
            }
        }
    }
}
