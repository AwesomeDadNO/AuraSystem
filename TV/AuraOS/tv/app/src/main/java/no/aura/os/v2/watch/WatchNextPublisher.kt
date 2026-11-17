package no.aura.os.v2.watch

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri

data class OwnWatchNextItem(val key: String, val title: String, val positionMs: Long, val durationMs: Long,
    val engagedAtMs: Long, val launch: Intent, val artwork: String? = null)
data class WatchNextPublishResult(val published: Int, val message: String)
/** Writes only AuraOS-owned ids saved on this device. Never queries cross-app or Google account watchlists. */
class WatchNextPublisher(private val context: Context) {
    private val prefs = context.getSharedPreferences("aura_own_watch_next", Context.MODE_PRIVATE)
    @Synchronized fun publish(profile: String, explicitlyOptedIn: Boolean, items: List<OwnWatchNextItem>): WatchNextPublishResult {
        val cleared = clearOwn()
        if (!cleared) return WatchNextPublishResult(0, "Android TV Watch Next unavailable or permission denied. Previously published items could not be removed.")
        if (!explicitlyOptedIn) return WatchNextPublishResult(0, "Android TV Watch Next is off. AuraOS local progress remains separate.")
        if (profile.isBlank()) return WatchNextPublishResult(0, "Select a profile before publishing.")
        var count = 0
        try {
            items.filter { it.durationMs > 0 && it.positionMs >= 5000 && it.positionMs < it.durationMs * .95 && it.title.isNotBlank() }
                .take(20).forEach { item ->
                    if (item.launch.component?.packageName != context.packageName || !WatchKeys.isValid(item.key)) return@forEach
                    val movie = item.key.startsWith("tmdb:movie:")
                    if (!movie && !item.key.contains(":s")) return@forEach
                    val values = ContentValues().apply {
                        put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, "aura:$profile:${item.key}")
                        put(TvContract.WatchNextPrograms.COLUMN_TITLE, item.title.take(160))
                        put(TvContract.WatchNextPrograms.COLUMN_TYPE, if (movie) TvContract.WatchNextPrograms.TYPE_MOVIE else TvContract.WatchNextPrograms.TYPE_TV_EPISODE)
                        put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, item.positionMs)
                        put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, item.durationMs)
                        put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, item.engagedAtMs)
                        put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, item.launch.toUri(Intent.URI_INTENT_SCHEME))
                        item.artwork?.takeIf { it.startsWith("https://image.tmdb.org/t/p/") }?.let { put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it) }
                    }
                    val uri = context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values) ?: return@forEach
                    val ids = prefs.getStringSet("own_ids", emptySet()).orEmpty().toMutableSet()
                    ids.add(ContentUris.parseId(uri).toString())
                    if (!prefs.edit().putStringSet("own_ids", ids).commit()) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        return WatchNextPublishResult(count, "Watch Next publication stopped because local tracking could not be saved.")
                    }
                    count++
                }
        } catch (_: Exception) { return WatchNextPublishResult(count, "Watch Next publishing unavailable or permission denied. Device support and certification vary.") }
        return WatchNextPublishResult(count, "Published $count AuraOS items. System display is controlled by the device; this is not Google-account Watchlist sync.")
    }
    /** Call on opt-out and before profile switches; report failure, because the system row is shared. */
    @Synchronized fun clearOwn(): Boolean {
        val remaining = prefs.getStringSet("own_ids", emptySet()).orEmpty().toMutableSet()
        remaining.toList().forEach { id ->
            val numeric = id.toLongOrNull() ?: return@forEach
            try { context.contentResolver.delete(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, numeric), null, null); remaining.remove(id) }
            catch (_: Exception) { }
        }
        prefs.edit().putStringSet("own_ids", remaining).commit()
        return remaining.isEmpty()
    }
}
