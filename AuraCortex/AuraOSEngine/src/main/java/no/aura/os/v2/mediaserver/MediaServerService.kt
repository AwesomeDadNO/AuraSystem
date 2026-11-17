package no.aura.os.v2.mediaserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.aura.os.v2.settings.ConnectionId
import no.aura.os.v2.settings.ConnectionState
import no.aura.os.v2.settings.ConnectionStatus
import no.aura.os.v2.tmdb.Json
import no.aura.os.v2.tmdb.bool
import no.aura.os.v2.tmdb.list
import no.aura.os.v2.tmdb.obj
import no.aura.os.v2.tmdb.str
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-profile storage of verified server sign-ins. Implementations must encrypt at rest (the Android one uses a Keystore AES-GCM key)
 * and must never log. One profile can never read another profile's accounts: every call names the profile.
 */
interface MediaAccountStore {
    /** Cheap presence check (no decryption): safe on the main thread. */
    fun has(profileId: String): Boolean
    fun load(profileId: String): List<ServerAccount>
    fun save(profileId: String, accounts: List<ServerAccount>): Boolean
    fun clear(profileId: String)
}

class MemoryMediaAccountStore : MediaAccountStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun has(profileId: String) = map.containsKey(profileId)
    override fun load(profileId: String) = map[profileId]?.let(AccountCodec::decode) ?: emptyList()
    override fun save(profileId: String, accounts: List<ServerAccount>): Boolean {
        if (profileId.isBlank()) return false
        if (accounts.isEmpty()) map.remove(profileId) else map[profileId] = AccountCodec.encode(accounts)
        return true
    }
    override fun clear(profileId: String) { map.remove(profileId) }
    override fun toString() = "MemoryMediaAccountStore(${map.size} profiles)"
}

/** Plain-text form that goes INSIDE the encryption. Decoding is strict: one malformed or unsafe entry is dropped, never repaired. */
object AccountCodec {
    const val MAX_ACCOUNTS = 8
    const val MAX_CHARS = 48 * 1024
    fun encode(accounts: List<ServerAccount>): String = JsonOut.write(mapOf("v" to 1, "accounts" to accounts.take(MAX_ACCOUNTS).map {
        linkedMapOf("kind" to it.kind.name, "serverId" to it.serverId, "serverName" to it.serverName, "base" to it.base, "lanHttp" to it.lanHttpApproved,
            "userId" to it.userId, "userName" to it.userName, "token" to it.token, "deviceId" to it.deviceId)
    }))
    fun decode(text: String?): List<ServerAccount> {
        if (text == null || text.length > MAX_CHARS) return emptyList()
        return Json.parse(text).obj()?.get("accounts").list().mapNotNull { it.obj() }.mapNotNull { o ->
            val kind = ServerKind.values().firstOrNull { it.name == o.str("kind") } ?: return@mapNotNull null
            val base = o.str("base") ?: return@mapNotNull null
            val lanHttp = o.bool("lanHttp")
            // A stored address goes through the same policy as a typed one: a tampered store cannot point a token at a forbidden host.
            if (ServerAddress.parse(base, lanHttp) !is ServerAddress.Parse.Ok) return@mapNotNull null
            ServerAccount(kind, o.str("serverId") ?: return@mapNotNull null, o.str("serverName") ?: kind.title, base, lanHttp,
                o.str("userId") ?: return@mapNotNull null, o.str("userName") ?: "User", o.str("token") ?: return@mapNotNull null, o.str("deviceId") ?: return@mapNotNull null)
        }.take(MAX_ACCOUNTS)
    }
}

/**
 * Sign-ins and their verification state for Plex, Jellyfin and Emby.
 *
 * "Connected" is a fact about THIS process: an account counts as connected only after [verify] (or the sign-in that produced it) succeeded
 * here, with the time it happened. A saved sign-in that has not been checked yet is reported as saved-but-unverified, an installed app or a
 * reachable address is nothing at all. Verification failures are kept so Settings can say why.
 */
