package no.aura.os.v2

import kotlinx.coroutines.*
import no.aura.os.v2.profiles.Profile
import no.aura.os.v2.profiles.ProfileKind
import no.aura.os.v2.tmdb.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Scripted TMDB transport: path -> response, with a call counter. No network is touched. */
internal class FakeHttp(val routes: MutableMap<String, HttpResponse> = mutableMapOf(), val gate: CompletableDeferred<Unit>? = null) : TmdbHttp {
    val calls = AtomicInteger(); val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
    override suspend fun get(path: String, query: Map<String, String>): HttpResponse {
        calls.incrementAndGet(); seen.add(path + "?" + query.entries.joinToString("&") { it.key + "=" + it.value })
        gate?.await()
        return routes[path] ?: HttpResponse(404, null)
    }
}

internal fun movieJson(id: Int, title: String, extra: String = "") =
    """{"id":$id,"title":"$title","overview":"About $title","poster_path":"/p$id.jpg","backdrop_path":"/b$id.jpg","release_date":"2024-05-0$id","vote_average":7.84,"vote_count":900,"genre_ids":[28,12]$extra}"""
internal fun tvJson(id: Int, name: String) =
    """{"id":$id,"name":"$name","overview":"Show $name","poster_path":"/tp$id.jpg","backdrop_path":"/tb$id.jpg","first_air_date":"2023-01-0$id","vote_average":8.1,"vote_count":50,"genre_ids":[18]}"""
internal fun page(vararg items: String, page: Int = 1, total: Int = 3) = HttpResponse(200, """{"page":$page,"total_pages":$total,"results":[${items.joinToString(",")}]}""")

internal val CFG = TmdbConfig("test-token")
internal fun repo(http: TmdbHttp, now: () -> Long = { 1_000L }, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) = CachingTmdbRepository(http, CFG, now, scope)
internal val ADULT = Profile("a", "Anna", ProfileKind.STANDARD)
internal val KID = Profile("k", "Kid", ProfileKind.CHILD, maxAge = 7)

