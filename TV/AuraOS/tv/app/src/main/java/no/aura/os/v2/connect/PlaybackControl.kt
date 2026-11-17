package no.aura.os.v2.connect


/**
 * Remote control of the TV's AuraAmp player from a paired phone. The operations (`playback.*`, `queue.*`) are granted by an ordinary pair
 * (PLAYBACK_CONTROL / QUEUE_EDIT in [Permission.DEFAULT_CONTROLLER]) — no extra consent: controlling your own music on
 * your own TV. The core already validates the numeric arguments (seek position, queue indices) before a command reaches here.
 *
 * [PlaybackTarget] is the seam the shell backs with the live [no.aura.os.v2.amp.AmpPlayback] (every call marshalled to the main thread,
 * since the player and its Compose state live there). Keeping it an interface lets the handler be unit-tested over loopback with a fake.
 * Every command answers with a fresh [PlaybackSnapshot] so the phone's transport controls reflect the real state without a second poll.
 */
data class PlaybackSnapshot(
    val playing: Boolean, val loading: Boolean, val shuffle: Boolean, val repeat: Boolean,
    val positionMs: Long, val durationMs: Long,
    val selectedId: String?, val selectedTitle: String?, val selectedArtist: String?, val queueLength: Int,
)

interface PlaybackTarget {
    fun resume(); fun pause(); fun next(); fun previous()
    /** [on] null toggles; non-null sets the exact state (so a phone's switch and the TV never drift apart). */
    fun setShuffle(on: Boolean?); fun setRepeat(on: Boolean?)
    fun seek(positionMs: Long)
    fun enqueue(track: SyncTrack)
    fun removeFromQueue(index: Int)
    fun moveInQueue(from: Int, to: Int)
    fun clearQueue()
    fun snapshot(): PlaybackSnapshot
}

/**
 * Handles every `playback.*` / `queue.*` operation against [target] (read fresh per call; null when AuraAmp is not running for the active
 * profile, which answers "no player" rather than a silent success). Returns the post-command snapshot.
 */
class PlaybackCommandHandler(private val target: () -> PlaybackTarget?) : CommandHandler {
    companion object {
        val OPS = setOf(
            "playback.status", "playback.play", "playback.pause", "playback.next", "playback.previous",
            "playback.seek", "playback.shuffle", "playback.repeat",
            "queue.add", "queue.remove", "queue.move", "queue.clear",
        )
        fun snapshotMap(s: PlaybackSnapshot): Map<String, Any?> = linkedMapOf(
            "playing" to s.playing, "loading" to s.loading, "shuffle" to s.shuffle, "repeat" to s.repeat,
            "positionMs" to s.positionMs, "durationMs" to s.durationMs,
            "trackId" to s.selectedId, "title" to s.selectedTitle, "artist" to s.selectedArtist, "queueLength" to s.queueLength.toLong(),
        )
        /** The track for `queue.add`: the phone sends full metadata under `track` (preferred); `trackId` alone still enqueues (title fills in on play). */
        private fun enqueueTrack(args: Map<String, Any?>): SyncTrack? {
            (args["track"] as? Map<*, *>)?.let { m ->
                val id = m["id"] as? String ?: (args["trackId"] as? String) ?: return null
                return SyncTrack(id, m["title"] as? String ?: "", m["artist"] as? String ?: "",
                    (m["durationMs"] as? Number)?.toLong() ?: 0L, isrc = m["isrc"] as? String, cover = m["cover"] as? String)
            }
            val id = args["trackId"] as? String ?: return null
            return SyncTrack(id, "", "", 0L)
        }
    }

    override fun handle(context: CommandContext): Map<String, Any?> {
        if (context.op !in OPS) throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        val t = target() ?: throw ProtocolException(ErrorCode.HANDLER_FAILED, "No player is running for this profile")
        when (context.op) {
            "playback.status" -> {}
            "playback.play" -> t.resume()
            "playback.pause" -> t.pause()
            "playback.next" -> t.next()
            "playback.previous" -> t.previous()
            "playback.seek" -> t.seek(context.args["positionMs"] as? Long ?: 0L)
            "playback.shuffle" -> t.setShuffle(context.args["on"] as? Boolean)
            "playback.repeat" -> t.setRepeat(context.args["on"] as? Boolean)
            "queue.add" -> t.enqueue(enqueueTrack(context.args) ?: throw ProtocolException(ErrorCode.BAD_REQUEST, "trackId required"))
            "queue.remove" -> t.removeFromQueue((context.args["index"] as? Long ?: 0L).toInt())
            "queue.move" -> t.moveInQueue((context.args["from"] as? Long ?: 0L).toInt(), (context.args["to"] as? Long ?: 0L).toInt())
            "queue.clear" -> t.clearQueue()
            else -> throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        }
        return snapshotMap(t.snapshot())
    }
}
