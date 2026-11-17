package no.aura.os.v2.youtube

/** Failures a caller must handle. [QuotaExceeded] and [KeyRejected] are distinct so the UI can say what to do. */
sealed interface YouTubeError {
    /** No API key in this build: nothing was requested. */
    object NotConfigured : YouTubeError
    /** Google rejected the key (invalid, restricted or the API is not enabled for it). */
    object KeyRejected : YouTubeError
    /** Google's daily quota is exhausted. */
    object QuotaExceeded : YouTubeError
    /** AuraOS's own budget guard stopped the call before it was sent, to protect the daily quota. */
    object BudgetGuard : YouTubeError
    object NotFound : YouTubeError
    object Network : YouTubeError
    object BadResponse : YouTubeError
    object InvalidRequest : YouTubeError
}

sealed interface YtResult<out T> {
    data class Ok<T>(val value: T) : YtResult<T>
    data class Failure(val error: YouTubeError) : YtResult<Nothing>
}

/**
 * YouTube Data API v3 data layer. F3b defines the contract and its safety rules only; the HTTP implementation arrives with F4c and needs the
 * developer's API key. All calls are `suspend` so callers can cancel them with their scope; implementations do their I/O off the main thread.
 *
 * Quota cost per call (official units): [search] 100; every other list call 1.
 */
interface YouTubeRepository {
    suspend fun search(request: SearchRequest): YtResult<Page<SearchHit>>
    /** Up to 50 ids per call (one quota unit for the batch). Unknown ids are simply absent. */
    suspend fun videos(ids: List<String>): YtResult<List<VideoSummary>>
    suspend fun channel(id: String): YtResult<ChannelSummary>
    suspend fun channelPlaylists(channelId: String, pageToken: String? = null): YtResult<Page<PlaylistSummary>>
    suspend fun playlist(id: String): YtResult<PlaylistSummary>
    suspend fun playlistVideos(playlistId: String, pageToken: String? = null): YtResult<Page<VideoSummary>>
}

/** Used while no key exists. Never fabricates data and never touches the network. */
object NotConfiguredYouTubeRepository : YouTubeRepository {
    private val fail = YtResult.Failure(YouTubeError.NotConfigured)
    override suspend fun search(request: SearchRequest) = fail
    override suspend fun videos(ids: List<String>) = fail
    override suspend fun channel(id: String) = fail
    override suspend fun channelPlaylists(channelId: String, pageToken: String?) = fail
    override suspend fun playlist(id: String) = fail
    override suspend fun playlistVideos(playlistId: String, pageToken: String?) = fail
}

/** The API key from git-ignored developer configuration. Never printed. */
class YouTubeConfig(private val apiKey: String) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()
    /** Only the HTTP layer reads the key. */
    internal fun key(): String = apiKey
    override fun toString() = "YouTubeConfig(configured=$isConfigured)"
}

object QuotaCost { const val SEARCH = 100; const val LIST = 1 }
