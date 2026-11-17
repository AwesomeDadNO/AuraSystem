package no.aura.os.v2.connect

import no.aura.connect.core.CommandContext
import no.aura.connect.core.CommandHandler
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.ProtocolException
import java.security.MessageDigest

/**
 * Serving the TV's AuraAmp playlists to a paired phone over AuraConnect (the `sync.playlists` operation, which a pair already grants through
 * PLAYLIST_SYNC — no extra consent: this is the person's own library data going to their own phone).
 *
 * The exchange "sees differences" instead of copying blindly: the phone sends what it already has (`have`: id + content hash), the TV returns
 * the full manifest (so the phone can drop anything the TV no longer has) and only the bodies that are new or changed, bounded per call so a
 * large library pages. No track stream URL or credential is ever part of a playlist body. Child profiles expose nothing (the source returns
 * an empty list).
 */

/** A track as it travels (metadata only; never a stream URL or credential). */
data class SyncTrack(val id: String, val title: String, val artist: String, val durationMs: Long, val isrc: String? = null, val cover: String? = null)
/** A playlist as it travels. [hash] is a stable content hash so the phone can tell "changed" from "same" without comparing bodies. */
data class SyncPlaylist(val id: String, val title: String, val description: String, val tracks: List<SyncTrack>) {
    val trackCount: Int get() = tracks.size
    val hash: String by lazy { PlaylistHash.of(this) }
}

/** The profile's playlists for AuraConnect. Backed by AuraAmp's library in the app; empty for a child profile or when AuraAmp is off. */
interface PlaylistSource { fun playlists(): List<SyncPlaylist> }

object PlaylistHash {
    /** SHA-256 over id, title, description and each track's id + isrc + title, so any meaningful change flips the hash; order matters. */
    fun of(p: SyncPlaylist): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(s: String?) { val b = (s ?: "").toByteArray(Charsets.UTF_8); md.update((b.size and 0xff).toByte()); md.update((b.size shr 8).toByte()); md.update(b) }
        feed(p.id); feed(p.title); feed(p.description)
        for (t in p.tracks) { feed(t.id); feed(t.isrc); feed(t.title) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Pure reconciliation used by the server to decide what to send, and reusable by the phone to decide what to drop. Unit-tested. */
object PlaylistReconcile {
    /** Ids the phone must (re)fetch: on the server and either absent from, or changed since, what the phone reported in [have]. */
    fun toFetch(have: Map<String, String>, serverManifest: List<SyncPlaylist>): List<String> =
        serverManifest.filter { have[it.id] != it.hash }.map { it.id }
    /** Ids the phone should delete: it has them, the server does not. */
    fun toRemove(haveIds: Set<String>, serverIds: Set<String>): List<String> = (haveIds - serverIds).toList()
}

/** Where pushed playlists land: the active adult profile's library in the app. A child/blank profile cannot receive ([canReceive] false). */
interface PlaylistSink {
    fun canReceive(): Boolean
    /** Import or update one playlist (newest-wins by id). Throws [IllegalArgumentException] to reject one item (e.g. a library size limit). */
    fun importOne(playlist: SyncPlaylist)
}

object PlaylistSync {
    const val OP = "sync.playlists"
    const val PUSH_OP = "playlists.push"
    const val MAX_BODIES = 25
    const val MAX_TRACKS = 2000
    const val MAX_HAVE = 500

    private fun trackMap(t: SyncTrack) = linkedMapOf<String, Any?>("id" to t.id, "title" to t.title, "artist" to t.artist, "durationMs" to t.durationMs, "isrc" to t.isrc, "cover" to t.cover)
    fun bodyMap(p: SyncPlaylist): Map<String, Any?> = linkedMapOf(
        "id" to p.id, "title" to p.title, "description" to p.description, "hash" to p.hash,
        "tracks" to p.tracks.take(MAX_TRACKS).map(::trackMap),
    )
    private fun metaMap(p: SyncPlaylist) = linkedMapOf<String, Any?>("id" to p.id, "hash" to p.hash, "title" to p.title, "trackCount" to p.trackCount.toLong())

    /**
     * The reply to one `sync.playlists` call. [have] is the phone's `{id -> hash}`. Returns the full manifest (authoritative list), the
     * bodies the phone is missing or has stale (up to [maxBodies]), and `more` when the phone should call again to get the rest.
     */
    fun reply(source: PlaylistSource, have: Map<String, String>, maxBodies: Int = MAX_BODIES): Map<String, Any?> {
        val playlists = source.playlists()
        val fetchIds = PlaylistReconcile.toFetch(have, playlists)
        val toSend = playlists.filter { it.id in fetchIds }
        return linkedMapOf(
            "manifest" to playlists.map(::metaMap),
            "bodies" to toSend.take(maxBodies).map(::bodyMap),
            "more" to (toSend.size > maxBodies),
        )
    }

    /** Parses the phone's `have` argument (a list of {id, hash}) defensively. */
    fun parseHave(arg: Any?): Map<String, String> {
        val list = arg as? List<*> ?: return emptyMap()
        return list.asSequence().mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val id = m["id"] as? String ?: return@mapNotNull null
            val hash = m["hash"] as? String ?: return@mapNotNull null
            id to hash
        }.take(MAX_HAVE).toMap()
    }

    private fun parseTrack(m: Map<*, *>): SyncTrack? {
        val id = m["id"] as? String ?: return null
        return SyncTrack(id, m["title"] as? String ?: "", m["artist"] as? String ?: "",
            (m["durationMs"] as? Number)?.toLong() ?: 0L, isrc = m["isrc"] as? String, cover = m["cover"] as? String)
    }

    /** Parses the phone's pushed `playlists` argument (the same body shape [bodyMap] produces) defensively, bounded per call. */
    fun parsePush(arg: Any?): List<SyncPlaylist> {
        val list = arg as? List<*> ?: return emptyList()
        return list.asSequence().mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val id = m["id"] as? String ?: return@mapNotNull null
            val tracks = (m["tracks"] as? List<*>)?.asSequence()?.mapNotNull { t -> (t as? Map<*, *>)?.let(::parseTrack) }?.take(MAX_TRACKS)?.toList() ?: emptyList()
            SyncPlaylist(id, m["title"] as? String ?: "", m["description"] as? String ?: "", tracks)
        }.take(MAX_BODIES).toList()
    }
}

