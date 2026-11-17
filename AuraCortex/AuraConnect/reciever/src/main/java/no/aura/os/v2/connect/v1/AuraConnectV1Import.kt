package no.aura.os.v2.connect.v1

import no.aura.os.v2.amp.AmpPlaylist
import no.aura.os.v2.amp.AmpTrack
import org.json.JSONObject

/**
 * Turns the sanitized AURA Connect v1 snapshot that [AuraConnectHub] hands to its MetadataSink (protocol 2, built by
 * AuraSyncJournal.sanitizePlaylistPush / sanitizeFavoritesPush / sanitizeApprovedIncoming) into AuraOS AmpLibrary items.
 *
 * The snapshot is already bounded and stripped of secrets by the hub; this only maps it. Playlists pushed from a phone are
 * stored under a "phone:" id prefix so they never collide with Deezer playlist ids in the library.
 */
object AuraConnectV1Import {
    data class Result(val playlists: List<Pair<AmpPlaylist, List<AmpTrack>>>, val favourites: List<AmpTrack>)

    const val PLAYLIST_PREFIX = "phone:"

    fun parse(json: String, profile: String): Result {
        val root = JSONObject(json)
        require(root.optInt("protocol") == 2) { "Unsupported metadata version" }
        val me = root.optJSONObject("profiles")?.optJSONObject(profile) ?: throw IllegalArgumentException("Snapshot is for another profile")
        val known = HashMap<String, AmpTrack>()
        root.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val id = t.optString("id")
                if (id.isBlank()) continue
                known[id] = AmpTrack(id = id, title = t.optString("title").ifBlank { "Unknown song" }, artist = t.optString("artist"),
                    duration = 0, cover = t.optString("cover").takeIf { it.startsWith("https://") })
            }
        }
        fun track(id: String) = known[id] ?: AmpTrack(id = id, title = "Unknown song", artist = "", duration = 0)
        val playlists = buildList {
            val arr = me.optJSONArray("playlists") ?: return@buildList
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optString("id")
                if (id.isBlank()) continue
                val ids = p.optJSONArray("tracks")
                val tracks = (0 until (ids?.length() ?: 0)).mapNotNull { ids!!.optString(it).takeIf(String::isNotBlank) }.distinct().map(::track)
                add(AmpPlaylist(id = PLAYLIST_PREFIX + id, title = p.optString("title").ifBlank { "Playlist" }.take(120),
                    description = "From AuraAmp on your phone", trackCount = tracks.size) to tracks)
            }
        }
        val favs = me.optJSONArray("favorites")
        val favourites = (0 until (favs?.length() ?: 0)).mapNotNull { favs!!.optString(it).takeIf(String::isNotBlank) }.distinct().map(::track)
        return Result(playlists, favourites)
    }
}