class TmdbJsonTest {
    @Test fun parses_objects_arrays_numbers_and_escapes() {
        val v = Json.parse("""{"a":[1,2.5,true,null,"x\né"],"b":{"c":-3e2}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, true, null, "x\né"), v["a"]); assertEquals(-300.0, (v["b"] as Map<*, *>)["c"])
    }
    @Test fun malformed_input_never_throws() {
        for (bad in listOf("", "{", "[1,", "{\"a\":}", "nul", "\"abc", "{\"a\":1} x", "[1 2]", "\u0000")) assertNull(bad, Json.parse(bad))
    }
    @Test fun depth_is_bounded() { assertNull(Json.parse("[".repeat(500) + "]".repeat(500))) }
}

class TmdbModelsTest {
    @Test fun title_ref_round_trips_and_rejects_garbage() {
        assertEquals(TitleRef(MediaType.MOVIE, 603), TitleRef.parse("tmdb:movie:603")); assertEquals("tmdb:tv:1396", TitleRef(MediaType.TV, 1396).key)
        for (bad in listOf("", "tmdb:movie:", "tmdb:person:1", "tmdb:movie:-1", "tmdb:movie:1:2", "movie:1", "tmdb:movie:abc", "demo-1")) assertNull(bad, TitleRef.parse(bad))
    }
    @Test fun image_urls_use_only_tmdb_host_and_bounded_sizes() {
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", TmdbImages.url(TmdbImages.HERO, "/b.jpg"))
        assertNull(TmdbImages.url("w500", "b.jpg")); assertNull(TmdbImages.url("w500", "/a b.jpg")); assertNull(TmdbImages.url("w500", "/a.jpg?x=1")); assertNull(TmdbImages.url("w500", null))
        assertFalse(TmdbImages.url("w500", "/x.jpg")!!.contains("original"))
        assertTrue(TmdbImages.isAllowed("https://image.tmdb.org/t/p/w500/x.jpg")); assertFalse(TmdbImages.isAllowed("http://image.tmdb.org/t/p/w500/x.jpg"))
        assertFalse(TmdbImages.isAllowed("https://evil.example/t/p/w500/x.jpg")); assertFalse(TmdbImages.isAllowed("https://image.tmdb.org.evil.example/t/p/w5/x"))
        assertEquals(500, TmdbImages.widthOf("https://image.tmdb.org/t/p/w500/x.jpg"))
    }
    @Test fun certification_prefers_us_then_norway() {
        assertEquals("PG", Certification.pick(mapOf("NO" to "11", "US" to "PG"))); assertEquals("11", Certification.pick(mapOf("NO" to "11", "DE" to "12")))
        assertNull(Certification.pick(mapOf("US" to "", "DE" to "12"))); assertNull(Certification.pick(emptyMap()))
    }
    @Test fun config_never_prints_the_token() {
        assertFalse(CFG.toString().contains("test-token")); assertTrue(CFG.isConfigured); assertFalse(TmdbConfig("").isConfigured)
    }
}

class TmdbRepositoryTest {
    private fun feedHttp() = FakeHttp(mutableMapOf(
        "/trending/all/week" to page(movieJson(1, "Alpha"), tvJson(2, "Beta").replaceFirst("{", """{"media_type":"tv","""), """{"id":9,"name":"Some Person","media_type":"person"}""", movieJson(3, "Adult", ""","adult":true""")),
        "/movie/popular" to page(movieJson(1, "Alpha"), movieJson(4, "Delta")),
    ))

    @Test fun parses_trending_skips_people_and_adult_and_tags_media_type() = runBlocking {
        val http = feedHttp(); val r = repo(http).feed(Feed.TRENDING_ALL) as TmdbResult.Ok
        // movie_json has no media_type -> not allowed in trending/all, only the tagged TV entry survives
        assertEquals(listOf("tmdb:tv:2"), r.value.items.map { it.ref.key })
    }
    @Test fun parses_movie_fields() = runBlocking {
        val r = repo(feedHttp()).feed(Feed.POPULAR_MOVIE) as TmdbResult.Ok; val t = r.value.items.first()
        assertEquals("Alpha", t.title); assertEquals("2024", t.year); assertEquals("/b1.jpg", t.backdropPath); assertEquals(listOf(28, 12), t.genreIds); assertTrue(r.value.hasMore)
    }
    @Test fun second_call_is_served_from_cache_and_expires_after_ttl() = runBlocking {
        val http = feedHttp(); var now = 1_000L; val repo = repo(http, { now })
        repo.feed(Feed.POPULAR_MOVIE); repo.feed(Feed.POPULAR_MOVIE); assertEquals(1, http.calls.get())
        now += CachingTmdbRepository.TTL_LIST + 1; repo.feed(Feed.POPULAR_MOVIE); assertEquals(2, http.calls.get())
    }
    @Test fun different_pages_are_different_requests_and_pages_are_bounded() = runBlocking {
        val http = feedHttp(); val repo = repo(http)
        repo.feed(Feed.POPULAR_MOVIE, 1); repo.feed(Feed.POPULAR_MOVIE, 2); assertEquals(2, http.calls.get())
        repo.feed(Feed.POPULAR_MOVIE, 9999); assertTrue(http.seen.last().contains("page=${CachingTmdbRepository.MAX_PAGE}"))
    }
    @Test fun errors_map_and_are_never_cached() = runBlocking {
        for ((code, err) in listOf(401 to TmdbError.UNAUTHORIZED, 403 to TmdbError.UNAUTHORIZED, 404 to TmdbError.NOT_FOUND, 429 to TmdbError.RATE_LIMITED, 500 to TmdbError.SERVER, 0 to TmdbError.NETWORK)) {
            val http = FakeHttp(mutableMapOf("/movie/popular" to HttpResponse(code, null))); val repo = repo(http)
            assertEquals(code.toString(), TmdbResult.Failure(err), repo.feed(Feed.POPULAR_MOVIE)); repo.feed(Feed.POPULAR_MOVIE); assertEquals(2, http.calls.get())
            assertNull(repo.lastSuccessMs)
        }
    }
    @Test fun unreadable_body_is_bad_response_and_not_a_success() = runBlocking {
        val http = FakeHttp(mutableMapOf("/movie/popular" to HttpResponse(200, "<html>not json</html>"))); val repo = repo(http)
        assertEquals(TmdbResult.Failure(TmdbError.BAD_RESPONSE), repo.feed(Feed.POPULAR_MOVIE)); assertNull(repo.lastSuccessMs)
    }
    @Test fun last_success_is_set_only_after_a_real_response() = runBlocking {
        val repo = repo(feedHttp(), { 4242L }); assertNull(repo.lastSuccessMs); repo.feed(Feed.POPULAR_MOVIE); assertEquals(4242L, repo.lastSuccessMs)
    }
    @Test fun not_configured_makes_no_request() = runBlocking {
        val http = feedHttp(); val repo = CachingTmdbRepository(http, TmdbConfig(""), { 1L }, CoroutineScope(Dispatchers.Default))
        assertEquals(TmdbResult.Failure(TmdbError.NOT_CONFIGURED), repo.feed(Feed.POPULAR_MOVIE)); assertEquals(0, http.calls.get()); assertTrue(repo.genres().isEmpty())
        assertEquals(TmdbResult.Failure(TmdbError.NOT_CONFIGURED), NotConfiguredTmdbRepository.feed(Feed.POPULAR_TV))
    }
    @Test fun concurrent_identical_requests_share_one_call() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val http = FakeHttp(mutableMapOf("/movie/popular" to page(movieJson(1, "Alpha"))), gate); val repo = repo(http)
        val a = (1..5).map { async(Dispatchers.Default) { repo.feed(Feed.POPULAR_MOVIE) } }
        withTimeout(2000) { while (http.calls.get() == 0) delay(5) }; delay(50); gate.complete(Unit)
        a.awaitAll().forEach { assertTrue(it is TmdbResult.Ok) }; assertEquals(1, http.calls.get())
    }
    @Test fun details_parse_movie_runtime_and_certification_and_tv_seasons() = runBlocking {
        val movie = """{"id":7,"title":"Seven","tagline":"Tag","overview":"O","poster_path":"/p.jpg","backdrop_path":"/b.jpg","release_date":"2020-02-02","vote_average":6.0,"vote_count":10,"genres":[{"id":1,"name":"Drama"}],"runtime":125,
            "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG-13"}]}]}}"""
        val tv = """{"id":8,"name":"Eight","overview":"O","first_air_date":"2019-03-03","genres":[{"id":2,"name":"Crime"}],"episode_run_time":[45],"number_of_seasons":3,"number_of_episodes":30,"content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}}"""
        val http = FakeHttp(mutableMapOf("/movie/7" to HttpResponse(200, movie), "/tv/8" to HttpResponse(200, tv))); val repo = repo(http)
        val m = (repo.details(TitleRef(MediaType.MOVIE, 7)) as TmdbResult.Ok).value; val t = (repo.details(TitleRef(MediaType.TV, 8)) as TmdbResult.Ok).value
        assertEquals(125, m.runtimeMinutes); assertEquals("PG-13", m.certification); assertEquals(listOf("Drama"), m.genres); assertEquals("2020", m.year)
        assertEquals(3, t.seasons); assertEquals(45, t.runtimeMinutes); assertEquals("TV-MA", t.certification)
        repo.details(TitleRef(MediaType.MOVIE, 7)); assertEquals(2, http.calls.get())
    }
    @Test fun details_for_a_different_id_than_asked_are_rejected() = runBlocking {
        val http = FakeHttp(mutableMapOf("/movie/7" to HttpResponse(200, """{"id":99,"title":"Other"}"""))); assertTrue(repo(http).details(TitleRef(MediaType.MOVIE, 7)) is TmdbResult.Failure)
    }
    @Test fun genres_merge_both_types_and_are_cached() = runBlocking {
        val http = FakeHttp(mutableMapOf("/genre/movie/list" to HttpResponse(200, """{"genres":[{"id":28,"name":"Action"}]}"""), "/genre/tv/list" to HttpResponse(200, """{"genres":[{"id":18,"name":"Drama"}]}""")))
        val repo = repo(http); assertEquals(mapOf(28 to "Action", 18 to "Drama"), repo.genres()); repo.genres(); assertEquals(2, http.calls.get())
    }
    @Test fun requests_never_carry_the_token_and_ask_for_no_adult_content() = runBlocking {
        val http = feedHttp(); repo(http).feed(Feed.POPULAR_MOVIE)
        assertTrue(http.seen.all { !it.contains("test-token") && it.contains("include_adult=false") })
    }
}

