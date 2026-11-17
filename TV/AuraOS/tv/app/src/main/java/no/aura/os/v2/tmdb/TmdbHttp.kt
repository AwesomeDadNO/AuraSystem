package no.aura.os.v2.tmdb

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One HTTP exchange. [body] is null when the transport failed. */
data class HttpResponse(val code: Int, val body: String?, val retryAfterSeconds: Long? = null)

interface TmdbHttp { suspend fun get(path: String, query: Map<String, String> = emptyMap()): HttpResponse }

/**
 * TMDB API v3 over HTTPS with the read access token as a Bearer header (the token is never put in a URL and never logged; this class has no
 * logging at all). Connect 6 s / read 10 s, body capped at 2 MB. The blocking call runs on a small pool and is cancellable: cancelling the
 * coroutine disconnects the socket.
 */
class UrlConnectionTmdbHttp(private val config: TmdbConfig, private val base: String = "https://api.themoviedb.org/3") : TmdbHttp {
    override suspend fun get(path: String, query: Map<String, String>): HttpResponse {
        if (!config.isConfigured) return HttpResponse(0, null)
        val url = base + path + (if (query.isEmpty()) "" else "?" + query.entries.sortedBy { it.key }.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })
        return suspendCancellableCoroutine { cont ->
            val conn = try { URL(url).openConnection() as HttpURLConnection } catch (_: Exception) { cont.resume(HttpResponse(0, null)); return@suspendCancellableCoroutine }
            cont.invokeOnCancellation { try { conn.disconnect() } catch (_: Exception) {} }
            pool.execute {
                val r = try { exchange(conn) } catch (_: Exception) { HttpResponse(0, null) } finally { try { conn.disconnect() } catch (_: Exception) {} }
                if (cont.isActive) cont.resume(r)
            }
        }
    }

    private fun exchange(conn: HttpURLConnection): HttpResponse {
        conn.connectTimeout = CONNECT_MS; conn.readTimeout = READ_MS; conn.instanceFollowRedirects = false; conn.useCaches = false
        conn.setRequestProperty("Authorization", "Bearer " + config.bearer())
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val retry = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        val stream = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: return HttpResponse(code, null, retry)
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        stream.use { s ->
            while (true) { val n = s.read(buf); if (n < 0) break; if (out.size() + n > MAX_BODY) return HttpResponse(code, null, retry); out.write(buf, 0, n) }
        }
        return HttpResponse(code, out.toString("UTF-8"), retry)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val CONNECT_MS = 6_000; const val READ_MS = 10_000; const val MAX_BODY = 2 * 1024 * 1024
        private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "tmdb-http").apply { isDaemon = true } }
    }
}
