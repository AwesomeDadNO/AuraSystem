package no.aura.os.v2.music

import android.content.Context
import android.content.SharedPreferences
import no.aura.os.v2.tmdb.*
import no.aura.os.v2.mediaserver.JsonOut

data class FollowedArtist(val id: String, val name: String)
object ArtistIds { fun valid(id: String) = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id) }
interface FollowedArtistStore {
    fun artists(profile: String): List<FollowedArtist>
    fun follow(profile: String, artist: FollowedArtist, on: Boolean): Boolean
    fun purge(profile: String)
}
class MemoryFollowedArtistStore : FollowedArtistStore {
    private val entries = mutableMapOf<String, List<FollowedArtist>>()
    override fun artists(profile: String) = entries[profile].orEmpty().toList()
    override fun follow(profile: String, artist: FollowedArtist, on: Boolean): Boolean {
        if (profile.isBlank() || !ArtistIds.valid(artist.id) || artist.name.isBlank() || artist.name.length > 160) return false
        val next = artists(profile).filterNot { it.id == artist.id } + if (on) listOf(artist) else emptyList()
        if (next.size > 40) return false
        entries[profile] = next; return true
    }
    override fun purge(profile: String) { entries.remove(profile) }
}
class PrefsFollowedArtistStore(private val prefs: SharedPreferences) : FollowedArtistStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_followed_artists", Context.MODE_PRIVATE))
    override fun artists(profile: String): List<FollowedArtist> {
        val raw = prefs.getString("artists:$profile", null)?.takeIf { it.length < 24000 } ?: return emptyList()
        return Json.parse(raw).list().mapNotNull { row -> row.obj()?.let { o ->
            val id = o.str("id")?.takeIf(ArtistIds::valid) ?: return@let null
            val name = o.str("name")?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return@let null
            FollowedArtist(id, name)
        } }.distinctBy { it.id }.take(40)
    }
    override fun follow(profile: String, artist: FollowedArtist, on: Boolean): Boolean {
        val memory = MemoryFollowedArtistStore()
        artists(profile).forEach { memory.follow(profile, it, true) }
        if (!memory.follow(profile, artist, on)) return false
        prefs.edit().putString("artists:$profile", JsonOut.write(memory.artists(profile).map { mapOf("id" to it.id, "name" to it.name) })).apply()
        return true
    }
    override fun purge(profile: String) { prefs.edit().remove("artists:$profile").apply() }
}
