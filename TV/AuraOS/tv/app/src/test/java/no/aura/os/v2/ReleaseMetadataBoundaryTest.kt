package no.aura.os.v2

import no.aura.os.v2.notifications.ReleaseWindow
import no.aura.os.v2.music.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReleaseMetadataBoundaryTest {
    private val today = LocalDate.of(2026, 11, 12)
    private val artist = FollowedArtist("12345678-1234-1234-1234-123456789012", "Fixture artist")
    @Test fun partialFutureAndOldDatesCannotClaimRecentRelease() {
        listOf("2026", "2026-11", "2026-11-13", "2026-11-04", "2026-02-30").forEach { assertNull(ReleaseWindow.date(it, today)) }
        assertEquals(today.minusDays(7), ReleaseWindow.date("2026-11-05", today))
    }
    @Test fun followsAreProfileScopedAndInvalidIdsAreRejected() {
        val store = MemoryFollowedArtistStore()
        assertTrue(store.follow("a", artist, true))
        assertTrue(store.artists("b").isEmpty())
        assertFalse(store.follow("b", artist.copy(id = "other"), true))
        store.purge("a"); assertTrue(store.artists("a").isEmpty())
    }
    @Test fun artistCreditMustMatchEvenWhenSearchReturnedTheRelease() {
        val json = """{"release-groups":[{"id":"87654321-1234-1234-1234-123456789012","title":"New release","first-release-date":"2026-11-12","artist-credit":[{"artist":{"id":"${artist.id}"}}]}]}"""
        assertEquals(1, MusicBrainzArtistReleaseSource.parse(json, artist, today).size)
        assertTrue(MusicBrainzArtistReleaseSource.parse(json, artist.copy(id = "00000000-1234-1234-1234-123456789012"), today).isEmpty())
        assertTrue(MusicBrainzArtistReleaseSource.parse(json, artist, today.minusDays(1)).isEmpty())
    }
}
