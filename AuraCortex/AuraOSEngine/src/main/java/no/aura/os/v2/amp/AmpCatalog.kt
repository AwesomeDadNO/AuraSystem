package no.aura.os.v2.amp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

// Public catalog metadata only. Playback and account credentials belong to Eclipse.
data class AmpTrack(val id: String, val title: String, val artist: String, val duration: Int, val cover: String? = null, val albumId: String? = null, val artistId: String? = null, val releaseDate: String? = null, val isrc:String?=null)
data class AmpAlbum(val id: String, val title: String, val artist: String, val cover: String? = null, val releaseDate: String? = null, val artistId: String? = null, val heroImage:String?=null)
data class AmpArtist(val id: String, val name: String, val picture: String? = null)
data class AmpPlaylist(val id: String, val title: String, val picture: String? = null, val description: String = "", val trackCount: Int = 0, val creationDate: String? = null)
data class AmpHome(val hero: List<AmpAlbum>, val releases: List<AmpAlbum>, val artists: List<AmpArtist>, val albums: List<AmpAlbum>, val tracks: List<AmpTrack>, val playlists: List<AmpPlaylist>, val releaseLabel: String, val errors: List<String> = emptyList())

object AmpSort {
    internal fun date(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    private fun <T> ordered(items: List<T>, newestFirst: Boolean, dateOf: (T) -> String?): List<T> = items.sortedWith { a, b ->
        val x = date(dateOf(a)); val y = date(dateOf(b))
        when { x == null && y == null -> 0; x == null -> 1; y == null -> -1; newestFirst -> y.compareTo(x); else -> x.compareTo(y) }
    }
    fun albums(items: List<AmpAlbum>, newestFirst: Boolean) = ordered(items, newestFirst) { it.releaseDate }
    fun playlists(items: List<AmpPlaylist>, newestFirst: Boolean) = ordered(items, newestFirst) { it.creationDate }
}

class AmpCatalog {
    suspend fun home(onInitial: suspend (AmpHome) -> Unit = {}): AmpHome = withContext(Dispatchers.IO) {
        supervisorScope {
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            fun section(label: String, path: String): JSONObject? = try { get(path) } catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "$label is temporarily unavailable"; null }
            val selectionRequest = async { section("Editorial selection", "/editorial/0/selection?limit=25") }
            val chartRequest = async { section("Popular music", "/chart/0?limit=25") }
            val releaseRequest = async { section("New releases", "/editorial/0/releases?limit=25") }
            val editorial = AmpJson.albums(selectionRequest.await()?.optJSONArray("data")).take(25)
            val chart = chartRequest.await()
            val chartAlbums = AmpJson.albums(chart?.optJSONObject("albums")?.optJSONArray("data")).take(25)
            val artists = AmpJson.artists(chart?.optJSONObject("artists")?.optJSONArray("data"))
            val tracks = AmpJson.tracks(chart?.optJSONObject("tracks")?.optJSONArray("data"))
            val playlists = AmpJson.playlists(chart?.optJSONObject("playlists")?.optJSONArray("data"))
            // Publish the useful primary shelves before waiting for release-date detail lookups.
            onInitial(AmpHome(editorial.take(5), emptyList(), artists, chartAlbums, tracks, playlists,
                "Recent releases", errors.toList().distinct().sorted()))
            val releaseFeed = AmpJson.albums(releaseRequest.await()?.optJSONArray("data")).take(25)
            val fallback = releaseFeed.isEmpty()
            // One detail lookup per unique candidate, shared by the chart and releases. At most
            // four simultaneous requests and 50 details when the releases endpoint is empty.
            val candidates = (chartAlbums + if (fallback) editorial else releaseFeed).distinctBy { it.id }.take(50)
            val semaphore = Semaphore(4)
            val details = candidates.map { candidate -> async {
                semaphore.withPermit {
                    candidate.id to (section("Album release details", "/album/${candidate.id}")?.let(AmpJson::album) ?: candidate)
                }
            } }.awaitAll().toMap()
            val albums = chartAlbums.map { details[it.id] ?: it }
            val heroAlbums=editorial.take(5).map { album -> async {
                semaphore.withPermit {
                    val artist=album.artistId?.let { section("Hero artist", "/artist/$it") }
                    val portrait=artist?.let { AmpJson.artists(JSONArray().put(it)).firstOrNull()?.picture }
                    album.copy(heroImage=portrait)
                }
            }}.awaitAll()
            val today = LocalDate.now(java.time.ZoneOffset.UTC)
            val releases = (if (fallback) (editorial + chartAlbums).distinctBy { it.id } else releaseFeed)
                .map { details[it.id] ?: it }
                .filter { album -> AmpSort.date(album.releaseDate)?.let { !it.isAfter(today) } ?: !fallback }
            val releaseLabel = when {
                fallback -> "Recent editorial & chart albums"
                releases.any { AmpSort.date(it.releaseDate) == null } -> "Release selections (some dates unavailable)"
                else -> "New releases"
            }
            AmpHome(heroAlbums, AmpSort.albums(releases, true).take(25), artists, albums,
                tracks, playlists, releaseLabel, errors.toList().distinct().sorted())
        }
    }
    suspend fun album(id: String): Pair<AmpAlbum, List<AmpTrack>> = io {
        val json = get("/album/${id(id)}")
        val album = AmpJson.album(json) ?: unavailable()
        val tracks=mutableListOf<AmpTrack>()
        var index=0
        while(true) {
            require(index in 0 until 2000)
            val page=get("/album/${album.id}/tracks?limit=100&index=$index")
            val rows=page.optJSONArray("data") ?: unavailable()
            require(rows.length()<=100 && index+rows.length()<=2000)
            val decoded=AmpJson.tracks(rows)
            require(decoded.size==rows.length())
            tracks+=decoded
            require(tracks.size<=2000)
            val more=page.optString("next").let{it.isNotBlank() && it!="null"}
            if(!more)break
            require(rows.length()>0 && index+rows.length()<2000)
            index+=rows.length()
        }
        album to tracks.map { it.copy(cover = it.cover ?: album.cover, albumId = album.id, releaseDate = it.releaseDate ?: album.releaseDate) }
    }
    suspend fun artistAlbums(id: String): List<AmpAlbum> = io { AmpJson.albums(get("/artist/${id(id)}/albums?limit=100").optJSONArray("data")) }
    suspend fun artistTracks(id: String): List<AmpTrack> = io { AmpJson.tracks(get("/artist/${id(id)}/top?limit=100").optJSONArray("data")) }
    suspend fun playlist(id: String): Pair<AmpPlaylist, List<AmpTrack>> = io {
        val safeId = id(id)
        val json = get("/playlist/$safeId")
        val playlist=AmpJson.playlist(json) ?: unavailable()
        val tracks=mutableListOf<AmpTrack>()
        var index=0
        while(true) {
            val page=get("/playlist/$safeId/tracks?limit=100&index=$index")
            val rows=page.optJSONArray("data") ?: unavailable()
            for(i in 0 until rows.length()) rows.optJSONObject(i)?.let(AmpJson::track)?.let(tracks::add)
            if(tracks.size>2000 || rows.length()>100) throw IllegalStateException("Playlist exceeds the current import limit.")
            val more=page.optString("next").let{it.isNotBlank()&&it!="null"}
            if(!more)break
            if(rows.length()==0 || tracks.size>=2000) throw IllegalStateException("Playlist exceeds the current 2,000-song import limit.")
            index+=rows.length()
        }
        playlist to tracks
    }
    suspend fun search(query: String): List<AmpTrack> = io {
        if (query.isBlank()) emptyList() else AmpJson.tracks(get("/search?q=${URLEncoder.encode(query.take(200), "UTF-8")}&limit=100").optJSONArray("data"))
    }
    suspend fun playlistInfo(playlistId:String):AmpPlaylist = io { AmpJson.playlist(get("/playlist/${id(playlistId)}")) ?: unavailable() }
    suspend fun userPlaylists(userId: String): List<AmpPlaylist> = io { AmpJson.playlists(get("/user/${id(userId)}/playlists?limit=100").optJSONArray("data")) }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { unavailable() }
    }
    private fun id(value: String): String = value.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: unavailable()
    private fun unavailable(): Nothing = throw IllegalStateException("Deezer catalog is temporarily unavailable. Please try again.")
    private fun get(path: String): JSONObject {
        val url = URL("https://api.deezer.com$path")
        check(url.protocol == "https" && url.host == "api.deezer.com" && url.port == -1 && url.userInfo == null)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false // Refuse redirects, including any host or scheme change.
            connection.connectTimeout = 6000; connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES) unavailable()
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > MAX_BYTES) unavailable(); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8)).also { if (it.has("error")) unavailable() }
        } finally { connection.disconnect() }
    }
    companion object { const val LIST_LIMIT = 100; private const val MAX_BYTES = 2 * 1024 * 1024 }
}

