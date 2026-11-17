package no.aura.os.v2.amp

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Foreground, profile-owned player. The owning shell must pause on stop and close on profile change. */
class AmpPlayback(context: Context, profile: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library = AmpLibrary(context, profile)
    private val vault = AmpArlVault(context)
    private val provider = AmpProvider(vault, profile)
    private var closed = false
    private var generation = 0L
    val selectionVersion:Long get()=generation
    private var resolving: Job? = null
    private var recorded = false
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableStateOf(false); private set
    var selected: AmpTrack? by mutableStateOf(null); private set
    var playing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var error: String? by mutableStateOf(null); private set
    /** The last failure Eclipse reported for this profile's playback (e.g. a rejected ARL); cleared when audio plays again. */
    var providerFailure: AmpProviderFailure? by mutableStateOf(null); private set
    /** When audio actually played on this device in this process: the only proof that playback works. */
    var audioConfirmedAt: Long? by mutableStateOf(null); private set
    var queue by mutableStateOf(library.queue()); private set
    var recent by mutableStateOf(library.recent()); private set
    var favourites by mutableStateOf(library.favourites()); private set
    var playlists by mutableStateOf(library.playlists()); private set
    private val player = ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { AmpHttpsDataSource { vault.read(profile) } })).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying && playbackState == Player.STATE_READY) { audioConfirmedAt = System.currentTimeMillis(); providerFailure = null }
                if (isPlaying && playbackState == Player.STATE_READY && !recorded) {
                    selected?.let { library.played(it); recent = library.recent(); recorded = true }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) durationMs = duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                if (state == Player.STATE_ENDED) this@AmpPlayback.next()
            }
            override fun onPlayerError(e: PlaybackException) {
                loading = false; playing = false
                val diagnostic = generateSequence(e as Throwable?) { it.cause }.filterIsInstance<AmpAudioRequestException>().firstOrNull()
                diagnostic?.failure?.let { providerFailure = it }
                error = diagnostic?.failure?.safeMessage ?: "Audio playback failed (${e.errorCodeName}). Try another track."
                val token = generation
                scope.launch { delay(1500); if (!closed && token == generation && !playing) next() }
            }
        })
    }
    init { scope.launch { while (isActive) { positionMs = player.currentPosition.coerceAtLeast(0); delay(500) } } }
    fun play(track: AmpTrack, tracks: List<AmpTrack> = emptyList()) {
        if (closed) return
        val token = ++generation
        resolving?.cancel(); player.stop(); player.clearMediaItems(); selected = track; loading = true; playing = false; error = null; positionMs = 0; durationMs = 0; recorded = false
        if (tracks.isNotEmpty()) {
            queue = AmpQueueRules.afterSelection(track, tracks); library.setQueue(queue)
        }
        resolving = scope.launch {
            val streamUrl = if (track.id.startsWith("server|")) {
                // Not supported yet for media servers
                null
            } else if (track.id.startsWith("auramusic:")) {
                // Need to get the engine
                null
            } else {
                provider.resolveStream(track)?.url
            }

            if (closed || token != generation || !isActive) return@launch
            if (streamUrl == null) { 
                loading = false; providerFailure = provider.lastFailure; error = provider.lastFailure?.safeMessage ?: "Stream resolution failed."
                scope.launch { delay(1500); if (!closed && token == generation && !playing) next() }
                return@launch 
            }
            player.setMediaItem(MediaItem.Builder().setUri(streamUrl).build()); player.prepare(); player.play()
        }
    }
    fun enqueue(track: AmpTrack) { if (!closed) { queue = (queue + track).take(200); library.setQueue(queue) } }
    fun playQueued(index:Int) { if(closed)return; val track=queue.getOrNull(index) ?: return; queue=queue.drop(index+1);library.setQueue(queue);play(track) }
    fun removeQueuedAt(index:Int) { if(closed || index !in queue.indices)return; queue=queue.filterIndexed { i,_->i!=index };library.setQueue(queue) }
    fun removeQueued(id: String) { queue = queue.filterNot { it.id == id }; library.setQueue(queue) }
    fun toggle() { if (closed) return; if (loading || player.isPlaying) pause() else if (player.mediaItemCount > 0) player.play() else selected?.let { play(it) } }
    /** Resume/start playback without toggling (for a remote "play" command, which must not pause when already playing). */
    fun resume() { if (closed) return; if (player.mediaItemCount > 0) player.play() else selected?.let { play(it) } }
    fun applyShuffle(on: Boolean) { if (!closed) shuffle = on }
    fun applyRepeat(on: Boolean) { if (!closed) repeat = on }
    fun clearQueue() { if (!closed) { queue = emptyList(); library.setQueue(queue) } }
    /** Reorders the queue (for a remote "move" command); indices out of range are clamped, no-op on an empty/invalid source index. */
    fun moveQueued(from: Int, to: Int) { if (closed || from !in queue.indices) return; val list = queue.toMutableList(); val item = list.removeAt(from); list.add(to.coerceIn(0, list.size), item); queue = list.take(200); library.setQueue(queue) }
    /** Replacement or removal of the ARL: stop any resolution in flight, drop the prepared stream and forget what the old credential proved. */
    fun invalidateSource() { if (!closed) { pause(); player.stop(); player.clearMediaItems(); positionMs=0; durationMs=0; error=null; providerFailure=null; audioConfirmedAt=null } }
    fun pause() { if (!closed) { ++generation; resolving?.cancel(); loading = false; player.pause() } }
    fun toggleShuffle() { if (!closed) shuffle = !shuffle }
    fun toggleRepeat() { if (!closed) repeat = !repeat }
    fun next() {
        if (closed) return
        val index = if (shuffle && queue.isNotEmpty()) kotlin.random.Random.nextInt(queue.size) else 0
        val step = AmpQueueRules.advance(queue, selected, repeat, index) ?: return
        queue = step.second; library.setQueue(queue); play(step.first)
    }
    fun previous() { if (closed) return; if (player.currentPosition > 3000) seek(0) else recent.firstOrNull { it.id != selected?.id }?.let { play(it) } }
    fun seek(ms: Long) { if (!closed) player.seekTo(ms.coerceIn(0, durationMs.coerceAtLeast(0))) }
    fun toggleFavourite(track: AmpTrack) { library.toggleFavourite(track); favourites = library.favourites() }
    fun importPlaylist(playlist: AmpPlaylist, tracks: List<AmpTrack>) { library.importPlaylist(playlist, tracks); playlists = library.playlists() }
    /** Merge liked songs received from a phone (AURA Connect) through the live library, so the player's own copy isn't stale. */
    fun importFavourites(tracks: List<AmpTrack>) { library.importFavourites(tracks); favourites = library.favourites() }
    fun playlistTracks(id: String) = library.playlistTracks(id)
    fun favouriteTracks() = library.favouriteTracks()
    fun close() { if (!closed) { closed = true; ++generation; scope.cancel(); player.release(); playing = false; loading = false } }
}

