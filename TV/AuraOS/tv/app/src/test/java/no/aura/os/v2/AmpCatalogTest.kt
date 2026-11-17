package no.aura.os.v2

import no.aura.os.v2.amp.AmpAlbum
import no.aura.os.v2.amp.AmpPlaylist
import no.aura.os.v2.amp.AmpSort
import org.junit.Assert.assertEquals
import org.junit.Test

class AmpCatalogTest {
    private val albums = listOf(
        AmpAlbum("1", "Missing", "Artist"),
        AmpAlbum("2", "Older", "Artist", releaseDate = "2020-01-01"),
        AmpAlbum("3", "Invalid", "Artist", releaseDate = "2026-02-30"),
        AmpAlbum("4", "Recent", "Artist", releaseDate = "2026-11-01"),
        AmpAlbum("5", "Same day", "Artist", releaseDate = "2026-11-01")
    )
    @Test fun latest_albums_use_real_dates_with_missing_dates_last() {
        assertEquals(listOf("4", "5", "2", "1", "3"), AmpSort.albums(albums, true).map { it.id })
    }
    @Test fun oldest_albums_still_keep_missing_dates_last_and_ties_stable() {
        assertEquals(listOf("2", "4", "5", "1", "3"), AmpSort.albums(albums, false).map { it.id })
    }
    @Test fun playlist_creation_dates_support_both_directions_and_missing_dates() {
        val playlists = listOf(AmpPlaylist("1", "Unknown"), AmpPlaylist("2", "New", creationDate = "2026-11-01 12:00:00"), AmpPlaylist("3", "Old", creationDate = "2020-01-01"))
        assertEquals(listOf("2", "3", "1"), AmpSort.playlists(playlists, true).map { it.id })
        assertEquals(listOf("3", "2", "1"), AmpSort.playlists(playlists, false).map { it.id })
    }
    @Test fun sorting_preserves_original_catalog_order() {
        AmpSort.albums(albums, true)
        assertEquals(listOf("1", "2", "3", "4", "5"), albums.map { it.id })
    }
}