internal object AmpJson {
    private fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun JSONObject.identifier() = text("id")?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
    private fun JSONObject.art(vararg keys: String): String? = keys.firstNotNullOfOrNull { text(it) }?.takeIf { runCatching { URL(it).protocol == "https" }.getOrDefault(false) }
    private fun <T> list(array: JSONArray?, parse: (JSONObject) -> T?): List<T> = (0 until minOf(array?.length() ?: 0, AmpCatalog.LIST_LIMIT)).mapNotNull { array?.optJSONObject(it)?.let(parse) }
    fun album(j: JSONObject): AmpAlbum? = j.identifier()?.let { id -> j.text("title")?.let { AmpAlbum(id, it, j.optJSONObject("artist")?.text("name").orEmpty(), j.art("cover_xl", "cover_big", "cover_medium", "cover"), j.text("release_date"), j.optJSONObject("artist")?.identifier()) } }
    fun track(j: JSONObject): AmpTrack? = j.identifier()?.let { id -> j.text("title")?.let { AmpTrack(id, it, j.optJSONObject("artist")?.text("name").orEmpty(), j.optInt("duration").coerceAtLeast(0), j.optJSONObject("album")?.art("cover_xl", "cover_big", "cover_medium", "cover"), j.optJSONObject("album")?.identifier(), j.optJSONObject("artist")?.identifier(), j.text("release_date"),j.text("isrc")) } }
    fun playlist(j: JSONObject): AmpPlaylist? = j.identifier()?.let { id -> j.text("title")?.let { AmpPlaylist(id, it, j.art("picture_xl", "picture_big", "picture_medium", "picture"), j.text("description").orEmpty(), j.optInt("nb_tracks").coerceAtLeast(0), j.text("creation_date")) } }
    fun albums(a: JSONArray?) = list(a, ::album).distinctBy { it.id }
    fun tracks(a: JSONArray?) = list(a, ::track).distinctBy { it.id }
    fun playlists(a: JSONArray?) = list(a, ::playlist).distinctBy { it.id }
    fun artists(a: JSONArray?) = list(a) { j -> j.identifier()?.let { id -> j.text("name")?.let { AmpArtist(id, it, j.art("picture_xl", "picture_big", "picture_medium", "picture")) } } }.distinctBy { it.id }
}