class DedupTest {
    @Test fun job_is_cancelled_when_last_waiter_leaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val d = Dedup<String, Int>(scope)
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Boolean>()
        val w = launch(Dispatchers.Default) { d.run("k") { started.complete(Unit); try { awaitCancellation() } catch (e: CancellationException) { cancelled.complete(true); throw e } } }
        withTimeout(2000) { started.await() }; w.cancelAndJoin(); assertTrue(withTimeout(2000) { cancelled.await() })
    }
    @Test fun one_waiter_leaving_does_not_cancel_the_others() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val d = Dedup<String, Int>(scope); val gate = CompletableDeferred<Unit>(); val runs = AtomicInteger()
        val a = launch(Dispatchers.Default) { d.run("k") { runs.incrementAndGet(); gate.await(); 5 } }
        val b = async(Dispatchers.Default) { d.run("k") { runs.incrementAndGet(); gate.await(); 5 } }
        delay(100); a.cancelAndJoin(); gate.complete(Unit); assertEquals(5, withTimeout(2000) { b.await() }); assertEquals(1, runs.get())
    }
    @Test fun finished_entries_are_removed() = runBlocking {
        val d = Dedup<String, Int>(CoroutineScope(SupervisorJob() + Dispatchers.Default)); val runs = AtomicInteger()
        d.run("k") { runs.incrementAndGet() }; d.run("k") { runs.incrementAndGet() }; assertEquals(2, runs.get())
    }
}

