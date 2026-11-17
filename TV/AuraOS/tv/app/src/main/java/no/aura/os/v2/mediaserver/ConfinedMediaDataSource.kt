package no.aura.os.v2.mediaserver

import android.media.MediaDataSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Bounded window arithmetic shared with the decoder cache; positions outside it trigger a new range. */
object MediaRangeWindow {
    const val CAPACITY = 1024 * 1024
    fun available(start: Long, count: Int, position: Long): Int {
        if (start < 0 || position < start || count <= 0) return 0
        val delta = position - start
        return if (delta >= count) 0 else count - delta.toInt()
    }
    fun end(position: Long): Long = if (position > Long.MAX_VALUE - CAPACITY + 1) Long.MAX_VALUE else position + CAPACITY - 1
}

/** Seekable platform source: every range request stays on the verified URL and redirects fail closed. */
class ConfinedMediaDataSource(private val source: ServerPlayback, private val freshHeaders: suspend () -> MediaResult<Map<String, String>>) : MediaDataSource() {
    @Volatile private var closed = false
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var length = -1L
    private var cache = ByteArray(MediaRangeWindow.CAPACITY)
    private var cacheStart = -1L
    private var cacheCount = 0
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("Source closed")
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) throw IOException("Invalid range")
        if (size == 0) return 0
        if (length >= 0 && position >= length) return -1
        if (MediaRangeWindow.available(cacheStart, cacheCount, position) == 0) fill(position)
        if (closed) throw IOException("Source closed")
        val available = MediaRangeWindow.available(cacheStart, cacheCount, position)
        if (available == 0) return -1
        val count = size.coerceAtMost(available)
        cache.copyInto(buffer, offset, (position - cacheStart).toInt(), (position - cacheStart).toInt() + count)
        return count
    }
    private fun fill(position: Long) {
        cacheStart = -1; cacheCount = 0
        val connection = URL(source.url).openConnection() as HttpURLConnection
        active = connection
        try {
            if (closed) throw IOException("Source closed")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000; connection.readTimeout = 12_000
            val headers = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                when (val r = freshHeaders()) { is MediaResult.Ok -> r.value; is MediaResult.Failure -> throw IOException("Stream authorization failed") }
            }
            if (closed) throw IOException("Source closed")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=$position-${MediaRangeWindow.end(position)}")
            val code = connection.responseCode
            if (code == 416) return
            if (code != 206 && !(code == 200 && position == 0L)) throw IOException("Stream request rejected")
            val range = connection.getHeaderField("Content-Range")
            if (code == 206 && range?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() != position) throw IOException("Invalid stream range")
            val total = range?.substringAfterLast('/')?.toLongOrNull()
                ?: if (code == 200) connection.getHeaderFieldLong("Content-Length", -1) else -1
            if (total >= 0) length = total
            connection.inputStream.use { input ->
                while (cacheCount < cache.size && !closed) {
                    val n = input.read(cache, cacheCount, cache.size - cacheCount)
                    if (n < 0) break
                    if (n == 0) continue
                    cacheCount += n
                }
            }
            if (closed) { cache.fill(0); cacheCount = 0; throw IOException("Source closed") }
            cacheStart = position
        } finally { active = null; connection.disconnect() }
    }
    override fun getSize(): Long = length
    override fun close() { closed = true; active?.disconnect(); cache.fill(0); cacheCount = 0; cacheStart = -1 }
    override fun toString() = "ConfinedMediaDataSource(<redacted>)"
}
