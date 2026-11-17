package no.aura.os.v2.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import no.aura.os.v2.diag.Diag
import no.aura.os.v2.tmdb.Dedup
import no.aura.os.v2.tmdb.TmdbImages
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Provided by the activity; null in previews/tests (artwork then shows the neutral surface). */
val LocalAuraImageLoader = compositionLocalOf<AuraImageLoader?> { null }

/**
 * Small purpose-built image loader (no third-party library):
 * - only approved TMDB/YouTube/Deezer image hosts and paths over HTTPS are fetched (allowlist), no redirects followed, no cookies;
 * - memory LRU bounded in BYTES, disk cache bounded in size and age, both cheap to trim;
 * - one download per URL no matter how many cards ask (shared job, cancelled when the last card leaves composition);
 * - at most [PARALLEL] downloads at once, with disk reads gated separately ([TieredFetch]) so cached images never wait behind slow transfers;
 * - decoding happens off the main thread; card-sized images use RGB_565 (half the memory), hero-sized keep full quality.
 * TMDB image paths are immutable, so a disk entry is valid until it ages out ([MAX_AGE_MS]).
 */
class AuraImageLoader(private val cacheDir: File, private val context: android.content.Context? = null, memoryBytes: Int = DEFAULT_MEMORY_BYTES) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = Dedup<String, ImageBitmap?>(scope)
    private val tiers = TieredFetch(disk = { u -> withContext(Dispatchers.IO) { Diag.timed("img.disk.read") { readDisk(u) } } }, network = { u -> Diag.timed("img.net.download") { download(u) } }, onDiskWrite = ::writeDisk, networkPermits = PARALLEL)
    private val decodeGate = Semaphore(DECODE_PARALLEL)
    private val memory = object : LruCache<String, ImageBitmap>(memoryBytes) {
        // The real allocation: RGB_565 card images are 2 bytes/pixel, ARGB_8888 hero images 4. (Counting every image at 4 bytes/pixel made
        // the cache believe it was full at about half its budget and evict card artwork that then had to be re-read and re-decoded.)
        override fun sizeOf(key: String, value: ImageBitmap): Int = ImageBytes.of(value)
    }
    @Volatile private var trimmed = false
    private val firstDecoded = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Memory-cache lookup only; no I/O. Lets a card draw immediately when its image is already decoded. */
    fun peek(url: String): ImageBitmap? = memory.get(url).also { if (it != null) Diag.count("img.mem.hit") }

    suspend fun load(url: String): ImageBitmap? {
        val isContent = url.startsWith("content://") || url.startsWith("android.resource://") || url.startsWith("file://")
        if (!isContent && !TmdbImages.isAllowed(url) && !YOUTUBE_THUMB.matches(url) && !no.aura.os.v2.amp.AmpArtwork.allowed(url)) return null
        memory.get(url)?.let { Diag.count("img.mem.hit"); return it }
        return flights.run(url) {
            memory.get(url)?.let { return@run it }
            val r = if (isContent && context != null) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes() } }.getOrNull()
                }
                bytes?.let { TieredFetch.Result(it, TieredFetch.Source.NETWORK) }
            } else {
                tiers.fetch(url)
            } ?: return@run null
            Diag.count(if (r.source == TieredFetch.Source.DISK) "img.disk.hit" else "img.net.miss")
            decodeGate.withPermit { Diag.timed("img.decode") { withContext(Dispatchers.Default) { decode(r.bytes, url) } } }?.also {
                memory.put(url, it); Diag.gauge("img.mem.bytes", memory.size().toLong())
                if (firstDecoded.compareAndSet(false, true)) Diag.milestone("firstImageDecoded")
            }
        }
    }

    private fun file(url: String) = File(cacheDir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })

    private fun readDisk(url: String): ByteArray? = try {
        val f = file(url)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) f.readBytes() else null
    } catch (_: Exception) { null }

    private fun writeDisk(url: String, bytes: ByteArray) {
        try {
            cacheDir.mkdirs(); val tmp = File(cacheDir, "tmp-${Thread.currentThread().id}-${System.nanoTime()}")
            tmp.writeBytes(bytes); if (!tmp.renameTo(file(url))) tmp.delete()
            if (!trimmed) { trimmed = true; scope.launch { trim() } }
        } catch (_: Exception) {}
    }

    /** Deletes aged entries, then the oldest until the directory is under [DISK_BYTES]. Runs once per process, in the background. */
    internal fun trim() {
        try {
            val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
            val now = System.currentTimeMillis()
            files.filter { now - it.lastModified() > MAX_AGE_MS || it.name.startsWith("tmp-") }.forEach { it.delete() }
            var total = 0L; val keep = files.filter { it.exists() }.sortedByDescending { it.lastModified() }
            for (f in keep) { total += f.length(); if (total > DISK_BYTES) f.delete() }
        } catch (_: Exception) {}
    }

    private suspend fun download(url: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (_: Exception) { cont.resume(null); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { try { conn.disconnect() } catch (_: Exception) {} }
        pool.execute {
            val r = try {
                conn.connectTimeout = 6_000; conn.readTimeout = 10_000; conn.instanceFollowRedirects = false; conn.useCaches = false
                if (conn.responseCode != 200) null else {
                    val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var ok = true
                    conn.inputStream.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; if (out.size() + n > MAX_IMAGE_BYTES) { ok = false; break }; out.write(buf, 0, n) } }
                    if (ok) out.toByteArray() else null
                }
            } catch (_: Exception) { null } finally { try { conn.disconnect() } catch (_: Exception) {} }
            if (cont.isActive) cont.resume(r)
        }
    }

    private fun decode(bytes: ByteArray, url: String): ImageBitmap? = try {
        val w = TmdbImages.widthOf(url) ?: no.aura.os.v2.amp.AmpArtwork.width(url) ?: if (YOUTUBE_THUMB.matches(url)) 320 else 500
        // Bounds first (no pixel allocation), then subsample if the file is larger than the variant the URL asked for.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = if (w <= 500) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            inSampleSize = DecodeSizing.sampleSize(bounds.outWidth, bounds.outHeight, w)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { memory.evictAll(); null }

    /** Bytes a decoded image really occupies. */
    object ImageBytes {
        fun of(image: ImageBitmap): Int = try { image.asAndroidBitmap().allocationByteCount } catch (_: Throwable) { of(image.width, image.height, 4) }
        /** Fallback and unit-testable arithmetic: [bytesPerPixel] is 2 for RGB_565 and 4 for ARGB_8888. */
        fun of(width: Int, height: Int, bytesPerPixel: Int): Int = (width.toLong() * height * bytesPerPixel).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private val YOUTUBE_THUMB = Regex("^https://i\\.ytimg\\.com/vi/[A-Za-z0-9_-]{11}/mqdefault\\.jpg$")
        const val PARALLEL = 4            // concurrent network downloads
        const val DECODE_PARALLEL = 2
        const val DEFAULT_MEMORY_BYTES = 32 * 1024 * 1024
        const val DISK_BYTES = 64L * 1024 * 1024
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private val pool = Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r, "aura-img").apply { isDaemon = true } }
    }
}
