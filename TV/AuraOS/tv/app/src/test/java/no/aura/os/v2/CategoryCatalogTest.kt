package no.aura.os.v2

import kotlinx.coroutines.*
import no.aura.os.v2.tmdb.*
import org.junit.Assert.*
import org.junit.Test

class CategoryCatalogTest {
    @Test fun distinct_discover_queries_and_date_bounds() {
        val http = FakeHttp(mutableMapOf("/discover/movie" to page(movieJson(1, "A")), "/discover/tv" to page(tvJson(1, "B")), "/trending/movie/week" to page(movieJson(1, "A"))))
        runBlocking {
            val r = repo(http)
            r.categoryFeed(MediaType.MOVIE, 28, CategoryFeed.TRENDING, 1, "2026-11-12")
            r.categoryFeed(MediaType.MOVIE, 28, CategoryFeed.POPULAR, 1, "2026-11-12")
            r.categoryFeed(MediaType.MOVIE, 28, CategoryFeed.NEW, 1, "2026-11-12")
            r.categoryFeed(MediaType.TV, 18, CategoryFeed.NEW, 1, "2026-11-12")
            r.categoryFeed(MediaType.MOVIE, 28, CategoryFeed.TOP_RATED, 1, "2026-11-12")
        }
        assertTrue(http.seen[0].startsWith("/trending/movie/week?"))
        assertTrue(http.seen[1].contains("sort_by=popularity.desc"))
        assertTrue(http.seen[2].contains("primary_release_date.lte=2026-11-12"))
        assertTrue(http.seen[3].contains("first_air_date.lte=2026-11-12"))
        assertTrue(http.seen[4].contains("vote_count.gte=100"))
    }

    @Test fun partial_failure_keeps_independent_rows_and_retry_is_honest() = runBlocking {
        val http = FakeHttp(mutableMapOf("/discover/movie" to page(movieJson(1, "A"))))
        val service = RealCatalogService(repo(http))
        val state = service.categoryPage(MediaType.MOVIE, 28, ADULT, "2026-11-12")
        assertEquals(setOf(CategoryFeed.TRENDING), state.errors.keys)
        assertEquals(3, state.content.shelves.count { it.items.isNotEmpty() })
        http.routes["/trending/movie/week"] = page(movieJson(2, "B"))
        val retried = service.categoryMore(MediaType.MOVIE, 28, CategoryFeed.TRENDING, state, ADULT, "2026-11-12")
        assertTrue(retried.errors.isEmpty())
        assertEquals("B", retried.content.shelves.first().items.single().item.title)
        assertEquals(state.content.shelves.drop(1), retried.content.shelves.drop(1))
    }

    @Test fun child_lookups_fail_closed_even_after_adult_cache() = runBlocking {
        val http = FakeHttp(mutableMapOf("/discover/movie" to page(movieJson(1, "A")), "/trending/movie/week" to page(movieJson(1, "A"))))
        val service = RealCatalogService(repo(http))
        assertTrue(service.categoryPage(MediaType.MOVIE, 28, ADULT).content.shelves.any { it.items.isNotEmpty() })
        assertTrue(service.categoryPage(MediaType.MOVIE, 28, KID).content.shelves.all { it.items.isEmpty() })
    }

    @Test fun page_twenty_is_a_hard_stop_even_when_genre_has_no_matches() = runBlocking {
        val http = FakeHttp(mutableMapOf("/discover/movie" to page(page = 20, total = 500)))
        val service = RealCatalogService(repo(http))
        val initial = service.categoryPage(MediaType.MOVIE, 28, ADULT)
        val before = initial.copy(nextPage = initial.nextPage + (CategoryFeed.POPULAR to 20))
        val after = service.categoryMore(MediaType.MOVIE, 28, CategoryFeed.POPULAR, before, ADULT)
        assertFalse(CategoryFeed.POPULAR in after.more)
        val calls = http.calls.get()
        assertEquals(after, service.categoryMore(MediaType.MOVIE, 28, CategoryFeed.POPULAR, after, ADULT))
        assertEquals(calls, http.calls.get())
    }

    @Test fun cancelling_category_cancels_its_waiting_network_work() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val http = object : TmdbHttp {
            override suspend fun get(path: String, query: Map<String, String>): HttpResponse {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = launch { RealCatalogService(repo(http, scope = scope)).categoryPage(MediaType.MOVIE, 28, ADULT) }
            withTimeout(2_000) { started.await() }
            job.cancelAndJoin()
            withTimeout(2_000) { cancelled.await() }
        } finally { scope.cancel() }
    }

    @Test fun filtering_paging_and_item_caps_are_enforced() = runBlocking {
        val http = object : TmdbHttp {
            override suspend fun get(path: String, query: Map<String, String>): HttpResponse {
                val start = ((query["page"]?.toInt() ?: 1) - 1) * 30
                return page(*(1..30).map { movieJson(start + it, "A") }.toTypedArray(), page = query["page"]?.toInt() ?: 1, total = 100)
            }
        }
        val service = RealCatalogService(repo(http))
        var state = service.categoryPage(MediaType.MOVIE, 28, ADULT)
        repeat(8) { state = service.categoryMore(MediaType.MOVIE, 28, CategoryFeed.POPULAR, state, ADULT) }
        assertEquals(60, state.content.shelves.first { it.id == CategoryFeed.POPULAR.name }.items.size)
        assertFalse(CategoryFeed.POPULAR in state.more)
        val title = TmdbTitle(TitleRef(MediaType.MOVIE, 1), "A", "", null, null, "2099-01-01", 9.0, 99, listOf(28))
        assertFalse(CategoryQueries.accepts(title, MediaType.MOVIE, 28, CategoryFeed.NEW, "2026-11-12"))
        assertFalse(CategoryQueries.accepts(title, MediaType.MOVIE, 28, CategoryFeed.TOP_RATED, "2026-11-12"))
        assertFalse(CategoryQueries.accepts(title, MediaType.MOVIE, 18, CategoryFeed.TRENDING, "2026-11-12"))
    }
}
