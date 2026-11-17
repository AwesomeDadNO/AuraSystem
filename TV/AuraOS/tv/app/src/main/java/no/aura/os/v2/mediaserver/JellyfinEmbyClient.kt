package no.aura.os.v2.mediaserver

import no.aura.os.v2.tmdb.Json
import no.aura.os.v2.tmdb.bool
import no.aura.os.v2.tmdb.dbl
import no.aura.os.v2.tmdb.int
import no.aura.os.v2.tmdb.list
import no.aura.os.v2.tmdb.obj
import no.aura.os.v2.tmdb.str
import java.net.URLEncoder

/** What a server says about itself without any credential (used before sign-in and to pin the server identity). */
data class ServerInfo(val id: String, val name: String, val version: String?, val product: String?)

/** A Jellyfin Quick Connect request. [secret] is a credential for this one request and is never printed; [code] is what the person types. */
data class QuickConnect(val code: String, val secret: String) { override fun toString() = "QuickConnect(code=$code, secret=<redacted>)" }

/** Identifies this app installation to the server. [deviceId] is a random id created once on this device (not a hardware id). */
data class ClientIdentity(val deviceId: String, val version: String, val device: String = "Google TV", val client: String = "AuraOS")

/**
 * Jellyfin and Emby speak the same "MediaBrowser" REST dialect; the differences are the authorization header and Quick Connect (Jellyfin only).
 * References: https://jellyfin.org/docs/general/server/quick-connect/ and https://dev.emby.media/doc/restapi/User-Authentication.html
 *
 * Trust rules enforced here:
 * - a token is only ever sent to the origin of the account it belongs to (requests are built from [ServerAccount.base], never from server data);
 * - a sign-in result must name the same server id that the public info named, and [verify] checks both the server id and the user id;
 * - passwords are sent once in the sign-in body and are never stored or returned.
 */
class JellyfinEmbyClient(private val kind: ServerKind, private val http: MediaHttp, private val identity: ClientIdentity) {
    init { require(kind == ServerKind.JELLYFIN || kind == ServerKind.EMBY) }

    private fun clean(s: String) = s.replace("\"", "").replace(",", " ").take(64)
    private fun authValue(token: String?) =
        "MediaBrowser Client=\"${clean(identity.client)}\", Device=\"${clean(identity.device)}\", DeviceId=\"${clean(identity.deviceId)}\", Version=\"${clean(identity.version)}\"" +
            (if (token != null && kind == ServerKind.JELLYFIN) ", Token=\"$token\"" else "")
    /** Jellyfin: one Authorization header carrying the token. Emby: X-Emby-Authorization plus X-Emby-Token. */
    internal fun headers(token: String?): Map<String, String> =
        if (kind == ServerKind.JELLYFIN) mapOf("Authorization" to authValue(token))
        else mapOf("X-Emby-Authorization" to authValue(null)) + (if (token != null) mapOf("X-Emby-Token" to token) else emptyMap())

    private suspend fun call(address: ServerAddress, method: String, path: String, token: String? = null, body: String? = null): MediaResult<Any?> {
        val r = http.send(MediaRequest(method, address.url(path), headers(token), body))
        r.error()?.let { return MediaResult.Failure(it) }
        if (r.body.isNullOrBlank()) return MediaResult.Ok(null)
        val parsed = Json.parse(r.body) ?: return MediaResult.Failure(MediaError.BAD_RESPONSE)
        return MediaResult.Ok(parsed)
    }

    /** Unauthenticated identity of the server at [address]. Proves reachability only, never a connection. */
    suspend fun publicInfo(address: ServerAddress): MediaResult<ServerInfo> = when (val r = call(address, "GET", "/System/Info/Public")) {
        is MediaResult.Failure -> r
        is MediaResult.Ok -> {
            val o = r.value.obj(); val id = o?.str("Id")
            if (o == null || id == null || !ID.matches(id)) MediaResult.Failure(MediaError.BAD_RESPONSE)
            else MediaResult.Ok(ServerInfo(id, (o.str("ServerName") ?: kind.title).take(80), o.str("Version")?.take(32), o.str("ProductName")?.take(48)))
        }
    }

    // ---- Jellyfin Quick Connect ---------------------------------------------------------------------------------------------------------------
    suspend fun quickConnectEnabled(address: ServerAddress): MediaResult<Boolean> {
        if (kind != ServerKind.JELLYFIN) return MediaResult.Ok(false)
        val r = http.send(MediaRequest("GET", address.url("/QuickConnect/Enabled"), headers(null)))
        r.error()?.let { return if (it == MediaError.NOT_FOUND) MediaResult.Ok(false) else MediaResult.Failure(it) }
        return MediaResult.Ok(r.body?.trim() == "true")
    }

