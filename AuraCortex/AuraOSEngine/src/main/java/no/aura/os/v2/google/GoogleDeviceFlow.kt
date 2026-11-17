package no.aura.os.v2.google

import kotlinx.coroutines.delay
import no.aura.os.v2.identity.GoogleIdentity
import no.aura.os.v2.profiles.Outcome
import no.aura.os.v2.profiles.ProfileService

interface GoogleTvApi {
    val configured: Boolean
    suspend fun begin(): GoogleTvCode?
    suspend fun check(code: GoogleTvCode): GoogleTvPoll
    suspend fun refresh(saved: String): String?
    suspend fun identity(saved: String): GoogleIdentity?
    suspend fun revoke(saved: String): Boolean
    suspend fun myPlaylists(saved: String): List<Pair<String, String>>?
    suspend fun subscriptions(saved: String): List<Pair<String, String>>? = emptyList()
    suspend fun playlistItems(saved: String, playlist: String): List<Pair<String, String>>?
}
class GooglePolling(intervalMs: Long, private val expiresAt: Long, start: Long = 0) {
    private var interval = intervalMs.coerceAtLeast(5000)
    private var next = start + interval
    fun ready(now: Long) = now >= next && !expired(now)
    fun expired(now: Long) = now >= expiresAt
    fun after(result: GoogleTvPoll, now: Long) { if (result == GoogleTvPoll.SlowDown) interval += 5000; next = now + interval }
}
interface GoogleTokenVault { fun get(profile: String): String?; fun save(profile: String, token: String): Boolean; fun clear(profile: String) }
data class GoogleFlowState(val message: String = "Connect your Google account with consent on your phone.", val code: GoogleTvCode? = null,
    val connected: Boolean = false, val playlists: List<Pair<String,String>> = emptyList(), val videos: List<Pair<String,String>> = emptyList(), val busy: Boolean = false, val subscriptions: List<Pair<String,String>> = emptyList())
/** One instance belongs to one captured profile; host cancels its coroutine when that profile or page changes. */
class GoogleDeviceFlow(private val profile: String, private val api: GoogleTvApi, private val vault: GoogleTokenVault,
    private val profiles: ProfileService, private val activeProfile: () -> String?, private val now: () -> Long = System::currentTimeMillis) {
    private fun active() = activeProfile() == profile
    suspend fun connect(emit: (GoogleFlowState) -> Unit) {
        if (!active()) return
        if (!api.configured) { emit(GoogleFlowState("Google TV OAuth client is not configured in this build.")); return }
        emit(GoogleFlowState("Requesting authorization code…", busy = true))
        val code = api.begin() ?: run { emit(GoogleFlowState("Google authorization unavailable. Check TV client setup and connection.")); return }
        emit(GoogleFlowState("Open the address on your phone, enter the code, then consent. Administrator authorization is required to save.", code, busy = true))
        val polling = GooglePolling(code.intervalSeconds * 1000, code.expiresAtMs, now())
        while (active() && !polling.expired(now())) {
            delay(250)
            if (!polling.ready(now())) continue
            val result = api.check(code)
            polling.after(result, now())
            when (result) {
                GoogleTvPoll.Pending, GoogleTvPoll.SlowDown -> Unit
                is GoogleTvPoll.Failed -> { emit(GoogleFlowState(result.message)); return }
                is GoogleTvPoll.Authorized -> {
                    val identity = api.identity(result.tokenJson)
                    if (!active()) return
                    if (identity == null || !identity.isValid) { api.revoke(result.tokenJson); emit(GoogleFlowState("Google identity could not be verified from authenticated userinfo.")); return }
                    val outcome = profiles.linkGoogle(profile, identity)
                    if (outcome !is Outcome.Ok) { api.revoke(result.tokenJson); emit(GoogleFlowState("Account was not saved. Unlock System Administrator and retry; duplicate accounts cannot be linked.")); return }
                    if (!kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { active() && vault.save(profile, result.tokenJson) }) { profiles.unlinkGoogle(profile); api.revoke(result.tokenJson); emit(GoogleFlowState("Secure token storage unavailable. Account was not connected.")); return }
                    browse(emit); return
                }
            }
        }
        if (active()) emit(GoogleFlowState("Authorization expired. Start again for a new code."))
    }
    private suspend fun renewable(): String? {
        if (!active()) return null
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vault.get(profile) } ?: return null
        val refreshed = api.refresh(saved) ?: return null
        if (!active() || !kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { active() && vault.save(profile, refreshed) }) return null
        return refreshed
    }
    suspend fun browse(emit: (GoogleFlowState) -> Unit) {
        val saved = renewable()
        if (!active()) return
        if (saved == null) { emit(GoogleFlowState("Authorization expired or revoked. Reconnect your account.")); return }
        val rows = api.myPlaylists(saved)
        val subscriptions = if (active()) api.subscriptions(saved) else return
        if (active()) emit(GoogleFlowState(if (rows == null) "YouTube unavailable, access denied, or consent missing. Retry or reconnect." else "Your playlists and subscriptions (up to 500 each). Watch Later is not available through this API.", connected = rows != null, playlists = rows.orEmpty(), subscriptions = subscriptions.orEmpty()))
    }
    suspend fun openPlaylist(id: String, emit: (GoogleFlowState) -> Unit) {
        val saved = renewable() ?: run { if (active()) emit(GoogleFlowState("Reconnect to read this playlist.")); return }
        val rows = api.playlistItems(saved, id)
        if (active()) emit(GoogleFlowState(if (rows == null) "Playlist unavailable or access denied." else "Playlist videos (up to 500). Playback opens the official YouTube app.", connected = true, videos = rows.orEmpty()))
    }
    suspend fun disconnect(emit: (GoogleFlowState) -> Unit) {
        if (!active()) return
        val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vault.get(profile) }
        // Local credentials are cleared before network activity, including offline revoke failures.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vault.clear(profile) }
        val unlink = profiles.unlinkGoogle(profile)
        val revoked = token == null || api.revoke(token)
        if (active()) emit(GoogleFlowState(if (!revoked) "Disconnected locally. Google revoke failed; remove AuraOS in Google account permissions to finish." else if (unlink !is Outcome.Ok) "Disconnected locally. Unlock Administrator to remove the profile's account label." else "Google account disconnected."))
    }
}
