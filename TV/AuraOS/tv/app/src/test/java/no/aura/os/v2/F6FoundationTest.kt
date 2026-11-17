package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.tmdb.*
import no.aura.os.v2.watch.*
import org.junit.Assert.*
import org.junit.Test

class TmdbCreditsTest {
    private val detailsJson = """{"id":7,"title":"Seven","overview":"o","release_date":"2020-01-01","genres":[],"release_dates":{"results":[]},
      "credits":{"cast":[{"id":1,"name":"Ann Actor","character":"Jack","profile_path":"/a.jpg","order":0},{"id":2,"name":"Bob Actor","character":"Elena","profile_path":null},{"id":1,"name":"Ann Actor","character":"Dup"},{"id":0,"name":"Bad"},{"id":3,"name":"  "}],
        "crew":[{"id":10,"name":"Dee Director","job":"Director","profile_path":"/d.jpg"},{"id":11,"name":"Wes Writer","job":"Screenplay"},{"id":12,"name":"Pat Producer","job":"Producer"},{"id":13,"name":"Cam Camera","job":"Director of Photography"},{"id":12,"name":"Pat Producer","job":"Executive Producer"}]},
      "recommendations":{"results":[{"id":21,"title":"Rec One","poster_path":"/r.jpg","release_date":"2021-01-01"},{"id":22,"title":"Adult Rec","adult":true}]},
      "similar":{"results":[{"id":31,"title":"Sim One"}]},
      "videos":{"results":[{"site":"Vimeo","type":"Trailer","key":"abcdefghijk"},{"site":"YouTube","type":"Teaser","key":"teaserteas1"},{"site":"YouTube","type":"Trailer","key":"bad key!","official":true},{"site":"YouTube","type":"Trailer","key":"A1b2C3d4E5f","official":true}]}}"""
    private fun details(json: String = detailsJson) = runBlocking { (repo(FakeHttp(mutableMapOf("/movie/7" to HttpResponse(200, json)))).details(TitleRef(MediaType.MOVIE, 7)) as TmdbResult.Ok).value }

