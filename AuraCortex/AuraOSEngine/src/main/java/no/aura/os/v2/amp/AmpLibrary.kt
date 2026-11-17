package no.aura.os.v2.amp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local metadata only; credentials and stream URLs never enter this store. */
class AmpLibrary(context: Context, profile: String) {
    private val prefs = context.applicationContext.getSharedPreferences("amp_library_" + ampProfileKey(profile), Context.MODE_PRIVATE)
    private var root = runCatching { JSONObject(prefs.getString("state", "{}")!!) }.getOrElse { JSONObject() }
    private fun encode(t: AmpTrack) = JSONObject().put("id", t.id.take(200)).put("title", t.title.take(300)).put("artist", t.artist.take(300)).put("duration", t.duration.coerceIn(0, 86400)).apply {
        t.cover?.takeIf(AmpProviderSafety::publicHttps)?.let { put("cover", it.take(2048)) }
        put("albumId", t.albumId); put("artistId", t.artistId); put("releaseDate", t.releaseDate); put("isrc", AmpEclipseProtocol.normalizedIsrc(t.isrc))
    }
    private fun decode(o: JSONObject) = AmpTrack(o.optString("id"), o.optString("title"), o.optString("artist"), o.optInt("duration"), o.optString("cover").takeIf(AmpProviderSafety::publicHttps), o.optString("albumId").takeIf(String::isNotBlank), o.optString("artistId").takeIf(String::isNotBlank), o.optString("releaseDate").takeIf(String::isNotBlank), AmpEclipseProtocol.normalizedIsrc(o.optString("isrc")))
    private fun tracks(array: JSONArray?, limit:Int=200) = (0 until (array?.length() ?: 0).coerceAtMost(limit)).mapNotNull { array?.optJSONObject(it)?.let(::decode) }
    private fun save() { prefs.edit().putString("state", root.toString()).apply() }
    fun queue() = tracks(root.optJSONArray("queue"))
    fun recent() = tracks(root.optJSONArray("recent"))
    fun favourites(): Set<String> = tracks(root.optJSONArray("favourites")).map { it.id }.toSet()
    fun favouriteTracks() = tracks(root.optJSONArray("favourites"))
    fun setQueue(value: List<AmpTrack>) { root.put("queue", JSONArray(value.take(200).map(::encode))); save() }
    fun played(track: AmpTrack) { root.put("recent", JSONArray((listOf(track) + recent().filterNot { it.id == track.id }).take(100).map(::encode))); save() }
    fun toggleFavourite(track: AmpTrack) {
        val old = favouriteTracks()
        root.put("favourites", JSONArray((if (old.any { it.id == track.id }) old.filterNot { it.id == track.id } else old + track).take(200).map(::encode))); save()
    }
    fun playlistTracks(id: String) = tracks(root.optJSONObject("playlists")?.optJSONObject(id)?.optJSONArray("tracks"),2000)
    fun playlists(): List<AmpPlaylist> {
        val lists = root.optJSONObject("playlists") ?: return emptyList()
        return lists.keys().asSequence().take(50).map { id -> val o = lists.getJSONObject(id); AmpPlaylist(id = id, title = o.optString("title"), picture = o.optString("picture").takeIf(AmpProviderSafety::publicHttps), description = o.optString("description"), trackCount = playlistTracks(id).size, creationDate = o.optString("creationDate").takeIf(String::isNotBlank)) }.toList()
    }
    fun importPlaylist(playlist: AmpPlaylist, tracks: List<AmpTrack>) {
        val lists = root.optJSONObject("playlists") ?: JSONObject()
        require(lists.has(playlist.id) || lists.length() < 50) { "The local library supports 50 imported playlists." }
        require(tracks.size<=2000) { "Playlist exceeds the 2,000-song import limit." }
        val otherTracks=lists.keys().asSequence().filter{it!=playlist.id}.sumOf{lists.optJSONObject(it)?.optJSONArray("tracks")?.length() ?: 0}
        require(otherTracks+tracks.size<=5000) { "The local library supports 5,000 imported songs." }
        lists.put(playlist.id, JSONObject().put("title", playlist.title.take(300)).put("description", playlist.description.take(2000)).put("creationDate", playlist.creationDate?.take(100)).put("picture", playlist.picture?.takeIf(AmpProviderSafety::publicHttps)).put("tracks", JSONArray(tracks.map(::encode))))
        root.put("playlists", lists); save()
    }
    fun importFavourites(incoming: List<AmpTrack>) {
        val merged = (incoming.filter { it.id.isNotBlank() } + favouriteTracks()).distinctBy { it.id }.take(200)
        root.put("favourites", JSONArray(merged.map(::encode))); save()
    }
    fun clear() { root = JSONObject(); prefs.edit().clear().commit() }
}

internal object AmpQueueRules {
    fun advance(queue: List<AmpTrack>, current: AmpTrack?, repeat: Boolean, index: Int): Pair<AmpTrack, List<AmpTrack>>? {
        if (queue.isEmpty()) return if (repeat && current != null) current to emptyList() else null
        val chosen = index.coerceIn(queue.indices)
        val remaining = queue.filterIndexed { i, _ -> i != chosen }
        return queue[chosen] to (if (repeat && current != null) (remaining + current).take(200) else remaining)
    }
    fun afterSelection(track: AmpTrack, tracks: List<AmpTrack>): List<AmpTrack> {
        val index = tracks.indexOfFirst { it.id == track.id }
        return (if (index >= 0) tracks.drop(index + 1) else tracks).take(200)
    }
}
