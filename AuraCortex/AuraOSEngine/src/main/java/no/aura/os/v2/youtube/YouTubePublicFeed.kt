package no.aura.os.v2.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Metadata from YouTube Data API v3 only. Playback always uses the official YouTube TV app. */
data class PublicYouTubeVideo(val id: String, val title: String, val channel: String) {
    val artworkUrl: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

sealed interface PublicYouTubeResult {
    data class Ready(val videos: List<PublicYouTubeVideo>) : PublicYouTubeResult
    object NotConfigured : PublicYouTubeResult
    object Unavailable : PublicYouTubeResult
}

/**
 * Public, low-quota videos.list(chart=mostPopular), not Google account data. API key is supplied
 * by the existing git-ignored BuildConfig configuration and is never written to source or logs.
 */
class YouTubePublicFeed(private val config: YouTubeConfig) {
    val configured: Boolean get() = config.isConfigured
    @Volatile var lastVerifiedAtMs: Long? = null
        private set
    @Volatile private var lastFailureAtMs: Long = 0
    @Volatile private var cachedAtMs: Long = 0
    @Volatile private var cached: PublicYouTubeResult.Ready? = null

    suspend fun popular(): PublicYouTubeResult {
        if (!configured) return PublicYouTubeResult.NotConfigured
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < 20 * 60_000L) return it }
        if (now - lastFailureAtMs < 60_000L) return PublicYouTubeResult.Unavailable
        return withContext(Dispatchers.IO) {
            val query = "part=snippet&chart=mostPopular&regionCode=NO&maxResults=20&key=" +
                URLEncoder.encode(config.key(), "UTF-8")
            val c = runCatching { URL("https://www.googleapis.com/youtube/v3/videos?$query").openConnection() as HttpURLConnection }.getOrNull()
                ?: return@withContext PublicYouTubeResult.Unavailable
            try {
                c.connectTimeout = 6_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode != 200) { lastFailureAtMs = System.currentTimeMillis(); return@withContext PublicYouTubeResult.Unavailable }
                val bytes = c.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 1024 * 1024) return@withContext PublicYouTubeResult.Unavailable
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val data = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("items")
                    ?: return@withContext PublicYouTubeResult.Unavailable
                val titles = (0 until data.length()).mapNotNull { i ->
                    val video = data.optJSONObject(i) ?: return@mapNotNull null
                    val id = video.optString("id")
                    if (!VideoId.isValid(id)) return@mapNotNull null
                    val snippet = video.optJSONObject("snippet") ?: return@mapNotNull null
                    val title = snippet.optString("title").trim().take(160)
                    if (title.isEmpty()) return@mapNotNull null
                    PublicYouTubeVideo(id, title, snippet.optString("channelTitle").take(80))
                }
                PublicYouTubeResult.Ready(titles).also { cached = it; cachedAtMs = System.currentTimeMillis(); lastVerifiedAtMs = cachedAtMs }
            } catch (_: Exception) {
                lastFailureAtMs = System.currentTimeMillis()
                PublicYouTubeResult.Unavailable
            } finally {
                c.disconnect()
            }
        }
    }
}
