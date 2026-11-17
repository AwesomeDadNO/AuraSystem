package no.aura.os.v2

import no.aura.os.v2.playback.*
import org.junit.Assert.*
import org.junit.Test

class AudioTrackPolicyTest {
    @Test fun unsupportedPreferredAudioDoesNotOverridePlayableFallback() {
        val tracks = listOf(TrackInfo(0, "eng", "English DTS", false), TrackInfo(1, "nor", "Norwegian AAC"))
        assertEquals(1, TrackPicker.audio(tracks, PlaybackPrefs())?.index)
    }
    @Test fun missingAndUnsupportedAudioHaveDifferentHonestMessages() {
        assertTrue(AudioTrackPolicy.message(emptyList(), null)!!.contains("no discovered"))
        assertTrue(AudioTrackPolicy.message(listOf(TrackInfo(0, "en", "DTS", false)), null)!!.contains("cannot decode"))
        assertNull(AudioTrackPolicy.message(listOf(TrackInfo(0, "en", "AAC")), 0))
    }
    @Test fun bitmapSubtitlesAreNotAdvertisedAsRenderableByTextOverlay() {
        assertFalse(TextTrackPolicy.canRender("application/pgs"))
        assertFalse(TextTrackPolicy.canRender("application/dvbsubs"))
        assertFalse(TextTrackPolicy.canRender(null))
        assertTrue(TextTrackPolicy.canRender("text/vtt"))
        assertTrue(TextTrackPolicy.canRender("application/x-subrip"))
    }
    @Test fun unsupportedSubtitlesAreNotChosenByPreferences() {
        assertNull(TrackPicker.subtitle(listOf(TrackInfo(0, "en", "Unknown", false)), PlaybackPrefs(subtitlesOn = true)))
    }
}
