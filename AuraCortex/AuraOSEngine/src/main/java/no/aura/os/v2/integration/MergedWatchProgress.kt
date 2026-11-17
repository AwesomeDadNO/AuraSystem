package no.aura.os.v2.integration

import no.aura.os.v2.data.*
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.profiles.ProfileService
import no.aura.os.v2.tmdb.WatchProgressSource

/** Only actual player/server progress; unknown server ratings are blocked for child profiles. */
class MergedWatchProgress(private val local: WatchProgressSource, private val servers: MediaServerService,
    private val profiles: ProfileService) : WatchProgressSource {
    override suspend fun continueWatching(profileId: String): List<ContinueItem> {
        val owner = profiles.state.profiles.firstOrNull { it.id == profileId } ?: return emptyList()
        val localItems = local.continueWatching(profileId)
        if (owner.isChild) return localItems
        val remote = servers.accounts(profileId).take(8).flatMap { account ->
            val entries = (servers.libraryPlayback.resume(profileId, account) as? MediaResult.Ok)?.value.orEmpty()
            entries.mapNotNull { entry ->
                val position = entry.resumeMs ?: return@mapNotNull null
                val duration = entry.durationMs?.takeIf { it > 0 } ?: return@mapNotNull null
                if (position < 5000 || position >= duration * .95) return@mapNotNull null
                ContinueItem(MediaItem("server:${account.key}|${entry.id}", entry.title, Artwork(),
                    subtitle = account.kind.title + " · " + entry.seriesTitle.orEmpty()), (position.toDouble()/duration).toFloat(), "${((duration-position)/60_000).coerceAtLeast(1)}m left")
            }
        }
        return (localItems + remote).distinctBy { it.item.id }.take(40)
    }
}
