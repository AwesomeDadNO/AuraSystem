package no.aura.os.v2.mediaserver

import no.aura.os.v2.tmdb.Json
import no.aura.os.v2.tmdb.bool
import no.aura.os.v2.tmdb.dbl
import no.aura.os.v2.tmdb.int
import no.aura.os.v2.tmdb.list
import no.aura.os.v2.tmdb.obj
import no.aura.os.v2.tmdb.str
import java.net.URI

/** A Plex link request: the TV shows [code], the person enters it at https://plex.tv/link while signed in to their own account. */
data class PlexPin(val id: Long, val code: String, val expiresAtMs: Long) { companion object { const val LINK_URL = "https://plex.tv/link" } }

/** The signed-in Plex user. [token] is the account credential and is never printed. */
data class PlexUser(val id: String, val name: String, val token: String) { override fun toString() = "PlexUser($name, token=<redacted>)" }

/** One connection the Plex account service lists for a server. Only these URIs may ever receive that server's token. */
data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean, val https: Boolean)

/** A Plex Media Server this account may use. [accessToken] is that server's own credential (not the account token). */
data class PlexServer(val machineId: String, val name: String, val owned: Boolean, val accessToken: String, val connections: List<PlexConnection>) {
    override fun toString() = "PlexServer($name, connections=${connections.size}, token=<redacted>)"
}

/**
 * Plex sign-in for a TV (PIN link flow) and server discovery through the account's resource list, then library reads on the chosen server.
 * Reference: https://developer.plex.tv/pms/ . The account service is always https://plex.tv / https://clients.plex.tv (fixed hosts).
 *
 * Trust rules:
 * - the account token goes only to the two fixed Plex account hosts; a server receives only its own [PlexServer.accessToken];
 * - a server connection is used only if it came from the account's resource list AND `/identity` there reports the listed machine id
 *   (checked without any token), so a token is never offered to an unexpected machine;
 * - secure connections are tried before plain-HTTP LAN ones; plain HTTP is used only when [allowLanHttp] was explicitly approved;
 * - tokens travel in the X-Plex-Token header, never in a URL.
 *
 * Legacy PIN remains an explicit compatibility path. Modern device-key sign-in and nonce renewal are in PlexDeviceJwtClient;
 * MediaServerService resolves refreshed account resources before verified library/playback operations.
 */
class PlexClient(private val http: MediaHttp, private val identity: ClientIdentity, private val clock: () -> Long = System::currentTimeMillis) {
    private fun headers(token: String? = null): Map<String, String> = linkedMapOf(
        "X-Plex-Client-Identifier" to identity.deviceId, "X-Plex-Product" to identity.client, "X-Plex-Version" to identity.version,
        "X-Plex-Platform" to "Android", "X-Plex-Device" to identity.device, "X-Plex-Device-Name" to "AuraOS",
    ).also { if (token != null) it["X-Plex-Token"] = token }

    private suspend fun call(method: String, url: String, token: String? = null, deviceId: String = identity.deviceId): MediaResult<Any?> {
        val r = http.send(MediaRequest(method, url, headers(token) + ("X-Plex-Client-Identifier" to deviceId)))
        r.error()?.let { return MediaResult.Failure(it) }
        return MediaResult.Ok(Json.parse(r.body) ?: return MediaResult.Failure(MediaError.BAD_RESPONSE))
    }

    // ---- account: PIN link --------------------------------------------------------------------------------------------------------------------
    suspend fun createPin(): MediaResult<PlexPin> = when (val r = call("POST", "$ACCOUNT/api/v2/pins")) {
        is MediaResult.Failure -> r
        is MediaResult.Ok -> {
            val o = r.value.obj(); val id = o?.dbl("id")?.toLong(); val code = o?.str("code")
            if (id == null || id <= 0 || code == null || !CODE.matches(code)) MediaResult.Failure(MediaError.BAD_RESPONSE)
            else MediaResult.Ok(PlexPin(id, code, clock() + (o.int("expiresIn") ?: 900).coerceIn(60, 1800) * 1000L))
        }
    }

    /** Ok(user) once the person linked the code; Failure(PENDING) while waiting; Failure(EXPIRED) when the code is gone. */
    suspend fun checkPin(pin: PlexPin): MediaResult<PlexUser> {
        if (clock() >= pin.expiresAtMs) return MediaResult.Failure(MediaError.EXPIRED)
        return when (val r = call("GET", "$ACCOUNT/api/v2/pins/${pin.id}")) {
            is MediaResult.Failure -> if (r.error == MediaError.NOT_FOUND) MediaResult.Failure(MediaError.EXPIRED) else r
            is MediaResult.Ok -> {
                val token = r.value.obj()?.str("authToken") ?: return MediaResult.Failure(MediaError.PENDING)
                if (!TOKEN.matches(token)) MediaResult.Failure(MediaError.BAD_RESPONSE) else user(token)
            }
        }
    }

