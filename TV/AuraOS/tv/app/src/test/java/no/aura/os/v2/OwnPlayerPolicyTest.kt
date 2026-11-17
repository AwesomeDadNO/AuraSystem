package no.aura.os.v2

import no.aura.os.v2.playback.*
import org.junit.Assert.*
import org.junit.Test

class OwnPlayerPolicyTest {
    @Test fun sourcesAreActualDistinctResolvedChoices() {
        val first = PlayerSource("a", "1080p", "https://media.example/a")
        assertEquals(listOf(first), PlayerSourcePolicy.choices(listOf(first, first.copy(key = "duplicate"), first.copy(key = "empty", url = ""))))
        assertTrue(PlayerSourcePolicy.choices(emptyList()).isEmpty())
    }
    @Test fun diagnosticRepresentationDoesNotExposeStreamCredentials() {
        val source = PlayerSource("provider-1", "1080p", "https://media.example/a?token=fixture-secret")
        assertFalse(source.toString().contains("fixture-secret"))
        assertFalse(source.toString().contains("https://"))
    }
    @Test fun serverUsesSharedControlsWithoutUnwrappingAuthenticatedSource() {
        val host = java.io.File("src/main/java/no/aura/os/v2/overlay/MediaServerPlayerHost.kt").readText()
        assertTrue(host.contains("ConfinedMediaDataSource(playback)"))
        assertTrue(host.contains("streamHeaders(profileId, account, playback)"))
        assertTrue(host.contains("mediaSource = source"))
        assertFalse(host.contains("source = playback.url"))
        assertTrue(host.contains("ServerPlaybackEvent.START"))
        assertTrue(host.contains("ServerPlaybackEvent.STOP"))
    }
    @Test fun selectionKeepsConfirmedStateWhenSwitchFails() {
        assertEquals(2, TrackSelection.confirmed(2, 3, false, null))
        assertEquals(2, TrackSelection.confirmed(2, 3, true, 2))
        assertEquals(3, TrackSelection.confirmed(2, 3, true, 3))
        assertNull(TrackSelection.confirmed(2, null, true, null))
    }
}
