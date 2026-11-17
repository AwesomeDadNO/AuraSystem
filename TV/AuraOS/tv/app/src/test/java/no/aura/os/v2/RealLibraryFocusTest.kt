package no.aura.os.v2

import java.io.File
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.pages.LibrarySections
import no.aura.os.v2.tmdb.MediaType
import org.junit.Assert.*
import org.junit.Test

class RealLibraryFocusTest {
    private val source get() = File("src/main/java/no/aura/os/v2/pages/RealLibraryPage.kt").readText()

    @Test fun entry_target_is_composed_even_when_saved_rows_are_below_the_viewport() {
        // Watchlist TV follows both continue rows and the other three saved sections.
        // A requester on its first card (or the final empty state) is not attached at entry.
        val code = source
        val target = code.indexOf("Modifier.focusRequester(entry).focusable()")
        assertTrue("Entry must precede the lazy content", target >= 0 && target < code.indexOf("LazyColumn("))
        assertEquals("One stable target across loading, empty, and populated states", 1,
            Regex("focusRequester\\(entry\\)").findAll(code).count())
    }

    @Test fun continue_cards_keep_repeated_title_occurrences_without_duplicate_lazy_keys() {
        val code = source
        assertTrue(code.contains("itemsIndexed(watching"))
        assertTrue(code.contains("itemsIndexed(systemWatching"))
        assertFalse(code.contains("key={it.item.id}"))
    }

    @Test fun saved_titles_remain_separate_movies_and_series_in_title_order() {
        fun title(id: String, name: String) = MediaItem(id, name, Artwork())
        val input = listOf(title("tmdb:tv:1", "Series"), title("tmdb:movie:2", "Zulu"),
            title("tmdb:movie:3", "Alpha"))
        assertEquals(listOf("Alpha", "Zulu"), LibrarySections.titles(input, MediaType.MOVIE).map { it.title })
        assertEquals(listOf("Series"), LibrarySections.titles(input, MediaType.TV).map { it.title })
    }
}
