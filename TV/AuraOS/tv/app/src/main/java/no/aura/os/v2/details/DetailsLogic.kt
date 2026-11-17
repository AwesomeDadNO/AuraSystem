package no.aura.os.v2.details

import no.aura.os.v2.tmdb.MediaType
import no.aura.os.v2.tmdb.TmdbDetails
import no.aura.os.v2.tmdb.TmdbEpisode
import no.aura.os.v2.tmdb.TmdbPerson

/** Pure presentation logic of the cinematic details page (no Android, no network): unit-tested on the JVM. */
enum class DetailsTab(val label: String) {
    DETAILS("Details"), SEASONS("Seasons"), LATEST("Latest TV Shows"), RECOMMEND("Aura Recommended"), NEXT("What's next?");
    companion object {
        fun forType(t: MediaType): List<DetailsTab> = if (t == MediaType.MOVIE) listOf(DETAILS, RECOMMEND, NEXT) else listOf(LATEST, RECOMMEND, NEXT)
    }
}

object DetailsLogic {
    /** Prefer TMDB's supplied English name, then resolve the actual ISO code with locale data. */
    fun languageName(code: String, suppliedName: String? = null): String = suppliedName?.takeIf { it.isNotBlank() }
        ?: java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it.isNotBlank() && it != code }
        ?: code

    fun languageLabel(d: TmdbDetails): String {
        val spoken = d.spokenLanguages.map { languageName(it.code, it.englishName) }.distinct()
        val parts = listOfNotNull(
            spoken.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Spoken: $it" },
            d.originalLanguage?.let { "Original: ${languageName(it)}" },
        )
        return "Title languages (TMDB): " + (parts.joinToString(" · ").ifBlank { "unavailable" }) +
            ". Stream audio and subtitles depend on the selected source."
    }

    /** 65 → "1h 5m", 120 → "2h", 45 → "45m", 0/null → null (never "10h 5m" mangling). */
    fun runtimeLabel(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60; val m = minutes % 60
        return when { h == 0 -> "${m}m"; m == 0 -> "${h}h"; else -> "${h}h ${m}m" }
    }

    fun episodeCode(season: Int, episode: Int) = "S$season:E$episode"

    fun ratingLabel(d: TmdbDetails): String? = d.voteAverage?.takeIf { d.voteCount > 0 && it > 0 }?.let { "★ " + (Math.round(it * 10) / 10.0) }

    /** Factual meta parts only: year, official age rating (certification), duration (movie) or season count (series), user rating. */
    fun metaParts(d: TmdbDetails): List<String> = listOfNotNull(
        d.year, d.certification?.takeIf { it.isNotBlank() },
        if (d.ref.type == MediaType.MOVIE) runtimeLabel(d.runtimeMinutes) else d.seasons?.let { "$it season" + if (it == 1) "" else "s" },
        ratingLabel(d),
    )

    /** First unwatched aired-or-not episode, else the first one (null for an empty season). [isWatched] is keyed by episode number. */
    fun defaultEpisode(eps: List<TmdbEpisode>, isWatched: (Int) -> Boolean): Int? = eps.firstOrNull { !isWatched(it.number) }?.number ?: eps.firstOrNull()?.number

    /** Hero play label for series: "Play" when nothing is watched in this season, else "Continue Watching"; movies always "Play". */
    fun playLabel(type: MediaType, anyWatchedInSeason: Boolean) = if (type == MediaType.TV && anyWatchedInSeason) "Continue Watching" else "Play"

    /** yyyy-MM-dd strings compare chronologically; unknown air date counts as aired (TMDB often omits it for old shows). */
    fun isAired(airDate: String?, today: String): Boolean = airDate.isNullOrBlank() || airDate <= today

    /** Title of the cast row below the episode row. Episode credits only get the episode label; series fallback is labelled differently. */
    fun castSectionTitle(episodeCredits: Boolean, season: Int, episode: Int) =
        if (episodeCredits) "Cast in This Episode (${episodeCode(season, episode)})" else "Series Cast"

    /** Crew is grouped under its real job; cast members are never put into the crew list. */
    fun crewGroups(crew: List<TmdbPerson>): List<Pair<String, List<TmdbPerson>>> =
        crew.groupBy { it.role }.entries.map { it.key to it.value }

    fun mark(watched: Boolean) = if (watched) "Mark Unwatched" else "Mark Watched"
    fun watchlistLabel(inList: Boolean) = if (inList) "Remove from Watchlist" else "Add to Watchlist"
}

/** Sections stacked under the hero, in DPAD order. Each becomes one stable-keyed LazyColumn item (the page scrolls vertically as a whole). */
enum class DetailsSection(val key: String) { CAST("cast"), CREW("crew"), ADVISORY("advisory"), SEASONS("seasons"), RELATED("related") }

object DetailsLayout {
    /**
     * The hero is a list item sized from the ACTUAL dialog viewport (canvas units), not a constant: [HERO_FRACTION] of the height, never
     * below what its own content needs ([HERO_MIN_DP]: label, two-line title, metadata, text block, buttons, status) and never above
     * [HERO_MAX_DP]. On the 1080-high canvas that is 454, leaving the tab row and a full first content row on screen.
     */
    const val HERO_FRACTION = 0.42f
    const val HERO_MIN_DP = 430
    const val HERO_MAX_DP = 470
    const val HERO_TEXT_BLOCK_DP = 96
    const val TAB_ROW_DP = 56
    fun heroHeightDp(viewportHeightDp: Float): Int =
        if (!viewportHeightDp.isFinite() || viewportHeightDp <= 0f) HERO_MIN_DP else Math.round(viewportHeightDp * HERO_FRACTION).coerceIn(HERO_MIN_DP, HERO_MAX_DP)
    fun sections(type: MediaType, tab: DetailsTab): List<DetailsSection> = when (tab) {
        DetailsTab.DETAILS -> if (type == MediaType.MOVIE) listOf(DetailsSection.CAST, DetailsSection.CREW, DetailsSection.ADVISORY) else emptyList()
        DetailsTab.SEASONS -> listOf(DetailsSection.SEASONS)
        DetailsTab.LATEST, DetailsTab.RECOMMEND, DetailsTab.NEXT -> listOf(DetailsSection.RELATED)
    }
    /** Vertical budget above the first content row for a viewport of [viewportHeightDp] canvas units. */
    fun aboveContentDp(viewportHeightDp: Float, tabRowDp: Int = TAB_ROW_DP) = heroHeightDp(viewportHeightDp) + tabRowDp
}

/** Remembers, per season, which episode was last focused/played so returning to a season (or closing the player) restores the position. */
class EpisodeMemory {
    private val bySeason = HashMap<Int, Int>()
    fun remember(season: Int, episode: Int) { bySeason[season] = episode }
    fun recall(season: Int): Int? = bySeason[season]
    /** The remembered episode if it still exists in [available], else null. */
    fun restore(season: Int, available: List<Int>): Int? = recall(season)?.takeIf { it in available }
    fun clear() = bySeason.clear()
}