    @Test fun cast_is_real_deduplicated_and_ordered() {
        val c = details().cast; assertEquals(listOf("Ann Actor", "Bob Actor"), c.map { it.name }); assertEquals("Jack", c[0].role); assertEquals("/a.jpg", c[0].profilePath); assertNull(c[1].profilePath)
    }
    @Test fun crew_keeps_only_real_creative_jobs_in_order_and_never_cast() {
        val crew = details().crew
        assertEquals(listOf("Director" to "Dee Director", "Screenplay" to "Wes Writer", "Producer" to "Pat Producer", "Executive Producer" to "Pat Producer"), crew.map { it.role to it.name })
        assertTrue(crew.none { it.name == "Ann Actor" || it.name == "Cam Camera" })
    }
    @Test fun series_creators_come_first() {
        val tv = """{"id":8,"name":"Eight","created_by":[{"id":50,"name":"Cree Ator","profile_path":"/c.jpg"}],"content_ratings":{"results":[]},"credits":{"cast":[],"crew":[{"id":51,"name":"Pro Ducer","job":"Executive Producer"}]}}"""
        val d = runBlocking { (repo(FakeHttp(mutableMapOf("/tv/8" to HttpResponse(200, tv)))).details(TitleRef(MediaType.TV, 8)) as TmdbResult.Ok).value }
        assertEquals(listOf("Creator" to "Cree Ator", "Executive Producer" to "Pro Ducer"), d.crew.map { it.role to it.name }); assertTrue(d.cast.isEmpty())
    }
    @Test fun missing_credit_sections_give_empty_lists_not_placeholders() {
        val d = details("""{"id":7,"title":"Seven","release_dates":{"results":[]}}"""); assertTrue(d.cast.isEmpty() && d.crew.isEmpty() && d.recommendations.isEmpty() && d.similar.isEmpty()); assertNull(d.trailerKey)
    }
    @Test fun recommendations_skip_adult_and_keep_the_media_type() {
        val d = details(); assertEquals(listOf("tmdb:movie:21"), d.recommendations.map { it.ref.key }); assertEquals(listOf("tmdb:movie:31"), d.similar.map { it.ref.key })
    }
    @Test fun only_a_well_formed_official_youtube_trailer_is_kept() { assertEquals("A1b2C3d4E5f", details().trailerKey) }
    @Test fun one_details_request_carries_credits_recommendations_and_videos() {
        val http = FakeHttp(mutableMapOf("/movie/7" to HttpResponse(200, detailsJson))); runBlocking { repo(http).details(TitleRef(MediaType.MOVIE, 7)) }
        assertEquals(1, http.calls.get()); assertTrue(http.seen.single().contains("credits,recommendations,similar,videos"))
    }
    @Test fun episode_stills_are_parsed_and_use_a_small_size() = runBlocking {
        val season = """{"episodes":[{"episode_number":4,"name":"The Broken","overview":"o","still_path":"/s4.jpg","runtime":57}]}"""
        val e = ((repo(FakeHttp(mutableMapOf("/tv/8/season/2" to HttpResponse(200, season)))).season(TitleRef(MediaType.TV, 8), 2)) as TmdbResult.Ok).value.single()
        assertEquals("/s4.jpg", e.stillPath); assertEquals("https://image.tmdb.org/t/p/w300/s4.jpg", TmdbImages.url(TmdbImages.STILL, e.stillPath)); assertEquals("w185", TmdbImages.PROFILE)
    }
    @Test fun episode_credits_merge_cast_and_guests_and_are_cached_separately() = runBlocking {
        val json = """{"cast":[{"id":1,"name":"Reg","character":"Hero"}],"guest_stars":[{"id":2,"name":"Guest","character":"Visitor"},{"id":1,"name":"Reg","character":"Again"}]}"""
        val http = FakeHttp(mutableMapOf("/tv/8/season/2/episode/4/credits" to HttpResponse(200, json))); val r = repo(http); val ref = TitleRef(MediaType.TV, 8)
        assertEquals(listOf("Reg", "Guest"), (r.episodeCredits(ref, 2, 4) as TmdbResult.Ok).value.map { it.name }); r.episodeCredits(ref, 2, 4); assertEquals(1, http.calls.get())
        assertTrue(r.episodeCredits(TitleRef(MediaType.MOVIE, 8), 2, 4) is TmdbResult.Failure); assertTrue(r.episodeCredits(ref, 2, 0) is TmdbResult.Failure)
    }
    @Test fun episode_credits_and_related_are_gated_for_children() = runBlocking {
        val tvD = """{"id":8,"name":"Eight","content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}}"""
        val http = FakeHttp(mutableMapOf("/tv/8" to HttpResponse(200, tvD), "/tv/8/season/1/episode/1/credits" to HttpResponse(200, """{"cast":[{"id":1,"name":"A","character":"B"}]}"""),
            "/movie/21" to HttpResponse(200, """{"id":21,"title":"G Film","release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"G"}]}]}}"""),
            "/movie/22" to HttpResponse(200, """{"id":22,"title":"R Film","release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"R"}]}]}}""")))
        val svc = RealCatalogService(repo(http)); val ref = TitleRef(MediaType.TV, 8)
        assertTrue(svc.episodeCredits(ref, 1, 1, ADULT) is TmdbResult.Ok); assertTrue(svc.episodeCredits(ref, 1, 1, KID) is TmdbResult.Failure)
        val titles = listOf(TmdbTitle(TitleRef(MediaType.MOVIE, 21), "G Film", "", null, null, null, null, 0, emptyList()), TmdbTitle(TitleRef(MediaType.MOVIE, 22), "R Film", "", null, null, null, null, 0, emptyList()))
        assertEquals(listOf("tmdb:movie:21"), svc.related(titles, KID).map { it.id }); assertEquals(2, svc.related(titles, ADULT).size)
    }
}

