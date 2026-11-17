package no.aura.os.v2.youtube

/**
 * Wraps a real repository with: request validation, a bounded TTL cache (repeated screens cost no quota), a short negative cache for
 * "not found", and the local quota guard. Results are cached per request, never per profile data, so it can be shared safely.
 */
class CachingYouTubeRepository(
    private val delegate: YouTubeRepository, private val budget: QuotaBudget = QuotaBudget(),
    private val clock: () -> Long = System::currentTimeMillis, maxEntries: Int = 200,
) : YouTubeRepository {
    private val cache = TtlCache<String, Any>(maxEntries, clock)
    private val missing = TtlCache<String, Boolean>(maxEntries, clock)

    override suspend fun search(request: SearchRequest): YtResult<Page<SearchHit>> {
        val q = SearchQuery.normalize(request.query) ?: return invalid()
        val size = request.pageSize.coerceIn(1, SearchRequest.MAX_PAGE_SIZE)
        val r = request.copy(query = q, pageSize = size)
        return cached("s|${q.lowercase()}|${r.type}|${r.pageToken}|$size", TTL_SEARCH, QuotaCost.SEARCH) { delegate.search(r) }
    }

    override suspend fun videos(ids: List<String>): YtResult<List<VideoSummary>> {
        val clean = ids.distinct()
        if (clean.isEmpty() || clean.size > MAX_BATCH || !clean.all(VideoId::isValid)) return invalid()
        return cached("v|" + clean.sorted().joinToString(","), TTL_VIDEO, QuotaCost.LIST) { delegate.videos(clean) }
    }

    override suspend fun channel(id: String): YtResult<ChannelSummary> =
        if (!validId(id)) invalid() else cached("c|$id", TTL_CHANNEL, QuotaCost.LIST) { delegate.channel(id) }

    override suspend fun channelPlaylists(channelId: String, pageToken: String?): YtResult<Page<PlaylistSummary>> =
        if (!validId(channelId)) invalid() else cached("cp|$channelId|$pageToken", TTL_CHANNEL, QuotaCost.LIST) { delegate.channelPlaylists(channelId, pageToken) }

    override suspend fun playlist(id: String): YtResult<PlaylistSummary> =
        if (!validId(id)) invalid() else cached("p|$id", TTL_CHANNEL, QuotaCost.LIST) { delegate.playlist(id) }

    override suspend fun playlistVideos(playlistId: String, pageToken: String?): YtResult<Page<VideoSummary>> =
        if (!validId(playlistId)) invalid() else cached("pv|$playlistId|$pageToken", TTL_VIDEO, QuotaCost.LIST) { delegate.playlistVideos(playlistId, pageToken) }

    fun clear() { cache.clear(); missing.clear() }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, cost: Int, call: suspend () -> YtResult<T>): YtResult<T> {
        (cache.get(key) as? T)?.let { return YtResult.Ok(it) }
        if (missing.get(key) == true) return YtResult.Failure(YouTubeError.NotFound)
        if (!budget.tryCharge(cost)) return YtResult.Failure(YouTubeError.BudgetGuard)
        val r = call()
        when {
            r is YtResult.Ok -> cache.put(key, r.value, ttlMs)
            r is YtResult.Failure && r.error == YouTubeError.QuotaExceeded -> budget.markExhausted()
            r is YtResult.Failure && r.error == YouTubeError.NotFound -> missing.put(key, true, TTL_MISSING)
        }
        return r
    }

    private fun validId(id: String) = id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    private fun invalid() = YtResult.Failure(YouTubeError.InvalidRequest)

    companion object {
        const val MAX_BATCH = 50
        const val TTL_SEARCH = 10 * 60_000L; const val TTL_VIDEO = 60 * 60_000L; const val TTL_CHANNEL = 6 * 60 * 60_000L; const val TTL_MISSING = 5 * 60_000L
    }
}
