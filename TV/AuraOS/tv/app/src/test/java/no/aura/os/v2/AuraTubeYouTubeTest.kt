package no.aura.os.v2

import no.aura.os.v2.brand.BrandLabels
import no.aura.os.v2.modules.*
import no.aura.os.v2.pages.AuraTubeState
import no.aura.os.v2.settings.*
import no.aura.os.v2.shell.*
import no.aura.os.v2.youtube.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Runs a suspend block whose fakes never actually suspend (no coroutines library needed). */
private fun <T> blocking(block: suspend () -> T): T {
    var r: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { r = it })
    return r!!.getOrThrow()
}

class AuraTubeYouTubeTest {
    // ---- module, navigation, registry ---------------------------------------------------------------------------------------------
    @Test fun auratube_sits_between_auraamp_and_services_and_obeys_its_switch() {
        assertEquals(1, NavModel.groups(ModuleFlags()).size)
        val off = ModulePrefs(auraTube = false).toFlags()
        assertFalse(Route.AURATUBE in NavModel.items(off)); assertFalse(Route.AURATUBE in NavModel.reachable(off))     // shell guard sends the person Home
        assertEquals(listOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV), NavModel.items(off))
        assertEquals("AuraTube", SettingsCatalog.modules.first { it.id == "module.AURATUBE" }.title)
        assertEquals(ModuleKey.values().size, ModuleRegistry.all.size); ModuleKey.values().forEach { assertEquals(it, ModuleRegistry.of(it).key) }
        assertEquals(Route.AURATUBE, ModuleRegistry.of(ModuleKey.AURATUBE).route); assertEquals(ConnectionId.YOUTUBE, ModuleRegistry.of(ModuleKey.AURATUBE).connection)
    }

    @Test fun brand_heading_and_unfinished_contracts_are_declared_but_inactive() {
        assertEquals("AURA TUBE", BrandLabels.AURATUBE.text); assertEquals("AURA", BrandLabels.AURATUBE.primary); assertEquals("TUBE", BrandLabels.AURATUBE.accent)
        val d = ModuleRegistry.of(ModuleKey.AURATUBE)
        assertEquals(setOf(ModuleCapability.HOME_SHELF, ModuleCapability.GLOBAL_SEARCH), d.declared)
        assertTrue(d.active.isEmpty()); assertFalse(FeatureFlags.YOUTUBE_HOME_SHELF); assertFalse(FeatureFlags.YOUTUBE_GLOBAL_SEARCH)   // nothing is wired to Home/search until F4c
    }

    @Test fun top_navigation_fits_with_auratube_and_the_budget_test_would_catch_overflow() {
        val all = ModuleFlags()
        assertTrue("estimated ${NavMetrics.estimatedWidth(all)} of ${NavMetrics.CANVAS}", NavMetrics.slack(all) >= NavMetrics.MIN_SLACK)   // conservative estimate; the emulator is the authority
        assertEquals(23f, NavMetrics.itemPadding(all), 0f)                                                          // the approved padding is untouched while it fits
        assertTrue(NavMetrics.itemPadding(all, extraWidth = NavMetrics.slack(all) + 120f) < 23f)                                           // a future, wider nav shrinks padding instead of clipping
        assertTrue(NavMetrics.itemPadding(all, extraWidth = 5000f) >= NavMetrics.MIN_ITEM_PADDING)
        assertEquals(NavMetrics.estimatedWidth(all), NavMetrics.estimatedWidth(ModuleFlags(auraTube = false)), 0.01f) // rail module switches do not consume header width
    }

    // ---- honest status ------------------------------------------------------------------------------------------------------------
    @Test fun youtube_never_shows_connected_without_verification() {
        val none = DevConnections(YouTubeConfig("")).statuses().first { it.id == ConnectionId.YOUTUBE }
        val withKey = DevConnections(YouTubeConfig("AIza-test-not-a-real-key")).statuses().first { it.id == ConnectionId.YOUTUBE }
        assertEquals(ConnectionState.NOT_CONFIGURED, none.state); assertEquals(ConnectionState.NOT_CONFIGURED, withKey.state)   // a key alone is not a connection
        assertTrue(withKey.message!!.contains("not been verified")); assertFalse(withKey.message!!.contains("AIza")); assertTrue(none.message!!.contains("No YouTube API key"))
        assertEquals(10, DevConnections(YouTubeConfig("")).statuses().size)
        assertFalse(YouTubeConfig("secret").toString().contains("secret")); assertFalse(YouTubeConfig("").isConfigured); assertTrue(YouTubeConfig("k").isConfigured)
        assertTrue(AuraTubeState(false).headline.contains("isn't set up")); assertTrue(AuraTubeState(true).headline.contains("not verified"))
    }

    @Test fun without_a_key_every_call_fails_with_not_configured_and_no_data_is_invented() {
        val r = NotConfiguredYouTubeRepository
        assertEquals(YtResult.Failure(YouTubeError.NotConfigured), blocking { r.search(SearchRequest("music")) })
        assertEquals(YtResult.Failure(YouTubeError.NotConfigured), blocking { r.videos(listOf("dQw4w9WgXcQ")) })
        assertEquals(YtResult.Failure(YouTubeError.NotConfigured), blocking { r.channel("UC123") })
    }

    // ---- models & validation -------------------------------------------------------------------------------------------------------
    @Test fun video_ids_queries_and_thumbnails() {
        assertTrue(VideoId.isValid("dQw4w9WgXcQ")); assertTrue(VideoId.isValid("a_b-C123456"))
        listOf(null, "", "short", "dQw4w9WgXcQ1", "dQw4w9WgXc!", "dQw4w9WgXc ", "dQw4w9WgXc\n", "../../etc/pw").forEach { assertFalse("$it", VideoId.isValid(it)) }
        assertEquals("lo fi beats", SearchQuery.normalize("  lo   fi\tbeats  ")); assertNull(SearchQuery.normalize(" a ")); assertNull(SearchQuery.normalize("   "))
        assertEquals(SearchQuery.MAX_LENGTH, SearchQuery.normalize("x".repeat(500))!!.length)
        val t = Thumbnails(listOf(Thumbnail("a", 120, 90), Thumbnail("b", 320, 180), Thumbnail("c", 1280, 720)))
        assertEquals("b", t.bestFor(300)!!.url); assertEquals("a", t.bestFor(100)!!.url); assertEquals("c", t.bestFor(4000)!!.url); assertNull(Thumbnails().bestFor(100))   // never oversize
    }

    @Test fun playback_is_a_handoff_to_the_official_app_only() {
        val spec = PlaybackHandoff.watch("dQw4w9WgXcQ")!!
        assertEquals("android.intent.action.VIEW", spec.action); assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", spec.uri); assertEquals("com.google.android.youtube.tv", spec.packageName)
        assertNull(PlaybackHandoff.watch("x&autoplay=1&list=evil")); assertNull(PlaybackHandoff.watch(""))
    }

    // ---- quota, cache, guard -------------------------------------------------------------------------------------------------------
    private class FakeRepo : YouTubeRepository {
        var searches = 0; var videosCalls = 0; var next: YtResult<Page<SearchHit>> = YtResult.Ok(Page(emptyList(), "n1")); var channelResult: YtResult<ChannelSummary> = YtResult.Ok(ChannelSummary("UC1", "Chan"))
        override suspend fun search(request: SearchRequest): YtResult<Page<SearchHit>> { searches++; return next }
        override suspend fun videos(ids: List<String>): YtResult<List<VideoSummary>> { videosCalls++; return YtResult.Ok(ids.map { VideoSummary(it, "T", "c", "C") }) }
        override suspend fun channel(id: String) = channelResult
        override suspend fun channelPlaylists(channelId: String, pageToken: String?) = YtResult.Ok(Page(emptyList<PlaylistSummary>()))
        override suspend fun playlist(id: String) = YtResult.Ok(PlaylistSummary(id, "P"))
        override suspend fun playlistVideos(playlistId: String, pageToken: String?) = YtResult.Ok(Page(emptyList<VideoSummary>()))
    }

    @Test fun cache_serves_repeats_without_spending_quota_and_expires() {
        var now = 0L; val fake = FakeRepo(); val budget = QuotaBudget({ now }, dailyLimit = 1000); val repo = CachingYouTubeRepository(fake, budget, { now })
        repeat(3) { assertTrue(blocking { repo.search(SearchRequest("  Lo-Fi  ")) } is YtResult.Ok) }
        assertEquals(1, fake.searches); assertEquals(900, budget.remaining())                         // one 100-unit call for three screens
        blocking { repo.search(SearchRequest("lo-fi")) }; assertEquals(1, fake.searches)                    // normalised: same key
        blocking { repo.search(SearchRequest("lo-fi", pageToken = "n1")) }; assertEquals(2, fake.searches)  // another page is another request
        now += CachingYouTubeRepository.TTL_SEARCH + 1; blocking { repo.search(SearchRequest("lo-fi")) }; assertEquals(3, fake.searches)
        blocking { repo.videos(listOf("dQw4w9WgXcQ", "dQw4w9WgXcQ")) }; blocking { repo.videos(listOf("dQw4w9WgXcQ")) }; assertEquals(1, fake.videosCalls)   // de-duplicated
    }

    @Test fun invalid_requests_never_reach_the_api_or_cost_quota() {
        val fake = FakeRepo(); val budget = QuotaBudget(dailyLimit = 1000); val repo = CachingYouTubeRepository(fake, budget)
        val bad = YtResult.Failure(YouTubeError.InvalidRequest)
        assertEquals(bad, blocking { repo.search(SearchRequest("a")) }); assertEquals(bad, blocking { repo.videos(emptyList()) }); assertEquals(bad, blocking { repo.videos(listOf("nope")) })
        assertEquals(bad, blocking { repo.videos(List(51) { "dQw4w9WgX%02d".format(it % 100) }) }); assertEquals(bad, blocking { repo.channel("a b") }); assertEquals(bad, blocking { repo.playlist("../x") })
        assertEquals(0, fake.searches + fake.videosCalls); assertEquals(1000, budget.remaining())
    }

    @Test fun quota_guard_stops_calls_and_a_quota_error_exhausts_the_day_until_pacific_midnight() {
        var now = 1_700_000_000_000L; val fake = FakeRepo(); val budget = QuotaBudget({ now }, dailyLimit = 250); val repo = CachingYouTubeRepository(fake, budget, { now })
        assertTrue(blocking { repo.search(SearchRequest("one")) } is YtResult.Ok); assertTrue(blocking { repo.search(SearchRequest("two")) } is YtResult.Ok)
        assertEquals(YtResult.Failure(YouTubeError.BudgetGuard), blocking { repo.search(SearchRequest("three")) })   // 300 > 250: refused locally, never sent
        assertEquals(2, fake.searches)
        assertTrue(blocking { repo.channel("UC1") } is YtResult.Ok); assertEquals(49, budget.remaining())            // 1-unit calls still fit
        fake.channelResult = YtResult.Failure(YouTubeError.QuotaExceeded)
        assertEquals(YtResult.Failure(YouTubeError.QuotaExceeded), blocking { repo.channel("UC2") }); assertEquals(0, budget.remaining())
        assertEquals(YtResult.Failure(YouTubeError.BudgetGuard), blocking { repo.channel("UC3") })
        now += 24 * 3_600_000L; assertEquals(250, budget.remaining())                                         // next Pacific day
    }

    @Test fun not_found_is_cached_briefly_and_other_errors_are_not() {
        var now = 0L; val fake = FakeRepo(); val repo = CachingYouTubeRepository(fake, QuotaBudget({ now }), { now })
        fake.channelResult = YtResult.Failure(YouTubeError.NotFound)
        assertEquals(YtResult.Failure(YouTubeError.NotFound), blocking { repo.channel("UCgone") }); fake.channelResult = YtResult.Ok(ChannelSummary("UCgone", "Back"))
        assertEquals(YtResult.Failure(YouTubeError.NotFound), blocking { repo.channel("UCgone") })                   // negative cache
        now += CachingYouTubeRepository.TTL_MISSING + 1; assertTrue(blocking { repo.channel("UCgone") } is YtResult.Ok)
        fake.channelResult = YtResult.Failure(YouTubeError.Network)
        assertEquals(YtResult.Failure(YouTubeError.Network), blocking { repo.channel("UCnet") }); fake.channelResult = YtResult.Ok(ChannelSummary("UCnet", "Up"))
        assertTrue(blocking { repo.channel("UCnet") } is YtResult.Ok)                                              // transient errors are retried, not cached
    }

    @Test fun ttl_cache_is_bounded_lru() {
        var now = 0L; val c = TtlCache<String, String>(2) { now }
        c.put("a", "1", 100); c.put("b", "2", 100); c.get("a"); c.put("c", "3", 100)
        assertNull(c.get("b")); assertEquals("1", c.get("a")); assertEquals(2, c.size); now = 200; assertNull(c.get("a"))
    }

    // ---- secrets stay out of the source tree ----------------------------------------------------------------------------------------
    @Test fun no_api_keys_or_tokens_in_source_and_release_gets_no_dev_key() {
        val root = File("src").takeIf { it.isDirectory } ?: File("app/src")
        val patterns = listOf(Regex("AIza[0-9A-Za-z_\\-]{35}"), Regex("eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}"), Regex("(?i)(api[_-]?key|token|secret)\\s*=\\s*\"[A-Za-z0-9_\\-]{24,}\""))
        val hits = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "kts", "md", "json", "txt", "properties") }
            .flatMap { f -> patterns.filter { it.containsMatchIn(f.readText()) }.map { "${f.path}: ${it.pattern.take(12)}" } }.toList()
        assertTrue("possible secrets: $hits", hits.isEmpty())
        val gradle = (File("build.gradle.kts").takeIf { it.isFile } ?: File("app/build.gradle.kts")).readText()
        val release = gradle.substringAfter("release {").substringBefore("proguardFiles")
        assertTrue(release.contains("buildConfigField(\"String\", \"YOUTUBE_API_KEY\", \"\\\"\\\"\")")); assertFalse(release.contains("devValue"))
        assertTrue(gradle.substringAfter("debug {").substringBefore("release {").contains("devValue(\"YOUTUBE_API_KEY\")"))
    }
}
