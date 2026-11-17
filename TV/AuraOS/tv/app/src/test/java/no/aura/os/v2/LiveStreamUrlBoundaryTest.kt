package no.aura.os.v2

import no.aura.os.v2.overlay.LiveStreamUrlPolicy
import org.junit.Assert.*
import org.junit.Test

class LiveStreamUrlBoundaryTest {
    @Test fun acceptsHttpsPlaylistAndOpaqueQueryCredential() {
        assertTrue(LiveStreamUrlPolicy.allowed("https://tv.example/live/channel.m3u8?token=fixture"))
    }
    @Test fun rejectsUserinfoPlaintextLocalhostAndMalformedUrls() {
        listOf("http://tv.example/live", "https://user:password@tv.example/live", "file:///etc/passwd", "https://localhost/live", "https://127.0.0.1/live", "https://tv.example/live#token", "https://tv.example/a\nb", "https://tv.example:0/live").forEach { assertFalse(it, LiveStreamUrlPolicy.allowed(it)) }
    }
}
