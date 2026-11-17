package no.aura.os.v2.ultramax

import kotlinx.coroutines.suspendCancellableCoroutine
import no.aura.os.v2.tmdb.HttpResponse
import no.aura.os.v2.tmdb.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The UltraMax connection code: exactly 8 letters/digits (stored upper-case). It is a credential: [toString] is masked, and the value is
 * never logged, shown after saving, or placed anywhere except the fixed UltraMax URL built inside [UrlConnectionUltraHttp].
 */
class UltraCode private constructor(internal val value: String) {
    override fun toString() = "UltraCode(••••••••)"
    override fun equals(other: Any?) = other is UltraCode && other.value == value
    override fun hashCode() = value.hashCode()
    companion object {
        const val LENGTH = 8
        fun parse(raw: String?): UltraCode? = raw?.trim()?.uppercase()?.takeIf { it.length == LENGTH && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }?.let(::UltraCode)
        fun isValidChar(c: Char) = c in 'A'..'Z' || c in '0'..'9'
    }
}

/** Requests the client may make. Anything else cannot be expressed, so no arbitrary URL or path ever reaches the network. */
sealed class UltraRoute(val path: String) {
    object Manifest : UltraRoute("manifest.json")
    class Movie(imdb: String) : UltraRoute("stream/movie/$imdb.json") { init { require(IMDB.matches(imdb)) } }
    class Episode(imdb: String, season: Int, episode: Int) : UltraRoute("stream/series/$imdb:$season:$episode.json") { init { require(IMDB.matches(imdb) && season in 1..999 && episode in 1..9999) } }
    companion object { val IMDB = Regex("tt[0-9]{1,10}") }
}

interface UltraHttp { suspend fun get(code: UltraCode, route: UltraRoute): HttpResponse }

/** `https://ultramax.vip/c/<CODE>/<route>`: fixed host, HTTPS only, no redirects, no logging, body <= 4 MB, connect 6 s / read 15 s, cancellable. */
class UrlConnectionUltraHttp : UltraHttp {
    override suspend fun get(code: UltraCode, route: UltraRoute): HttpResponse = suspendCancellableCoroutine { cont ->
        val conn = try { URL("https://$HOST/c/${code.value}/${route.path}").openConnection() as HttpURLConnection } catch (_: Exception) { cont.resume(HttpResponse(0, null)); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { try { conn.disconnect() } catch (_: Exception) {} }
        pool.execute {
            val r = try { exchange(conn) } catch (_: Exception) { HttpResponse(0, null) } finally { try { conn.disconnect() } catch (_: Exception) {} }
            if (cont.isActive) cont.resume(r)
        }
    }
    private fun exchange(conn: HttpURLConnection): HttpResponse {
        conn.connectTimeout = 6_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false; conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        val status = conn.responseCode
        val stream = (if (status in 200..299) conn.inputStream else conn.errorStream) ?: return HttpResponse(status, null)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        stream.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; if (out.size() + n > MAX_BODY) return HttpResponse(status, null); out.write(buf, 0, n) } }
        return HttpResponse(status, out.toString("UTF-8"))
    }
    companion object { const val HOST = "ultramax.vip"; const val MAX_BODY = 4 * 1024 * 1024; private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "ultramax-http").apply { isDaemon = true } } }
}

enum class UltraError { INVALID_CODE, NETWORK, SERVER, BAD_RESPONSE }

sealed interface UltraResult<out T> {
    data class Ok<T>(val value: T) : UltraResult<T>
    data class Failure(val error: UltraError) : UltraResult<Nothing>
}

enum class Quality(val label: String, val height: Int) { AUTO("Auto", 0), P2160("4K", 2160), P1080("1080p", 1080), P720("720p", 720), P480("480p", 480), UNKNOWN("Unknown", 0);
    companion object { /** Quality hinted by a stream's name/title. */
        fun detect(text: String): Quality { val t = text.lowercase(); return when { "2160" in t || "4k" in t || "uhd" in t -> P2160; "1080" in t -> P1080; "720" in t -> P720; "480" in t -> P480; else -> UNKNOWN } } }
}

/** One playable HTTPS source. [url] is a (debrid) link: it is never logged, never shown, and [toString] redacts it. */
class UltraStream(val label: String, val detail: String, val quality: Quality, internal val url: String) {
    fun playUrl(): String = url
    override fun toString() = "UltraStream($label, ${quality.label}, url=redacted)"
}

/** UltraMax is a Stremio-style addon: `manifest.json` and `stream/movie|series/<imdb>[:s:e].json`. */
class UltraMaxClient(private val http: UltraHttp) {
    /** Verifies the code by fetching its manifest. Success = a JSON object that looks like an addon manifest. */
    suspend fun verify(code: UltraCode): UltraResult<String> = fetch(code, UltraRoute.Manifest) { root ->
        val o = root as? Map<*, *> ?: return@fetch null
        if (o["id"] == null && o["name"] == null && o["resources"] == null) null else (o["name"] as? String)?.take(60) ?: "UltraMax"
    }

    suspend fun streams(code: UltraCode, route: UltraRoute): UltraResult<List<UltraStream>> = fetch(code, route) { root ->
        val list = (root as? Map<*, *>)?.get("streams") as? List<*> ?: return@fetch null
        list.mapNotNull { s ->
            val o = s as? Map<*, *> ?: return@mapNotNull null
            val url = o["url"] as? String ?: return@mapNotNull null
            if (!url.startsWith("https://", ignoreCase = true) || url.any { it.isWhitespace() } || url.length > 2048) return@mapNotNull null   // HTTPS direct links only
            val name = (o["name"] as? String).orEmpty().replace('\n', ' ').take(60); val title = (o["title"] as? String).orEmpty().replace('\n', ' ').take(120)
            UltraStream(name.ifBlank { "UltraMax" }, title, Quality.detect("$name $title"), url)
        }.take(MAX_STREAMS)
    }

    private suspend fun <T> fetch(code: UltraCode, route: UltraRoute, parse: (Any?) -> T?): UltraResult<T> {
        val r = http.get(code, route)
        val err = when {
            r.code == 0 -> UltraError.NETWORK
            r.code == 401 || r.code == 403 || r.code == 404 -> UltraError.INVALID_CODE
            r.code !in 200..299 -> UltraError.SERVER
            else -> null
        }
        if (err != null) return UltraResult.Failure(err)
        // Parsing (a stream list can be several MB of JSON) never runs on the caller's thread: callers are UI coroutines.
        val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(Json.parse(r.body)) } ?: return UltraResult.Failure(UltraError.BAD_RESPONSE)
        return UltraResult.Ok(v)
    }
    companion object { const val MAX_STREAMS = 60 }
}