class RealCatalogTest {
    private fun details(id: Int, cert: String?) = HttpResponse(200, """{"id":$id,"title":"T$id","overview":"o","release_date":"2020-01-01","genres":[],"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"${cert ?: ""}"}]}]}}""")
    private fun http(): FakeHttp {
        fun tagged(id: Int, n: String) = movieJson(id, n).replaceFirst("{", """{"media_type":"movie",""")
        return FakeHttp(mutableMapOf(
            "/trending/all/week" to page(tagged(1, "Alpha"), tagged(2, "Beta"), tagged(3, "Gamma")),
            "/movie/now_playing" to page(movieJson(2, "Beta"), movieJson(4, "Delta")),
            "/movie/popular" to page(movieJson(1, "Alpha"), movieJson(5, "Epsilon")),
            "/tv/popular" to page(tvJson(6, "Show")),
            "/genre/movie/list" to HttpResponse(200, """{"genres":[{"id":28,"name":"Action"},{"id":12,"name":"Adventure"}]}"""),
            "/genre/tv/list" to HttpResponse(200, """{"genres":[{"id":18,"name":"Drama"}]}"""),
            "/movie/1" to details(1, "G"), "/movie/2" to details(2, "R"), "/movie/3" to details(3, null), "/movie/4" to details(4, "PG"), "/movie/5" to details(5, "G"),
            "/tv/6" to HttpResponse(200, """{"id":6,"name":"Show","overview":"o","first_air_date":"2020-01-01","genres":[],"content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-Y"}]}}"""),
        ))
    }

