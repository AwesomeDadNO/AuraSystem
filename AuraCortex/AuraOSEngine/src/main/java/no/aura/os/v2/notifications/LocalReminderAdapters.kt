package no.aura.os.v2.notifications

import no.aura.os.v2.tmdb.WatchProgressSource

/** Derived only from actual profile playback; completion is already removed by WatchProgressSource. */
class ContinueWatchingNotificationAdapter(private val progress: WatchProgressSource, private val now: () -> Long = System::currentTimeMillis) : NotificationSourceAdapter {
    override val category = NotificationCategory.CONTINUE_WATCHING
    override suspend fun fetch(profile: String): SourceBatch {
        val day = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return SourceBatch(progress.continueWatching(profile).filter { it.progress > 0f && it.progress < .95f }.map { item ->
            CenterEvent("continue:${item.item.id}:$day", category, "Local playback", "Continue Watching", item.item.title, now(), item.item.id)
        })
    }
}

/** Implement only with a verified provider plus this profile's actual watchlist membership. */
data class VerifiedWatchlistRelease(val key: String, val title: String, val episode: Boolean,
    val releaseDate: java.time.LocalDate, val source: String, val completed: Boolean = false)
interface WatchlistReleaseSource { suspend fun releases(profile: String): List<VerifiedWatchlistRelease> }
class WatchlistReleaseNotificationAdapter(override val category: NotificationCategory, private val source: WatchlistReleaseSource,
    private val zone: java.time.ZoneId, private val now: () -> Long = System::currentTimeMillis) : NotificationSourceAdapter {
    init { require(category == NotificationCategory.WATCHLIST_MOVIE || category == NotificationCategory.WATCHLIST_EPISODE) }
    override suspend fun fetch(profile: String): SourceBatch {
        val today = java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        return SourceBatch(source.releases(profile).filter {
            !it.completed && it.episode == (category == NotificationCategory.WATCHLIST_EPISODE) &&
                !it.releaseDate.isAfter(today) && !it.releaseDate.isBefore(today.minusDays(7)) && it.source.isNotBlank()
        }.map { release -> CenterEvent("release:${release.key}:${release.releaseDate}", category, release.source,
            release.title, "Release date: ${release.releaseDate}. Streaming availability depends on your provider and region.",
            release.releaseDate.atStartOfDay(zone).toInstant().toEpochMilli(), release.key) })
    }
}
