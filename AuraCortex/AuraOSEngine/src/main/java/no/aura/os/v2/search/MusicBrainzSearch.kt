package no.aura.os.v2.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.aura.os.v2.tmdb.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public music metadata, not audio playback. Noncommercial development use; commercial service terms are a release gate.
 * https://musicbrainz.org/doc/MusicBrainz_API (meaningful User-Agent, at most 1 request/second).
 */
class MusicBrainzSearch : SearchSource {
    override suspend fun search(request: SearchRequest): SearchBatch {
        if (request.isChild) return SearchBatch(emptyList(), message = "Music search needs a catalogue with child content ratings for this profile.")
        return MusicBrainzRateLimit.run {
            withContext(Dispatchers.IO) {
                val q = URLEncoder.encode(request.query, "UTF-8")
                val c = URL("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=20&offset=${(request.page - 1) * 20}").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 6000; c.readTimeout = 10000; c.instanceFollowRedirects = false
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "AuraOS-V2/0.1 (https://github.com/AwesomeDadNO/AURA-Ecosystem)")
                    if (c.responseCode != 200) return@withContext SearchBatch(emptyList(), message = "MusicBrainz is unavailable or rate limited.")
                    val body = c.inputStream.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            if (out.size() + read > 512 * 1024) return@withContext SearchBatch(emptyList(), message = "Music response exceeded size limit.")
                            out.write(buffer, 0, read)
                        }
                        String(out.toByteArray(), Charsets.UTF_8)
                    }
                    val root = Json.parse(body).obj()
                    SearchBatch(parse(body), hasMore = (root?.int("count") ?: 0) > request.page * 20,
                        message = "Music metadata by MusicBrainz · audio source not connected")
                } finally { c.disconnect() }
            }
        }
    }
    companion object {
        private val mbid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        fun parse(body: String): List<SearchHit> = Json.parse(body).obj()?.get("recordings").list().mapNotNull { value ->
            val o = value.obj() ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { mbid.matches(it) } ?: return@mapNotNull null
            val title = o.str("title")?.trim()?.take(160)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artist = o["artist-credit"].list().mapNotNull { it.obj()?.str("name") }.joinToString(", ").take(180)
            SearchHit(id, title, "MusicBrainz", SearchKind.MUSIC, "musicbrainz:recording:$id", artist, artistId = o["artist-credit"].list().firstOrNull().obj()?.get("artist").obj()?.str("id")?.takeIf { mbid.matches(it) })
        }.distinctBy { it.target }.take(20)
    }
}

/** Shared across music search and release adapters. */
object MusicBrainzRateLimit {
    private val gate = Mutex()
    private var lastAt: Long? = null
    suspend fun <T> run(block: suspend () -> T): T = gate.withLock {
        val now = System.nanoTime() / 1_000_000L
        val wait = lastAt?.let { 1100L - (now - it) } ?: 0L
        if (wait > 0) delay(wait)
        lastAt = System.nanoTime() / 1_000_000L
        block()
    }
}
