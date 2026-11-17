package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.search.*
import org.junit.Assert.*
import org.junit.Test

class GlobalSearchBehaviorTest {
    @Test fun failures_do_not_hide_other_groups_and_duplicate_ids_are_scoped() = runBlocking {
        val service = GlobalSearchService(mapOf(
            SearchKind.MOVIES to SearchSource { SearchBatch(listOf(SearchHit("1", "Movie", "TMDB", SearchKind.MOVIES, "tmdb:movie:1"))) },
            SearchKind.SERIES to SearchSource { error("offline") },
            SearchKind.APPS to SearchSource { SearchBatch(List(2) { SearchHit("1", "App", "Android TV", SearchKind.APPS, "app:one") }) }
        ))
        val result = service.search(SearchRequest("owner", "  blå  "))
        assertEquals("blå", result.query)
        assertEquals(1, result.groups[SearchKind.MOVIES]!!.hits.size)
        assertEquals(1, result.groups[SearchKind.APPS]!!.hits.size)
        assertNotNull(result.groups[SearchKind.SERIES]!!.message)
        assertNotNull(result.groups[SearchKind.MUSIC]!!.message)
    }
    @Test fun empty_or_short_queries_make_no_requests() = runBlocking {
        var calls = 0
        val service = GlobalSearchService(mapOf(SearchKind.MOVIES to SearchSource { calls++; SearchBatch(emptyList()) }))
        assertTrue(service.search(SearchRequest("p", " ")).groups.isEmpty())
        assertTrue(service.search(SearchRequest("p", "a")).groups.isEmpty())
        assertEquals(0, calls)
    }
    @Test fun source_receives_exact_profile_and_bounded_pagination() = runBlocking {
        var received: SearchRequest? = null
        val service = GlobalSearchService(mapOf(SearchKind.MUSIC to SearchSource { received = it; SearchBatch(emptyList()) }))
        service.search(SearchRequest("child", "Björk", 500, true))
        assertEquals(SearchRequest("child", "Björk", 20, true), received)
    }
    @Test fun music_parser_uses_stable_recording_and_artist_identity() {
        val hits = MusicBrainzSearch.parse("""{"recordings":[{"id":"12345678-1234-1234-1234-123456789abc","title":"Jóga","artist-credit":[{"name":"Björk","artist":{"id":"12345678-1234-1234-1234-123456789abd"}}]}]}""")
        assertEquals("Jóga", hits.single().title)
        assertEquals("Björk", hits.single().subtitle)
        assertEquals(SearchKind.MUSIC, hits.single().kind)
        assertTrue(MusicBrainzSearch.parse("""{"recordings":[{"id":"../bad","title":"No"}]}""").isEmpty())
    }
}
