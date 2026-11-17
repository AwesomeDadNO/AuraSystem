package no.aura.os.v2.connect

import android.content.Context
import no.aura.os.v2.amp.AmpLibrary
import no.aura.os.v2.amp.AmpPlaylist
import no.aura.os.v2.amp.AmpTrack

/**
 * Exposes the active profile's AuraAmp playlists to AuraConnect. Read-only metadata; AuraAmp's library already keeps no stream URL or
 * credential, and [SyncTrack] carries only title/artist/isrc/cover. A child profile (or AuraAmp off) exposes nothing.
 */
class AmpPlaylistSource(private val context: Context, private val profileId: String, private val isChild: Boolean) : PlaylistSource {
    override fun playlists(): List<SyncPlaylist> {
        if (isChild || profileId.isBlank()) return emptyList()
        val library = AmpLibrary(context.applicationContext, profileId)
        return library.playlists().take(PlaylistSync.MAX_BODIES * 4).map { p ->
            SyncPlaylist(
                id = p.id, title = p.title, description = p.description,
                tracks = library.playlistTracks(p.id).take(PlaylistSync.MAX_TRACKS).map { t ->
                    SyncTrack(t.id, t.title, t.artist, ampPlaylistDurationMs(t.duration), isrc = t.isrc, cover = t.cover)
                },
            )
        }
    }
}

/** AmpLibrary stores seconds; the shared sync contract carries milliseconds. */
internal fun ampPlaylistDurationMs(seconds: Int): Long = seconds.coerceIn(0, 86400).toLong() * 1000L
/**
 * Receives playlists a phone pushes (phone → TV) into the active adult profile's AuraAmp library. Metadata only — duration is seconds,
 * [AmpLibrary.importPlaylist] keeps no stream URL or credential and enforces the 50-playlist / 2,000-per / 5,000-total limits (an over-limit
 * push throws, which [PlaylistPushHandler] reports as skipped). A child or blank profile cannot receive.
 */
class AmpPlaylistSink(private val context: Context, private val profileId: String, private val isChild: Boolean,
    private val onImport: ((AmpPlaylist, List<AmpTrack>) -> Unit)? = null) : PlaylistSink {
    private val library by lazy { AmpLibrary(context.applicationContext, profileId) }
    override fun canReceive(): Boolean = !isChild && profileId.isNotBlank()
    override fun importOne(playlist: SyncPlaylist) {
        val meta = AmpPlaylist(id = playlist.id, title = playlist.title, picture = playlist.tracks.firstOrNull()?.cover, description = playlist.description, trackCount = playlist.tracks.size, creationDate = null)
        val tracks = playlist.tracks.map { t -> AmpTrack(t.id, t.title, t.artist, ampPlaylistDurationSeconds(t.durationMs), t.cover, null, null, null, t.isrc) }
        if (onImport != null) onImport.invoke(meta, tracks) else library.importPlaylist(meta, tracks)
    }
}

internal fun ampPlaylistDurationSeconds(milliseconds: Long): Int = (milliseconds.coerceIn(0L, 86_400_000L) / 1000L).toInt()
