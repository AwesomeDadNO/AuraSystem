package no.aura.os.v2.tmdb

enum class MediaType(val path: String, val label: String) {
    MOVIE("movie", "Movie"), TV("tv", "Series");
    companion object { fun fromPath(p: String?): MediaType? = values().firstOrNull { it.path == p } }
}

/** Stable reference to a TMDB title. Carried in UI item ids as `tmdb:movie:603`. */
data class TitleRef(val type: MediaType, val id: Int) {
    val key get() = "tmdb:${type.path}:$id"
    companion object {
        fun parse(key: String?): TitleRef? {
            val p = key?.split(':') ?: return null
            if (p.size != 3 || p[0] != "tmdb") return null
            val t = MediaType.fromPath(p[1]) ?: return null
            val id = p[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return TitleRef(t, id)
        }
    }
}

/** One catalogue entry from a list endpoint. Lists carry no certification, so child profiles resolve it through details before showing it. */
data class TmdbTitle(
    val ref: TitleRef, val title: String, val overview: String, val posterPath: String?, val backdropPath: String?,
    val date: String?, val voteAverage: Double?, val voteCount: Int, val genreIds: List<Int>,
) {
    val year: String? get() = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
}

data class TmdbPage(val items: List<TmdbTitle>, val page: Int, val totalPages: Int) { val hasMore get() = page < totalPages }

data class TmdbDetails(
    val ref: TitleRef, val title: String, val tagline: String?, val overview: String, val posterPath: String?, val backdropPath: String?,
    val date: String?, val voteAverage: Double?, val voteCount: Int, val genres: List<String>, val runtimeMinutes: Int?,
    val seasons: Int?, val episodes: Int?, val status: String?, val certification: String?, val imdbId: String? = null,
    /** Real credits (TMDB). Empty = not available; never filled with placeholders. */
    val cast: List<TmdbPerson> = emptyList(), val crew: List<TmdbPerson> = emptyList(),
    val recommendations: List<TmdbTitle> = emptyList(), val similar: List<TmdbTitle> = emptyList(),
    /** YouTube key of an official trailer (validated 11-char id), or null. */ val trailerKey: String? = null,
    /** Title metadata, never a claim about audio tracks in a selected stream. */
    val spokenLanguages: List<TmdbLanguage> = emptyList(), val originalLanguage: String? = null,
) {
    val year: String? get() = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
}

data class TmdbLanguage(val code: String, val englishName: String? = null, val nativeName: String? = null)

/** One episode of a season (TMDB). [airDate] is yyyy-MM-dd or null. */
data class TmdbEpisode(val number: Int, val name: String, val overview: String, val airDate: String?, val runtimeMinutes: Int?, val stillPath: String? = null)

/** A cast or crew entry. [role] is the character (cast) or the job (crew: Director, Screenplay, Producer, Creator…). */
data class TmdbPerson(val id: Int, val name: String, val role: String, val profilePath: String?)

enum class TmdbError { NOT_CONFIGURED, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, NETWORK, SERVER, BAD_RESPONSE }

sealed interface TmdbResult<out T> {
    data class Ok<T>(val value: T) : TmdbResult<T>
    data class Failure(val error: TmdbError) : TmdbResult<Nothing>
}

/** The read access token from git-ignored developer configuration. Never printed. */
class TmdbConfig(private val token: String) {
    val isConfigured: Boolean get() = token.isNotBlank()
    internal fun bearer(): String = token
    override fun toString() = "TmdbConfig(configured=$isConfigured)"
}

/** TMDB image CDN. Sizes are chosen per use so nothing downloads the original resolution. */
object TmdbImages {
    const val HOST = "image.tmdb.org"
    private const val BASE = "https://image.tmdb.org/t/p/"
    const val HERO = "w1280"          // hero / detail backdrop
    const val CARD_BACKDROP = "w500"  // landscape cards on a UI wider than 1920 px
    const val POSTER = "w780"         // detailed large 9:16 posters on 4K TVs
    const val CARD_BACKDROP_SMALL = "w300"   // landscape cards (<= 337 canvas px wide) on a UI up to 1920 px
    const val POSTER_SMALL = "w500"          // 332-wide 9:16 poster cards on 1080p TVs
    const val DETAIL_POSTER = "w500"
    const val STILL = "w300"          // episode stills (cards)
    const val PROFILE = "w185"        // cast / crew portraits
    fun url(size: String, path: String?): String? = path?.takeIf { it.startsWith("/") && it.length in 2..128 && it.none { c -> c.isWhitespace() || c == '?' || c == '#' || c == '\\' } }?.let { BASE + size + it }
    /** Only TMDB's image host over https is ever fetched. */
    fun isAllowed(url: String): Boolean = url.startsWith("https://$HOST/t/p/") && url.none { it.isWhitespace() }
    /** Width in px encoded in the URL (w500 -> 500); null for other sizes. */
    fun widthOf(url: String): Int? = Regex("/t/p/w(\\d{2,4})/").find(url)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Artwork variants for shelf cards, chosen from the real output width. Cards are at most 337 (landscape) / 332 (portrait) canvas units wide;
 * one canvas unit is one pixel on a 1920 px wide UI; w500 posters avoid upscaling on 1080p and w780 covers 4K. A wider (4K) UI keeps the
 * larger ones. Hero, detail and still sizes are unchanged.
 */
data class ArtSizes(val cardBackdrop: String, val poster: String) {
    companion object {
        val Small = ArtSizes(TmdbImages.CARD_BACKDROP_SMALL, TmdbImages.POSTER_SMALL)
        val Large = ArtSizes(TmdbImages.CARD_BACKDROP, TmdbImages.POSTER)
        fun forDisplayWidth(widthPx: Int): ArtSizes = if (widthPx in 1..1920) Small else Large
    }
}

/** Picks the content rating from TMDB's per-country lists. US first (TV-*, MPAA), then Norway; anything else stays unknown -> blocked for children. */
object Certification {
    val PREFERRED = listOf("US", "NO")
    fun pick(byCountry: Map<String, String?>): String? = PREFERRED.firstNotNullOfOrNull { byCountry[it]?.trim()?.takeIf { c -> c.isNotEmpty() } }
}
