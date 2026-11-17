package no.aura.os.v2.watch

import android.content.Context
import android.content.SharedPreferences
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.data.ContinueItem
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.tmdb.TitleRef
import no.aura.os.v2.tmdb.WatchProgressSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local viewing progress, scoped to the currently active profile. Stores only title metadata and
 * a playback position/duration from the actual player: no streaming URLs, tokens or demo entries.
 * A nearly finished item is removed rather than being advertised as Continue Watching.
 */
data class LocalPlaybackRecord(val key: String, val title: String, val artwork: String?, val positionMs: Long, val durationMs: Long, val engagedAtMs: Long)

class PrefsPlaybackProgress(context: Context) : WatchProgressSource {
    private val prefs: SharedPreferences = context.getSharedPreferences("aura_v2_playback_progress", Context.MODE_PRIVATE)

    @Synchronized
    fun record(profileId: String, ref: TitleRef, title: String, artworkUrl: String?, season: Int?, episode: Int?,
               positionMs: Long, durationMs: Long) {
        if (profileId.isBlank() || positionMs < 0 || durationMs <= 0) return
        val id = ref.key + (if (season != null && episode != null) ":s$season:e$episode" else "")
        if (!WatchKeys.isValid(id)) return
        val arr = read(profileId)
        val retained = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("key") != id) retained.add(o)
        }
        val fraction = positionMs.toDouble() / durationMs.toDouble()
        if (fraction < 0.95 && positionMs >= 5_000L) {
            retained.add(0, JSONObject().put("key", id).put("title", title.take(160))
                .put("art", artworkUrl?.takeIf { it.startsWith("https://image.tmdb.org/t/p/") }.orEmpty())
                .put("pos", positionMs.coerceAtMost(durationMs)).put("dur", durationMs)
                .put("at", System.currentTimeMillis()))
        }
        val result = JSONArray()
        retained.take(30).forEach { result.put(it) }
        prefs.edit().putString("progress:$profileId", result.toString()).apply()
    }

    /** Resume only the exact movie or episode the player is about to open. */
    @Synchronized
    fun resumePosition(profileId: String, ref: TitleRef, season: Int?, episode: Int?): Long {
        val key = ref.key + (if (season != null && episode != null) ":s$season:e$episode" else "")
        val a = read(profileId)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("key") == key && o.optLong("dur") > 0 &&
                o.optLong("pos") < o.optLong("dur") * 0.95) return o.optLong("pos").coerceAtLeast(0L)
        }
        return 0L
    }

    /** Actual recorded episode progress only, confined to the active profile and season. */
    @Synchronized
    fun episodeFractions(profileId: String, ref: TitleRef, season: Int): Map<Int, Float> {
        val a = read(profileId)
        val prefix = ref.key + ":s$season:e"
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            if (!key.startsWith(prefix)) return@mapNotNull null
            val episode = key.removePrefix(prefix).toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val fraction = no.aura.os.v2.details.CinemaSeriesLogic.progress(o.optLong("pos", -1), o.optLong("dur")) ?: return@mapNotNull null
            episode to fraction
        }.toMap()
    }

    /** Last started series episode; title/progress data are only read inside this profile. */
    @Synchronized
    fun lastEpisode(profileId: String, ref: TitleRef): Pair<Int, Int>? {
        val a = read(profileId)
        for (i in 0 until a.length()) {
            val id = a.optJSONObject(i)?.optString("key") ?: continue
            if (!id.startsWith(ref.key + ":s")) continue
            val season = id.substringAfter(":s").substringBefore(":e").toIntOrNull() ?: continue
            val episode = id.substringAfter(":e").toIntOrNull() ?: continue
            if (season in 1..999 && episode in 1..9999) return season to episode
        }
        return null
    }

    @Synchronized
    fun clear(profileId: String) { prefs.edit().remove("progress:$profileId").apply() }

    override suspend fun continueWatching(profileId: String): List<ContinueItem> = synchronized(this) {
        val arr = read(profileId)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("key")
            val ref = TitleRef.parse(id.substringBefore(":s")) ?: return@mapNotNull null
            if (!WatchKeys.isValid(id)) return@mapNotNull null
            val dur = o.optLong("dur")
            val pos = o.optLong("pos")
            if (dur <= 0 || pos <= 0 || pos >= dur * 0.95) return@mapNotNull null
            val remainingMinutes = ((dur - pos) / 60_000L).coerceAtLeast(1)
            ContinueItem(MediaItem(ref.key, o.optString("title").ifBlank { return@mapNotNull null },
                Artwork(imageUrl = o.optString("art").takeIf { it.isNotBlank() }),
                subtitle = if (ref.key != id) id.substringAfter(":s").replace(":e", " · E") else null),
                (pos.toDouble() / dur).toFloat().coerceIn(0f, 1f), "${remainingMinutes}m left")
        }
    }

    @Synchronized fun records(profileId: String): List<LocalPlaybackRecord> {
        val arr = read(profileId)
        return (0 until arr.length()).mapNotNull { index ->
            val o = arr.optJSONObject(index) ?: return@mapNotNull null
            val key = o.optString("key").takeIf(WatchKeys::isValid) ?: return@mapNotNull null
            val position = o.optLong("pos"); val duration = o.optLong("dur")
            if (position < 5000 || duration <= 0 || position >= duration * .95) return@mapNotNull null
            LocalPlaybackRecord(key, o.optString("title"), o.optString("art").takeIf { it.isNotBlank() }, position, duration, o.optLong("at"))
        }.take(20)
    }

    private fun read(profileId: String): JSONArray =
        runCatching { JSONArray(prefs.getString("progress:$profileId", "[]") ?: "[]") }.getOrDefault(JSONArray())
}
