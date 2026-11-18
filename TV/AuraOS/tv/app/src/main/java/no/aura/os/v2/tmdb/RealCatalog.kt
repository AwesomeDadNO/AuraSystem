package no.aura.os.v2.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import no.aura.os.v2.brand.BrandLabelSpec
import no.aura.os.v2.brand.BrandLabels
import no.aura.os.v2.data.*
import no.aura.os.v2.profiles.ChildPolicy
import no.aura.os.v2.profiles.Profile

/** The pages that show TMDB content in Real Mode. */
enum class RealPage { HOME, MOVIES, TV, BOX }

/**
 * Source of the person's own viewing progress (Watch Next, Simkl, AuraBox history ... later). TMDB does NOT know what anyone watched, so
 * Continue Watching is empty until a real source exists; nothing is ever invented.
 */
interface WatchProgressSource { suspend fun continueWatching(profileId: String): List<ContinueItem> }
object NoWatchProgress : WatchProgressSource { override suspend fun continueWatching(profileId: String): List<ContinueItem> = emptyList() }

/** One page of content plus the paging state of each shelf. Immutable: loading more returns a new value. */
data class RealContent(val content: ShelfPageContent, val nextPage: Map<String, Int> = emptyMap(), val more: Set<String> = emptySet())

sealed interface CatalogResult {
    data class Ready(val value: RealContent) : CatalogResult
    /** Everything was fetched but nothing is allowed/available for this profile. */
    object Empty : CatalogResult
    data class Failed(val error: TmdbError) : CatalogResult
}

sealed interface DetailsResult {
    data class Ok(val details: TmdbDetails) : DetailsResult
    /** The profile's content restrictions do not allow this title (or its rating is unknown). */
    object Blocked : DetailsResult
    data class Failed(val error: TmdbError) : DetailsResult
}

/**
 * Builds Home / Movies / TV Shows / AuraBox from the shared [TmdbRepository]. Child profiles: every title is checked against
 * [ChildPolicy] through its certification BEFORE it can reach the UI (details are cached, so each title costs one request); a lookup that
 * fails counts as blocked. The repository itself knows nothing about profiles, so cached data never carries an authorization decision.
 */
