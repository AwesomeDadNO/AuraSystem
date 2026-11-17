package no.aura.os.v2.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.aura.os.v2.amp.AmpAlbum
import no.aura.os.v2.amp.AmpTrack
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Apple iTunes Charts REST API client. Free public API, no API key required. */
class iTunesCatalog {
    suspend fun topSongs(limit: Int = 25): List<AmpTrack> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("https://itunes.apple.com/us/rss/topsongs/limit=$limit/json")
            val entries = json.optJSONObject("feed")?.optJSONArray("entry") ?: return@withContext emptyList()
            val result = mutableListOf<AmpTrack>()
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val id = item.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id") ?: i.toString()
                val title = item.optJSONObject("im:name")?.optString("label") ?: "Unknown Track"
                val artist = item.optJSONObject("im:artist")?.optString("label") ?: "Unknown Artist"
                val images = item.optJSONArray("im:image")
                val rawCover = images?.optJSONObject(images.length() - 1)?.optString("label")
                val cover = rawCover?.replace("100x100bb.jpg", "600x600bb.jpg")?.replace("100x100", "600x600")
                result += AmpTrack(
                    id = "itunes_$id",
                    title = title,
                    artist = artist,
                    duration = 210,
                    cover = cover
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun topAlbums(limit: Int = 25): List<AmpAlbum> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("https://itunes.apple.com/us/rss/topalbums/limit=$limit/json")
            val entries = json.optJSONObject("feed")?.optJSONArray("entry") ?: return@withContext emptyList()
            val result = mutableListOf<AmpAlbum>()
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val id = item.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id") ?: i.toString()
                val title = item.optJSONObject("im:name")?.optString("label") ?: "Unknown Album"
                val artist = item.optJSONObject("im:artist")?.optString("label") ?: "Unknown Artist"
                val images = item.optJSONArray("im:image")
                val rawCover = images?.optJSONObject(images.length() - 1)?.optString("label")
                val cover = rawCover?.replace("100x100bb.jpg", "600x600bb.jpg")?.replace("100x100", "600x600")
                val releaseDate = item.optJSONObject("im:releaseDate")?.optString("label")?.take(10)
                result += AmpAlbum(
                    id = "itunes_$id",
                    title = title,
                    artist = artist,
                    cover = cover,
                    releaseDate = releaseDate
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return JSONObject()
            val bytes = conn.inputStream.use { it.readBytes() }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }
}