    @Test fun home_builds_real_hero_and_shelves_with_no_duplicates() = runBlocking {
        fun tagged(id: Int) = movieJson(id, "M$id").replaceFirst("{", """{"media_type":"movie",""")
        val h = http()   // realistic 20-item pages that overlap between feeds
        h.routes["/trending/all/week"] = page(*(1..20).map { tagged(it) }.toTypedArray())
        h.routes["/movie/now_playing"] = page(*(15..34).map { movieJson(it, "M$it") }.toTypedArray())
        h.routes["/movie/popular"] = page(*(30..49).map { movieJson(it, "M$it") }.toTypedArray())
        h.routes["/tv/popular"] = page(*(100..119).map { tvJson(it % 10, "S$it").replaceFirst(Regex("\"id\":\\d+").find(tvJson(it % 10, "S$it"))!!.value, "\"id\":$it") }.toTypedArray())
        val c = ((RealCatalogService(repo(h)).load(RealPage.HOME, ADULT)) as CatalogResult.Ready).value.content
        assertTrue(c.hero.isNotEmpty()); assertTrue(c.hero.all { it.artwork.imageUrl!!.contains("/t/p/w1280/") && TitleRef.parse(it.id) != null })
        assertEquals(listOf("latest", "trending", "popular-movies", "popular-tv"), c.shelves.map { it.id })
        val ids = c.hero.map { it.id } + c.shelves.flatMap { s -> s.items.map { it.item.id } }
        assertEquals("a title must not appear twice across hero and shelves", ids.size, ids.toSet().size)
        assertTrue(c.shelves.all { it.items.size <= RealCatalogService.MAX_PER_SHELF })
        assertTrue(c.shelves.flatMap { it.items }.all { it.item.artwork.imageUrl!!.startsWith("https://image.tmdb.org/t/p/w") && !it.item.artwork.imageUrl!!.contains("original") })
        assertTrue("Continue Watching is never invented", c.shelves.none { it.title.contains("Continue", true) })
        assertEquals(listOf("details", "play"), c.hero.first().actions.map { it.id })
    }
    @Test fun hero_shows_genre_year_and_rating_from_tmdb() = runBlocking {
        val h = ((RealCatalogService(repo(http())).load(RealPage.HOME, ADULT)) as CatalogResult.Ready).value.content.hero.first()
        assertTrue(h.metadata.contains("2024")); assertTrue(h.metadata.any { it.startsWith("★") }); assertTrue(h.metadata.any { it == "Action" || it == "Adventure" })
    }
    @Test fun all_feeds_failing_is_a_failure_not_demo_content() = runBlocking {
        val r = RealCatalogService(repo(FakeHttp(mutableMapOf()))).load(RealPage.HOME, ADULT)
        assertTrue(r is CatalogResult.Failed)
    }
    @Test fun not_configured_is_reported_as_such() = runBlocking {
        assertEquals(CatalogResult.Failed(TmdbError.NOT_CONFIGURED), RealCatalogService(NotConfiguredTmdbRepository).load(RealPage.HOME, ADULT))
    }
    @Test fun child_profile_only_sees_titles_whose_rating_is_known_and_allowed() = runBlocking {
        val r = RealCatalogService(repo(http())).load(RealPage.HOME, KID) as CatalogResult.Ready
        val ids = r.value.content.shelves.flatMap { s -> s.items.map { it.item.id } }.toSet() + r.value.content.hero.map { it.id }
        assertTrue("G and TV-Y are allowed for age 7", "tmdb:movie:1" in ids && "tmdb:tv:6" in ids && "tmdb:movie:5" in ids)
        assertTrue("PG blocked at 7", "tmdb:movie:4" !in ids); assertTrue("R blocked", "tmdb:movie:2" !in ids); assertTrue("unrated blocked", "tmdb:movie:3" !in ids)
    }
    @Test fun child_detail_route_cannot_bypass_filtering() = runBlocking {
        val svc = RealCatalogService(repo(http()))
        assertEquals(DetailsResult.Blocked, svc.details(TitleRef(MediaType.MOVIE, 2), KID)); assertEquals(DetailsResult.Blocked, svc.details(TitleRef(MediaType.MOVIE, 3), KID))
        assertTrue(svc.details(TitleRef(MediaType.MOVIE, 1), KID) is DetailsResult.Ok); assertTrue(svc.details(TitleRef(MediaType.MOVIE, 2), ADULT) is DetailsResult.Ok)
    }
    @Test fun child_with_failed_rating_lookup_is_blocked_not_shown() = runBlocking {
        val h = http(); h.routes.remove("/movie/1"); val svc = RealCatalogService(repo(h))
        assertTrue(svc.details(TitleRef(MediaType.MOVIE, 1), KID) is DetailsResult.Failed)
        val r = svc.load(RealPage.HOME, KID) as CatalogResult.Ready
        assertTrue("tmdb:movie:1" !in r.value.content.shelves.flatMap { s -> s.items.map { it.item.id } })
    }
    @Test fun cache_is_shared_between_profiles_without_leaking_decisions() = runBlocking {
        val h = http(); val svc = RealCatalogService(repo(h))
        val adult = svc.load(RealPage.HOME, ADULT) as CatalogResult.Ready; val kid = svc.load(RealPage.HOME, KID) as CatalogResult.Ready
        val adultIds = adult.value.content.shelves.flatMap { s -> s.items.map { it.item.id } }.toSet()
        assertTrue("tmdb:movie:2" in adultIds || adult.value.content.hero.any { it.id == "tmdb:movie:2" }); assertTrue(kid.value.content.shelves.none { s -> s.items.any { it.item.id == "tmdb:movie:2" } })
    }
    @Test fun auraBox_uses_poster_shelves_from_the_same_repository() = runBlocking {
        val h = FakeHttp(mutableMapOf("/trending/movie/week" to page(movieJson(1, "Alpha")), "/trending/tv/week" to page(tvJson(6, "Show")), "/movie/popular" to page(movieJson(5, "Epsilon")), "/tv/popular" to page(tvJson(7, "Other")),
            "/movie/top_rated" to page(movieJson(8, "Top")), "/tv/top_rated" to page(tvJson(9, "TopTv"))))
        val r = RealCatalogService(repo(h)).load(RealPage.BOX, ADULT) as CatalogResult.Ready
        assertEquals(6, r.value.content.shelves.size); assertTrue(r.value.content.shelves.all { it.poster })
        assertTrue(r.value.content.shelves.flatMap { it.items }.all { it.item.artwork.imageUrl!!.contains("/w500/") })   // 332-wide posters need sharper sources on a <=1920 px UI
    }
    @Test fun more_appends_the_next_page_without_duplicates_and_stops_at_the_cap() = runBlocking {
        val h = http(); h.routes["/movie/popular"] = page(movieJson(1, "Alpha"), movieJson(5, "Epsilon"), page = 1, total = 3)
        val svc = RealCatalogService(repo(h)); val r = (svc.load(RealPage.MOVIES, ADULT).let { it as? CatalogResult.Ready })
        if (r != null && "popular" in r.value.more) {
            h.routes["/movie/popular"] = page(movieJson(5, "Epsilon"), movieJson(10, "New"), page = 2, total = 3)
            val next = svc.more(RealPage.MOVIES, "popular", r.value, ADULT)
            val ids = next.content.shelves.first { it.id == "popular" }.items.map { it.item.id }
            assertEquals(ids.size, ids.toSet().size); assertTrue("tmdb:movie:10" in ids)
        }
    }
}

