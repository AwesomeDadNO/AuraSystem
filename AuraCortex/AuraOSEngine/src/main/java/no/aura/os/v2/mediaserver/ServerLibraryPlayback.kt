package no.aura.os.v2.mediaserver

import no.aura.os.v2.tmdb.*
import java.net.URLEncoder

object ServerPlaybackPolicy {
    fun plexPart(path: String) = Regex("/library/parts/[0-9]+/[0-9]+/[A-Za-z0-9_.-]+").matches(path) && !path.contains("..")
    fun ticks(ms: Long) = ms.coerceIn(0, Long.MAX_VALUE / 10_000).let { if (ms > Long.MAX_VALUE / 10_000) Long.MAX_VALUE else it * 10_000 }
}

/** Credential-free handle bound to the profile, account and exact server base verified at open time. */
class ServerPlayback internal constructor(val url: String, val entry: MediaEntry, internal val sessionId: String?, internal val mediaSourceId: String?,
    internal val ownerProfile: String, internal val accountKey: String, internal val serverId: String, internal val userId: String, internal val serverBase: String) {
    override fun toString() = "ServerPlayback(<private media handle>)"
}
enum class ServerPlaybackEvent { START, PROGRESS, STOP }

/** Profile ownership is checked before every operation; identity is checked before any authenticated request. */
class ServerLibraryPlayback(private val service: MediaServerService, private val http: MediaHttp, private val identity: ClientIdentity) {
    private val issued = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ServerPlayback, Boolean>())
    private fun issue(handle: ServerPlayback): ServerPlayback = handle.also { issued[it] = true }
    private suspend fun allowed(profileId: String, account: ServerAccount): MediaResult<ServerAddress> {
        if (!service.mayBrowse(profileId)) return MediaResult.Failure(MediaError.FORBIDDEN)
        if (service.accounts(profileId).none { it == account }) return MediaResult.Failure(MediaError.FORBIDDEN)
        val a = (ServerAddress.parse(account.base, account.lanHttpApproved) as? ServerAddress.Parse.Ok)?.address ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        // Identity proof is reused for a short window (see MediaServerService.verifyFresh); ownership and address checks above run every time.
        when (val v = service.verifyFresh(profileId, account)) { is MediaResult.Failure -> return v; else -> Unit }
        return MediaResult.Ok(a)
    }
    private fun client(a: ServerAccount) = if (a.kind == ServerKind.EMBY) service.emby else service.jellyfin
    private fun headers(a: ServerAccount): Map<String, String> = if (a.kind == ServerKind.PLEX) mapOf("X-Plex-Token" to a.token, "X-Plex-Client-Identifier" to a.deviceId, "Accept" to "application/json") else client(a).headers(a.token)
    private suspend fun request(a: ServerAccount, address: ServerAddress, method: String, path: String, body: String? = null): MediaResult<Any?> {
        val r = http.send(MediaRequest(method, address.url(path), headers(a), body))
        r.error()?.let { return MediaResult.Failure(it) }
        return MediaResult.Ok(if (r.body.isNullOrBlank()) null else Json.parse(r.body))
    }
    suspend fun search(profileId: String, query: String): List<Pair<ServerAccount, MediaEntry>> {
        val q = query.trim().take(128)
        if (q.length < 2) return emptyList()
        return service.accounts(profileId).take(8).flatMap { supplied ->
            val a = (service.authorizedAccount(profileId,supplied) as? MediaResult.Ok)?.value ?: return@flatMap emptyList()
            val address = (allowed(profileId, a) as? MediaResult.Ok)?.value ?: return@flatMap emptyList()
            val path = if (a.kind == ServerKind.PLEX) "/hubs/search?query=${enc(q)}&limit=20" else "/Users/${enc(a.userId)}/Items?SearchTerm=${enc(q)}&IncludeItemTypes=Movie,Series,Episode&Recursive=true&Limit=20&EnableUserData=true"
            val result = (request(a, address, "GET", path) as? MediaResult.Ok)?.value
            val entries = if (a.kind == ServerKind.PLEX) {
                result.obj()?.get("MediaContainer").obj()?.get("Hub").list().flatMap { hub ->
                    service.plex.parsePage(mapOf("MediaContainer" to hub)).items
                }.take(20)
            } else client(a).parsePage(result).items.take(20)
            entries.map { a to it }
        }.take(80)
    }
    suspend fun libraries(profileId: String, supplied: ServerAccount): MediaResult<List<MediaLibrary>> {
        val a = when(val resolved = service.authorizedAccount(profileId,supplied)) { is MediaResult.Failure -> return resolved; is MediaResult.Ok -> resolved.value }
        when (val r = allowed(profileId, a)) { is MediaResult.Failure -> return r; else -> Unit }
        return if (a.kind == ServerKind.PLEX) service.plex.libraries(a) else client(a).libraries(a)
    }
    suspend fun items(profileId: String, supplied: ServerAccount, library: MediaLibrary, start: Int = 0, limit: Int = 60): MediaResult<MediaPage> {
        val a = when(val resolved = service.authorizedAccount(profileId,supplied)) { is MediaResult.Failure -> return resolved; is MediaResult.Ok -> resolved.value }
        when (val r = allowed(profileId, a)) { is MediaResult.Failure -> return r; else -> Unit }
        return if (a.kind == ServerKind.PLEX) service.plex.items(a, library, start, limit) else client(a).items(a, library, start, limit)
    }
    suspend fun resume(profileId: String, supplied: ServerAccount): MediaResult<List<MediaEntry>> {
        val a = when(val resolved = service.authorizedAccount(profileId,supplied)) { is MediaResult.Failure -> return resolved; is MediaResult.Ok -> resolved.value }
        when (val r = allowed(profileId, a)) { is MediaResult.Failure -> return r; else -> Unit }
        return if (a.kind == ServerKind.PLEX) service.plex.resume(a) else client(a).resume(a)
    }
    suspend fun episodes(profileId: String, supplied: ServerAccount, series: MediaEntry, start: Int = 0): MediaResult<MediaPage> {
        val a = when(val resolved = service.authorizedAccount(profileId,supplied)) { is MediaResult.Failure -> return resolved; is MediaResult.Ok -> resolved.value }
        val address = when (val r = allowed(profileId, a)) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
        if (series.type != EntryType.SERIES || !validId(a, series.id)) return MediaResult.Failure(MediaError.NOT_FOUND)
        val path = if (a.kind == ServerKind.PLEX) "/library/metadata/${series.id}/allLeaves?X-Plex-Container-Start=${start.coerceAtLeast(0)}&X-Plex-Container-Size=60" else "/Shows/${series.id}/Episodes?UserId=${enc(a.userId)}&StartIndex=${start.coerceAtLeast(0)}&Limit=60&EnableUserData=true"
        return request(a, address, "GET", path).map { if (a.kind == ServerKind.PLEX) service.plex.parsePage(it) else client(a).parsePage(it) }
    }
    suspend fun playback(profileId: String, supplied: ServerAccount, entry: MediaEntry): MediaResult<ServerPlayback> {
        val a = when(val resolved = service.authorizedAccount(profileId,supplied)) { is MediaResult.Failure -> return resolved; is MediaResult.Ok -> resolved.value }
        val address = when (val r = allowed(profileId, a)) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
        if (!validId(a, entry.id) || entry.type !in listOf(EntryType.MOVIE, EntryType.EPISODE)) return MediaResult.Failure(MediaError.NOT_FOUND)
        if (a.kind == ServerKind.PLEX) {
            val metadata = when (val r = request(a, address, "GET", "/library/metadata/${entry.id}")) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
            val item = metadata.obj()?.get("MediaContainer").obj()?.get("Metadata").list().firstOrNull().obj()
            val part = item?.get("Media").list().firstOrNull().obj()?.get("Part").list().firstOrNull().obj()?.str("key")
            if (part == null || !ServerPlaybackPolicy.plexPart(part)) return MediaResult.Failure(MediaError.BAD_RESPONSE)
            return MediaResult.Ok(issue(ServerPlayback(address.url(part), entry, null, null, profileId, a.key, a.serverId, a.userId, address.base)))
        }
        val info = when (val r = request(a, address, "POST", "/Items/${entry.id}/PlaybackInfo?UserId=${enc(a.userId)}", JsonOut.obj("UserId" to a.userId, "EnableDirectPlay" to true, "EnableDirectStream" to false, "EnableTranscoding" to false))) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value.obj() }
        val source = info?.get("MediaSources").list().mapNotNull { it.obj() }.firstOrNull { it.bool("SupportsDirectPlay") && !it.bool("IsRemote") }
            ?: return MediaResult.Failure(MediaError.BAD_RESPONSE)
        val id = source.str("Id")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,128}")) } ?: return MediaResult.Failure(MediaError.BAD_RESPONSE)
        return MediaResult.Ok(issue(ServerPlayback(address.url("/Videos/${entry.id}/stream?Static=true&MediaSourceId=${enc(id)}"), entry, info?.str("PlaySessionId"), id, profileId, a.key, a.serverId, a.userId, address.base)))
    }
    private suspend fun playbackAccount(profileId: String, supplied: ServerAccount, playback: ServerPlayback): MediaResult<ServerAccount> {
        if (issued[playback] != true) return MediaResult.Failure(MediaError.FORBIDDEN)
        if (profileId != playback.ownerProfile || supplied.key != playback.accountKey || supplied.serverId != playback.serverId || supplied.userId != playback.userId)
            return MediaResult.Failure(MediaError.FORBIDDEN)
        val saved = service.accounts(profileId).firstOrNull { it.key == playback.accountKey } ?: return MediaResult.Failure(MediaError.FORBIDDEN)
        // Fail BEFORE refresh/verification can offer a credential to a newly selected endpoint.
        if (saved.base != playback.serverBase) return MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
        val current = when (val r = service.authorizedAccount(profileId, saved)) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
        if (current.base != playback.serverBase || current.userId != playback.userId || current.serverId != playback.serverId)
            return MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
        when (val r = allowed(profileId, current)) { is MediaResult.Failure -> return r; else -> Unit }
        if (!playback.url.startsWith(playback.serverBase + "/")) return MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
        return MediaResult.Ok(current)
    }
    /** Resolved afresh for each HTTP range; removed/profile-denied accounts fail closed. Never cache these headers in a playback handle. */
    suspend fun streamHeaders(profileId: String, supplied: ServerAccount, playback: ServerPlayback): MediaResult<Map<String, String>> =
        playbackAccount(profileId, supplied, playback).map(::headers)
    suspend fun report(profileId: String, supplied: ServerAccount, playback: ServerPlayback, event: ServerPlaybackEvent, positionMs: Long, durationMs: Long, paused: Boolean): MediaResult<Unit> {
        val a = when (val r = playbackAccount(profileId, supplied, playback)) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
        val address = (ServerAddress.parse(a.base, a.lanHttpApproved) as ServerAddress.Parse.Ok).address
        val position = positionMs.coerceAtLeast(0).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        if (a.kind == ServerKind.PLEX) return request(a, address, "GET", "/:/timeline?ratingKey=${playback.entry.id}&key=${enc("/library/metadata/${playback.entry.id}")}&state=${if (event == ServerPlaybackEvent.STOP) "stopped" else if (paused) "paused" else "playing"}&time=$position&duration=${durationMs.coerceAtLeast(0)}").map { }
        val suffix = when (event) { ServerPlaybackEvent.START -> ""; ServerPlaybackEvent.PROGRESS -> "/Progress"; ServerPlaybackEvent.STOP -> "/Stopped" }
        return request(a, address, "POST", "/Sessions/Playing$suffix", JsonOut.obj("ItemId" to playback.entry.id, "MediaSourceId" to playback.mediaSourceId, "PlaySessionId" to playback.sessionId, "PositionTicks" to ServerPlaybackPolicy.ticks(position), "IsPaused" to paused, "EventName" to (if (paused) "Pause" else "TimeUpdate"), "CanSeek" to true, "PlayMethod" to "DirectPlay")).map { }
    }
    private fun validId(a: ServerAccount, id: String) = if (a.kind == ServerKind.PLEX) PlexClient.KEY.matches(id) else JellyfinEmbyClient.ID.matches(id)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
