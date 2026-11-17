package no.aura.os.v2.connect

import no.aura.cortex.AuraConnectCommandException
import no.aura.cortex.AuraPlaybackCommandHandler
import no.aura.cortex.AuraPlaybackTarget
import no.aura.cortex.AuraTrackReference
import no.aura.cortex.CallError

/** Compatibility data shape used by the TV's Android player adapter. */
data class PlaybackSnapshot(
    val playing: Boolean, val loading: Boolean, val shuffle: Boolean, val repeat: Boolean,
    val positionMs: Long, val durationMs: Long,
    val selectedId: String?, val selectedTitle: String?, val selectedArtist: String?, val queueLength: Int, val error: String? = null,
)

/** Android-side adapter seam; Coretex owns command parsing and dispatch. */
interface PlaybackTarget {
    fun resume(); fun pause(); fun next(); fun previous()
    fun setShuffle(on: Boolean?); fun setRepeat(on: Boolean?)
    fun seek(positionMs: Long)
    fun enqueue(track: SyncTrack)
    fun enqueueNext(track: SyncTrack) { enqueue(track) }
    fun removeFromQueue(index: Int)
    fun moveInQueue(from: Int, to: Int)
    fun clearQueue()
    fun handoff(track: SyncTrack, upcoming: List<SyncTrack>) {
        throw ProtocolException(ErrorCode.HANDLER_FAILED, "AuraPlay is not available for this player")
    }
    fun snapshot(): PlaybackSnapshot
}

/** Thin Android adapter around the shared command dispatcher in AuraCortex. */
class PlaybackCommandHandler(private val target: () -> PlaybackTarget?) : CommandHandler {
    private val core = AuraPlaybackCommandHandler {
        target()?.let { tv ->
            object : AuraPlaybackTarget {
                override fun resume() = tv.resume()
                override fun pause() = tv.pause()
                override fun next() = tv.next()
                override fun previous() = tv.previous()
                override fun seek(positionMs: Long) = tv.seek(positionMs)
                override fun setShuffle(on: Boolean?) = tv.setShuffle(on)
                override fun setRepeat(on: Boolean?) = tv.setRepeat(on)
                override fun add(track: AuraTrackReference, position: String) {
                    val item = track.toSyncTrack()
                    if (position == "next") tv.enqueueNext(item) else tv.enqueue(item)
                }
                override fun removeFromQueue(index: Int) = tv.removeFromQueue(index)
                override fun moveInQueue(from: Int, to: Int) = tv.moveInQueue(from, to)
                override fun clearQueue() = tv.clearQueue()
                override fun handoff(track: AuraTrackReference, upcoming: List<AuraTrackReference>) =
                    tv.handoff(track.toSyncTrack(), upcoming.map(AuraTrackReference::toSyncTrack))
                override fun snapshot(): Map<String, Any?> = snapshotMap(tv.snapshot())
            }
        }
    }

    companion object {
        val OPS = AuraPlaybackCommandHandler.PLAYBACK_OPERATIONS
        fun snapshotMap(s: PlaybackSnapshot): Map<String, Any?> = linkedMapOf(
            "playing" to s.playing, "loading" to s.loading, "shuffle" to s.shuffle, "repeat" to s.repeat,
            "positionMs" to s.positionMs, "durationMs" to s.durationMs,
            "trackId" to s.selectedId, "title" to s.selectedTitle, "artist" to s.selectedArtist, "queueLength" to s.queueLength.toLong(), "error" to s.error,
        )
    }

    override fun handle(context: CommandContext): Map<String, Any?> = try {
        core.execute(context)
    } catch (e: AuraConnectCommandException) {
        val code = when (e.callError) {
            CallError.UNKNOWN_OPERATION -> ErrorCode.UNKNOWN_OPERATION
            CallError.NOT_ALLOWED -> ErrorCode.NOT_AUTHORIZED
            CallError.BAD_REQUEST -> ErrorCode.BAD_REQUEST
            else -> ErrorCode.HANDLER_FAILED
        }
        throw ProtocolException(code, e.message ?: "The TV could not complete this")
    }
}

private fun AuraTrackReference.toSyncTrack() = SyncTrack(id, title, artist, durationMs, cover = cover, isrc = isrc)