class RealCatalogService(
    private val repo: TmdbRepository, private val watch: WatchProgressSource = NoWatchProgress,
    private val mediaServers: no.aura.os.v2.mediaserver.MediaServerService? = null,
    private val mediaPlayback: no.aura.os.v2.mediaserver.ServerLibraryPlayback? = null,
    private val heroCount: Int = HERO_COUNT, private val maxPerShelf: Int = MAX_PER_SHELF, private val art: ArtSizes = ArtSizes.Small,
) {
    private class Spec(val id: String, val title: String, val feed: Feed, val poster: Boolean = true)

    private fun specs(page: RealPage): List<Spec> = when (page) {
        RealPage.HOME -> listOf(Spec("latest", "Aura Latest", Feed.NOW_PLAYING), Spec("trending", "Trending on Aura", Feed.TRENDING_ALL),
            Spec("popular-movies", "Popular Movies", Feed.POPULAR_MOVIE), Spec("popular-tv", "Popular TV Shows", Feed.POPULAR_TV))
        RealPage.MOVIES -> listOf(Spec("now-playing", "Now Playing", Feed.NOW_PLAYING), Spec("popular", "Popular Movies", Feed.POPULAR_MOVIE),
            Spec("top", "Top Rated Movies", Feed.TOP_MOVIE), Spec("trending", "Trending Movies", Feed.TRENDING_MOVIE))
        RealPage.TV -> listOf(Spec("trending", "Trending Series", Feed.TRENDING_TV), Spec("popular", "Popular TV Shows", Feed.POPULAR_TV),
            Spec("top", "Top Rated Series", Feed.TOP_TV), Spec("airing", "On The Air", Feed.AIRING_TV))
        RealPage.BOX -> listOf(Spec("trending-movies", "Trending Movies", Feed.TRENDING_MOVIE, true), Spec("trending-tv", "Trending Series", Feed.TRENDING_TV, true),
            Spec("popular-movies", "Popular Movies", Feed.POPULAR_MOVIE, true), Spec("popular-tv", "Popular Series", Feed.POPULAR_TV, true),
            Spec("top-movies", "Top Rated Movies", Feed.TOP_MOVIE, true), Spec("top-tv", "Top Rated Series", Feed.TOP_TV, true))
    }

    private fun heroFeed(page: RealPage) = when (page) { RealPage.HOME -> Feed.TRENDING_ALL; RealPage.MOVIES, RealPage.BOX -> Feed.TRENDING_MOVIE; RealPage.TV -> Feed.TRENDING_TV }
    private fun heroLabel(page: RealPage): BrandLabelSpec = if (page == RealPage.BOX) BrandLabels.AURABOX else BrandLabels.RECOMMENDED

    suspend fun load(page: RealPage, profile: Profile): CatalogResult = no.aura.os.v2.diag.Diag.timed("tmdb.catalogue.ready") { loadImpl(page, profile) }

    private suspend fun loadImpl(page: RealPage, profile: Profile): CatalogResult = coroutineScope {
        val sp = specs(page)
        val genresJob = async { repo.genres() }
        val heroJob = async { repo.feed(heroFeed(page)) }
        val shelfJobs = sp.map { s -> async { s to repo.feed(s.feed) } }
        val genres = genresJob.await(); val heroRes = heroJob.await(); val fetched = shelfJobs.awaitAll()
        val firstError = (listOf(heroRes) + fetched.map { it.second }).filterIsInstance<TmdbResult.Failure>().firstOrNull()?.error
        val anyOk = fetched.any { it.second is TmdbResult.Ok } || heroRes is TmdbResult.Ok
        if (!anyOk) return@coroutineScope CatalogResult.Failed(firstError ?: TmdbError.NETWORK)

        // visible (child-checked) titles; the hero and shelves are filtered independently but the hero's ids are not repeated in shelves
        val heroTitles = ((heroRes as? TmdbResult.Ok)?.value?.items.orEmpty()).filter { it.backdropPath != null && it.overview.isNotBlank() }.let { visible(profile, it, heroCount) }.take(heroCount)
        val used = heroTitles.mapTo(HashSet()) { it.ref }
        val nextPage = HashMap<String, Int>(); val more = HashSet<String>()
        val shelves = ArrayList<Shelf>(); var fallbackHero: List<TmdbTitle> = emptyList()
        for ((s, res) in fetched) {
            val p = (res as? TmdbResult.Ok)?.value ?: continue
            val avoid = p.items.filter { it.ref !in used }.let { if (it.size >= MIN_AFTER_DEDUPE) it else p.items }     // avoid repeats when practical
            val items = visible(profile, avoid, maxPerShelf).take(maxPerShelf)
            if (items.isEmpty()) continue
            items.forEach { used.add(it.ref) }
            if (fallbackHero.isEmpty()) fallbackHero = items.filter { it.overview.isNotBlank() }
            nextPage[s.id] = p.page + 1; if (p.hasMore) more.add(s.id)
            shelves.add(Shelf(s.id, s.title, items.map { ContinueItem(mediaItem(it, s.poster), 0f, "") }, continueStyle = false, poster = s.poster))
        }
        if (page == RealPage.HOME) {
            val stored = watch.continueWatching(profile.id)
            // Progress is local to a profile, but a child profile still needs a fresh certification gate.
            val cont = if (profile.isChild) stored.filter { entry ->
                val ref = TitleRef.parse(entry.item.id)
                ref != null && (repo.details(ref) as? TmdbResult.Ok)?.value?.certification?.let { ChildPolicy.allows(profile, it) } == true
            } else stored
            if (cont.isNotEmpty()) shelves.add(0, Shelf("continue", "Continue Watching", cont, continueStyle = true))
        }

        if ((page == RealPage.MOVIES || page == RealPage.TV) && mediaServers != null && mediaPlayback != null && !profile.isChild) {
            val accounts = withContext(Dispatchers.IO) { mediaServers.accounts(profile.id) }
            for (acc in accounts) {
                val verifyRes = withContext(Dispatchers.IO) { mediaServers.verifyFresh(profile.id, acc) }
                val isOnline = verifyRes is no.aura.os.v2.mediaserver.MediaResult.Ok
                val libsRes = withContext(Dispatchers.IO) { mediaPlayback.libraries(profile.id, acc) }
                val libs = (libsRes as? no.aura.os.v2.mediaserver.MediaResult.Ok<*>)?.value as? List<*> ?: emptyList<Any?>()
                val targetType = if (page == RealPage.MOVIES) no.aura.os.v2.mediaserver.LibraryType.MOVIES else no.aura.os.v2.mediaserver.LibraryType.SHOWS
                val matchingLib = libs.mapNotNull { it as? no.aura.os.v2.mediaserver.MediaLibrary }.firstOrNull { it.type == targetType }
                if (matchingLib != null) {
                    val itemsRes = withContext(Dispatchers.IO) { mediaPlayback.items(profile.id, acc, matchingLib, 0, 20) }
                    val entries = ((itemsRes as? no.aura.os.v2.mediaserver.MediaResult.Ok<*>)?.value as? no.aura.os.v2.mediaserver.MediaPage)?.items.orEmpty()
                    if (entries.isNotEmpty()) {
                        val contItems = entries.map { entry ->
                            ContinueItem(
                                item = MediaItem(
                                    id = "server:${acc.serverId}|${entry.id}",
                                    title = entry.title,
                                    subtitle = entry.year?.toString() ?: entry.seriesTitle,
                                    artwork = Artwork(imageUrl = entry.imagePath ?: ""),
                                    overview = ""
                                ),
                                progress = 0f,
                                remainingLabel = ""
                            )
                        }
                        val statusText = if (isOnline) "🟢 Online" else "🔴 Offline"
                        val shelfTitle = "Latest on ${acc.kind.title}  $statusText"
                        shelves.add(0, Shelf("server:${acc.serverId}", shelfTitle, contItems, continueStyle = false, poster = true))
                    }
                }
            }
        }
        if (heroTitles.isEmpty() && shelves.none { !it.continueStyle }) return@coroutineScope CatalogResult.Empty
        val heroSource = heroTitles.ifEmpty { fallbackHero.filter { it.backdropPath != null }.take(heroCount) }
        if (heroSource.isEmpty()) return@coroutineScope CatalogResult.Empty          // the hero needs a real backdrop; none allowed -> truthful empty state
        val hero = heroSource.map { heroEntry(it, genres, heroLabel(page)) }
        CatalogResult.Ready(RealContent(ShelfPageContent(hero, shelves, when (page) {
            RealPage.MOVIES, RealPage.TV -> {
                val type = if (page == RealPage.MOVIES) MediaType.MOVIE else MediaType.TV
                repo.genreList(type).map { (id, name) -> CategoryCard("genre:${type.path}:$id", name) }
            }
            else -> emptyList()
        }), nextPage, more))
    }

    /** Loads the next TMDB page of one shelf (called when focus nears the end of the row). Failures leave the content unchanged. */
    suspend fun more(page: RealPage, shelfId: String, current: RealContent, profile: Profile): RealContent {
        val spec = specs(page).firstOrNull { it.id == shelfId } ?: return current
        if (shelfId !in current.more) return current
        val shelf = current.content.shelves.firstOrNull { it.id == shelfId } ?: return current
        if (shelf.items.size >= MAX_SHELF_TOTAL) return current.copy(more = current.more - shelfId)
        val p = (repo.feed(spec.feed, current.nextPage[shelfId] ?: return current) as? TmdbResult.Ok)?.value ?: return current
        val have = shelf.items.mapTo(HashSet()) { it.item.id }
        val add = visible(profile, p.items.filter { it.ref.key !in have }, maxPerShelf).map { ContinueItem(mediaItem(it, spec.poster), 0f, "") }
        val newShelf = shelf.copy(items = shelf.items + add)
        return RealContent(
            current.content.copy(shelves = current.content.shelves.map { if (it.id == shelfId) newShelf else it }),
            current.nextPage + (shelfId to p.page + 1), if (p.hasMore) current.more else current.more - shelfId,
        )
    }

    /** A small real library listing; every title is verified against the profile policy before display. */
    suspend fun savedTitles(keys: Set<String>, profile: Profile): List<MediaItem> = coroutineScope {
        val gate = Semaphore(4)
        keys.take(40).mapNotNull(TitleRef::parse).map { ref ->
            async {
                gate.withPermit {
                    val d = (repo.details(ref) as? TmdbResult.Ok)?.value
                    if (d == null || !ChildPolicy.allows(profile, d.certification)) null
                    else MediaItem(ref.key, d.title,
                        Artwork(imageUrl = TmdbImages.url(art.poster, d.posterPath ?: d.backdropPath)), subtitle = d.year,
                        focusArtwork = Artwork(imageUrl = TmdbImages.url(TmdbImages.CARD_BACKDROP, d.backdropPath)))
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun search(type: MediaType, query: String, page: Int, profile: Profile): TmdbResult<Pair<List<MediaItem>, Boolean>> =
        when (val r = repo.search(type, query, page)) {
            is TmdbResult.Failure -> r
            is TmdbResult.Ok -> TmdbResult.Ok(visible(profile, r.value.items, MAX_PER_SHELF).map { mediaItem(it, true) } to r.value.hasMore)
        }

    suspend fun category(type: MediaType, genre: Int, page: Int, profile: Profile): TmdbResult<Pair<List<MediaItem>, Boolean>> =
        when (val r = repo.discover(type, genre, page)) {
            is TmdbResult.Failure -> r
            is TmdbResult.Ok -> TmdbResult.Ok(visible(profile, r.value.items, MAX_PER_SHELF).map { mediaItem(it, true) } to r.value.hasMore)
        }

    /** Profile checks happen after shared raw caches; failed rows remain identifiable and retryable. */
    suspend fun categoryPage(type: MediaType, genre: Int, profile: Profile, today: String = java.time.LocalDate.now().toString()): CategoryContent {
        var value = CategoryContent(ShelfPageContent(emptyList(), CategoryFeed.values().map { Shelf(it.name, it.label, emptyList(), continueStyle = false, poster = true) }), emptyMap(), CategoryFeed.values().toSet())
        for (feed in CategoryFeed.values()) value = categoryMore(type, genre, feed, value, profile, today)
        return value
    }

    suspend fun categoryMore(type: MediaType, genre: Int, feed: CategoryFeed, current: CategoryContent, profile: Profile,
        today: String = java.time.LocalDate.now().toString()): CategoryContent {
        if (feed !in current.more) return current
        val shelf = current.content.shelves.firstOrNull { it.id == feed.name } ?: return current
        val page = current.nextPage[feed] ?: 1
        if (page > 20 || shelf.items.size >= MAX_SHELF_TOTAL) return current.copy(more = current.more - feed)
        return when (val result = repo.categoryFeed(type, genre, feed, page, today)) {
            is TmdbResult.Failure -> current.copy(errors = current.errors + (feed to result.error))
            is TmdbResult.Ok -> {
                val have = shelf.items.mapTo(HashSet()) { it.item.id }
                val titles = visible(profile, result.value.items.filter { CategoryQueries.accepts(it, type, genre, feed, today) && it.ref.key !in have }, MAX_PER_SHELF)
                    .distinctBy { it.ref }.take(minOf(MAX_PER_SHELF, MAX_SHELF_TOTAL - shelf.items.size))
                val updated = shelf.copy(items = shelf.items + titles.map { ContinueItem(mediaItem(it, true), 0f, "") })
                val hero = current.content.hero.ifEmpty { titles.filter { it.backdropPath != null && it.overview.isNotBlank() }.take(heroCount).map { heroEntry(it, emptyMap(), BrandLabels.RECOMMENDED) } }
                current.copy(content = current.content.copy(hero = hero, shelves = current.content.shelves.map { if (it.id == feed.name) updated else it }),
                    nextPage = current.nextPage + (feed to page + 1),
                    more = if (result.value.hasMore && page < 20 && updated.items.size < MAX_SHELF_TOTAL) current.more else current.more - feed,
                    errors = current.errors - feed)
            }
        }
    }

    /** Details for the title screen. A child profile can never open a title its policy blocks, whatever route led here. */
    suspend fun details(ref: TitleRef, profile: Profile): DetailsResult = when (val r = repo.details(ref)) {
        is TmdbResult.Failure -> DetailsResult.Failed(r.error)
        is TmdbResult.Ok -> if (ChildPolicy.allows(profile, r.value.certification)) DetailsResult.Ok(r.value) else DetailsResult.Blocked
    }

    /** Episodes of a season. Same gate as [details]: a child profile gets nothing for a title its policy blocks. */
    suspend fun season(ref: TitleRef, season: Int, profile: Profile): TmdbResult<List<TmdbEpisode>> = when (val d = repo.details(ref)) {
        is TmdbResult.Failure -> d
        is TmdbResult.Ok -> if (ChildPolicy.allows(profile, d.value.certification)) repo.season(ref, season) else TmdbResult.Failure(TmdbError.NOT_FOUND)
    }

    /** Cast of one episode, gated like [season]. */
    suspend fun episodeCredits(ref: TitleRef, season: Int, episode: Int, profile: Profile): TmdbResult<List<TmdbPerson>> = when (val d = repo.details(ref)) {
        is TmdbResult.Failure -> d
        is TmdbResult.Ok -> if (ChildPolicy.allows(profile, d.value.certification)) repo.episodeCredits(ref, season, episode) else TmdbResult.Failure(TmdbError.NOT_FOUND)
    }

    /** New series premieres, not popular series or unverified provider releases. */
    suspend fun latestTv(profile: Profile, today: String = java.time.LocalDate.now().toString()): TmdbResult<List<MediaItem>> =
        when(val result = repo.latestTv(1, today)) {
            is TmdbResult.Failure -> result
            is TmdbResult.Ok -> {
                val eligible = result.value.items.filter { title -> title.ref.type == MediaType.TV &&
                    title.date?.let { date -> runCatching { java.time.LocalDate.parse(date) }.getOrNull()?.toString()?.let { it <= today } } == true
                }.distinctBy { it.ref }.sortedByDescending { it.date }
                TmdbResult.Ok(visible(profile, eligible, MAX_PER_SHELF).take(MAX_PER_SHELF).map { mediaItem(it, true) })
            }
        }

    /** Related titles for a details page, passed through ChildPolicy before they reach the UI. */
    suspend fun related(titles: List<TmdbTitle>, profile: Profile): List<MediaItem> = visible(profile, titles, MAX_PER_SHELF).map { mediaItem(it, poster = true) }

    private suspend fun visible(profile: Profile, titles: List<TmdbTitle>, want: Int): List<TmdbTitle> {
        if (!profile.isChild) return titles
        val candidates = titles.take(CHILD_LOOKUPS)                      // bounded: every candidate costs one (cached) details request
        val gate = Semaphore(CHILD_PARALLEL)
        val certs = coroutineScope { candidates.map { t -> async { gate.withPermit { (repo.details(t.ref) as? TmdbResult.Ok)?.value?.certification } } }.awaitAll() }
        return candidates.filterIndexed { i, _ -> ChildPolicy.allows(profile, certs[i]) }.take(want)
    }

    private fun mediaItem(t: TmdbTitle, poster: Boolean): MediaItem {
        val url = if (poster) TmdbImages.url(art.poster, t.posterPath ?: t.backdropPath) else TmdbImages.url(art.cardBackdrop, t.backdropPath ?: t.posterPath)
        return MediaItem(t.ref.key, t.title, Artwork(imageUrl = url), subtitle = t.year,
            focusArtwork = Artwork(imageUrl = TmdbImages.url(TmdbImages.CARD_BACKDROP, t.backdropPath)), overview = t.overview, metadata = listOfNotNull(t.year, rating(t.voteAverage, t.voteCount)))
    }

    private fun heroEntry(t: TmdbTitle, genres: Map<Int, String>, label: BrandLabelSpec) = HeroEntry(
        t.ref.key, label, t.title, t.overview, t.ref.type.label.uppercase(),
        listOfNotNull(*t.genreIds.mapNotNull { genres[it] }.take(2).toTypedArray(), t.year, rating(t.voteAverage, t.voteCount)),
        Artwork(imageUrl = TmdbImages.url(TmdbImages.HERO, t.backdropPath)),
        actions = listOf(HeroAction("details", "More details", HeroIcon.INFO), HeroAction("play", "Play", HeroIcon.PLAY, primary = true)),
    )

    companion object {
        const val HERO_COUNT = 5; const val MAX_PER_SHELF = 20; const val MAX_SHELF_TOTAL = 60
        const val CHILD_LOOKUPS = 24; const val CHILD_PARALLEL = 4; const val MIN_AFTER_DEDUPE = 6
        fun rating(v: Double?, votes: Int): String? = v?.takeIf { votes > 0 && it > 0 }?.let { "★ " + (Math.round(it * 10) / 10.0) }
    }
}
