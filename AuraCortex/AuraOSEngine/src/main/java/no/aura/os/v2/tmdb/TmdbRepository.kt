package no.aura.os.v2.tmdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import no.aura.os.v2.youtube.TtlCache

/** The ONE TMDB data source for Home, Movies, TV Shows and AuraBox. Lists are bounded and paginated; details carry the certification. */
enum class Feed(val path: String, val media: MediaType?) {
    TRENDING_ALL("/trending/all/week", null), TRENDING_MOVIE("/trending/movie/week", MediaType.MOVIE), TRENDING_TV("/trending/tv/week", MediaType.TV),
    NOW_PLAYING("/movie/now_playing", MediaType.MOVIE), POPULAR_MOVIE("/movie/popular", MediaType.MOVIE), TOP_MOVIE("/movie/top_rated", MediaType.MOVIE),
    POPULAR_TV("/tv/popular", MediaType.TV), TOP_TV("/tv/top_rated", MediaType.TV), AIRING_TV("/tv/on_the_air", MediaType.TV),
}

interface TmdbRepository {
    suspend fun feed(feed: Feed, page: Int = 1): TmdbResult<TmdbPage>
    suspend fun details(ref: TitleRef): TmdbResult<TmdbDetails>
    /** Genre id -> name for both media types (cached for a day). Empty map when unavailable: genres are then simply not shown. */
    suspend fun genres(): Map<Int, String>
    /** Episodes of one season of a series. */
    suspend fun season(ref: TitleRef, season: Int): TmdbResult<List<TmdbEpisode>>
    /** Cast of one episode (regular + guest cast), cached separately from the series. */
    suspend fun episodeCredits(ref: TitleRef, season: Int, episode: Int): TmdbResult<List<TmdbPerson>>
    suspend fun search(type: MediaType, query: String, page: Int = 1): TmdbResult<TmdbPage> = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    suspend fun discover(type: MediaType, genreId: Int, page: Int = 1): TmdbResult<TmdbPage> = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    suspend fun categoryFeed(type: MediaType, genreId: Int, kind: CategoryFeed, page: Int = 1, today: String): TmdbResult<TmdbPage> = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    suspend fun latestTv(page: Int = 1, today: String): TmdbResult<TmdbPage> = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    suspend fun genreList(type: MediaType): Map<Int, String> = genres()
    /** Wall-clock time of the most recent successful TMDB response, or null if none yet. Display only (Settings → Connections). */
    val lastSuccessMs: Long?
}

object NotConfiguredTmdbRepository : TmdbRepository {
    override suspend fun feed(feed: Feed, page: Int) = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    override suspend fun details(ref: TitleRef) = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    override suspend fun genres(): Map<Int, String> = emptyMap()
    override suspend fun season(ref: TitleRef, season: Int) = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    override suspend fun episodeCredits(ref: TitleRef, season: Int, episode: Int) = TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
    override val lastSuccessMs: Long? get() = null
}

/**
 * Network + cache + dedup in one place. Cache rules (memory only; nothing is written to disk):
 * lists 15 min, details 6 h, genres 24 h; each cache is a bounded LRU. Errors are never cached, so Retry always asks again.
 * Concurrent identical requests share one call and the call is cancelled when nobody waits for it any more.
 * Responses are cached per request only: they carry no profile information, and child filtering happens after the cache (see RealCatalogService).
 */
