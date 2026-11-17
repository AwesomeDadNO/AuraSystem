package no.aura.os.v2.youtube

/** Data-layer models for the YouTube Data API v3. Metadata only: AuraOS never extracts or proxies streams. */
data class Thumbnail(val url: String, val width: Int, val height: Int)

data class Thumbnails(val items: List<Thumbnail> = emptyList()) {
    /** Smallest thumbnail at least [widthPx] wide, else the largest available. Avoids decoding oversized images on a TV. */
    fun bestFor(widthPx: Int): Thumbnail? = items.filter { it.width >= widthPx }.minByOrNull { it.width } ?: items.maxByOrNull { it.width }
}

data class VideoSummary(
    val id: String, val title: String, val channelId: String, val channelTitle: String,
    val thumbnails: Thumbnails = Thumbnails(), val publishedAt: String? = null, val durationSeconds: Long? = null,
)
data class ChannelSummary(val id: String, val title: String, val description: String = "", val thumbnails: Thumbnails = Thumbnails(), val subscriberCount: Long? = null)
data class PlaylistSummary(val id: String, val title: String, val channelTitle: String = "", val thumbnails: Thumbnails = Thumbnails(), val itemCount: Int? = null)

/** One page of results. [nextPageToken] null = last page. */
data class Page<T>(val items: List<T>, val nextPageToken: String? = null)

enum class SearchType { VIDEO, CHANNEL, PLAYLIST }
data class SearchRequest(val query: String, val type: SearchType = SearchType.VIDEO, val pageToken: String? = null, val pageSize: Int = DEFAULT_PAGE_SIZE) {
    companion object { const val DEFAULT_PAGE_SIZE = 20; const val MAX_PAGE_SIZE = 50 }
}

/** A search hit: exactly one of the three kinds, matching [SearchRequest.type]. */
sealed interface SearchHit {
    data class Video(val video: VideoSummary) : SearchHit
    data class Channel(val channel: ChannelSummary) : SearchHit
    data class Playlist(val playlist: PlaylistSummary) : SearchHit
}

/** YouTube video ids are exactly 11 characters of [A-Za-z0-9_-]. Anything else is rejected before it reaches an API or an intent. */
object VideoId {
    private val pattern = Regex("^[A-Za-z0-9_-]{11}$")
    fun isValid(id: String?) = id != null && pattern.matches(id)
}

object SearchQuery {
    const val MIN_LENGTH = 2; const val MAX_LENGTH = 100
    /** Debounce for typed search so a keystroke burst becomes one request. */
    const val DEBOUNCE_MS = 400L
    /** Trims, collapses whitespace and bounds the length; null when the query is too short to search. */
    fun normalize(raw: String): String? = raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().takeIf { it.length >= MIN_LENGTH }
}
