package no.aura.os.v2

import no.aura.os.v2.amp.AmpProviderSafety
import no.aura.os.v2.amp.AmpTrack
import no.aura.os.v2.amp.AmpAudioRange
import org.junit.Assert.*
import org.junit.Test

class AmpProviderMatchingTest {
    private val track = AmpTrack("123", "Same title", "Right Artist", 180)
    @Test fun addonIdsNeverCompareAgainstDeezerIds() {
        assertTrue(AmpProviderSafety.matchesTrack(track, "t123", "Same title", "Right Artist"))
        assertTrue(AmpProviderSafety.matchesTrack(track, "deezer:123", "Same title", "Right Artist"))
        assertTrue(AmpProviderSafety.matchesTrack(track, "t456", "Same title", "Right Artist"))
        assertFalse(AmpProviderSafety.matchesTrack(track, "123", "Same title", "Wrong Artist"))
    }
    @Test fun opaqueProviderIdsRequireExactArtistAndTitle() {
        assertTrue(AmpProviderSafety.matchesTrack(track, "opaque-id", " same  TITLE ", "RIGHT ARTIST"))
        assertFalse(AmpProviderSafety.matchesTrack(track, "opaque-id", "Same title", "Wrong Artist"))
        assertFalse(AmpProviderSafety.matchesTrack(track, "opaque-id", "Same title", ""))
    }
    @Test fun rangeMustStartAtRequestedPosition() {
        assertTrue(AmpAudioRange.validStart("bytes 100-199/200", 100))
        assertTrue(AmpAudioRange.validStart("bytes 100-199/*", 100))
        assertFalse(AmpAudioRange.validStart("bytes 0-99/200", 100))
        assertFalse(AmpAudioRange.validStart("bytes 100-99/200", 100))
        assertFalse(AmpAudioRange.validStart(null, 100))
        assertFalse(AmpAudioRange.validStart("bytes 999999999999999999999999-999999999999999999999999/*", 100))
    }
}