/** No credential headers, no automatic redirects, and address validation for every HLS/audio request. */
private class AmpAudioRequestException(val failure: AmpProviderFailure) : java.io.IOException(failure.safeMessage)
private class AmpHttpsDataSource(private val credential: () -> String?) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    override fun getUri(): Uri? = uri
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        var target = dataSpec.uri.toString()
        try {
            for (redirect in 0..4) {
                AmpProviderSafety.validateNetwork(target)
                val secret = credential()
                if (secret != null && !AmpEclipseProtocol.safeStreamUrl(target, secret)) throw AmpAudioRequestException(AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.UNSAFE_URL))
                val c = URL(target).openConnection() as HttpURLConnection
                connection = c; c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Accept-Encoding", "identity")
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
                    c.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 4)
                    val next = URI(target).resolve(c.getHeaderField("Location") ?: error("Missing redirect")).toString()
                    // Cross-host redirects (CDNs) are followed only to a public https address that does not carry the credential off Eclipse's
                    // host; the next loop turn validates DNS for it again. No credential header is ever sent by this transport.
                    if (!AmpEclipseProtocol.redirectAllowed(next, secret)) throw AmpAudioRequestException(AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.UNSAFE_URL))
                    c.disconnect(); connection = null; target = next; continue
                }
                if (code != 200 && code != 206) throw AmpAudioRequestException(AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.HTTP, code))
                if (code == 206 && !AmpAudioRange.validStart(c.getHeaderField("Content-Range"), dataSpec.position)) throw java.io.IOException("Invalid audio range")
                input = c.inputStream
                if (code == 200 && dataSpec.position > 0) {
                    var skip = dataSpec.position
                    while (skip > 0) { val n = input!!.skip(skip); if (n <= 0) { if (input!!.read() < 0) error("Truncated response"); skip-- } else skip -= n }
                }
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else c.getHeaderFieldLong("Content-Length", -1).let { if (it < 0) C.LENGTH_UNSET.toLong() else (it - if (code == 200) dataSpec.position else 0).coerceAtLeast(0) }
                uri = Uri.parse(target); opened = true; transferStarted(dataSpec); return remaining
            }
            error("Redirect limit")
        } catch (e: Exception) { runCatching { close() }; if (e is AmpAudioRequestException) throw e; throw AmpAudioRequestException(AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.NETWORK)) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val size = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val count = try { input?.read(buffer, offset, size) ?: return C.RESULT_END_OF_INPUT } catch (_: Exception) { throw AmpAudioRequestException(AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.NETWORK)) }
        if (count < 0) { if (remaining > 0) throw java.io.IOException("Truncated audio response"); return C.RESULT_END_OF_INPUT }
        if (remaining >= 0) remaining -= count
        bytesTransferred(count); return count
    }
    override fun close() { try { input?.close() } catch (_: Exception) { throw AmpAudioRequestException(AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.NETWORK)) } finally { input = null; connection?.disconnect(); connection = null; uri = null; if (opened) { opened = false; transferEnded() } } }
}

internal object AmpAudioRange {
    fun validStart(header: String?, expected: Long): Boolean {
        val groups = header?.let { Regex("bytes ([0-9]+)-([0-9]+)/(?:[0-9]+|\\*)", RegexOption.IGNORE_CASE).matchEntire(it.trim())?.groupValues } ?: return false
        val start = groups[1].toLongOrNull() ?: return false
        val end = groups[2].toLongOrNull() ?: return false
        return start == expected && end >= start
    }
}
