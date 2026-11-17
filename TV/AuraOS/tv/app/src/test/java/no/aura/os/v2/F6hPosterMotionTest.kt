package no.aura.os.v2

import no.aura.os.v2.cards.PosterGeometry
import no.aura.os.v2.apps.AppGridGeometry
import no.aura.os.v2.apps.ShortcutBrand
import org.junit.Assert.*
import org.junit.Test

/** Owner feedback supersedes the former focus expansion contract. */
class F6hPosterMotionTest {
    @Test fun staticPosterIsTwoToThreeAndThirtyFivePercentSmaller() {
        assertEquals(184f, PosterGeometry.WIDTH, 0f)
        assertEquals(276f, PosterGeometry.HEIGHT, 0f)
        assertEquals(2f / 3f, PosterGeometry.WIDTH / PosterGeometry.HEIGHT, 0.0001f)
        assertEquals(0.65f, PosterGeometry.WIDTH / 282f, 0.003f)
        assertEquals(12f, PosterGeometry.GAP, 0f)
    }
    @Test fun fiveAppTilesFitTheAvailableCanvas() {
        assertEquals(5, AppGridGeometry.COLUMNS)
        assertTrue(AppGridGeometry.COLUMNS * AppGridGeometry.WIDTH +
            (AppGridGeometry.COLUMNS - 1) * AppGridGeometry.GAP <= 1660f)
    }
    @Test fun shortcutIdentityUsesPackagesAndPreservesUnknownFallback() {
        assertEquals(ShortcutBrand.NETFLIX, ShortcutBrand.forPackage("com.netflix.ninja"))
        assertEquals(ShortcutBrand.YOUTUBE, ShortcutBrand.forPackage("com.google.android.youtube.tv"))
        assertNull(ShortcutBrand.forPackage("untrusted.netflix.clone"))
        assertNull(ShortcutBrand.forPackage("com.google.android.youtube.tvunplugged"))
    }
}
