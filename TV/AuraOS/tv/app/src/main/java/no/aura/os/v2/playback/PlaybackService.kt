package no.aura.os.v2.playback

import no.aura.os.v2.ultramax.*

/** Connection state of UltraMax as the person should see it. CONNECTED only after a real manifest or stream request succeeded. */
sealed interface UltraState {
    object NoCode : UltraState
    object Unverified : UltraState
    data class Verified(val atMs: Long, val name: String) : UltraState
    data class Failed(val error: UltraError) : UltraState
}

fun UltraError.message(): String = when (this) {
    UltraError.INVALID_CODE -> "UltraMax did not accept this code."
    UltraError.NETWORK -> "Could not reach UltraMax. Check the network connection."
    UltraError.SERVER -> "UltraMax reported a server problem."
    UltraError.BAD_RESPONSE -> "UltraMax returned a response this app could not read."
}

/** Finds playable sources for a title through UltraMax (the only source in F5). The stored code is read here and goes nowhere else. */
class PlaybackService(
    private val client: UltraMaxClient, private val codes: UltraCodeStore, private val authorizer: no.aura.os.v2.admin.Authorizer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Resolution {
        object NeedsCode : Resolution
        object NoImdbId : Resolution
        object NoSources : Resolution
        data class Sources(val streams: List<UltraStream>) : Resolution
        data class Failed(val error: UltraError) : Resolution
    }

    /** Called with a state change whenever a request proves or disproves the connection. */
    var onState: ((UltraState) -> Unit)? = null

    val hasCode: Boolean get() = codes.isSet

    suspend fun resolve(imdbId: String?, season: Int?, episode: Int?): Resolution {
        // codes.load() is an AndroidKeyStore call + AES-GCM decryption: off the main thread (resolve is called from UI coroutines).
        val code = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { codes.load() } ?: return Resolution.NeedsCode
        val id = imdbId?.takeIf { UltraRoute.IMDB.matches(it) } ?: return Resolution.NoImdbId
        val route = try { if (season == null || episode == null) UltraRoute.Movie(id) else UltraRoute.Episode(id, season, episode) } catch (_: IllegalArgumentException) { return Resolution.NoSources }
        return when (val r = client.streams(code, route)) {
            is UltraResult.Failure -> { onState?.invoke(UltraState.Failed(r.error)); Resolution.Failed(r.error) }
            is UltraResult.Ok -> { onState?.invoke(UltraState.Verified(clock(), "UltraMax")); if (r.value.isEmpty()) Resolution.NoSources else Resolution.Sources(r.value) }
        }
    }

    /** Verify the stored code (Settings → Connections → UltraMax). */
    suspend fun verify(): UltraState {
        val code = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { codes.load() } ?: return UltraState.NoCode.also { onState?.invoke(it) }
        return when (val r = client.verify(code)) {
            is UltraResult.Ok -> UltraState.Verified(clock(), r.value)
            is UltraResult.Failure -> UltraState.Failed(r.error)
        }.also { onState?.invoke(it) }
    }

    enum class SaveResult { SAVED, INVALID, NEEDS_ADMIN, STORAGE_FAILED }

    /** Saves a new code (validated, encrypted). Protected: needs an open Administrator session, enforced here and not only in the UI. */
    fun saveCode(raw: String): SaveResult {
        if (!authorizer.isAuthorized()) return SaveResult.NEEDS_ADMIN
        val code = UltraCode.parse(raw) ?: return SaveResult.INVALID
        authorizer.touch()
        return if (codes.save(code)) { onState?.invoke(UltraState.Unverified); SaveResult.SAVED } else SaveResult.STORAGE_FAILED
    }
    /** Removes the stored code. Protected like [saveCode]. */
    fun clearCode(): Boolean { if (!authorizer.isAuthorized()) return false; authorizer.touch(); codes.clear(); onState?.invoke(UltraState.NoCode); return true }
}
