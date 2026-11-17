package no.aura.os.v2.settings

/** External connections shown in Settings. [plannedIn] is the integration task that will make each one real. */
enum class ConnectionId(val title: String, val plannedIn: String) {
    TMDB("TMDB", "F4"), YOUTUBE("YouTube", "F4c"), ULTRAMAX("UltraMax", "F5"), ECLIPSE("Eclipse", "F7"),
    PLEX("Plex", "F10"), JELLYFIN("Jellyfin", "F10"), EMBY("Emby", "F10"), SIMKL("Simkl", "F9"), AURAMUSIC("AuraMusic", "F6L"),
    AURACONNECT("AuraConnect", "reserved until its security review"),
}

enum class ConnectionState(val label: String) {
    NOT_CONFIGURED("Not configured"), CONNECTING("Connecting"), CONNECTED("Connected"),
    AUTH_FAILED("Authentication failed"), OFFLINE("Offline"), UNREACHABLE("Unreachable"), ERROR("Error"),
}

/**
 * Verified status of one connection. Invariant: CONNECTED requires a verification timestamp, so entered credentials alone can never produce
 * Connected. [message] is a short, credential-free error summary.
 */
data class ConnectionStatus(val id: ConnectionId, val state: ConnectionState, val verifiedAtEpochMs: Long? = null, val message: String? = null) {
    init { require(state != ConnectionState.CONNECTED || verifiedAtEpochMs != null) { "Connected requires a successful verification" } }
    val isError: Boolean get() = state == ConnectionState.AUTH_FAILED || state == ConnectionState.OFFLINE || state == ConnectionState.UNREACHABLE || state == ConnectionState.ERROR
}

/** Source of verified connection statuses. Real providers arrive in their own tasks; F2 ships only [NotConfiguredConnections]. */
interface ConnectionRegistry {
    fun statuses(): List<ConnectionStatus>
}

/** F2: no integration exists, so nothing is configured and nothing can be Connected. */
object NotConfiguredConnections : ConnectionRegistry {
    override fun statuses() = ConnectionId.values().map {
        ConnectionStatus(it, ConnectionState.NOT_CONFIGURED, message = if (it == ConnectionId.AURACONNECT) "Reserved until its security review" else null)
    }
}

/**
 * Secure credential storage contract (UltraMax code, Eclipse ARL, tokens). F2 defines the interface only; the Android Keystore-backed
 * implementation arrives with the first integration that stores a secret. Implementations must never log or return the secret for display.
 */
interface CredentialStore {
    fun has(id: ConnectionId): Boolean
    fun save(id: ConnectionId, secret: String)
    fun clear(id: ConnectionId)
    /** Fixed-length mask for display, or null when nothing is stored. Never derived from the secret's content or length. */
    fun masked(id: ConnectionId): String? = if (has(id)) MASK else null
    companion object { const val MASK = "••••••••" }
}

/** In-memory store for tests and Demo Mode. Not persistent, not for real secrets. */
class MemoryCredentialStore : CredentialStore {
    private val map = mutableMapOf<ConnectionId, String>()
    override fun has(id: ConnectionId) = map.containsKey(id)
    override fun save(id: ConnectionId, secret: String) { map[id] = secret }
    override fun clear(id: ConnectionId) { map.remove(id) }
    override fun toString() = "MemoryCredentialStore(${map.size} entries)"   // never prints secrets
}

/**
 * Statuses with the developer configuration taken into account. A key being present is NOT a connection: nothing has been verified against
 * the service, so YouTube stays "Not configured" with an honest note until F4c verifies it.
 */
