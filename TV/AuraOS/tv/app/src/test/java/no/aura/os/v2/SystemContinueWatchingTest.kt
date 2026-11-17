package no.aura.os.v2

import no.aura.os.v2.watch.SystemContinuePolicy
import org.junit.Assert.*
import org.junit.Test

class SystemContinueWatchingTest {
    @Test fun keepsOnlyPublicHttpsPosterArtwork() {
        assertEquals("https://images.example/poster.jpg", SystemContinuePolicy.posterArtwork("https://images.example/poster.jpg"))
        assertEquals("content://private/image", SystemContinuePolicy.posterArtwork("content://private/image"))
        listOf("http://images.example/poster.jpg", "file:///poster", "https:///missing-host", "https://user:secret@example.com/poster", "https://example.com/has space", "").forEach {
            assertNull(it, SystemContinuePolicy.posterArtwork(it))
        }
        assertNull(SystemContinuePolicy.posterArtwork(null))
    }
    @Test fun requiresExplicitProfileConsentAndBlocksChildren() {
        assertFalse(SystemContinuePolicy.mayRead("main", false, false))
        assertFalse(SystemContinuePolicy.mayRead("", true, false))
        assertFalse(SystemContinuePolicy.mayRead("child", true, true))
        assertTrue(SystemContinuePolicy.mayRead("main", true, false))
    }
    @Test fun rejectsIntentPayloadsPrivateUrisAndMalformedLinks() {
        listOf("intent://play#Intent;package=evil;end", "content://private/video", "file:///private/data", "javascript:alert(1)",
            "data:text/plain,secret", "android.resource://app/1", "https://host/has space", "https://host/\n", "//no-scheme", "").forEach {
            assertFalse(it, SystemContinuePolicy.safeLink(it))
        }
    }
    @Test fun intentEnvelopeExtractsDataOnlyAndRejectsTargetMismatchOrSelectors() {
        assertEquals("streamapp://watch/1", SystemContinuePolicy.dataLink(
            "intent://watch/1#Intent;scheme=streamapp;package=verified.app;launchFlags=0x3;S.token=discarded;end", "verified.app"))
        assertNull(SystemContinuePolicy.dataLink("intent://watch/1#Intent;scheme=streamapp;package=evil.app;end", "verified.app"))
        assertNull(SystemContinuePolicy.dataLink("intent://watch/1#Intent;scheme=streamapp;SEL;scheme=https;end", "verified.app"))
        assertNull(SystemContinuePolicy.dataLink("intent://watch/1#Intent;scheme=content;end", "verified.app"))
    }
    @Test fun permitsOnlyPlainDataLinksForSubsequentPackageVerification() {
        assertTrue(SystemContinuePolicy.safeLink("https://example.com/watch/1"))
        assertTrue(SystemContinuePolicy.safeLink("streamapp://watch/1"))
    }
}