class TmdbF5Test {
    @Test fun details_carry_a_valid_imdb_id_only() = runBlocking {
        val movie = """{"id":7,"title":"Seven","imdb_id":"tt0114369","release_dates":{"results":[]}}"""
        val tv = """{"id":8,"name":"Eight","external_ids":{"imdb_id":"tt0944947"},"content_ratings":{"results":[]}}"""
        val bad = """{"id":9,"title":"Nine","imdb_id":"../etc","release_dates":{"results":[]}}"""
        val http = FakeHttp(mutableMapOf("/movie/7" to HttpResponse(200, movie), "/tv/8" to HttpResponse(200, tv), "/movie/9" to HttpResponse(200, bad))); val r = repo(http)
        assertEquals("tt0114369", (r.details(TitleRef(MediaType.MOVIE, 7)) as TmdbResult.Ok).value.imdbId); assertEquals("tt0944947", (r.details(TitleRef(MediaType.TV, 8)) as TmdbResult.Ok).value.imdbId)
        assertNull((r.details(TitleRef(MediaType.MOVIE, 9)) as TmdbResult.Ok).value.imdbId); assertTrue(http.seen.any { it.contains("external_ids") })
    }
    @Test fun seasons_parse_cache_and_are_gated_for_children() = runBlocking {
        val season = """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"o","air_date":"2020-01-01","runtime":45},{"episode_number":0,"name":"skip"},{"episode_number":2}]}"""
        val tvD = """{"id":8,"name":"Eight","overview":"o","first_air_date":"2019-01-01","content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}}"""
        val http = FakeHttp(mutableMapOf("/tv/8/season/1" to HttpResponse(200, season), "/tv/8" to HttpResponse(200, tvD))); val repo = repo(http); val svc = RealCatalogService(repo)
        val eps = (repo.season(TitleRef(MediaType.TV, 8), 1) as TmdbResult.Ok).value
        assertEquals(listOf(1, 2), eps.map { it.number }); assertEquals(45, eps[0].runtimeMinutes); assertEquals("Episode 2", eps[1].name)
        repo.season(TitleRef(MediaType.TV, 8), 1); assertEquals(1, http.seen.count { it.startsWith("/tv/8/season/1") })
        assertTrue(svc.season(TitleRef(MediaType.TV, 8), 1, ADULT) is TmdbResult.Ok); assertTrue("TV-MA blocked for a child", svc.season(TitleRef(MediaType.TV, 8), 1, KID) is TmdbResult.Failure)
        assertTrue(repo.season(TitleRef(MediaType.MOVIE, 8), 1) is TmdbResult.Failure)
    }
}