class CachingTmdbRepository(
    private val http: TmdbHttp, private val config: TmdbConfig, private val clock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TmdbRepository {
    private val lists = TtlCache<String, TmdbPage>(MAX_LISTS, clock)
    private val detail = TtlCache<String, TmdbDetails>(MAX_DETAILS, clock)
    private val seasons = TtlCache<String, List<TmdbEpisode>>(MAX_DETAILS, clock)
    private val episodeCast = TtlCache<String, List<TmdbPerson>>(MAX_EPISODE_CREDITS, clock)
    private val genreCache = TtlCache<String, Map<Int, String>>(2, clock)
    private val flights = Dedup<String, TmdbResult<*>>(scope)
    @Volatile override var lastSuccessMs: Long? = null; private set

    override suspend fun feed(feed: Feed, page: Int): TmdbResult<TmdbPage> {
        if (!config.isConfigured) return TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
        val p = page.coerceIn(1, MAX_PAGE)
        val key = "${feed.name}|$p"
        lists.get(key)?.let { return TmdbResult.Ok(it) }
        @Suppress("UNCHECKED_CAST")
        return flights.run("L|$key") {
            fetch(feed.path, mapOf("page" to p.toString(), "language" to LANGUAGE, "include_adult" to "false")) { TmdbParser.page(it, feed.media) }
                .also { if (it is TmdbResult.Ok) lists.put(key, it.value, TTL_LIST) }
        } as TmdbResult<TmdbPage>
    }

    override suspend fun search(type: MediaType, query: String, page: Int): TmdbResult<TmdbPage> {
        val q = query.trim().take(160)
        if (q.length < 2) return TmdbResult.Ok(TmdbPage(emptyList(), 1, 1))
        return catalogue("/search/${type.path}", type, page, mapOf("query" to q))
    }
    override suspend fun discover(type: MediaType, genreId: Int, page: Int): TmdbResult<TmdbPage> {
        if (genreId <= 0) return TmdbResult.Failure(TmdbError.NOT_FOUND)
        return catalogue("/discover/${type.path}", type, page, mapOf("with_genres" to genreId.toString(), "sort_by" to "popularity.desc"))
    }
    override suspend fun categoryFeed(type: MediaType, genreId: Int, kind: CategoryFeed, page: Int, today: String): TmdbResult<TmdbPage> {
        if (genreId <= 0) return TmdbResult.Failure(TmdbError.NOT_FOUND)
        if (kind == CategoryFeed.TRENDING) return feed(if (type == MediaType.MOVIE) Feed.TRENDING_MOVIE else Feed.TRENDING_TV, page)
        return catalogue("/discover/${type.path}", type, page, CategoryQueries.discover(type, genreId, kind, today))
    }
    override suspend fun latestTv(page: Int, today: String): TmdbResult<TmdbPage> =
        catalogue("/discover/tv", MediaType.TV, page, mapOf("sort_by" to "first_air_date.desc", "first_air_date.lte" to today, "include_null_first_air_dates" to "false"))

    private suspend fun catalogue(path: String, type: MediaType, page: Int, extra: Map<String, String>): TmdbResult<TmdbPage> {
        if (!config.isConfigured) return TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
        val query = extra + mapOf("page" to page.coerceIn(1, MAX_PAGE).toString(), "language" to LANGUAGE, "include_adult" to "false")
        val key = path + query.entries.joinToString { it.key + "=" + it.value }
        lists.get(key)?.let { return TmdbResult.Ok(it) }
        return fetch(path, query) { TmdbParser.page(it, type) }.also { if (it is TmdbResult.Ok) lists.put(key, it.value, TTL_LIST) }
    }
    override suspend fun genreList(type: MediaType): Map<Int, String> {
        if (!config.isConfigured) return emptyMap()
        val key = type.path
        genreCache.get(key)?.let { return it }
        val r = fetch("/genre/${type.path}/list", mapOf("language" to LANGUAGE)) { root -> root.obj()?.get("genres").list().mapNotNull { it.obj() }.mapNotNull { o ->
            val id = o.int("id") ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            id to name
        }.toMap() }
        return (r as? TmdbResult.Ok)?.value.orEmpty().also { if (it.isNotEmpty()) genreCache.put(key, it, TTL_GENRES) }
    }

    override suspend fun details(ref: TitleRef): TmdbResult<TmdbDetails> {
        if (!config.isConfigured) return TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
        detail.get(ref.key)?.let { return TmdbResult.Ok(it) }
        val append = (if (ref.type == MediaType.MOVIE) "release_dates,external_ids" else "content_ratings,external_ids") + ",credits,recommendations,similar,videos"
        @Suppress("UNCHECKED_CAST")
        return flights.run("D|${ref.key}") {
            fetch("/${ref.type.path}/${ref.id}", mapOf("language" to LANGUAGE, "append_to_response" to append)) { TmdbParser.details(it, ref) }
                .also { if (it is TmdbResult.Ok) detail.put(ref.key, it.value, TTL_DETAIL) }
        } as TmdbResult<TmdbDetails>
    }

    override suspend fun season(ref: TitleRef, season: Int): TmdbResult<List<TmdbEpisode>> {
        if (!config.isConfigured) return TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
        if (ref.type != MediaType.TV || season !in 0..999) return TmdbResult.Failure(TmdbError.NOT_FOUND)
        val key = "${ref.key}|s$season"
        seasons.get(key)?.let { return TmdbResult.Ok(it) }
        @Suppress("UNCHECKED_CAST")
        return flights.run("S|$key") {
            fetch("/tv/${ref.id}/season/$season", mapOf("language" to LANGUAGE)) { TmdbParser.season(it) }.also { if (it is TmdbResult.Ok) seasons.put(key, it.value, TTL_DETAIL) }
        } as TmdbResult<List<TmdbEpisode>>
    }

    override suspend fun episodeCredits(ref: TitleRef, season: Int, episode: Int): TmdbResult<List<TmdbPerson>> {
        if (!config.isConfigured) return TmdbResult.Failure(TmdbError.NOT_CONFIGURED)
        if (ref.type != MediaType.TV || season !in 0..999 || episode !in 1..9999) return TmdbResult.Failure(TmdbError.NOT_FOUND)
        val key = "${ref.key}|s$season|e$episode"
        episodeCast.get(key)?.let { return TmdbResult.Ok(it) }
        @Suppress("UNCHECKED_CAST")
        return flights.run("EC|$key") {
            fetch("/tv/${ref.id}/season/$season/episode/$episode/credits", mapOf("language" to LANGUAGE)) { TmdbParser.episodeCredits(it) }.also { if (it is TmdbResult.Ok) episodeCast.put(key, it.value, TTL_DETAIL) }
        } as TmdbResult<List<TmdbPerson>>
    }

    override suspend fun genres(): Map<Int, String> {
        if (!config.isConfigured) return emptyMap()
        genreCache.get("all")?.let { return it }
        val merged = LinkedHashMap<Int, String>()
        for (t in MediaType.values()) {
            @Suppress("UNCHECKED_CAST")
            val r = flights.run("G|${t.path}") { fetch("/genre/${t.path}/list", mapOf("language" to LANGUAGE)) { TmdbParser.genres(it) } } as TmdbResult<Map<Int, String>>
            if (r is TmdbResult.Ok) merged.putAll(r.value) else return emptyMap()   // partial genres would mislabel; skip them (not cached)
        }
        genreCache.put("all", merged, TTL_GENRES)
        return merged
    }

    private suspend fun <T> fetch(path: String, query: Map<String, String>, parse: (Any?) -> T?): TmdbResult<T> {
        val r = http.get(path, query)
        val err = when {
            r.code == 0 -> TmdbError.NETWORK
            r.code == 401 || r.code == 403 -> TmdbError.UNAUTHORIZED
            r.code == 404 -> TmdbError.NOT_FOUND
            r.code == 429 -> TmdbError.RATE_LIMITED
            r.code !in 200..299 -> TmdbError.SERVER
            else -> null
        }
        if (err != null) return TmdbResult.Failure(err)
        val value = parse(Json.parse(r.body)) ?: return TmdbResult.Failure(TmdbError.BAD_RESPONSE)
        lastSuccessMs = clock()
        return TmdbResult.Ok(value)
    }

    companion object {
        const val LANGUAGE = "en-US"
        const val MAX_PAGE = 20          // bounded pagination: at most 20 pages (400 titles) per feed
        const val MAX_LISTS = 64; const val MAX_DETAILS = 160; const val MAX_EPISODE_CREDITS = 200
        const val TTL_LIST = 15 * 60_000L; const val TTL_DETAIL = 6 * 3_600_000L; const val TTL_GENRES = 24 * 3_600_000L
    }
}

/** Parses TMDB v3 JSON into the models. Tolerant of missing fields; rejects entries without an id or title. */
internal object TmdbParser {
    fun page(root: Any?, fixed: MediaType?): TmdbPage? {
        val o = root.obj() ?: return null
        val results = o["results"] as? List<*> ?: return null
        val items = results.mapNotNull { title(it.obj(), fixed) }
        return TmdbPage(items, o.int("page") ?: 1, o.int("total_pages") ?: 1)
    }

    private fun title(o: Map<String, Any?>?, fixed: MediaType?): TmdbTitle? {
        o ?: return null
        val type = fixed ?: MediaType.fromPath(o.str("media_type")) ?: return null          // trending/all also lists people: skipped
        if (o.bool("adult")) return null
        val id = o.int("id")?.takeIf { it > 0 } ?: return null
        val name = (if (type == MediaType.MOVIE) o.str("title") else o.str("name")) ?: return null
        return TmdbTitle(
            TitleRef(type, id), name, o.str("overview").orEmpty(), o.str("poster_path"), o.str("backdrop_path"),
            if (type == MediaType.MOVIE) o.str("release_date") else o.str("first_air_date"),
            o.dbl("vote_average"), o.int("vote_count") ?: 0, o["genre_ids"].list().mapNotNull { (it as? Double)?.toInt() },
        )
    }

    fun season(root: Any?): List<TmdbEpisode>? {
        val list = root.obj()?.get("episodes") as? List<*> ?: return null
        return list.mapNotNull { e -> e.obj()?.let { o -> o.int("episode_number")?.takeIf { it in 1..9999 }?.let { n -> TmdbEpisode(n, o.str("name") ?: "Episode $n", o.str("overview").orEmpty(), o.str("air_date"), o.int("runtime")?.takeIf { it > 0 }, o.str("still_path")) } } }.take(200)
    }

    private fun titles(section: Any?, type: MediaType): List<TmdbTitle> = (section.obj()?.get("results") as? List<*>)?.mapNotNull { title(it.obj(), type) }?.take(MAX_RELATED) ?: emptyList()

    private fun person(o: Map<String, Any?>, role: String?): TmdbPerson? {
        val id = o.int("id")?.takeIf { it > 0 } ?: return null
        val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TmdbPerson(id, name.take(80), role?.trim().orEmpty().take(80), o.str("profile_path"))
    }

    fun cast(list: Any?): List<TmdbPerson> = (list as? List<*>)?.mapNotNull { e -> e.obj()?.let { person(it, it.str("character")) } }?.distinctBy { it.id }?.take(MAX_CAST) ?: emptyList()

    /** Director, then writers, then producers (real jobs only; cast is never relabelled as crew). Series creators come first. */
    fun crew(o: Map<String, Any?>, movie: Boolean): List<TmdbPerson> {
        val creators = if (movie) emptyList() else (o["created_by"] as? List<*>)?.mapNotNull { e -> e.obj()?.let { person(it, "Creator") } } ?: emptyList()
        val all = (o["credits"].obj()?.get("crew") as? List<*>)?.mapNotNull { e -> e.obj()?.let { c -> c.str("job")?.let { job -> person(c, job) } } } ?: emptyList()
        fun pick(jobs: Set<String>, label: (String) -> String, max: Int) = all.filter { it.role in jobs }.map { it.copy(role = label(it.role)) }.distinctBy { it.id to it.role }.take(max)
        val rest = pick(setOf("Director"), { "Director" }, 3) + pick(setOf("Screenplay", "Writer", "Story", "Novel"), { it }, 3) + pick(setOf("Producer", "Executive Producer"), { it }, 4)
        return (creators.take(3) + rest).distinctBy { it.id to it.role }.take(MAX_CREW)
    }

    fun episodeCredits(root: Any?): List<TmdbPerson>? {
        val o = root.obj() ?: return null
        return cast(o["cast"]) .let { regular -> (regular + cast(o["guest_stars"])).distinctBy { it.id }.take(MAX_CAST) }
    }

    /** An official YouTube trailer: only a well-formed 11-character key is ever kept. */
    fun trailer(videos: Any?): String? = (videos.obj()?.get("results") as? List<*>)?.mapNotNull { it.obj() }
        ?.filter { it.str("site") == "YouTube" && it.str("type") == "Trailer" && (it.str("key") ?: "").matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { l -> l.firstOrNull { it.bool("official") }?.str("key") }

    const val MAX_CAST = 20; const val MAX_CREW = 10; const val MAX_RELATED = 20

    fun genres(root: Any?): Map<Int, String>? {
        val list = root.obj()?.get("genres") as? List<*> ?: return null
        return list.mapNotNull { g -> g.obj()?.let { o -> o.int("id")?.let { id -> o.str("name")?.let { id to it } } } }.toMap()
    }

    fun details(root: Any?, ref: TitleRef): TmdbDetails? {
        val o = root.obj() ?: return null
        val movie = ref.type == MediaType.MOVIE
        val title = (if (movie) o.str("title") else o.str("name")) ?: return null
        if (o.int("id") != ref.id || o.bool("adult")) return null
        val cert: String? = if (movie) {
            val byCountry = o["release_dates"].obj()?.get("results").list().mapNotNull { it.obj() }.associate { c ->
                (c.str("iso_3166_1") ?: "") to c["release_dates"].list().mapNotNull { it.obj()?.str("certification") }.firstOrNull()
            }
            Certification.pick(byCountry)
        } else {
            Certification.pick(o["content_ratings"].obj()?.get("results").list().mapNotNull { it.obj() }.associate { (it.str("iso_3166_1") ?: "") to it.str("rating") })
        }
        return TmdbDetails(
            ref, title, o.str("tagline"), o.str("overview").orEmpty(), o.str("poster_path"), o.str("backdrop_path"),
            if (movie) o.str("release_date") else o.str("first_air_date"), o.dbl("vote_average"), o.int("vote_count") ?: 0,
            o["genres"].list().mapNotNull { it.obj()?.str("name") },
            if (movie) o.int("runtime")?.takeIf { it > 0 } else o["episode_run_time"].list().firstNotNullOfOrNull { (it as? Double)?.toInt() }?.takeIf { it > 0 },
            o.int("number_of_seasons"), o.int("number_of_episodes"), o.str("status"), cert,
            (if (movie) o.str("imdb_id") else o["external_ids"].obj()?.str("imdb_id"))?.takeIf { it.matches(Regex("tt[0-9]{1,10}")) },
            cast = cast(o["credits"].obj()?.get("cast")), crew = crew(o, movie),
            recommendations = titles(o["recommendations"], ref.type), similar = titles(o["similar"], ref.type), trailerKey = trailer(o["videos"]),
            spokenLanguages = o["spoken_languages"].list().mapNotNull { value -> value.obj()?.let { language ->
                language.str("iso_639_1")?.takeIf { it.matches(Regex("[a-z]{2,3}")) }?.let { code ->
                    TmdbLanguage(code, language.str("english_name"), language.str("name"))
                }
            } }.distinctBy { it.code },
            originalLanguage = o.str("original_language")?.takeIf { it.matches(Regex("[a-z]{2,3}")) },
        )
    }
}
