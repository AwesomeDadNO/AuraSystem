package no.aura.os.v2

import no.aura.os.v2.amp.*
import org.junit.Assert.*
import org.junit.Test

/** Schema fixtures from https://eclipsemusic.app/docs; no live account or JSON test dependency. */
class AmpEclipseContractTest {
    private val song = AmpTrack("123", "Song Title", "Artist Name", 240, isrc = "USRC12345678")
    @Test fun manifestRequiresStreamAndSupportedLookup() {
        assertTrue(AmpEclipseProtocol.canPlay(AmpEclipseProtocol.capabilities(listOf("search", "stream"))))
        assertTrue(AmpEclipseProtocol.canPlay(setOf("isrc", "stream")))
        assertTrue(AmpEclipseProtocol.canPlay(setOf("resolve", "stream")))
        assertFalse(AmpEclipseProtocol.canPlay(setOf("album", "artist")))
        assertFalse(AmpEclipseProtocol.canPlay(setOf("search")))
        assertFalse(AmpEclipseProtocol.canPlay(setOf("stream")))
    }
    @Test fun searchFixtureUsesAddonLocalIdAndCamelcaseDirectStream() {
        val item = mapOf("id" to "987654", "title" to "Song Title", "artist" to "Artist Name", "duration" to 240, "format" to "mp3", "streamURL" to "https://cdn.example.com/audio.mp3")
        assertTrue(AmpEclipseProtocol.matches(song, item))
        assertEquals("987654", AmpEclipseProtocol.lookupId(item))
        assertEquals("https://cdn.example.com/audio.mp3", AmpEclipseProtocol.streamUrl(item))
        assertEquals("audio/mpeg", AmpEclipseProtocol.mime(item))
        assertFalse(AmpEclipseProtocol.matches(song, item + ("artist" to "Wrong Artist")))
    }
    @Test fun isrcLookupAcceptsBothDocumentedIdFields() {
        assertEquals("track_123", AmpEclipseProtocol.lookupId(mapOf("trackId" to "track_123")))
        assertEquals("track_123", AmpEclipseProtocol.lookupId(mapOf("id" to "track_123")))
        assertNull(AmpEclipseProtocol.lookupId(mapOf("trackId" to null)))
        assertTrue(AmpEclipseProtocol.matches(song, mapOf("isrc" to "us-rc1-23-45678", "title" to "Enriched title", "artist" to "Enriched artist")))
        assertFalse(AmpEclipseProtocol.matches(song, mapOf("isrc" to "USRC12345679", "title" to song.title, "artist" to song.artist)))
    }
    @Test fun streamMetadataSelectsHlsEvenWithoutPlaylistExtension() {
        val item = mapOf("url" to "https://cdn.example.com/proxy/audio", "format" to "flac", "manifest" to "hls", "codec" to "flac", "expiresAt" to 1999999999)
        assertEquals("https://cdn.example.com/proxy/audio", AmpEclipseProtocol.streamUrl(item))
        assertEquals("application/x-mpegURL", AmpEclipseProtocol.mime(item))
        assertEquals("audio/flac", AmpEclipseProtocol.mime(item + ("manifest" to "none")))
    }
    @Test fun authenticatedProxyCredentialStaysOnExactProviderHost() {
        val sample = "b".repeat(128)
        assertTrue(AmpEclipseProtocol.safeStreamUrl("https://eclipse.ultramax.vip/$sample/audio/1", sample))
        assertFalse(AmpEclipseProtocol.safeStreamUrl("https://other.example/$sample/audio/1", sample))
        assertFalse(AmpEclipseProtocol.safeStreamUrl("https://eclipse.ultramax.vip.other.example/$sample/audio/1", sample))
        assertTrue(AmpEclipseProtocol.safeStreamUrl("https://cdn.example.com/audio.mp3?signature=signed", sample))
    }
    @Test fun documentedRoutesKeepAddonIdentityAndEncodeOnlyParameters() {
        assertEquals("search?q=Artist%20Name%20Song%20Title", AmpEclipseProtocol.searchRoute(song))
        assertEquals("stream/track_123", AmpEclipseProtocol.streamRoute("track_123"))
        assertEquals("stream/a%2Fb%3Ftoken%3Dvalue", AmpEclipseProtocol.streamRoute("a/b?token=value"))
        assertEquals("resolve-isrc?isrc=USRC12345678", AmpEclipseProtocol.isrcRoute(song.isrc!!))
        assertEquals("resolve?title=Song%20Title&artist=Artist%20Name&durationMs=240000&isrc=USRC12345678", AmpEclipseProtocol.resolveRoute(song, song.isrc))
    }
}