class WatchStoreTest {
    private val series = TitleRef(MediaType.TV, 1396); private val movie = TitleRef(MediaType.MOVIE, 603)
    @Test fun keys_are_stable_and_validated() {
        assertEquals("tmdb:tv:1396:s2:e4", WatchKeys.episode(series, 2, 4)); assertNull(WatchKeys.episode(movie, 1, 1)); assertNull(WatchKeys.episode(series, 1, 0)); assertEquals("tmdb:movie:603", WatchKeys.title(movie))
        for (bad in listOf("", "x", "tmdb:movie:", "tmdb:tv:1:s1", "tmdb:tv:1:s1:e1:extra", "../../etc", "tmdb:movie:1 ")) assertFalse(bad, WatchKeys.isValid(bad))
    }
    @Test fun watchlist_and_watched_are_different_records() {
        val s = MemoryWatchStore(); val k = WatchKeys.title(movie)
        assertTrue(s.setWatchlist("p1", k, true)); assertTrue(s.inWatchlist("p1", k)); assertFalse(s.isWatched("p1", k))
        assertTrue(s.setWatched("p1", k, true)); assertTrue(s.isWatched("p1", k)); assertTrue(s.setWatchlist("p1", k, false)); assertTrue(s.isWatched("p1", k)); assertFalse(s.inWatchlist("p1", k))
    }
    @Test fun profiles_never_see_or_change_each_others_records() {
        val s = MemoryWatchStore(); val k = WatchKeys.episode(series, 1, 1)!!
        s.setWatched("kid", k, true); s.setWatchlist("kid", WatchKeys.title(series), true)
        assertFalse(s.isWatched("adult", k)); assertTrue(s.watchlist("adult").isEmpty()); s.setWatched("adult", k, false); assertTrue(s.isWatched("kid", k))
        s.purge("kid"); assertTrue(s.watched("kid").isEmpty() && s.watchlist("kid").isEmpty())
    }
    @Test fun invalid_keys_and_blank_profiles_are_rejected_and_the_store_is_bounded() {
        val s = MemoryWatchStore(); assertFalse(s.setWatched("p", "not-a-key", true)); assertFalse(s.setWatched("", WatchKeys.title(movie), true))
        for (i in 1..MemoryWatchStore.LIMIT) assertTrue(s.setWatchlist("p", "tmdb:movie:$i", true)); assertFalse(s.setWatchlist("p", "tmdb:movie:999999", true)); assertTrue(s.setWatchlist("p", "tmdb:movie:1", true))
    }
    @Test fun marking_unwatched_removes_only_that_episode() {
        val s = MemoryWatchStore(); val a = WatchKeys.episode(series, 1, 1)!!; val b = WatchKeys.episode(series, 1, 2)!!
        s.setWatched("p", a, true); s.setWatched("p", b, true); s.setWatched("p", a, false); assertEquals(setOf(b), s.watched("p"))
    }
}

class DetailsLogicTest {
    private fun ep(n: Int, air: String? = "2020-01-01") = no.aura.os.v2.tmdb.TmdbEpisode(n, "E$n", "", air, 40)
    @org.junit.Test fun runtime() {
        val r = no.aura.os.v2.details.DetailsLogic::runtimeLabel
        org.junit.Assert.assertEquals("1h 5m", r(65)); org.junit.Assert.assertEquals("2h", r(120)); org.junit.Assert.assertEquals("45m", r(45))
        org.junit.Assert.assertEquals("10h 5m", r(605)); org.junit.Assert.assertNull(r(0)); org.junit.Assert.assertNull(r(null))
    }
    @org.junit.Test fun tabs() {
        org.junit.Assert.assertEquals(listOf("Details", "Aura Recommended", "What's next?"), no.aura.os.v2.details.DetailsTab.forType(no.aura.os.v2.tmdb.MediaType.MOVIE).map { it.label })
        org.junit.Assert.assertEquals(listOf("Latest TV Shows", "Aura Recommended", "What's next?"), no.aura.os.v2.details.DetailsTab.forType(no.aura.os.v2.tmdb.MediaType.TV).map { it.label })
    }
    @org.junit.Test fun episodes() {
        val L = no.aura.os.v2.details.DetailsLogic
        org.junit.Assert.assertEquals("S2:E4", L.episodeCode(2, 4))
        org.junit.Assert.assertEquals(3, L.defaultEpisode(listOf(ep(1), ep(2), ep(3)), { it < 3 }))
        org.junit.Assert.assertEquals(1, L.defaultEpisode(listOf(ep(1), ep(2)), { true }))
        org.junit.Assert.assertNull(L.defaultEpisode(emptyList(), { false }))
        org.junit.Assert.assertTrue(L.isAired(null, "2026-11-12")); org.junit.Assert.assertTrue(L.isAired("2026-11-12", "2026-11-12"))
        org.junit.Assert.assertFalse(L.isAired("2026-12-01", "2026-11-12"))
        org.junit.Assert.assertEquals("Continue Watching", L.playLabel(no.aura.os.v2.tmdb.MediaType.TV, true))
        org.junit.Assert.assertEquals("Play", L.playLabel(no.aura.os.v2.tmdb.MediaType.MOVIE, true))
    }
    @org.junit.Test fun castLabelsAreHonest() {
        val L = no.aura.os.v2.details.DetailsLogic
        org.junit.Assert.assertEquals("Series Cast", L.castSectionTitle(false, 2, 4))
        org.junit.Assert.assertTrue(L.castSectionTitle(true, 2, 4).startsWith("Cast in This Episode"))
        org.junit.Assert.assertEquals("Mark Unwatched", L.mark(true)); org.junit.Assert.assertEquals("Remove from Watchlist", L.watchlistLabel(true))
    }
}