    /** Verifies an account token with Plex and returns who it belongs to. */
    suspend fun user(token: String): MediaResult<PlexUser> = when (val r = call("GET", "$ACCOUNT/api/v2/user", token)) {
        is MediaResult.Failure -> r
        is MediaResult.Ok -> {
            val o = r.value.obj(); val id = o?.str("uuid") ?: o?.dbl("id")?.toLong()?.toString()
            if (o == null || id == null) MediaResult.Failure(MediaError.BAD_RESPONSE)
            else MediaResult.Ok(PlexUser(id.take(64), (o.str("friendlyName") ?: o.str("title") ?: o.str("username") ?: "Plex user").take(80), token))
        }
    }

    // ---- discovery: the servers this account may use --------------------------------------------------------------------------------------------
    suspend fun servers(user: PlexUser): MediaResult<List<PlexServer>> =
        call("GET", "$RESOURCES/api/v2/resources?includeHttps=1&includeRelay=1", user.token).map { v ->
            v.list().mapNotNull { it.obj() }.filter { o -> (o.str("provides") ?: "").split(',').any { it.trim() == "server" } }.mapNotNull { o ->
                val machine = o.str("clientIdentifier")?.takeIf(MACHINE::matches) ?: return@mapNotNull null
                val token = o.str("accessToken")?.takeIf(TOKEN::matches) ?: return@mapNotNull null
                val conns = o["connections"].list().mapNotNull { it.obj() }.mapNotNull { c ->
                    val uri = c.str("uri") ?: return@mapNotNull null
                    val u = runCatching { URI(uri) }.getOrNull() ?: return@mapNotNull null
                    val scheme = u.scheme?.lowercase()
                    if ((scheme != "http" && scheme != "https") || u.host == null || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return@mapNotNull null
                    PlexConnection(uri.trimEnd('/'), c.bool("local"), c.bool("relay"), scheme == "https")
                }.take(MAX_CONNECTIONS)
                if (conns.isEmpty()) null else PlexServer(machine, (o.str("name") ?: "Plex Media Server").take(80), o.bool("owned"), token, conns)
            }.take(MAX_SERVERS)
        }

    /** Connection order: secure direct, secure relay, then (only if approved) plain HTTP on the LAN. Plain HTTP to a public host is never used. */
    internal fun candidates(server: PlexServer, allowLanHttp: Boolean): List<Pair<PlexConnection, ServerAddress>> =
        server.connections.sortedWith(compareBy({ !it.https }, { it.relay }, { !it.local })).mapNotNull { c ->
            (ServerAddress.parse(c.uri, lanHttpApproved = allowLanHttp) as? ServerAddress.Parse.Ok)?.address?.let { c to it }
        }

    /**
     * Finds a connection that really is [server] (its `/identity` machine id matches, asked WITHOUT a token) and builds the account for it.
     * A machine that answers with another id is skipped and never sees the token.
     */
    suspend fun connect(user: PlexUser, server: PlexServer, allowLanHttp: Boolean): MediaResult<ServerAccount> {
        var last: MediaError = MediaError.UNREACHABLE
        for ((_, address) in candidates(server, allowLanHttp)) {
            when (val id = machineId(address)) {
                is MediaResult.Failure -> last = id.error
                is MediaResult.Ok -> if (id.value != server.machineId) last = MediaError.IDENTITY_MISMATCH else {
                    val account = ServerAccount(ServerKind.PLEX, server.machineId, server.name, address.base, allowLanHttp && !address.https, user.id, user.name, server.accessToken, identity.deviceId)
                    return when (val l = libraries(account)) { is MediaResult.Failure -> { last = l.error; continue }; is MediaResult.Ok -> MediaResult.Ok(account) }
                }
            }
        }
        return MediaResult.Failure(last)
    }

    private suspend fun machineId(address: ServerAddress): MediaResult<String> = when (val r = call("GET", address.url("/identity"))) {
        is MediaResult.Failure -> r
        is MediaResult.Ok -> r.value.obj()?.get("MediaContainer").obj()?.str("machineIdentifier")?.let { MediaResult.Ok(it) } ?: MediaResult.Failure(MediaError.BAD_RESPONSE)
    }

    private fun address(account: ServerAccount): ServerAddress? = (ServerAddress.parse(account.base, account.lanHttpApproved) as? ServerAddress.Parse.Ok)?.address

    /** The real "Connected" check for a saved Plex account: same machine at the saved address, and the saved token still reads the library list. */
    suspend fun verify(account: ServerAccount): MediaResult<ServerInfo> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        when (val id = machineId(a)) { is MediaResult.Failure -> return id; is MediaResult.Ok -> if (id.value != account.serverId) return MediaResult.Failure(MediaError.IDENTITY_MISMATCH) }
        return libraries(account).map { ServerInfo(account.serverId, account.serverName, null, "Plex Media Server") }
    }