/** The command handler for `sync.playlists`. [source] is read fresh per call (the active profile's library). */
class PlaylistSyncHandler(private val source: () -> PlaylistSource) : CommandHandler {
    override fun handle(context: CommandContext): Map<String, Any?> {
        if (context.op != PlaylistSync.OP) throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        return PlaylistSync.reply(source(), PlaylistSync.parseHave(context.args["have"]))
    }
}

/**
 * The command handler for `playlists.push` (phone → TV): the phone sends playlist bodies and the TV imports/updates them in the active
 * profile's library. Granted by an ordinary pair (PLAYLIST_SYNC) — it is the person's own library data going to their own TV, no secret.
 * A child/blank profile refuses the whole call; a single over-limit playlist is skipped (reported in `skipped`) without failing the rest.
 */
class PlaylistPushHandler(private val sink: () -> PlaylistSink) : CommandHandler {
    override fun handle(context: CommandContext): Map<String, Any?> {
        if (context.op != PlaylistSync.PUSH_OP) throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        val target = sink()
        if (!target.canReceive()) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "This profile cannot receive playlists")
        var imported = 0
        val skipped = ArrayList<Map<String, Any?>>()
        val bodies = context.args["playlists"] as? List<*> ?: throw ProtocolException(ErrorCode.BAD_REQUEST, "Playlists must be a list")
        if (bodies.size > PlaylistSync.MAX_BODIES) throw ProtocolException(ErrorCode.BAD_REQUEST, "Send at most 25 playlists per call")
        for (raw in bodies) {
            val body = raw as? Map<*, *> ?: continue
            val id = body["id"] as? String ?: continue
            val rows = body["tracks"] as? List<*>
            if (rows == null || rows.size > PlaylistSync.MAX_TRACKS) {
                skipped.add(linkedMapOf("id" to id, "reason" to "Invalid or over-limit playlist tracks"))
                continue
            }
            val p = PlaylistSync.parsePush(listOf(body)).singleOrNull() ?: continue
            if (p.tracks.size != rows.size) {
                skipped.add(linkedMapOf("id" to id, "reason" to "Invalid track metadata"))
                continue
            }
            try { target.importOne(p); imported++ }
            catch (e: IllegalArgumentException) { skipped.add(linkedMapOf("id" to p.id, "reason" to (e.message ?: "rejected"))) }
        }
        return linkedMapOf("imported" to imported, "skipped" to skipped)
    }
}

/** A trusted peer's profile comes from its trust record, not from the profile currently displayed on the TV. */
class ProfileBoundPlaylistHandler(private val owner:String,private val stillAllowed:()->Boolean,private val delegate:CommandHandler):CommandHandler {
    override fun handle(context:CommandContext):Map<String,Any?> {
        if(owner.isBlank() || context.profileId!=owner || !stillAllowed())
            throw ProtocolException(ErrorCode.NOT_AUTHORIZED,"This profile cannot access playlists")
        return delegate.handle(context)
    }
}