class CodeInputTest {
    private val C = no.aura.os.v2.settings.CodeInput
    @org.junit.Test fun normalizesCaseSeparatorsAndLength() {
        org.junit.Assert.assertEquals("AB12CD34", C.normalize("ab12cd34"))
        org.junit.Assert.assertEquals("AB12CD34", C.normalize(" ab12-cd34\n"))
        org.junit.Assert.assertEquals("AB12CD34", C.normalize("ab12cd34zzzz"))
        org.junit.Assert.assertEquals("AB12", C.normalize("ab!12é"))
    }
    @org.junit.Test fun flagsOnlyRealRejects() {
        org.junit.Assert.assertFalse(C.hadRejected("ab12-cd34 "))
        org.junit.Assert.assertTrue(C.hadRejected("ab!12")); org.junit.Assert.assertTrue(C.hadRejected("é"))
    }
    @org.junit.Test fun completeAndMessages() {
        org.junit.Assert.assertTrue(C.isComplete("AB12CD34")); org.junit.Assert.assertFalse(C.isComplete("AB12CD3")); org.junit.Assert.assertFalse(C.isComplete("ab12cd34"))
        org.junit.Assert.assertEquals("4 of 8 characters.", C.message("AB12", false))
        org.junit.Assert.assertTrue(C.message("AB12", true).startsWith("Only letters"))
        org.junit.Assert.assertTrue(C.message("AB12CD34", false).contains("Save"))
    }
    @org.junit.Test fun savedCodeStillParsesViaUltraCode() {
        org.junit.Assert.assertNotNull(no.aura.os.v2.ultramax.UltraCode.parse(C.normalize("zx90 qw12")))
    }
}

class DetailsLayoutTest {
    private val L = no.aura.os.v2.details.DetailsLayout
    @org.junit.Test fun heroDoesNotConsumeViewport() {
        // 1080-high canvas: hero + tab row must leave room for at least one full content row (episode card ~ 290dp).
        org.junit.Assert.assertTrue(L.aboveContentDp(1080f) + 290 <= 1080)
        org.junit.Assert.assertTrue(L.heroHeightDp(1080f) < 1080 / 2)
    }
    @org.junit.Test fun sectionsPerTab() {
        org.junit.Assert.assertEquals(listOf(no.aura.os.v2.details.DetailsSection.CAST, no.aura.os.v2.details.DetailsSection.CREW, no.aura.os.v2.details.DetailsSection.ADVISORY), L.sections(no.aura.os.v2.tmdb.MediaType.MOVIE, no.aura.os.v2.details.DetailsTab.DETAILS))
        org.junit.Assert.assertEquals(listOf(no.aura.os.v2.details.DetailsSection.SEASONS), L.sections(no.aura.os.v2.tmdb.MediaType.TV, no.aura.os.v2.details.DetailsTab.SEASONS))
        org.junit.Assert.assertEquals(listOf(no.aura.os.v2.details.DetailsSection.RELATED), L.sections(no.aura.os.v2.tmdb.MediaType.MOVIE, no.aura.os.v2.details.DetailsTab.NEXT))
        org.junit.Assert.assertTrue(L.sections(no.aura.os.v2.tmdb.MediaType.TV, no.aura.os.v2.details.DetailsTab.DETAILS).isEmpty())
        val keys = no.aura.os.v2.details.DetailsSection.values().map { it.key }; org.junit.Assert.assertEquals(keys.size, keys.toSet().size)   // stable, unique list keys
    }
    @org.junit.Test fun episodeMemoryPerSeason() {
        val m = no.aura.os.v2.details.EpisodeMemory()
        m.remember(1, 4); m.remember(2, 7)
        org.junit.Assert.assertEquals(4, m.restore(1, listOf(1, 2, 3, 4, 5)))
        org.junit.Assert.assertEquals(7, m.recall(2))
        org.junit.Assert.assertNull(m.restore(2, listOf(1, 2)))       // episode no longer listed
        org.junit.Assert.assertNull(m.recall(3))
        m.clear(); org.junit.Assert.assertNull(m.recall(1))
    }
}
