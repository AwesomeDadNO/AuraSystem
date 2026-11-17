package no.aura.os.v2.playback

import android.content.Context
import android.media.MediaDataSource
import android.view.SurfaceHolder
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

enum class PlayerStatus { PREPARING, READY, ENDED, ERROR }

/** Observable player state (Compose state, written only on the main thread). The stream URL is never stored here. */
@Stable
class PlayerModel {
    var status by mutableStateOf(PlayerStatus.PREPARING)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var durationMs by mutableStateOf(0L)
    var audioTracks by mutableStateOf(emptyList<TrackInfo>())
    var subtitleTracks by mutableStateOf(emptyList<TrackInfo>())
    var audioSelected by mutableStateOf<Int?>(null)
    var subtitleSelected by mutableStateOf<Int?>(null)
    var subtitleText by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var trackMessage by mutableStateOf<String?>(null)
}

/** Media3 decoding under Aura's own controls. Confined sources never enter Media3's HTTP stack. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerEngine(context: Context, private val model: PlayerModel, private val prefs: PlaybackPrefs) {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var ownedSource: MediaDataSource? = null
    private var released = false
    private var preferencesApplied = false
    private var tracks = emptyMap<Int, Pair<Tracks.Group, Int>>()

    init {
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*listOfNotNull(prefs.audio.code, prefs.audioSecondary?.code).toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitlesOn)
            .setPreferredTextLanguage(prefs.subtitleLang.code).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                model.buffering = state == Player.STATE_BUFFERING
                model.status = when (state) {
                    Player.STATE_READY -> PlayerStatus.READY
                    Player.STATE_ENDED -> PlayerStatus.ENDED
                    else -> if (model.status == PlayerStatus.READY) PlayerStatus.READY else PlayerStatus.PREPARING
                }
                model.durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                model.playing = player.isPlaying
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { model.playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) {
                model.status = PlayerStatus.ERROR; model.playing = false
                model.error = "This source could not be played (network or format problem)."
            }
            override fun onTracksChanged(value: Tracks) { readTracks(value) }
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                model.subtitleText = cueGroup.cues.mapNotNull { it.text }.joinToString("\n").take(1000)
            }
        })
    }
    fun open(source: String, autoStart: Boolean) {
        runCatching {
            player.setMediaItem(MediaItem.fromUri(source)); player.playWhenReady = autoStart; player.prepare()
        }.onFailure { failOpen() }
    }
    fun open(source: MediaDataSource, autoStart: Boolean) {
        ownedSource = source
        val factory = androidx.media3.datasource.DataSource.Factory { ConfinedPlayerDataSource(source) }
        runCatching {
            player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("aura-confined://media")))
            player.playWhenReady = autoStart; player.prepare()
        }.onFailure { failOpen() }
    }
    private fun failOpen() {
        model.status = PlayerStatus.ERROR; model.playing = false
        model.error = "This source could not be opened."
    }
    fun attach(h: SurfaceHolder) { player.setVideoSurfaceHolder(h) }
    fun detach() { if (!released) player.clearVideoSurface() }

    private fun readTracks(value: Tracks) {
        val refs = linkedMapOf<Int, Pair<Tracks.Group, Int>>()
        val audio = arrayListOf<TrackInfo>(); val subs = arrayListOf<TrackInfo>()
        value.groups.forEach { group ->
            for (i in 0 until group.length) {
                if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
                val id = refs.size
                refs[id] = group to i
                val format = group.getTrackFormat(i)
                val language = format.language?.takeUnless { it.isBlank() || it == "und" }
                val list = if (group.type == C.TRACK_TYPE_AUDIO) audio else subs
                val languageLabel = language?.let { l -> Lang.values().firstOrNull { it.matches(l) }?.label ?: l.uppercase() }
                val label = format.label ?: languageLabel ?: "${if (group.type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"} ${list.size + 1}"
                val renderable = group.type != C.TRACK_TYPE_TEXT || TextTrackPolicy.canRender(format.sampleMimeType)
                list.add(TrackInfo(id, language, label, group.isTrackSupported(i) && renderable))
            }
        }
        tracks = refs
        model.audioTracks = audio; model.subtitleTracks = subs
        model.audioSelected = audio.firstOrNull { refs[it.index]?.let { (g, i) -> g.isTrackSelected(i) } == true }?.index
        model.subtitleSelected = subs.firstOrNull { refs[it.index]?.let { (g, i) -> g.isTrackSelected(i) } == true }?.index
        // Media3 applies preferred audio languages itself. Subtitle policy never guesses another language.
        if (!preferencesApplied && value.groups.isNotEmpty()) {
            preferencesApplied = true
            selectSubtitle(TrackPicker.subtitle(subs, prefs)?.index)
        }
        model.trackMessage = AudioTrackPolicy.message(audio, model.audioSelected)
    }
    fun selectAudio(index: Int) = select(index, C.TRACK_TYPE_AUDIO)
    private fun select(index: Int, type: Int) {
        val (group, track) = tracks[index] ?: return
        val exposed = if (type == C.TRACK_TYPE_AUDIO) model.audioTracks else model.subtitleTracks
        if (group.type != type || exposed.none { it.index == index && it.supported }) {
            model.trackMessage = "This TV cannot decode that track."; return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track)).build()
        // Selection is reported only by onTracksChanged, never optimistically checked in the menu.
    }
    fun selectSubtitle(index: Int?) {
        if (index != null) select(index, C.TRACK_TYPE_TEXT)
        else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            model.subtitleText = ""
        }
    }
    fun positionMs(): Long = if (released) 0 else player.currentPosition.coerceAtLeast(0)
    fun seekTo(ms: Long) { if (!released) player.seekTo(ms.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) = seekTo(PlayerMath.step(positionMs(), model.durationMs, deltaMs))
    fun togglePlay() {
        if (released) return
        if (model.status == PlayerStatus.ENDED) { player.seekTo(0); player.play() }
        else if (player.playWhenReady) player.pause() else player.play()
    }
    fun pause() { if (!released) player.pause() }
    fun release() {
        if (released) return
        released = true
        // Cancel an outstanding confined read before waiting for the playback thread to stop.
        runCatching { ownedSource?.close() }; ownedSource = null
        player.release()
    }
}