    /** Starts a request: the TV shows [QuickConnect.code]; the person approves it in a Jellyfin app or web client where they are signed in. */
    suspend fun quickConnectStart(address: ServerAddress): MediaResult<QuickConnect> {
        if (kind != ServerKind.JELLYFIN) return MediaResult.Failure(MediaError.QUICK_CONNECT_DISABLED)
        var r = call(address, "POST", "/QuickConnect/Initiate")
        if (r is MediaResult.Failure && (r.error == MediaError.NOT_FOUND || r.error == MediaError.SERVER)) r = call(address, "GET", "/QuickConnect/Initiate")   // servers before 10.9
        return when (r) {
            is MediaResult.Failure -> if (r.error == MediaError.AUTH_FAILED || r.error == MediaError.FORBIDDEN) MediaResult.Failure(MediaError.QUICK_CONNECT_DISABLED) else r
            is MediaResult.Ok -> {
                val o = r.value.obj(); val code = o?.str("Code"); val secret = o?.str("Secret")
                if (code == null || secret == null || code.length > 12 || !SECRET.matches(secret)) MediaResult.Failure(MediaError.BAD_RESPONSE) else MediaResult.Ok(QuickConnect(code, secret))
            }
        }
    }

    /** Ok(true) once approved, Ok(false) while waiting. The secret is the request's own handle, defined by the API as a query value. */
    suspend fun quickConnectApproved(address: ServerAddress, qc: QuickConnect): MediaResult<Boolean> =
        when (val r = call(address, "GET", "/QuickConnect/Connect?secret=" + enc(qc.secret))) {
            is MediaResult.Failure -> if (r.error == MediaError.NOT_FOUND) MediaResult.Failure(MediaError.EXPIRED) else r
            is MediaResult.Ok -> MediaResult.Ok(r.value.obj()?.bool("Authenticated") ?: false)
        }

    suspend fun quickConnectFinish(address: ServerAddress, lanHttpApproved: Boolean, expected: ServerInfo, qc: QuickConnect): MediaResult<ServerAccount> =
        account(address, lanHttpApproved, expected, call(address, "POST", "/Users/AuthenticateWithQuickConnect", body = JsonOut.obj("Secret" to qc.secret)))

    // ---- manual sign-in (Jellyfin without Quick Connect, and Emby) --------------------------------------------------------------------------------
    suspend fun signIn(address: ServerAddress, lanHttpApproved: Boolean, expected: ServerInfo, userName: String, password: String): MediaResult<ServerAccount> {
        if (userName.isBlank() || userName.length > 128 || password.length > 256) return MediaResult.Failure(MediaError.AUTH_FAILED)
        return account(address, lanHttpApproved, expected, call(address, "POST", "/Users/AuthenticateByName", body = JsonOut.obj("Username" to userName, "Pw" to password)))
    }

    private fun account(address: ServerAddress, lanHttpApproved: Boolean, expected: ServerInfo, r: MediaResult<Any?>): MediaResult<ServerAccount> = when (r) {
        is MediaResult.Failure -> r
        is MediaResult.Ok -> {
            val o = r.value.obj(); val user = o?.get("User").obj()
            val token = o?.str("AccessToken"); val userId = user?.str("Id"); val serverId = o?.str("ServerId")
            when {
                o == null || token == null || userId == null || !TOKEN.matches(token) || !ID.matches(userId) -> MediaResult.Failure(MediaError.BAD_RESPONSE)
                // The sign-in must come from the server we looked at: a different id means something else answered at this address.
                serverId != null && serverId != expected.id -> MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
                else -> MediaResult.Ok(ServerAccount(kind, expected.id, expected.name, address.base, lanHttpApproved, userId, (user.str("Name") ?: "User").take(80), token, identity.deviceId))
            }
        }
    }

    private fun address(account: ServerAccount): ServerAddress? = (ServerAddress.parse(account.base, account.lanHttpApproved) as? ServerAddress.Parse.Ok)?.address