class DevConnections(
    private val youtube: no.aura.os.v2.youtube.YouTubeConfig,
    private val tmdbConfigured: Boolean = false, private val tmdbVerifiedAt: () -> Long? = { null },
    private val ultra: () -> no.aura.os.v2.playback.UltraState = { no.aura.os.v2.playback.UltraState.NoCode },
    private val youtubeVerifiedAt: () -> Long? = { null },
    private val simklConfigured: Boolean = false,
    private val simklVerifiedAt: () -> Long? = { null },
    /** Plex / Jellyfin / Emby: the verified state of the active profile's server sign-ins, or null to keep "Not configured". */
    private val media: (ConnectionId) -> ConnectionStatus? = { null },
) : ConnectionRegistry {
    override fun statuses() = NotConfiguredConnections.statuses().map {
        val server = if (it.id == ConnectionId.PLEX || it.id == ConnectionId.JELLYFIN || it.id == ConnectionId.EMBY) media(it.id) else null
        if (server != null) server
        else if (it.id == ConnectionId.TMDB) {
            // CONNECTED only after a real TMDB request has succeeded in this process; a token being present proves nothing.
            val at = tmdbVerifiedAt()
            when {
                at != null -> ConnectionStatus(ConnectionId.TMDB, ConnectionState.CONNECTED, verifiedAtEpochMs = at, message = "Verified by a successful catalogue request.")
                tmdbConfigured -> it.copy(message = "A development token is present but no request has succeeded yet. Open Home in Real mode.")
                else -> it.copy(message = "No TMDB token in this build.")
            }
        } else if (it.id == ConnectionId.ULTRAMAX) {
            // CONNECTED only after UltraMax actually answered (manifest or a stream request). A saved code alone proves nothing.
            when (val u = ultra()) {
                no.aura.os.v2.playback.UltraState.NoCode -> it.copy(message = "No UltraMax code saved.")
                no.aura.os.v2.playback.UltraState.Unverified -> it.copy(message = "A code is saved but has not been verified yet. Choose Verify.")
                is no.aura.os.v2.playback.UltraState.Verified -> ConnectionStatus(ConnectionId.ULTRAMAX, ConnectionState.CONNECTED, verifiedAtEpochMs = u.atMs, message = "Verified by a successful UltraMax request.")
                is no.aura.os.v2.playback.UltraState.Failed -> ConnectionStatus(ConnectionId.ULTRAMAX, when (u.error) {
                    no.aura.os.v2.ultramax.UltraError.INVALID_CODE -> ConnectionState.AUTH_FAILED; no.aura.os.v2.ultramax.UltraError.NETWORK -> ConnectionState.OFFLINE; else -> ConnectionState.ERROR
                }, message = u.error.let { e -> when (e) { no.aura.os.v2.ultramax.UltraError.INVALID_CODE -> "UltraMax did not accept the saved code."; no.aura.os.v2.ultramax.UltraError.NETWORK -> "Could not reach UltraMax."; no.aura.os.v2.ultramax.UltraError.SERVER -> "UltraMax reported a server problem."; no.aura.os.v2.ultramax.UltraError.BAD_RESPONSE -> "UltraMax returned an unreadable response." } })
            }
        } else if (it.id == ConnectionId.SIMKL) {
            val at = simklVerifiedAt()
            if (at != null) ConnectionStatus(ConnectionId.SIMKL, ConnectionState.CONNECTED, verifiedAtEpochMs = at,
                message = "AUTH V2 PIN pairing verified for this profile.")
            else it.copy(message = if (simklConfigured) "Client configured. Choose Simkl to pair this profile using a PIN." else "SIMKL_CLIENT_ID is missing in this private build.")
        } else if (it.id == ConnectionId.YOUTUBE) {
            val at = youtubeVerifiedAt()
            if (at != null) ConnectionStatus(ConnectionId.YOUTUBE, ConnectionState.CONNECTED, verifiedAtEpochMs = at,
                message = "Public YouTube Data API verified. Google account OAuth is not connected.")
            else it.copy(message = if (youtube.isConfigured) "A development API key is present but has not been verified yet." else "No YouTube API key in this build.")
        } else it
    }
}