class MediaServerService(
    private val store: MediaAccountStore, http: MediaHttp, private val identity: ClientIdentity, private val canBrowse: (String) -> Boolean = { true },
    val modernPlex: ((String, Boolean) -> PlexModernClient?)? = null,
    private val forgetModernPlex: ((String) -> Boolean)? = null,
    private val mayManage: ((String) -> Boolean)? = null, private val purgeModernPlex: ((String) -> Boolean)? = null, private val clock: () -> Long = System::currentTimeMillis,
) {
    val libraryPlayback = ServerLibraryPlayback(this, http, identity)
    suspend fun search(profileId: String, query: String): List<Pair<ServerAccount, MediaEntry>> = libraryPlayback.search(profileId, query)
    internal fun mayBrowse(profileId: String) = canBrowse(profileId)
    val jellyfin = JellyfinEmbyClient(ServerKind.JELLYFIN, http, identity)
    val emby = JellyfinEmbyClient(ServerKind.EMBY, http, identity)
    val plex = PlexClient(http, identity, clock)

    private val lifecycleLock = Any()
    private val purgedProfiles = ConcurrentHashMap.newKeySet<String>()
    private fun persist(profileId: String, accounts: List<ServerAccount>, managementRequired: Boolean = false): Boolean = synchronized(lifecycleLock) {
        if (profileId in purgedProfiles || (managementRequired && (mayManage?.invoke(profileId) != true))) false
        else if (!store.save(profileId, accounts)) false
        else { cache[profileId] = accounts; true }
    }
    private val cache = ConcurrentHashMap<String, List<ServerAccount>>()          // profile -> decrypted accounts (memory only)
    private val verifiedAt = ConcurrentHashMap<String, Long>()                    // "profile|accountKey" -> time
    private val failures = ConcurrentHashMap<String, MediaError>()

    private fun k(profileId: String, a: ServerAccount) = "$profileId|${a.key}"

    /** Decrypts the profile's accounts once, off the main thread. */
    suspend fun accounts(profileId: String): List<ServerAccount> {
        if (profileId in purgedProfiles) return emptyList()
        cache[profileId]?.let { return it }
        val loaded = withContext(Dispatchers.Default) { store.load(profileId) }
        return synchronized(lifecycleLock) { if (profileId in purgedProfiles) emptyList() else loaded.also { cache[profileId] = it } }
    }

    /**
     * Stores an account that a sign-in in this process just produced (so it is verified now). A second sign-in for the same server and user
     * replaces the first. Returns false when the profile already has [AccountCodec.MAX_ACCOUNTS] or the store refused.
     */
    suspend fun add(profileId: String, account: ServerAccount): Boolean {
        refreshLock.lock()
        try {
        if (profileId.isBlank() || profileId in purgedProfiles || ((account.deviceId.startsWith("aura-jwt-") || mayManage != null) && (mayManage?.invoke(profileId) != true))) return false
        val next = accounts(profileId).filter { it.key != account.key } + account
        if (next.size > AccountCodec.MAX_ACCOUNTS) return false
        if (!withContext(Dispatchers.Default) { persist(profileId, next, account.deviceId.startsWith("aura-jwt-") || mayManage != null) }) return false
        if (profileId in purgedProfiles) return false
        verifiedAt[k(profileId, account)] = clock(); failures.remove(k(profileId, account))
        return true
        } finally { refreshLock.unlock() }
    }

    suspend fun modernClient(profileId: String, create: Boolean): PlexModernClient? {
        if (profileId in purgedProfiles || (create && (mayManage?.invoke(profileId) != true))) return null
        return withContext(Dispatchers.Default) { modernPlex?.invoke(profileId,create) }
    }
    suspend fun modernResources(client: PlexModernClient, token: String) = client.authorizedResources(token,identity)
    suspend fun connectModern(profileId: String, client: PlexModernClient, user: PlexUser, server: PlexServer, lan: Boolean): MediaResult<ServerAccount> {
        if ((mayManage?.invoke(profileId) != true) || modernClient(profileId,false)?.clientIdentifier != client.clientIdentifier) return MediaResult.Failure(MediaError.FORBIDDEN)
        return PlexClient(httpForModern,identity.copy(deviceId=client.clientIdentifier),clock).connect(user,server,lan)
    }

    private val modernRefreshedAt = ConcurrentHashMap<String,Long>()
    private val refreshLock = kotlinx.coroutines.sync.Mutex()
    /** Modern bindings live in the existing encrypted account.deviceId; no account JWT is persisted. */
    suspend fun authorizedAccount(profileId: String, supplied: ServerAccount): MediaResult<ServerAccount> {
        if (!canBrowse(profileId)) return MediaResult.Failure(MediaError.FORBIDDEN)
        val saved = accounts(profileId).firstOrNull { it.key == supplied.key } ?: return MediaResult.Failure(MediaError.FORBIDDEN)
        if (saved.kind != ServerKind.PLEX || !saved.deviceId.startsWith("aura-jwt-")) return MediaResult.Ok(saved)
        refreshLock.lock()
        try {
            val current = accounts(profileId).firstOrNull { it.key == supplied.key } ?: return MediaResult.Failure(MediaError.FORBIDDEN)
            if (modernRefreshedAt[k(profileId,current)]?.let { clock() - it < 5 * 60_000L } == true) return MediaResult.Ok(current)
            val modern = modernClient(profileId,false) ?: return MediaResult.Failure(MediaError.AUTH_FAILED)
            if (modern.clientIdentifier != current.deviceId) return MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
            val jwt = when(val r = modern.refresh()) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
            val resources = when(val r = modern.authorizedResources(jwt,identity)) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
            if (resources.first.id != current.userId) return MediaResult.Failure(MediaError.IDENTITY_MISMATCH)
            val server = resources.second.firstOrNull { it.machineId == current.serverId } ?: return MediaResult.Failure(MediaError.FORBIDDEN)
            val candidateClient = PlexClient(httpForModern, identity.copy(deviceId = modern.clientIdentifier), clock)
            val fresh = when(val r = candidateClient.connect(resources.first,server,current.lanHttpApproved)) { is MediaResult.Failure -> return r; is MediaResult.Ok -> r.value }
            if (!canBrowse(profileId) || accounts(profileId).none { it.key == current.key }) return MediaResult.Failure(MediaError.FORBIDDEN)
            val next = accounts(profileId).map { if (it.key == fresh.key) fresh else it }
            if (!persist(profileId,next)) return MediaResult.Failure(MediaError.SERVER)
            modernRefreshedAt[k(profileId,fresh)] = clock()
            return MediaResult.Ok(fresh)
        } finally { refreshLock.unlock() }
    }
    private val httpForModern = http

    /** Checks one saved account against its server (identity first, then the credential). */
    suspend fun verify(profileId: String, account: ServerAccount): MediaResult<ServerInfo> {
        val fresh = when(val resolved = authorizedAccount(profileId,account)) { is MediaResult.Failure -> { verifiedAt.remove(k(profileId,account)); failures[k(profileId,account)] = resolved.error; return resolved }; is MediaResult.Ok -> resolved.value }
        val r = when (fresh.kind) { ServerKind.JELLYFIN -> jellyfin.verify(fresh); ServerKind.EMBY -> emby.verify(fresh); ServerKind.PLEX -> plex.verify(fresh) }
        if (r is MediaResult.Ok) { verifiedAt[k(profileId, account)] = clock(); failures.remove(k(profileId, account)) }
        else if (r is MediaResult.Failure) { verifiedAt.remove(k(profileId, account)); failures[k(profileId, account)] = r.error }
        return r
    }

    /**
     * [verify], unless the same account was verified successfully in this process within [maxAgeMs]. Used in front of library reads and of
     * every playback range: the profile, account and server-address checks around it still run each time (they are in memory), but the
     * network round trip to re-prove the server and user identity is made at most once per window instead of once per megabyte of video.
     * A failed verification is never trusted: it removes the timestamp, so the next call checks again.
     */
    suspend fun verifyFresh(profileId: String, account: ServerAccount, maxAgeMs: Long = VERIFY_FRESH_MS): MediaResult<Unit> {
        val at = verifiedAt[k(profileId, account)]
        if (at != null && clock() - at in 0 until maxAgeMs) return MediaResult.Ok(Unit)
        return verify(profileId, account).map { }
    }

    suspend fun verifyAll(profileId: String) { accounts(profileId).forEach { verify(profileId, it) } }

    /**
     * Re-checks the accounts whose last successful check is older than [maxAgeMs] (or that were never checked in this process). Called when
     * the app comes to the foreground, so a saved sign-in becomes Connected (or shows why not) without the person opening Settings.
     * Returns true when anything was checked. Does nothing, and costs no decryption, for a profile without stored sign-ins.
     */
    suspend fun verifyStale(profileId: String, maxAgeMs: Long = 5 * 60_000L): Boolean {
        if (cache[profileId] == null && !store.has(profileId)) return false
        val due = accounts(profileId).filter { a -> verifiedAt[k(profileId, a)]?.let { clock() - it >= maxAgeMs } ?: true }
        due.forEach { verify(profileId, it) }
        return due.isNotEmpty()
    }

    /** Signs out on the server where the API has that (best effort) and always removes the local credential. */
    suspend fun remove(profileId: String, supplied: ServerAccount): Boolean {
        refreshLock.lock()
        try {
        val account = accounts(profileId).firstOrNull { it.key == supplied.key } ?: return false
        if ((account.deviceId.startsWith("aura-jwt-") || mayManage != null) && (mayManage?.invoke(profileId) != true)) return false
        when (account.kind) { ServerKind.JELLYFIN -> jellyfin.signOut(account); ServerKind.EMBY -> emby.signOut(account); ServerKind.PLEX -> Unit }
        val next = accounts(profileId).filter { it.key != account.key }
        val ok = withContext(Dispatchers.Default) { persist(profileId, next, account.deviceId.startsWith("aura-jwt-") || mayManage != null) }
        if (ok) {
            verifiedAt.remove(k(profileId, account)); failures.remove(k(profileId, account)); modernRefreshedAt.remove(k(profileId,account))
            if (account.deviceId.startsWith("aura-jwt-") && next.none { it.deviceId == account.deviceId } && (mayManage?.invoke(profileId) == true)) withContext(Dispatchers.Default) { forgetModernPlex?.invoke(profileId) }
        }
        return ok
        } finally { refreshLock.unlock() }
    }

    /** A deleted profile takes its server sign-ins with it. */
    fun purge(profileId: String) {
        synchronized(lifecycleLock) { purgedProfiles.add(profileId); store.clear(profileId); cache.remove(profileId) }
        purgeModernPlex?.invoke(profileId)
        modernRefreshedAt.keys.filter { it.startsWith("$profileId|") }.forEach(modernRefreshedAt::remove)
        verifiedAt.keys.filter { it.startsWith("$profileId|") }.forEach(verifiedAt::remove); failures.keys.filter { it.startsWith("$profileId|") }.forEach(failures::remove)
    }

    fun verifiedAt(profileId: String, account: ServerAccount): Long? = verifiedAt[k(profileId, account)]

    companion object { const val VERIFY_FRESH_MS = 120_000L }

    /** Main-thread safe: reads memory only (plus one cheap presence check when the accounts were not decrypted yet). */
    fun status(profileId: String, kind: ServerKind): ConnectionStatus {
        val id = when (kind) { ServerKind.PLEX -> ConnectionId.PLEX; ServerKind.JELLYFIN -> ConnectionId.JELLYFIN; ServerKind.EMBY -> ConnectionId.EMBY }
        val loaded = cache[profileId]
        if (loaded == null) return ConnectionStatus(id, ConnectionState.NOT_CONFIGURED,
            message = if (store.has(profileId)) "Saved server sign-ins have not been checked in this session yet." else "No ${kind.title} server is connected for this profile.")
        val mine = loaded.filter { it.kind == kind }
        if (mine.isEmpty()) return ConnectionStatus(id, ConnectionState.NOT_CONFIGURED, message = "No ${kind.title} server is connected for this profile.")
        val ok = mine.mapNotNull { a -> verifiedAt[k(profileId, a)] }
        if (ok.isNotEmpty()) return ConnectionStatus(id, ConnectionState.CONNECTED, verifiedAtEpochMs = ok.max(),
            message = "${ok.size} of ${mine.size} ${kind.title} server sign-in${if (mine.size == 1) "" else "s"} verified for this profile.")
        val error = mine.firstNotNullOfOrNull { failures[k(profileId, it)] }
            ?: return ConnectionStatus(id, ConnectionState.NOT_CONFIGURED, message = "A ${kind.title} sign-in is saved but has not been verified in this session yet.")
        return ConnectionStatus(id, when (error) {
            MediaError.AUTH_FAILED, MediaError.FORBIDDEN -> ConnectionState.AUTH_FAILED
            MediaError.UNREACHABLE -> ConnectionState.UNREACHABLE
            else -> ConnectionState.ERROR
        }, message = error.message())
    }

    fun status(profileId: String, id: ConnectionId): ConnectionStatus? = when (id) {
        ConnectionId.PLEX -> status(profileId, ServerKind.PLEX); ConnectionId.JELLYFIN -> status(profileId, ServerKind.JELLYFIN); ConnectionId.EMBY -> status(profileId, ServerKind.EMBY)
        else -> null
    }
}
