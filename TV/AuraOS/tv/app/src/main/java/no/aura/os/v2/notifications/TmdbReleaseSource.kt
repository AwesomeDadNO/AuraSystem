package no.aura.os.v2.notifications

import no.aura.os.v2.profiles.ProfileService
import no.aura.os.v2.tmdb.*
import no.aura.os.v2.watch.WatchStore
import no.aura.os.v2.watch.WatchKeys
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

/** Uses the same rating gate as details and episode navigation; never reads another profile's watchlist. */
class TmdbWatchlistReleaseSource(private val watch: WatchStore, private val catalog: RealCatalogService,
    private val profiles: ProfileService, private val zone: ZoneId = ZoneId.systemDefault(), private val now: () -> Long = System::currentTimeMillis) : WatchlistReleaseSource {
    override suspend fun releases(profile: String): List<VerifiedWatchlistRelease> {
        val owner = profiles.state.find(profile) ?: return emptyList()
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        val done = watch.watched(profile)
        val result = mutableListOf<VerifiedWatchlistRelease>()
        for (key in watch.watchlist(profile).sorted().take(100)) {
            if (key in done) continue
            val ref = TitleRef.parse(key) ?: continue
            val details = (catalog.details(ref, owner) as? DetailsResult.Ok)?.details ?: continue
            if (ref.type == MediaType.MOVIE) {
                val date = ReleaseWindow.date(details.date, today) ?: continue
                result += VerifiedWatchlistRelease(ref.key, details.title, false, date, "TMDB")
            } else {
                val latest = (details.seasons ?: 0).coerceIn(0, 999)
                for (season in (latest - 1).coerceAtLeast(1)..latest) {
                    val episodes = (catalog.season(ref, season, owner) as? TmdbResult.Ok)?.value ?: continue
                    for (episode in episodes) {
                        val date = ReleaseWindow.date(episode.airDate, today) ?: continue
                        val episodeKey = WatchKeys.episode(ref, season, episode.number) ?: continue
                        if (episodeKey in done) continue
                        result += VerifiedWatchlistRelease(episodeKey, "${details.title} · ${episode.name}", true, date, "TMDB")
                    }
                }
            }
        }
        return result.distinctBy { it.key }.take(200)
    }
}
object ReleaseWindow {
    /** Imprecise year/month dates cannot substantiate a release-day notification. */
    fun date(raw: String?, today: LocalDate): LocalDate? = raw?.takeIf { Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(today) && !it.isBefore(today.minusDays(7)) }
}