    /**
     * The real "Connected" check: the server at the saved address still has the saved id, and the saved token still belongs to the saved user.
     * The public info is checked FIRST and without the token, so a different machine at that address never receives the credential.
     */
    suspend fun verify(account: ServerAccount): MediaResult<ServerInfo> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        val info = when (val p = publicInfo(a)) { is MediaResult.Failure -> return p; is MediaResult.Ok -> p.value }
        if (info.id != account.serverId) return MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
        val path = if (kind == ServerKind.JELLYFIN) "/Users/Me" else "/Users/" + enc(account.userId)
        return when (val r = call(a, "GET", path, account.token)) {
            is MediaResult.Failure -> r
            is MediaResult.Ok -> if (r.value.obj()?.str("Id") == account.userId) MediaResult.Ok(info) else MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
        }
    }

    suspend fun libraries(account: ServerAccount): MediaResult<List<MediaLibrary>> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        return call(a, "GET", "/Users/${enc(account.userId)}/Views", account.token).map { v ->
            v.obj()?.get("Items").list().mapNotNull { it.obj() }.mapNotNull { o ->
                val id = o.str("Id")?.takeIf(ID::matches) ?: return@mapNotNull null
                MediaLibrary(id, (o.str("Name") ?: "Library").take(80), when (o.str("CollectionType")) { "movies" -> LibraryType.MOVIES; "tvshows" -> LibraryType.SHOWS; "music" -> LibraryType.MUSIC; else -> LibraryType.OTHER })
            }.take(MAX_LIBRARIES)
        }
    }

    /** One page of the movies or series of a library, sorted by name. */
    suspend fun items(account: ServerAccount, library: MediaLibrary, start: Int = 0, limit: Int = PAGE): MediaResult<MediaPage> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        val type = when (library.type) { LibraryType.MOVIES -> "Movie"; LibraryType.SHOWS -> "Series"; LibraryType.MUSIC -> "MusicAlbum,MusicArtist,Audio"; LibraryType.OTHER -> "Movie,Series" }
        val q = "?ParentId=${enc(library.id)}&IncludeItemTypes=$type&Recursive=true&SortBy=SortName&SortOrder=Ascending" +
            "&StartIndex=${start.coerceAtLeast(0)}&Limit=${limit.coerceIn(1, PAGE)}&Fields=ProductionYear&EnableImageTypes=Primary&EnableUserData=true"
        return call(a, "GET", "/Users/${enc(account.userId)}/Items$q", account.token).map(::parsePage)
    }

    /** What this user was watching (the server's own Continue Watching), newest first. */
    suspend fun resume(account: ServerAccount, limit: Int = 20): MediaResult<List<MediaEntry>> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        val q = "?Limit=${limit.coerceIn(1, PAGE)}&MediaTypes=Video&Recursive=true&Fields=ProductionYear&EnableImageTypes=Primary&EnableUserData=true"
        return call(a, "GET", "/Users/${enc(account.userId)}/Items/Resume$q", account.token).map { parsePage(it).items }
    }

    /** Ends the session on the server (token revocation). The local account must be removed whatever this returns. */
    suspend fun signOut(account: ServerAccount): MediaResult<Unit> {
        val a = address(account) ?: return MediaResult.Failure(MediaError.INSECURE_ADDRESS)
        return call(a, "POST", "/Sessions/Logout", account.token).map { }
    }

    internal fun parsePage(v: Any?): MediaPage {
        val root = v.obj()
        val items = root?.get("Items").list().mapNotNull { it.obj() }.mapNotNull { o ->
            val id = o.str("Id")?.takeIf(ID::matches) ?: return@mapNotNull null
            val title = o.str("Name")?.take(160) ?: return@mapNotNull null
            val data = o["UserData"].obj()
            val tag = o["ImageTags"].obj()?.str("Primary")?.takeIf(TAG::matches)
            MediaEntry(
                id, title, when (o.str("Type")) { "Movie" -> EntryType.MOVIE; "Series" -> EntryType.SERIES; "Episode" -> EntryType.EPISODE; "MusicArtist" -> EntryType.ARTIST; "MusicAlbum" -> EntryType.ALBUM; "Audio" -> EntryType.TRACK; else -> EntryType.OTHER },
                year = o.int("ProductionYear")?.takeIf { it in 1870..2200 },
                imagePath = tag?.let { "/Items/$id/Images/Primary?maxWidth=500&quality=90&tag=$it" },
                durationMs = ticksToMs(o.dbl("RunTimeTicks")), resumeMs = ticksToMs(data?.dbl("PlaybackPositionTicks"))?.takeIf { it > 0 },
                played = data?.bool("Played") ?: false, seriesTitle = o.str("SeriesName")?.take(160),
                artist = (o["Artists"].list().firstOrNull()?.obj()?.str("Name") ?: o.str("AlbumArtist"))?.take(160),
                album = o.str("Album")?.take(160)
            )
        }
        return MediaPage(items.take(PAGE), root?.int("TotalRecordCount")?.coerceAtLeast(items.size) ?: items.size)
    }

    companion object {
        const val PAGE = 60
        const val MAX_LIBRARIES = 40
        /** .NET ticks are 100 ns. */
        fun ticksToMs(ticks: Double?): Long? = ticks?.takeIf { it.isFinite() && it >= 0 && it < 9.0e17 }?.let { (it / 10_000.0).toLong() }
        internal val ID = Regex("[A-Za-z0-9-]{8,64}")
        internal val TOKEN = Regex("[A-Za-z0-9._~+/=-]{16,512}")
        internal val SECRET = Regex("[A-Za-z0-9-]{16,128}")
        internal val TAG = Regex("[A-Za-z0-9]{8,64}")
        internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
