package no.aura.os.v2.overlay

import androidx.compose.runtime.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.playback.PlaybackPrefs
import no.aura.os.v2.playback.PlayerStatus

/** Aura controls with the confined server transport. No plain-URL fallback or invented renditions. */
@Composable
fun MediaServerPlayerHost(service: MediaServerService, profileId: String, account: ServerAccount, playback: ServerPlayback, prefs: PlaybackPrefs = PlaybackPrefs(), onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val source = remember(profileId, account, playback) {
        ConfinedMediaDataSource(playback) { service.libraryPlayback.streamHeaders(profileId, account, playback) }
    }
    val reportLock = remember(source) { Mutex() }
    var started by remember(source) { mutableStateOf(false) }
    var stopped by remember(source) { mutableStateOf(false) }
    var reportError by remember(source) { mutableStateOf<String?>(null) }
    fun report(event: ServerPlaybackEvent, position: Long, duration: Long, playing: Boolean, final: Boolean = false) {
        // Disposal must still deliver the final report after the composition scope is cancelled.
        scope.launch(if (final) NonCancellable else kotlin.coroutines.EmptyCoroutineContext) {
            reportLock.withLock {
                val result = service.libraryPlayback.report(profileId, account, playback, event, position, duration, !playing)
                if (result is MediaResult.Failure) reportError = "Playback reporting: ${result.error.message()}"
            }
        }
    }
    VideoPlayerHost(
        title = playback.entry.title, subtitle = null, source = "", prefs = prefs, onClose = onClose,
        startPositionMs = playback.entry.resumeMs ?: 0L, mediaSource = source, notice = reportError,
        onPlaybackState = { position, duration, playing, status ->
            if (status == PlayerStatus.READY && stopped) { stopped = false; started = false }
            if (!stopped) {
                if (status == PlayerStatus.ENDED) {
                    stopped = true
                    report(ServerPlaybackEvent.STOP, duration, duration, false, final = true)
                } else {
                    val event = if (started) ServerPlaybackEvent.PROGRESS else ServerPlaybackEvent.START
                    started = true
                    report(event, position, duration, playing)
                }
            }
        },
        onStopped = { position, duration ->
            if (started && !stopped) {
                stopped = true
                report(ServerPlaybackEvent.STOP, position, duration, false, final = true)
            }
        },
    )
}
