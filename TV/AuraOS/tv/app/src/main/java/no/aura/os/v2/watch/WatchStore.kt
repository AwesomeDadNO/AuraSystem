package no.aura.os.v2.watch

import android.content.Context
import android.content.SharedPreferences
import no.aura.os.v2.tmdb.TitleRef

/** Stable identifiers for watchlist/watched records (TMDB ids, never titles). */
object WatchKeys {
    fun title(ref: TitleRef): String = ref.key
    fun episode(series: TitleRef, season: Int, episode: Int): String? =
        if (series.type == no.aura.os.v2.tmdb.MediaType.TV && season in 0..999 && episode in 1..9999) "${series.key}:s$season:e$episode" else null
    fun isValid(key: String) = key.length in 8..64 && KEY.matches(key)
    private val KEY = Regex("tmdb:(movie|tv):[0-9]{1,9}(:s[0-9]{1,3}:e[0-9]{1,4})?")
}

/**
 * Local, per-profile watchlist and watched state. Two DIFFERENT records: adding to the watchlist is not watching.
 * Every call names the profile, so one profile can never read or change another profile's records. Works without any account or sync.
 */
interface WatchStore {
    fun watchlist(profileId: String): Set<String>
    fun inWatchlist(profileId: String, key: String): Boolean = key in watchlist(profileId)
    fun setWatchlist(profileId: String, key: String, on: Boolean): Boolean
    fun favorites(profileId: String): Set<String>
    fun inFavorites(profileId: String, key: String): Boolean = key in favorites(profileId)
    fun setFavorite(profileId: String, key: String, on: Boolean): Boolean
    fun watched(profileId: String): Set<String>
    fun isWatched(profileId: String, key: String): Boolean = key in watched(profileId)
    fun setWatched(profileId: String, key: String, on: Boolean): Boolean
    /** Removes everything the profile owns (used when a profile is deleted). */
    fun purge(profileId: String)
}

class MemoryWatchStore : WatchStore {
    private val lists = HashMap<String, MutableSet<String>>(); private val done = HashMap<String, MutableSet<String>>(); private val fav = HashMap<String, MutableSet<String>>()
    override fun watchlist(profileId: String): Set<String> = lists[profileId]?.toSet() ?: emptySet()
    override fun watched(profileId: String): Set<String> = done[profileId]?.toSet() ?: emptySet()
    override fun favorites(profileId: String): Set<String> = fav[profileId]?.toSet() ?: emptySet()
    override fun setWatchlist(profileId: String, key: String, on: Boolean) = edit(lists, profileId, key, on)
    override fun setWatched(profileId: String, key: String, on: Boolean) = edit(done, profileId, key, on)
    override fun setFavorite(profileId: String, key: String, on: Boolean) = edit(fav, profileId, key, on)
    override fun purge(profileId: String) { lists.remove(profileId); done.remove(profileId); fav.remove(profileId) }
    private fun edit(m: HashMap<String, MutableSet<String>>, p: String, key: String, on: Boolean): Boolean {
        if (!WatchKeys.isValid(key) || p.isBlank()) return false
        val s = m.getOrPut(p) { HashSet() }
        if (on) { if (key !in s && s.size >= LIMIT) return false; s.add(key) } else s.remove(key)
        return true
    }
    companion object { const val LIMIT = 5_000 }
}

/** SharedPreferences-backed store: one string set per profile and record type, written with apply() only when something changed. */
class PrefsWatchStore(private val prefs: SharedPreferences) : WatchStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_watch", Context.MODE_PRIVATE))
    private fun read(k: String): Set<String> = runCatching { prefs.getStringSet(k, null)?.toSet() }.getOrNull() ?: emptySet()
    override fun watchlist(profileId: String) = read(WL + profileId)
    override fun watched(profileId: String) = read(WD + profileId)
    override fun favorites(profileId: String) = read(FT + profileId)
    override fun setWatchlist(profileId: String, key: String, on: Boolean) = edit(WL + profileId, profileId, key, on)
    override fun setWatched(profileId: String, key: String, on: Boolean) = edit(WD + profileId, profileId, key, on)
    override fun setFavorite(profileId: String, key: String, on: Boolean) = edit(FT + profileId, profileId, key, on)
    override fun purge(profileId: String) { prefs.edit().remove(WL + profileId).remove(WD + profileId).remove(FT + profileId).apply() }
    private fun edit(k: String, p: String, key: String, on: Boolean): Boolean {
        if (!WatchKeys.isValid(key) || p.isBlank()) return false
        val cur = read(k)
        if ((key in cur) == on) return true                       // nothing to write
        if (on && cur.size >= MemoryWatchStore.LIMIT) return false
        // apply(): the in-memory value is updated at once (the next read sees it) and the disk write happens in the background. This is called
        // from the DPAD handler on the main thread; a synchronous commit() there blocks the UI on flash I/O for no benefit.
        prefs.edit().putStringSet(k, if (on) cur + key else cur - key).apply()
        return true
    }
    private companion object { const val WL = "wl:"; const val WD = "wd:"; const val FT = "fav:" }
}