    suspend fun libraries(account: ServerAccount): MediaResult<List<MediaLibrary>> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        return call("GET", a.url("/library/sections"), account.token, account.deviceId).map { v ->
            v.obj()?.get("MediaContainer").obj()?.get("Directory").list().mapNotNull { it.obj() }.mapNotNull { o ->
                val key = o.str("key")?.takeIf(KEY::matches) ?: return@mapNotNull null
                MediaLibrary(key, (o.str("title") ?: "Library").take(80), when (o.str("type")) { "movie" -> LibraryType.MOVIES; "show" -> LibraryType.SHOWS; "artist" -> LibraryType.MUSIC; else -> LibraryType.OTHER })
            }.take(JellyfinEmbyClient.MAX_LIBRARIES)
        }
    }

    suspend fun items(account: ServerAccount, library: MediaLibrary, start: Int = 0, limit: Int = JellyfinEmbyClient.PAGE): MediaResult<MediaPage> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        if (!KEY.matches(library.id)) return MediaResult.Failure(MediaError.NOT_FOUND)
        val type = when (library.type) { LibraryType.MOVIES -> "&type=1"; LibraryType.SHOWS -> "&type=2"; LibraryType.MUSIC -> "&type=10"; LibraryType.OTHER -> "" }
        val url = a.url("/library/sections/${library.id}/all?sort=titleSort$type&X-Plex-Container-Start=${start.coerceAtLeast(0)}&X-Plex-Container-Size=${limit.coerceIn(1, JellyfinEmbyClient.PAGE)}")
        return call("GET", url, account.token, account.deviceId).map(::parsePage)
    }

    /** The server's own On Deck / Continue Watching for this user. */
    suspend fun resume(account: ServerAccount, limit: Int = 20): MediaResult<List<MediaEntry>> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        return call("GET", a.url("/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=${limit.coerceIn(1, JellyfinEmbyClient.PAGE)}"), account.token, account.deviceId).map { parsePage(it).items }
    }

    internal fun parsePage(v: Any?): MediaPage {
        val mc = v.obj()?.get("MediaContainer").obj()
        val items = mc?.get("Metadata").list().mapNotNull { it.obj() }.mapNotNull { o ->
            val id = o.str("ratingKey")?.takeIf(KEY::matches) ?: return@mapNotNull null
            val title = o.str("title")?.take(160) ?: return@mapNotNull null
            // thumb is a server-relative path such as /library/metadata/1/thumb/1700000000: accepted only in that exact shape.
            val thumb = (o.str("grandparentThumb") ?: o.str("thumb"))?.takeIf(THUMB::matches)
            MediaEntry(
                id, title, when (o.str("type")) { "movie" -> EntryType.MOVIE; "show" -> EntryType.SERIES; "episode" -> EntryType.EPISODE; "artist" -> EntryType.ARTIST; "album" -> EntryType.ALBUM; "track" -> EntryType.TRACK; else -> EntryType.OTHER },
                year = o.int("year")?.takeIf { it in 1870..2200 }, imagePath = thumb,
                durationMs = o.dbl("duration")?.takeIf { it.isFinite() && it >= 0 }?.toLong(), resumeMs = o.dbl("viewOffset")?.takeIf { it.isFinite() && it > 0 }?.toLong(),
                played = (o.int("viewCount") ?: 0) > 0, seriesTitle = o.str("grandparentTitle")?.take(160),
                artist = o.str("grandparentTitle")?.take(160), album = o.str("parentTitle")?.take(160)
            )
        }
        return MediaPage(items.take(JellyfinEmbyClient.PAGE), mc?.int("totalSize")?.coerceAtLeast(items.size) ?: items.size)
    }

    companion object {
        const val ACCOUNT = "https://plex.tv"
        const val RESOURCES = "https://clients.plex.tv"
        const val MAX_SERVERS = 16
        const val MAX_CONNECTIONS = 12
        internal val CODE = Regex("[A-Za-z0-9]{4,32}")
        internal val TOKEN = Regex("[A-Za-z0-9._~+/=-]{16,1024}")
        internal val MACHINE = Regex("[A-Za-z0-9-]{8,64}")
        internal val KEY = Regex("[0-9]{1,12}")
        internal val THUMB = Regex("/library/metadata/[0-9]{1,12}/(thumb|art)/[0-9]{1,14}")
    }
}
